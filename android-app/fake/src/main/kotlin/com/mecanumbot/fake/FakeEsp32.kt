package com.mecanumbot.fake

import com.mecanumbot.core.control.Mecanum
import com.mecanumbot.core.control.Wire
import com.mecanumbot.core.protocol.Ack
import com.mecanumbot.core.protocol.AckStatus
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.ConfigData
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.GetConfig
import com.mecanumbot.core.protocol.Hello
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Log
import com.mecanumbot.core.protocol.LogLevel
import com.mecanumbot.core.protocol.MotorRaw
import com.mecanumbot.core.protocol.OtaAbort
import com.mecanumbot.core.protocol.OtaBegin
import com.mecanumbot.core.protocol.OtaData
import com.mecanumbot.core.protocol.OtaEnd
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Ping
import com.mecanumbot.core.protocol.Pong
import com.mecanumbot.core.protocol.Protocol
import com.mecanumbot.core.protocol.Reboot
import com.mecanumbot.core.protocol.SetConfig
import com.mecanumbot.core.protocol.Stop
import com.mecanumbot.core.protocol.Telemetry
import com.mecanumbot.core.protocol.TelemetryFlags
import com.mecanumbot.core.protocol.WifiOtaEnter
import com.mecanumbot.core.protocol.WifiOtaExit
import com.mecanumbot.core.protocol.WifiStatus
import kotlin.math.abs

/**
 * Model of the ESP32 firmware — PROTOCOL.md §5. Pure: time comes in as `now` (ms), replies go
 * out through [emit]. OTA writes nothing; Wi-Fi "connects" after 500 ms with [FAKE_IP].
 */
class FakeEsp32(private val emit: (Payload) -> Unit) {
    enum class Mode { DRIVE, RAW, OTA, WIFI }

    var mode = Mode.DRIVE
        private set
    var config = Config.DEFAULT
        private set
    var resetCount = 0
        private set
    var lastSeq = 0
        private set
    var stopsReceived = 0
        private set

    var protoVer = Protocol.PROTO_VER
    var faultA = false
    var faultB = false
    var extraSagMv = 0

    /** ESP32-side parser rejects; set by the link that owns the parser. */
    var crcErr = 0

    private class Ota(val size: Long) {
        var received = 0L
        var lastChunk = -1L
        var lastAt = 0L
    }

    private var bootAt = 0L
    private var lastFeed: Long? = null
    private var failsafeOn = true
    private var drive = Drive(0, 0, 0, 0)
    private var raw = ZEROS
    private val rxTimes = ArrayDeque<Long>()
    private var nextTelemetryAt = 0L
    private var ota: Ota? = null
    private var wifiConnectAt: Long? = null
    private var savedWifi: Pair<String, String>? = null
    private var rebootAt: Long? = null

    fun boot(now: Long) {
        resetCount = (resetCount + 1) and 0xFFFF
        bootAt = now
        lastFeed = null
        failsafeOn = true
        mode = Mode.DRIVE
        drive = Drive(0, 0, 0, 0)
        raw = ZEROS
        rxTimes.clear()
        nextTelemetryAt = now
        ota = null
        wifiConnectAt = null
        rebootAt = null
    }

    fun announce() = emit(HelloAck(protoVer, FW_MAJOR, FW_MINOR, RESET_REASON_SW, resetCount))

    fun failsafeActive(now: Long): Boolean = lastFeed?.let { now - it >= config.failsafeMs } ?: true

    fun onFrame(frame: Frame, now: Long) {
        rxTimes.addLast(now)
        val p = FrameCodec.decode(frame) ?: return
        val halted = mode == Mode.OTA || mode == Mode.WIFI
        when (p) {
            is Hello -> announce()
            is Drive -> if (!halted) {
                drive = p
                setMode(Mode.DRIVE)
                lastSeq = frame.seq
                feed(now)
            }
            is MotorRaw -> if (!halted) {
                raw = p.m
                setMode(Mode.RAW)
                lastSeq = frame.seq
                feed(now)
            }
            is Ping -> emit(Pong(p.ts))
            is SetConfig -> ack(
                frame,
                when {
                    halted -> AckStatus.BUSY
                    !p.config.isValid() -> AckStatus.ERR
                    else -> { config = p.config; AckStatus.OK }
                },
            )
            Stop -> {
                stopsReceived++
                drive = Drive(0, 0, 0, 0)
                raw = ZEROS
                if (mode == Mode.RAW) setMode(Mode.DRIVE)
                ack(frame, AckStatus.OK)
            }
            GetConfig -> emit(ConfigData(config))
            is OtaBegin -> ack(
                frame,
                when {
                    mode == Mode.WIFI -> AckStatus.BUSY
                    p.size == 0L -> AckStatus.ERR
                    else -> {
                        ota = Ota(p.size).also { it.lastAt = now }
                        setMode(Mode.OTA)
                        AckStatus.OK
                    }
                },
            )
            is OtaData -> ack(frame, otaData(p, now))
            OtaEnd -> ack(frame, otaEnd(now))
            OtaAbort -> {
                if (mode == Mode.OTA) {
                    ota = null
                    setMode(Mode.DRIVE)
                }
                ack(frame, AckStatus.OK)
            }
            is WifiOtaEnter -> {
                val status = wifiEnter(p, now)
                ack(frame, status)
                if (status == AckStatus.OK) emit(WifiStatus(1, ZEROS))
            }
            WifiOtaExit -> {
                ack(frame, AckStatus.OK)
                if (mode == Mode.WIFI) wifiOff()
            }
            Reboot -> rebootAt = now
            else -> Unit // ESP32 → phone types: accepted by the parser, ignored (PROTOCOL.md §3.1)
        }
    }

