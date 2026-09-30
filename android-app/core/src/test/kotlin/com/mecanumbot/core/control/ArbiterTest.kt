package com.mecanumbot.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArbiterTest {
    private val pulse = Output.Drive(0, 0, 0, 0)
    private fun cmd(source: Source, vy: Float = 0.5f, enable: Boolean = true) = Command(0f, vy, 0f, enable, source)

    @Test
    fun `no source gives the zero pulse`() {
        assertEquals(pulse, Arbiter().tick(0))
    }

    @Test
    fun `flags carry enable and source like the vectors`() {
        val a = Arbiter()
        a.update(Command(-1f, -1f, -1f, true, Source.REMOTE), 0)
        assertEquals(Output.Drive(5, -127, -127, -127), a.tick(0))   // drive_remote_full_negative
        a.update(Command(0f, 0f, 1f, true, Source.LOCAL_PAD), 0)
        assertEquals(Output.Drive(3, 0, 0, 127), a.tick(0))          // drive_local_pad_turn
        a.update(cmd(Source.TEST), 0)
        assertEquals(Output.Drive(1, 0, 64, 0), a.tick(0))           // drive_forward_half
    }

    @Test
    fun `TEST beats LOCAL_PAD beats REMOTE`() {
        val a = Arbiter()
        a.update(cmd(Source.REMOTE), 0)
        a.update(cmd(Source.LOCAL_PAD), 0)
        assertEquals(Source.LOCAL_PAD, a.activeSource(0))
        a.update(cmd(Source.TEST), 0)
        assertEquals(Source.TEST, a.activeSource(0))
    }

    @Test
    fun `source expires 300 ms after its last update`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST), 1000)
        assertEquals(Source.TEST, a.activeSource(1299))
        assertNull(a.activeSource(1300))
        assertEquals(pulse, a.tick(1300))
    }

    @Test
    fun `enable false is not active and lets a lower source through`() {
        val a = Arbiter()
        a.update(cmd(Source.LOCAL_PAD), 0)
        a.update(cmd(Source.TEST, enable = false), 0)
        assertEquals(Source.LOCAL_PAD, a.activeSource(0))
    }

    @Test
    fun `latest command wins - a new speed limit applies on the next tick`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST, vy = 0.5f), 0)
        a.update(cmd(Source.TEST, vy = 0.25f), 0)
        assertEquals(Output.Drive(1, 0, 32, 0), a.tick(0))
    }

    @Test
    fun `LOCAL_ONLY ignores REMOTE, REMOTE_ONLY ignores LOCAL_PAD, TEST always allowed`() {
        val a = Arbiter()
        a.update(cmd(Source.REMOTE), 0)
        a.setMode(Mode.LOCAL_ONLY)
        assertNull(a.activeSource(0))
        a.update(cmd(Source.LOCAL_PAD), 0)
        a.setMode(Mode.REMOTE_ONLY)
        assertEquals(Source.REMOTE, a.activeSource(0))
        a.update(cmd(Source.TEST), 0)
        assertEquals(Source.TEST, a.activeSource(0))
        a.setMode(Mode.LOCAL_ONLY)
        assertEquals(Source.TEST, a.activeSource(0))
    }

    @Test
    fun `RAW overrides DRIVE, is clamped and expires after 300 ms`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST), 0)
        a.setRaw(listOf(200, -200, 64, 0), 0)
        assertEquals(Output.Raw(listOf(127, -127, 64, 0)), a.tick(100))
        a.update(cmd(Source.TEST), 250)
        assertEquals(Output.Drive(1, 0, 64, 0), a.tick(300))  // RAW is stale at 300, TEST is fresh
    }

    @Test
    fun `clearRaw returns to DRIVE`() {
        val a = Arbiter()
        a.setRaw(listOf(10, 10, 10, 10), 0)
        a.clearRaw()
        assertEquals(pulse, a.tick(0))
    }

    @Test
    fun `stop clears every source and RAW`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST), 0)
        a.update(cmd(Source.REMOTE), 0)
        a.setRaw(listOf(1, 2, 3, 4), 0)
        a.stop()
        assertEquals(pulse, a.tick(1))
        assertNull(a.activeSource(1))
    }
}
