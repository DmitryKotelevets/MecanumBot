package com.mecanumbot.core.protocol

import java.io.ByteArrayOutputStream

/** Payload ⇄ bytes, little-endian — PROTOCOL.md §2, §4. */
object FrameCodec {
    fun encode(payload: Payload, seq: Int): ByteArray =
        Frame(payload.type, seq and 0xFF, encodePayload(payload)).toBytes()

    fun encodePayload(payload: Payload): ByteArray {
        val w = Writer()
        when (payload) {
            is Hello -> { w.u8(payload.protoVer); w.u8(payload.appMajor); w.u8(payload.appMinor) }
            is Drive -> { w.u8(payload.flags); w.u8(payload.vx); w.u8(payload.vy); w.u8(payload.w) }
            is MotorRaw -> payload.m.forEach(w::u8)
            is Ping -> w.u32(payload.ts)
            is SetConfig -> w.config(payload.config)
            is OtaBegin -> { w.u32(payload.size); w.bytes(payload.sha256.array) }
            is OtaData -> { w.u32(payload.offset); w.bytes(payload.data.array) }
            is WifiOtaEnter -> { w.str8(payload.ssid); w.str8(payload.pass) }
            is HelloAck -> {
                w.u8(payload.protoVer); w.u8(payload.fwMajor); w.u8(payload.fwMinor)
                w.u8(payload.resetReason); w.u16(payload.resetCount)
            }
            is Telemetry -> {
                w.u8(payload.lastSeq); w.u8(payload.flags); payload.pwm.forEach(w::u8)
                w.u16(payload.vmMv); w.u16(payload.crcErr); w.u16(payload.rxFrames); w.u32(payload.uptimeS)
                w.u8(payload.fwMajor); w.u8(payload.fwMinor); w.u16(payload.loopMaxUs)
            }
            is Ack -> { w.u8(payload.reqType); w.u8(payload.reqSeq); w.u8(payload.status) }
            is Log -> { w.u8(payload.level); w.bytes(payload.text.encodeToByteArray()) }
            is Pong -> w.u32(payload.ts)
            is ConfigData -> w.config(payload.config)
            is WifiStatus -> { w.u8(payload.state); payload.ip.forEach(w::u8) }
            Stop, GetConfig, OtaEnd, OtaAbort, WifiOtaExit, Reboot -> Unit
        }
        return w.toByteArray()
    }

    /** Null when the content doesn't fit its type (a str8 running past the end, trailing bytes). */
    fun decode(frame: Frame): Payload? = try {
        val r = Reader(frame.payload)
        val p = read(frame.type, r)
        if (r.remaining == 0) p else null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun read(type: FrameType, r: Reader): Payload = when (type) {
        FrameType.HELLO -> Hello(r.u8(), r.u8(), r.u8())
        FrameType.DRIVE -> Drive(r.u8(), r.i8(), r.i8(), r.i8())
        FrameType.MOTOR_RAW -> MotorRaw(List(4) { r.i8() })
        FrameType.PING -> Ping(r.u32())
        FrameType.CONFIG -> SetConfig(r.config())
        FrameType.STOP -> Stop
        FrameType.GET_CONFIG -> GetConfig
        FrameType.OTA_BEGIN -> OtaBegin(r.u32(), Bytes(r.bytes(32)))
        FrameType.OTA_DATA -> OtaData(r.u32(), Bytes(r.rest()))
        FrameType.OTA_END -> OtaEnd
        FrameType.OTA_ABORT -> OtaAbort
        FrameType.WIFI_OTA_ENTER -> WifiOtaEnter(r.str8(), r.str8())
        FrameType.WIFI_OTA_EXIT -> WifiOtaExit
        FrameType.REBOOT -> Reboot
        FrameType.HELLO_ACK -> HelloAck(r.u8(), r.u8(), r.u8(), r.u8(), r.u16())
        FrameType.TELEMETRY -> Telemetry(
            lastSeq = r.u8(),
            flags = r.u8(),
            pwm = List(4) { r.i8() },
            vmMv = r.u16(),
            crcErr = r.u16(),
            rxFrames = r.u16(),
            uptimeS = r.u32(),
            fwMajor = r.u8(),
            fwMinor = r.u8(),
            loopMaxUs = r.u16(),
        )
        FrameType.ACK -> Ack(r.u8(), r.u8(), r.u8())
        FrameType.LOG -> Log(r.u8(), r.rest().decodeToString())
        FrameType.PONG -> Pong(r.u32())
        FrameType.CONFIG_DATA -> ConfigData(r.config())
        FrameType.WIFI_STATUS -> WifiStatus(r.u8(), List(4) { r.u8() })
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        fun bytes(b: ByteArray) = out.write(b)
        fun str8(s: String) { val b = s.encodeToByteArray(); u8(b.size); bytes(b) }

        fun config(c: Config) {
            u8(c.version); c.map.forEach(::u8); c.invert.forEach(::u8); u8(c.maxDuty); u16(c.slewMs)
            c.trim.forEach(::u8); u8(c.brake); u8(c.minDuty); u16(c.failsafeMs); u16(c.pwmHz)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** Throws IndexOutOfBoundsException past the end. Arguments are evaluated left to right. */
    private class Reader(private val b: ByteArray) {
        private var pos = 0
        val remaining: Int get() = b.size - pos

        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun i8(): Int = b[pos++].toInt()
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = (0 until 4).fold(0L) { acc, i -> acc or (u8().toLong() shl (8 * i)) }

        fun bytes(n: Int): ByteArray {
            if (n > remaining) throw IndexOutOfBoundsException("need $n, have $remaining")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun rest(): ByteArray = bytes(remaining)
        fun str8(): String = bytes(u8()).decodeToString()

        fun config() = Config(
            version = u8(),
            map = List(4) { u8() },
            invert = List(4) { u8() },
            maxDuty = u8(),
            slewMs = u16(),
            trim = List(4) { u8() },
            brake = u8(),
            minDuty = u8(),
            failsafeMs = u16(),
            pwmHz = u16(),
        )
    }
}
