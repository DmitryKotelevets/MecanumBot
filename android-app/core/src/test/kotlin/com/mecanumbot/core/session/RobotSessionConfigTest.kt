package com.mecanumbot.core.session

import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Ack
import com.mecanumbot.core.protocol.AckStatus
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.SetConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RobotSessionConfigTest {
    private val inverted = Config(invert = listOf(1, 0, 0, 0))

    private fun ScriptedLink.configSeq(): Int = ofType(FrameType.CONFIG).single().frame.seq

    @Test
    fun `sendConfig stops the robot first, then sends CONFIG and is pending`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        assertTrue(s.sendConfig(inverted))
        assertEquals(listOf(FrameType.STOP, FrameType.STOP, FrameType.STOP, FrameType.CONFIG), link.sent.map { it.frame.type })
        assertEquals(SetConfig(inverted), link.ofType(FrameType.CONFIG).single().payload)
        assertEquals(Priority.OTHER, link.ofType(FrameType.CONFIG).single().priority)
        assertEquals(ConfigWrite.Pending, s.state.value.configWrite)
    }

    @Test
    fun `ACK OK saves and re-reads the config`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.sendConfig(inverted)
        link.receive(Ack(FrameType.CONFIG.code, link.configSeq(), AckStatus.OK)); runCurrent()
        assertEquals(ConfigWrite.Saved, s.state.value.configWrite)
        assertEquals(1, link.ofType(FrameType.GET_CONFIG).size)
    }

    @Test
    fun `ACK ERR and BUSY are reported as rejected without re-reading`() = runTest {
        for (status in listOf(AckStatus.ERR, AckStatus.BUSY)) {
            val link = ScriptedLink()
            val s = readySession(link)
            s.sendConfig(inverted)
            link.receive(Ack(FrameType.CONFIG.code, link.configSeq(), status)); runCurrent()
            assertEquals(ConfigWrite.Rejected(status), s.state.value.configWrite)
            assertTrue(link.ofType(FrameType.GET_CONFIG).isEmpty())
        }
    }

    @Test
    fun `ACKs for another frame are ignored`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.sendConfig(inverted)
        val seq = link.configSeq()
        link.receive(Ack(FrameType.CONFIG.code, (seq + 1) and 0xFF, AckStatus.OK))
        link.receive(Ack(FrameType.STOP.code, seq, AckStatus.OK))
        runCurrent()
        assertEquals(ConfigWrite.Pending, s.state.value.configWrite)
    }

    @Test
    fun `no ACK within 1 s is reported as no answer`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.sendConfig(inverted)
        advanceTimeBy(999); runCurrent()
        assertEquals(ConfigWrite.Pending, s.state.value.configWrite)
        advanceTimeBy(2); runCurrent()
        assertEquals(ConfigWrite.NoAnswer, s.state.value.configWrite)
        link.receive(Ack(FrameType.CONFIG.code, link.configSeq(), AckStatus.OK)); runCurrent()
        assertEquals(ConfigWrite.NoAnswer, s.state.value.configWrite) // a late ACK changes nothing
    }

    @Test
    fun `link loss while pending is reported as no answer`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.sendConfig(inverted)
        link.disconnect(); runCurrent()
        assertEquals(ConfigWrite.NoAnswer, s.state.value.configWrite)
    }

    @Test
    fun `not sent outside READY`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent() // HANDSHAKING
        link.sent.clear()
        assertFalse(s.sendConfig(inverted))
        assertTrue(link.sent.isEmpty())
        assertEquals(ConfigWrite.Idle, s.state.value.configWrite)
    }

    @Test
    fun `an invalid config is not sent and does not stop the robot`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        assertFalse(s.sendConfig(Config(failsafeMs = 50)))
        assertTrue(link.sent.isEmpty())
        assertEquals(ConfigWrite.Idle, s.state.value.configWrite)
    }

    @Test
    fun `refreshConfig sends GET_CONFIG in READY only`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.refreshConfig()
        assertEquals(1, link.ofType(FrameType.GET_CONFIG).size)
        link.disconnect(); runCurrent()
        s.refreshConfig()
        assertEquals(0, link.sendsWhileDisconnected)
    }
}
