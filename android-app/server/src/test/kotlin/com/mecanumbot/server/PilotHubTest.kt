package com.mecanumbot.server

import com.mecanumbot.core.control.Mode
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.fake.FakeLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class PilotHubTest {
    /** Records what the hub sends to one client. */
    private class Client : PilotHub.Connection {
        val texts = mutableListOf<String>()
        override fun send(text: String) {
            texts += text
        }
        fun statuses() = texts.filter { it.startsWith("""{"t":"status"""") }
        fun lastRole(): String? = statuses().lastOrNull()?.substringAfter(""""role":"""")?.substringBefore('"')
    }

    private fun drive(vy: Float, en: Boolean = true) = """{"t":"drive","vx":0,"vy":$vy,"w":0,"en":$en}"""

    private suspend fun TestScope.readySession(): Pair<RobotSession, FakeLink> {
        val link = FakeLink(backgroundScope, { testScheduler.currentTime }, Random(1))
        val s = RobotSession(link, backgroundScope, { testScheduler.currentTime }).also { it.start() }
        runCurrent()
        link.open(); runCurrent()
        advanceTimeBy(150); runCurrent()
        check(s.state.value.phase == Phase.READY) { "not READY: ${s.state.value}" }
        return s to link
    }

    private fun TestScope.hub(session: RobotSession?, video: VideoSource = FakeVideo()) =
        PilotHub(MutableStateFlow(session), video, { testScheduler.currentTime }, "0.1")

    @Test
    fun `first connection drives, the second watches`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        assertEquals("driver", a.lastRole())
        assertEquals("watcher", b.lastRole())
        assertEquals(PilotHub.Summary(true, 1, 0), hub.summary.value)
        assertEquals("""{"t":"status","role":"driver","fw":"0.1","app":"0.1","mode":"AUTO"}""", a.statuses().single())
    }

    @Test
    fun `driver's drive reaches the session as REMOTE`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
    }

    @Test
    fun `watcher's drive is dropped`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        Client().also(hub::connect)
        val b = Client().also(hub::connect)
        hub.onText(b, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `watcher's stop stops the robot`() = runTest {
        val (s, link) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        hub.onText(b, """{"t":"stop"}""")
        advanceTimeBy(30); runCurrent()
        assertEquals(1, s.state.value.stops)
        assertEquals(3, link.esp.stopsReceived)
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `driver leaving releases REMOTE at once and promotes the oldest watcher`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        val c = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
        hub.disconnect(a)
        advanceTimeBy(25); runCurrent() // well inside the arbiter's 300 ms
        assertNull(s.state.value.activeSource)
        assertEquals("driver", b.lastRole())
        assertEquals("watcher", c.lastRole())
        assertEquals(1, c.statuses().size) // c's role did not change, so no new status
        hub.onText(b, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
    }

    @Test
    fun `a watcher leaving does not touch the driver`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        hub.disconnect(b)
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
        assertEquals(PilotHub.Summary(true, 0, 0), hub.summary.value)
    }

    @Test
    fun `out-of-range speeds drive like full scale`() = runTest {
        val (s, link) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        suspend fun pwmAfter(text: String): List<Int> {
            repeat(20) { hub.onText(a, text); advanceTimeBy(25); runCurrent() } // 500 ms, past slew
            return link.esp.pwm(testScheduler.currentTime)
        }
        val full = pwmAfter("""{"t":"drive","vx":-1,"vy":1,"w":0.5,"en":true}""")
        val over = pwmAfter("""{"t":"drive","vx":-5,"vy":9,"w":0.5,"en":true}""")
        assertEquals(full, over)
        assertTrue(full.any { it != 0 })
    }

    @Test
    fun `garbage is counted, the connection keeps working`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        hub.onText(a, "garbage")
        hub.onText(a, """{"t":"fly"}""")
        assertEquals(2, hub.summary.value.ignored)
        hub.onText(a, """{"t":"ping","ts":42}""")
        assertEquals("""{"t":"pong","ts":42}""", a.texts.last())
    }

    @Test
    fun `messages from an unknown connection are ignored`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val ghost = Client()
        hub.onText(ghost, drive(0.5f))
        hub.onText(ghost, """{"t":"stop"}""")
        advanceTimeBy(30); runCurrent()
        assertNull(s.state.value.activeSource)
        assertEquals(0, s.state.value.stops)
    }

    @Test
    fun `a session swap routes later commands to the new session`() = runTest {
        val (s1, _) = readySession()
        val (s2, _) = readySession()
        val sessions = MutableStateFlow<RobotSession?>(s1)
        val hub = PilotHub(sessions, FakeVideo(), { testScheduler.currentTime }, "0.1")
        val a = Client().also(hub::connect)
        sessions.value = s2
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertNull(s1.state.value.activeSource)
        assertEquals(Source.REMOTE, s2.state.value.activeSource)
    }

    @Test
    fun `telemetry before any session is all nulls`() = runTest {
        val video = FakeVideo().apply { tempC.value = null; level.value = VideoLevel.OFF }
        assertEquals(
            TelemetryMsg(null, null, null, null, false, "DISCONNECTED", null, null, null, 0, null, "off", null),
            hub(null, video).telemetry(),
        )
    }

    @Test
    fun `telemetry carries session, video age and temperature`() = runTest {
        val (s, _) = readySession()
        val video = FakeVideo()
        val hub = hub(s, video)
        val a = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(200); runCurrent() // FakeEsp32 telemetry is 10 Hz
        video.frames.value = JpegFrame(byteArrayOf(1), testScheduler.currentTime - 60, 1)
        val t = hub.telemetry()
        assertEquals(true, t.usb)
        assertEquals("READY", t.phase)
        assertEquals("REMOTE", t.active)
        assertNotNull(t.vm)
        assertEquals(4, t.pwm?.size)
        assertEquals(false, t.failsafe)
        assertEquals(60L, t.videoAgeMs)
        assertEquals(31.5f, t.tempC)
        assertEquals("normal", t.video)
    }

    @Test
    fun `tick sends telemetry to everyone and status only on change`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        hub.tick()
        hub.tick()
        assertEquals(2, a.texts.count { it.startsWith("""{"t":"telemetry"""") })
        assertEquals(2, b.texts.count { it.startsWith("""{"t":"telemetry"""") })
        assertEquals(1, a.statuses().size)
        s.setMode(Mode.LOCAL_ONLY)
        hub.tick()
        assertEquals(2, a.statuses().size)
        assertTrue(a.statuses().last().contains(""""mode":"LOCAL_ONLY""""))
    }

    @Test
    fun `after close the driver's drive is ignored`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
        hub.close()
        s.stop()
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `after close connect gets no status and tick sends nothing`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        hub.close()
        val a = Client().also(hub::connect)
        hub.tick()
        assertTrue(a.texts.isEmpty())
        assertEquals(PilotHub.Summary(false, 0, 0), hub.summary.value)
    }
}