    fun tick(now: Long) {
        rebootAt?.let {
            if (now >= it) {
                boot(now)
                announce()
            }
        }
        ota?.let {
            if (now - it.lastAt >= OTA_TIMEOUT_MS) {
                ota = null
                setMode(Mode.DRIVE)
                log(LogLevel.WARN, "ota timeout")
            }
        }
        wifiConnectAt?.let {
            if (now >= it) {
                wifiConnectAt = null
                emit(WifiStatus(2, FAKE_IP))
            }
        }
        if (!failsafeOn && failsafeActive(now)) {
            failsafeOn = true
            log(LogLevel.INFO, "failsafe on")
        }
        while (rxTimes.isNotEmpty() && now - rxTimes.first() >= 1_000) rxTimes.removeFirst()
        if (now >= nextTelemetryAt) {
            emit(telemetry(now))
            nextTelemetryAt = now + TELEMETRY_PERIOD_MS
        }
    }

    /** Output per physical channel (i8), as TELEMETRY pwm[4] reports it. No slew is modelled. */
    fun pwm(now: Long): List<Int> {
        if (mode == Mode.OTA || mode == Mode.WIFI || failsafeActive(now)) return ZEROS
        val channels = when (mode) {
            Mode.RAW -> FloatArray(4) { Wire.fromWire(raw[it]) * config.maxDuty / 100f }
            else -> Mecanum.drive(drive.vx, drive.vy, drive.w, drive.flags and 1 != 0, config)
        }
        return channels.map { Wire.toWire(it) }
    }

    private fun telemetry(now: Long): Telemetry {
        val pwm = pwm(now)
        val failsafe = failsafeActive(now)
        var flags = 0
        if (failsafe) flags = flags or TelemetryFlags.FAILSAFE
        if (faultA) flags = flags or TelemetryFlags.FAULT_A
        if (faultB) flags = flags or TelemetryFlags.FAULT_B
        if (mode == Mode.RAW) flags = flags or TelemetryFlags.RAW
        if (mode == Mode.OTA) flags = flags or TelemetryFlags.OTA
        if (mode == Mode.WIFI) flags = flags or TelemetryFlags.WIFI
        if (mode == Mode.DRIVE && !failsafe && drive.flags and 1 != 0) flags = flags or TelemetryFlags.ENABLE
        val load = pwm.sumOf { abs(it) }
        val vm = (VM_NOMINAL_MV - load * FULL_LOAD_SAG_MV / (4 * 127) - extraSagMv).coerceAtLeast(0)
        return Telemetry(
            lastSeq = lastSeq,
            flags = flags,
            pwm = pwm,
            vmMv = vm,
            crcErr = crcErr.coerceAtMost(0xFFFF),
            rxFrames = rxTimes.size.coerceAtMost(0xFFFF),
            uptimeS = (now - bootAt) / 1_000,
            fwMajor = FW_MAJOR,
            fwMinor = FW_MINOR,
            loopMaxUs = LOOP_MAX_US,
        )
    }

    private fun feed(now: Long) {
        lastFeed = now
        if (failsafeOn) {
            failsafeOn = false
            log(LogLevel.INFO, "failsafe off")
        }
    }

    private fun otaData(p: OtaData, now: Long): Int {
        val o = ota ?: return AckStatus.BUSY
        o.lastAt = now
        return when (p.offset) {
            o.received ->
                if (o.received + p.data.size > o.size) {
                    AckStatus.ERR
                } else {
                    o.lastChunk = o.received
                    o.received += p.data.size
                    AckStatus.OK
                }
            o.lastChunk -> AckStatus.OK // our ACK was lost; the phone repeated the chunk
            else -> AckStatus.ERR
        }
    }

    private fun otaEnd(now: Long): Int {
        val o = ota ?: return AckStatus.BUSY
        ota = null
        return if (o.received == o.size) {
            rebootAt = now + 200
            AckStatus.OK
        } else {
            setMode(Mode.DRIVE)
            AckStatus.ERR
        }
    }

    private fun wifiEnter(p: WifiOtaEnter, now: Long): Int {
        if (mode == Mode.OTA) return AckStatus.BUSY
        val creds = if (p.ssid.isEmpty() && p.pass.isEmpty()) savedWifi ?: return AckStatus.ERR else p.ssid to p.pass
        savedWifi = creds
        setMode(Mode.WIFI)
        wifiConnectAt = now + WIFI_CONNECT_MS
        return AckStatus.OK
    }

    private fun wifiOff() {
        wifiConnectAt = null
        setMode(Mode.DRIVE)
        emit(WifiStatus(0, ZEROS))
    }

    private fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        log(LogLevel.INFO, "mode ${m.name}")
    }

    private fun ack(frame: Frame, status: Int) = emit(Ack(frame.type.code, frame.seq, status))

    private fun log(level: Int, text: String) = emit(Log(level, text))

    companion object {
        const val FW_MAJOR = 0
        const val FW_MINOR = 1
        const val RESET_REASON_SW = 3 // ESP_RST_SW
        const val VM_NOMINAL_MV = 3600
        const val FULL_LOAD_SAG_MV = 150
        const val LOOP_MAX_US = 850
        const val TELEMETRY_PERIOD_MS = 100L
        const val OTA_TIMEOUT_MS = 5_000L
        const val WIFI_CONNECT_MS = 500L
        val FAKE_IP = listOf(192, 168, 4, 2)
        private val ZEROS = listOf(0, 0, 0, 0)
    }
}
