package com.mecanumbot.fake

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.Link
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.Telemetry
import com.mecanumbot.core.protocol.TelemetryFlags
import com.mecanumbot.core.session.ConfigWrite
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.core.session.SessionEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class FakeLinkTest {
    private fun TestScope.fakeLink() = FakeLink(backgroundScope, { testScheduler.currentTime }, Random(1))

    private fun TestScope.session(link: Link) =
        RobotSession(link, backgroundScope, { testScheduler.currentTime }).also { it.start(); runCurrent() }

    private suspend fun TestScope.ready(link: FakeLink): RobotSession {
        val s = session(link)
        link.open(); runCurrent()
        advanceTimeBy(150); runCurrent()
        check(s.state.value.phase == Phase.READY) { "not READY: ${s.state.value}" }
        return s
    }

    private fun TestScope.collectTelemetry(link: FakeLink): MutableList<Telemetry> {
        val list = mutableListOf<Telemetry>()
        backgroundScope.launch { link.incoming.collect { f -> (FrameCodec.decode(f) as? Telemetry)?.let { list += it } } }
        runCurrent()
        return list
    }

    private suspend fun TestScope.driveFor(s: RobotSession, ms: Long) {
        repeat((ms / 25).toInt()) {
            s.update(Command(0f, 1f, 0f, true, Source.TEST))
            advanceTimeBy(25); runCurrent()
        }
    }

    @Test
    fun `handshake reaches READY with telemetry and config`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        assertEquals(1, s.state.value.helloAck!!.resetCount)
        assertNotNull(s.state.value.telemetry)
        assertEquals(Config.DEFAULT, s.state.value.config)
        assertNull(s.state.value.lastEvent)
    }

    @Test
    fun `driving clears failsafe, closing the session trips it within failsafe_ms plus one telemetry period`() = runTest {
        val link = fakeLink()
        val telemetry = collectTelemetry(link)
        val s = ready(link)
        driveFor(s, 1_000)
        val driving = telemetry.last()
        assertEquals(0, driving.flags and TelemetryFlags.FAILSAFE)
        assertEquals(listOf(127, 127, 127, 127), driving.pwm)
        s.close() // the app died: no more pulses
        telemetry.clear()
        advanceTimeBy(300 + 100 + 20); runCurrent()
        val last = telemetry.last()
        assertTrue(last.flags and TelemetryFlags.FAILSAFE != 0)
        assertEquals(listOf(0, 0, 0, 0), last.pwm)
    }

    @Test
    fun `releasing the drive button keeps the pulse - motors stop without failsafe`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        driveFor(s, 500)
        advanceTimeBy(600); runCurrent()
        val t = s.state.value.telemetry!!
        assertFalse(t.failsafe)
        assertFalse(t.enabled)
        assertEquals(listOf(0, 0, 0, 0), t.telemetry.pwm)
    }

    @Test
    fun `STOP x3 queued before close reaches the ESP32`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        s.stop()
        link.close()
        assertEquals(3, link.esp.stopsReceived)
    }

    @Test
    fun `Reboot fault is reported and the session sends STOP x3`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.reboot(); runCurrent()
        assertEquals(SessionEvent.Rebooted(FakeEsp32.RESET_REASON_SW, 2), s.state.value.lastEvent)
        advanceTimeBy(50); runCurrent()
        assertEquals(3, link.esp.stopsReceived)
    }

    @Test
    fun `Wrong proto_ver switches to VERSION_MISMATCH and back`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.wrongProtoVer = true; runCurrent()
        assertEquals(Phase.VERSION_MISMATCH, s.state.value.phase)
        link.faults.wrongProtoVer = false; runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
    }

    @Test
    fun `Disconnect and Reconnect return to READY without a reboot event`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.disconnect(); runCurrent()
        assertEquals(Phase.DISCONNECTED, s.state.value.phase)
        link.faults.reconnect(); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
        assertNull(s.state.value.lastEvent)
    }

    @Test
    fun `dropping every frame trips the ESP32 failsafe while the session stays READY`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.dropPercent = 100
        advanceTimeBy(500); runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
        assertTrue(s.state.value.telemetry!!.failsafe)
    }

    @Test
    fun `garbage is counted by the phone parser and telemetry keeps flowing`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.injectGarbage()
        advanceTimeBy(300); runCurrent()
        assertTrue(s.state.value.phoneParserErrors >= 1)
        assertEquals(Phase.READY, s.state.value.phase)
        assertTrue(testScheduler.currentTime - s.state.value.telemetry!!.receivedAt <= 110)
    }

    @Test
    fun `nFAULT and VM sag appear in telemetry`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.faultA = true
        link.faults.vmSagMv = 500
        advanceTimeBy(150); runCurrent()
        val t = s.state.value.telemetry!!
        assertTrue(t.faultA)
        assertFalse(t.faultB)
        assertEquals(FakeEsp32.VM_NOMINAL_MV - 500, t.telemetry.vmMv)
    }

    @Test
    fun `a saved config is read back and changes the motor output`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        assertTrue(s.sendConfig(Config(invert = listOf(1, 0, 0, 0))))
        advanceTimeBy(100); runCurrent()
        assertEquals(ConfigWrite.Saved, s.state.value.configWrite)
        assertEquals(listOf(1, 0, 0, 0), s.state.value.config!!.invert)
        driveFor(s, 300)
        assertEquals(listOf(-127, 127, 127, 127), s.state.value.telemetry!!.telemetry.pwm)
    }
}
