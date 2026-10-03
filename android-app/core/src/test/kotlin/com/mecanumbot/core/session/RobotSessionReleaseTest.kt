package com.mecanumbot.core.session

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Mode
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.FrameType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RobotSessionReleaseTest {
    @Test
    fun `releaseLocal with TEST driving sends STOP x3 and clears REMOTE too`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        s.update(Command(0f, 0.5f, 0f, true, Source.REMOTE))
        advanceTimeBy(30); runCurrent()
        s.releaseLocal()
        assertEquals(3, link.ofType(FrameType.STOP).size)
        assertEquals(1, s.state.value.stops)
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `releaseLocal with RAW driving sends STOP x3`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.setRaw(listOf(50, 50, 50, 50))
        advanceTimeBy(30); runCurrent()
        s.releaseLocal()
        assertEquals(3, link.ofType(FrameType.STOP).size)
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `releaseLocal with only REMOTE active sends no STOP and REMOTE keeps driving`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 0.5f, 0f, false, Source.TEST)) // Test screen open, deadman not held
        s.update(Command(0f, 0.5f, 0f, true, Source.REMOTE))
        advanceTimeBy(30); runCurrent()
        s.releaseLocal()
        advanceTimeBy(25); runCurrent()
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
        assertEquals(0, s.state.value.stops)
        assertEquals(Source.REMOTE, s.state.value.activeSource)
        assertEquals(Drive(1 or (Source.REMOTE.code shl 1), 0, 64, 0), link.lastMotion())
    }

    @Test
    fun `releaseLocal with nothing active is quiet`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.releaseLocal()
        advanceTimeBy(30); runCurrent()
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `mode reflects setMode`() = runTest {
        val s = readySession(ScriptedLink())
        assertEquals(Mode.AUTO, s.mode)
        s.setMode(Mode.LOCAL_ONLY)
        assertEquals(Mode.LOCAL_ONLY, s.mode)
    }
}
