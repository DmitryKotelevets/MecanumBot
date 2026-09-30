package com.mecanumbot.core.session

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.Hello
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RobotSessionHandshakeTest {
    private val telemetry = Telemetry(4, 0x40, listOf(40, -40, 40, -40), 3580, 2, 40, 3600, 0, 1, 850)

    @Test
    fun `sends HELLO on connect and no motion before HELLO_ACK`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        assertEquals(listOf<Payload>(Hello(1, 0, 1)), link.payloads())
        s.update(Command(0f, 1f, 0f, true, Source.TEST))
        advanceTimeBy(900); runCurrent()
        assertEquals(Phase.HANDSHAKING, s.state.value.phase)
        assertTrue(link.ofType(FrameType.DRIVE).isEmpty())
        assertTrue(link.ofType(FrameType.MOTOR_RAW).isEmpty())
    }

    @Test
    fun `retries HELLO every second, reports not responding at 3 s, then retries every 3 s`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        advanceTimeBy(2_500); runCurrent()
        assertEquals(3, link.ofType(FrameType.HELLO).size)
        assertNull(s.state.value.lastEvent)
        advanceTimeBy(600); runCurrent() // t = 3100
        assertEquals(4, link.ofType(FrameType.HELLO).size)
        assertEquals(SessionEvent.NotResponding, s.state.value.lastEvent)
        advanceTimeBy(2_800); runCurrent() // t = 5900
        assertEquals(4, link.ofType(FrameType.HELLO).size)
        advanceTimeBy(200); runCurrent() // t = 6100
        assertEquals(5, link.ofType(FrameType.HELLO).size)
    }

    @Test
    fun `matching HELLO_ACK enters READY, asks for config, starts DRIVE and stops HELLO`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        link.receive(HelloAck(1, 0, 1, 1, 5)); runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
        assertEquals(HelloAck(1, 0, 1, 1, 5), s.state.value.helloAck)
        assertEquals(1, link.ofType(FrameType.GET_CONFIG).size)
        advanceTimeBy(100); runCurrent()
        assertTrue(link.ofType(FrameType.DRIVE).isNotEmpty())
        val hellos = link.ofType(FrameType.HELLO).size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(hellos, link.ofType(FrameType.HELLO).size)
    }

    @Test
    fun `version mismatch blocks motion`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        link.receive(HelloAck(2, 0, 1, 1, 5)); runCurrent()
        assertEquals(Phase.VERSION_MISMATCH, s.state.value.phase)
        assertEquals(SessionEvent.VersionMismatch(2), s.state.value.lastEvent)
        s.update(Command(0f, 1f, 0f, true, Source.TEST))
        advanceTimeBy(2_000); runCurrent()
        assertTrue(link.ofType(FrameType.DRIVE).isEmpty())
        assertTrue(link.ofType(FrameType.MOTOR_RAW).isEmpty())
    }

    @Test
    fun `a matching HELLO_ACK after a mismatch enters READY`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        link.receive(HelloAck(2, 0, 1, 1, 5)); runCurrent()
        link.receive(HelloAck(1, 0, 2, 1, 5)); runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
    }

    @Test
    fun `reboot across a reconnect is reported and stops the robot`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link, resetCount = 5)
        link.disconnect(); runCurrent()
        assertEquals(Phase.DISCONNECTED, s.state.value.phase)
        link.connect(); runCurrent()
        link.sent.clear()
        link.receive(HelloAck(1, 0, 1, 12, 6)); runCurrent()
        assertEquals(SessionEvent.Rebooted(12, 6), s.state.value.lastEvent)
        val stops = link.ofType(FrameType.STOP)
        assertEquals(3, stops.size)
        assertTrue(stops.all { it.priority == Priority.STOP })
        assertEquals(Phase.READY, s.state.value.phase)
    }

    @Test
    fun `same reset_count after a reconnect is not a reboot`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link, resetCount = 5)
        link.disconnect(); runCurrent()
        link.connect(); runCurrent()
        link.receive(HelloAck(1, 0, 1, 1, 5)); runCurrent()
        assertNull(s.state.value.lastEvent)
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
        assertEquals(Phase.READY, s.state.value.phase)
    }

    @Test
    fun `duplicate HELLO_ACK in READY is ignored and driving continues`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link, resetCount = 5)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        link.receive(HelloAck(1, 0, 1, 1, 5)); runCurrent()
        advanceTimeBy(30); runCurrent()
        assertNull(s.state.value.lastEvent)
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
        assertEquals(Drive(1, 0, 64, 0), link.lastMotion())
    }

    @Test
    fun `reboot while READY sends STOP x3 and clears commands`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link, resetCount = 5)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        link.receive(HelloAck(1, 0, 1, 3, 6)); runCurrent()
        assertEquals(SessionEvent.Rebooted(3, 6), s.state.value.lastEvent)
        assertEquals(3, link.ofType(FrameType.STOP).size)
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `link loss resets to DISCONNECTED and the tick stops`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        link.receive(telemetry); runCurrent()
        assertNotNull(s.state.value.telemetry)
        link.disconnect(); runCurrent()
        assertEquals(Phase.DISCONNECTED, s.state.value.phase)
        assertNull(s.state.value.telemetry)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, link.sendsWhileDisconnected)
        link.sent.clear()
        link.connect(); runCurrent()
        assertEquals(listOf<Payload>(Hello(1, 0, 1)), link.payloads())
    }
}
