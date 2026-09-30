package com.mecanumbot.fake

import com.mecanumbot.core.protocol.Ack
import com.mecanumbot.core.protocol.AckStatus
import com.mecanumbot.core.protocol.Bytes
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.ConfigData
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.GetConfig
import com.mecanumbot.core.protocol.Hello
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Log
import com.mecanumbot.core.protocol.MotorRaw
import com.mecanumbot.core.protocol.OtaBegin
import com.mecanumbot.core.protocol.OtaData
import com.mecanumbot.core.protocol.OtaEnd
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Ping
import com.mecanumbot.core.protocol.Pong
import com.mecanumbot.core.protocol.Reboot
import com.mecanumbot.core.protocol.SetConfig
import com.mecanumbot.core.protocol.Stop
import com.mecanumbot.core.protocol.Telemetry
import com.mecanumbot.core.protocol.TelemetryFlags
import com.mecanumbot.core.protocol.WifiOtaEnter
import com.mecanumbot.core.protocol.WifiOtaExit
import com.mecanumbot.core.protocol.WifiStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeEsp32Test {
    private val out = mutableListOf<Payload>()
    private val esp = FakeEsp32 { out += it }.also { it.boot(0) }
    private var seq = 0

    /** Sends a frame and returns its seq. */
    private fun send(p: Payload, now: Long): Int {
        val s = seq
        esp.onFrame(Frame(p.type, s, FrameCodec.encodePayload(p)), now)
        seq = (seq + 1) and 0xFF
        return s
    }

    private inline fun <reified T : Payload> last(): T = out.filterIsInstance<T>().last()

    private fun telemetryAt(now: Long): Telemetry {
        out.clear()
        esp.tick(now)
        return last()
    }

    private fun has(t: Telemetry, bit: Int) = t.flags and bit != 0

    @Test
    fun `first boot announces reset_count 1`() {
        esp.announce()
        assertEquals(HelloAck(1, 0, 1, FakeEsp32.RESET_REASON_SW, 1), last<HelloAck>())
    }

    @Test
    fun `HELLO is answered with HELLO_ACK, PING with PONG`() {
        send(Hello(1, 0, 1), 0)
        send(Ping(0xDEADBEEF), 0)
        assertTrue(out[0] is HelloAck)
        assertEquals(Pong(0xDEADBEEF), out[1])
    }

    @Test
    fun `failsafe is active after boot`() {
        val t = telemetryAt(0)
        assertTrue(has(t, TelemetryFlags.FAILSAFE))
        assertEquals(listOf(0, 0, 0, 0), t.pwm)
    }

    @Test
    fun `DRIVE clears failsafe and drives the mix`() {
        esp.tick(0)
        send(Drive(1, 0, 127, 0), 10)
        val t = telemetryAt(100)
        assertFalse(has(t, TelemetryFlags.FAILSAFE))
        assertTrue(has(t, TelemetryFlags.ENABLE))
        assertEquals(listOf(127, 127, 127, 127), t.pwm)
    }

    @Test
    fun `failsafe fires failsafe_ms after the last DRIVE and logs it`() {
        send(Drive(1, 0, 127, 0), 0)
        assertFalse(esp.failsafeActive(299))
        assertTrue(esp.failsafeActive(300))
        esp.tick(0)
        out.clear()
        esp.tick(300)
        assertTrue(out.contains(Log(2, "failsafe on")))
        val t = last<Telemetry>()
        assertTrue(has(t, TelemetryFlags.FAILSAFE))
        assertEquals(listOf(0, 0, 0, 0), t.pwm)
    }

    @Test
    fun `zero pulse keeps failsafe off with motors stopped`() {
        esp.tick(0)
        for (t in 0L..1000L step 25) send(Drive(0, 0, 0, 0), t)
        val t = telemetryAt(1000)
        assertFalse(has(t, TelemetryFlags.FAILSAFE))
        assertFalse(has(t, TelemetryFlags.ENABLE))
        assertEquals(listOf(0, 0, 0, 0), t.pwm)
    }

    @Test
    fun `STOP is acknowledged, zeroes output and leaves RAW mode`() {
        send(MotorRaw(listOf(127, 0, 0, 0)), 0)
        assertEquals(FakeEsp32.Mode.RAW, esp.mode)
        val s = send(Stop, 5)
        assertEquals(Ack(FrameType.STOP.code, s, AckStatus.OK), last<Ack>())
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
        assertEquals(listOf(0, 0, 0, 0), esp.pwm(10))
        assertEquals(1, esp.stopsReceived)
    }

    @Test
    fun `MOTOR_RAW sets raw mode and honours max_duty`() {
        send(SetConfig(Config(maxDuty = 80, minDuty = 15)), 0)
        assertEquals(AckStatus.OK, last<Ack>().status)
        send(MotorRaw(listOf(127, -127, 0, 64)), 0)
        val t = telemetryAt(10)
        assertTrue(has(t, TelemetryFlags.RAW))
        assertEquals(listOf(102, -102, 0, 51), t.pwm) // 64/127 × 0.8 × 127 = 51.2
    }

    @Test
    fun `invalid CONFIG is rejected and not applied`() {
        val s = send(SetConfig(Config(failsafeMs = 50)), 0)
        assertEquals(Ack(FrameType.CONFIG.code, s, AckStatus.ERR), last<Ack>())
        send(GetConfig, 0)
        assertEquals(ConfigData(Config.DEFAULT), last<ConfigData>())
    }

    @Test
    fun `OTA flow with duplicate and wrong offsets, then reboot`() {
        assertEquals(AckStatus.OK, run { send(OtaBegin(10, Bytes(ByteArray(32))), 0); last<Ack>().status })
        assertEquals(FakeEsp32.Mode.OTA, esp.mode)
        send(Drive(1, 0, 127, 0), 1)
        assertEquals(listOf(0, 0, 0, 0), esp.pwm(1))
        send(SetConfig(Config.DEFAULT), 1)
        assertEquals(AckStatus.BUSY, last<Ack>().status)
        send(OtaData(0, Bytes(ByteArray(6))), 2); assertEquals(AckStatus.OK, last<Ack>().status)
        send(OtaData(0, Bytes(ByteArray(6))), 3); assertEquals(AckStatus.OK, last<Ack>().status) // lost ACK repeat
        send(OtaData(3, Bytes(ByteArray(3))), 4); assertEquals(AckStatus.ERR, last<Ack>().status)
        send(OtaData(6, Bytes(ByteArray(4))), 5); assertEquals(AckStatus.OK, last<Ack>().status)
        send(OtaEnd, 6); assertEquals(AckStatus.OK, last<Ack>().status)
        out.clear()
        esp.tick(206)
        assertEquals(2, last<HelloAck>().resetCount)
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
    }

    @Test
    fun `OTA_END with missing bytes fails and returns to DRIVE`() {
        send(OtaBegin(10, Bytes(ByteArray(32))), 0)
        send(OtaData(0, Bytes(ByteArray(4))), 1)
        send(OtaEnd, 2)
        assertEquals(AckStatus.ERR, last<Ack>().status)
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
    }

    @Test
    fun `OTA_BEGIN with size 0 is an error`() {
        send(OtaBegin(0, Bytes(ByteArray(32))), 0)
        assertEquals(AckStatus.ERR, last<Ack>().status)
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
    }

    @Test
    fun `OTA times out after 5 s without data`() {
        send(OtaBegin(10, Bytes(ByteArray(32))), 0)
        esp.tick(4_999)
        assertEquals(FakeEsp32.Mode.OTA, esp.mode)
        esp.tick(5_000)
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
    }

    @Test
    fun `Wi-Fi OTA - ACK, connecting, connected, BUSY for OTA, exit, saved credentials`() {
        send(WifiOtaEnter("", ""), 0)
        assertEquals(AckStatus.ERR, last<Ack>().status) // nothing saved yet
        out.clear()
        send(WifiOtaEnter("net", "pw"), 0)
        // ACK first, then WIFI_STATUS "connecting" (PROTOCOL.md §4.1); a mode LOG may come before both.
        val ack = out.indexOfFirst { it is Ack }
        val connecting = out.indexOf(WifiStatus(1, listOf(0, 0, 0, 0)))
        assertEquals(AckStatus.OK, (out[ack] as Ack).status)
        assertTrue(ack in 0 until connecting)
        esp.tick(500)
        assertEquals(WifiStatus(2, FakeEsp32.FAKE_IP), last<WifiStatus>())
        send(OtaBegin(10, Bytes(ByteArray(32))), 600)
        assertEquals(AckStatus.BUSY, last<Ack>().status)
        send(WifiOtaExit, 700)
        assertEquals(WifiStatus(0, listOf(0, 0, 0, 0)), last<WifiStatus>())
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
        send(WifiOtaEnter("", ""), 800)
        assertEquals(AckStatus.OK, last<Ack>().status)
    }

    @Test
    fun `REBOOT restarts - reset_count grows, uptime and failsafe reset`() {
        send(Drive(1, 0, 127, 0), 5_000)
        send(Reboot, 5_000)
        out.clear()
        esp.tick(5_000)
        assertEquals(2, last<HelloAck>().resetCount)
        val t = telemetryAt(5_100) // the reboot tick already sent one; the next is due 100 ms later
        assertEquals(0L, t.uptimeS)
        assertTrue(has(t, TelemetryFlags.FAILSAFE))
    }

    @Test
    fun `rx_frames counts the last second, VM sags with load and fault`() {
        esp.tick(0)
        for (t in 0L until 1000L step 25) send(Drive(1, 0, 127, 0), t)
        val t = telemetryAt(1000)
        assertEquals(39, t.rxFrames) // the frame sent at t = 0 has left the 1 s window
        assertEquals(FakeEsp32.VM_NOMINAL_MV - FakeEsp32.FULL_LOAD_SAG_MV, t.vmMv)
        esp.extraSagMv = 500
        esp.faultA = true
        val t2 = telemetryAt(1100)
        assertEquals(FakeEsp32.VM_NOMINAL_MV - FakeEsp32.FULL_LOAD_SAG_MV - 500, t2.vmMv)
        assertTrue(has(t2, TelemetryFlags.FAULT_A))
        assertFalse(has(t2, TelemetryFlags.FAULT_B))
    }

    @Test
    fun `wrong proto_ver is reported in HELLO_ACK`() {
        esp.protoVer = 2
        send(Hello(1, 0, 1), 0)
        assertEquals(2, last<HelloAck>().protoVer)
    }

    @Test
    fun `frames of the ESP32 direction are ignored`() {
        send(Telemetry(0, 0, listOf(0, 0, 0, 0), 0, 0, 0, 0, 0, 1, 0), 0)
        assertTrue(out.isEmpty())
    }
}
