package com.mecanumbot.core.session

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.ConfigData
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.Log
import com.mecanumbot.core.protocol.MotorRaw
import com.mecanumbot.core.protocol.Ping
import com.mecanumbot.core.protocol.Pong
import com.mecanumbot.core.protocol.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RobotSessionReadyTest {
    private val telemetry = Telemetry(4, 0x40, listOf(40, -40, 40, -40), 3580, 2, 40, 3600, 0, 1, 850)

    @Test
    fun `DRIVE is sent at 40 Hz`() = runTest {
        val link = ScriptedLink()
        readySession(link)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(link.ofType(FrameType.DRIVE).size in 39..41, "${link.ofType(FrameType.DRIVE).size}")
    }

    @Test
    fun `without an active source every DRIVE is the zero pulse`() = runTest {
        val link = ScriptedLink()
        readySession(link)
        advanceTimeBy(500); runCurrent()
        assertTrue(link.ofType(FrameType.DRIVE).all { it.payload == Drive(0, 0, 0, 0) })
        assertTrue(link.ofType(FrameType.DRIVE).all { it.priority == Priority.MOTION })
    }

    @Test
    fun `TEST command drives and expires 300 ms after the last update`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(1, 0, 64, 0), link.lastMotion())
        assertEquals(Source.TEST, s.state.value.activeSource)
        advanceTimeBy(300); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `latest command wins - a new speed limit applies on the next frame`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        advanceTimeBy(30); runCurrent()
        s.update(Command(0f, 0.25f, 0f, true, Source.TEST))
        advanceTimeBy(25); runCurrent()
        assertEquals(Drive(1, 0, 32, 0), link.lastMotion())
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
    }

    @Test
    fun `RAW sends MOTOR_RAW until it goes stale`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.setRaw(listOf(127, -127, 64, 0))
        advanceTimeBy(30); runCurrent()
        assertEquals(MotorRaw(listOf(127, -127, 64, 0)), link.lastMotion())
        advanceTimeBy(300); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `stop sends STOP x3 at once, then the tick sends the zero pulse`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 1f, 0f, true, Source.TEST))
        advanceTimeBy(30); runCurrent()
        link.sent.clear()
        s.stop()
        assertEquals(List(3) { FrameType.STOP }, link.sent.map { it.frame.type })
        assertTrue(link.sent.all { it.priority == Priority.STOP })
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `stop while handshaking still sends STOP x3, while disconnected sends nothing`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        link.sent.clear()
        s.stop()
        assertEquals(3, link.ofType(FrameType.STOP).size)
        link.disconnect(); runCurrent()
        s.stop()
        assertEquals(0, link.sendsWhileDisconnected)
    }

    @Test
    fun `PING every second and RTT from the matching PONG`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        advanceTimeBy(1_001); runCurrent()
        val ping = link.ofType(FrameType.PING).last().payload as Ping
        assertEquals(1_000L, ping.ts)
        advanceTimeBy(6); runCurrent() // t = 1007
        link.receive(Pong(ping.ts)); runCurrent()
        assertEquals(7L, s.state.value.rttMs)
    }

    @Test
    fun `telemetry, config and log reach state and events`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        val events = mutableListOf<SessionEvent>()
        backgroundScope.launch { s.events.collect { events += it } }
        runCurrent()
        link.receive(telemetry)
        link.receive(ConfigData(Config(failsafeMs = 500)))
        link.receive(Log(2, "boot ok"))
        runCurrent()
        val t = s.state.value.telemetry!!
        assertEquals(telemetry, t.telemetry)
        assertTrue(t.enabled)
        assertFalse(t.failsafe)
        assertEquals(500, s.state.value.config!!.failsafeMs)
        assertEquals(listOf<SessionEvent>(SessionEvent.EspLog(2, "boot ok")), events)
        assertNull(s.state.value.lastEvent) // LOG lines don't replace the status-bar event
    }

    @Test
    fun `motion frames per second are reported`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(s.state.value.motionSentPerSec in 39..41, "${s.state.value.motionSentPerSec}")
    }

    @Test
    fun `seq increases by one per frame and wraps after 255`() = runTest {
        val link = ScriptedLink()
        readySession(link)
        advanceTimeBy(8_000); runCurrent()
        val seqs = link.sent.map { it.frame.seq }
        assertTrue(seqs.size > 256)
        seqs.zipWithNext().forEach { (a, b) -> assertEquals((a + 1) and 0xFF, b) }
    }

    @Test
    fun `every stop increments the stop counter`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        val before = s.state.value.stops
        s.stop()
        s.stop()
        assertEquals(before + 2, s.state.value.stops)
    }
}
