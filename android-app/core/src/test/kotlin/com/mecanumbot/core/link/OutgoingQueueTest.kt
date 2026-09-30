package com.mecanumbot.core.link

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutgoingQueueTest {
    private fun b(v: Int) = byteArrayOf(v.toByte())
    private fun OutgoingQueue.pollInt(): Int? = poll()?.get(0)?.toInt()

    @Test
    fun `STOP before MOTION before OTHER`() {
        val q = OutgoingQueue()
        q.offer(b(3), Priority.OTHER)
        q.offer(b(1), Priority.STOP)
        q.offer(b(2), Priority.MOTION)
        assertEquals(listOf(1, 2, 3, null), List(4) { q.pollInt() })
    }

    @Test
    fun `newer MOTION replaces an unsent one`() {
        val q = OutgoingQueue()
        q.offer(b(1), Priority.MOTION)
        q.offer(b(2), Priority.MOTION)
        assertEquals(2, q.pollInt())
        assertNull(q.poll())
    }

    @Test
    fun `STOP frames are all kept in order`() {
        val q = OutgoingQueue()
        repeat(3) { q.offer(b(it), Priority.STOP) }
        assertEquals(listOf(0, 1, 2), List(3) { q.pollInt() })
    }

    @Test
    fun `OTHER is capped, oldest dropped`() {
        val q = OutgoingQueue(maxOther = 2)
        repeat(3) { q.offer(b(it), Priority.OTHER) }
        assertEquals(listOf(1, 2, null), List(3) { q.pollInt() })
    }

    @Test
    fun `drainStops returns only STOP frames and leaves the rest`() {
        val q = OutgoingQueue()
        repeat(3) { q.offer(b(it), Priority.STOP) }
        q.offer(b(9), Priority.MOTION)
        assertEquals(listOf(0, 1, 2), q.drainStops().map { it[0].toInt() })
        assertEquals(9, q.pollInt())
    }

    @Test
    fun `STOP drops an unsent MOTION frame`() {
        val q = OutgoingQueue()
        q.offer(b(9), Priority.MOTION)
        repeat(3) { q.offer(b(it), Priority.STOP) }
        assertEquals(listOf(0, 1, 2, null), List(4) { q.pollInt() })
    }

    @Test
    fun `clear empties everything`() {
        val q = OutgoingQueue()
        q.offer(b(1), Priority.STOP); q.offer(b(2), Priority.MOTION); q.offer(b(3), Priority.OTHER)
        q.clear()
        assertNull(q.poll())
    }

    @Test
    fun `take suspends until a frame is offered`() = runTest {
        val q = OutgoingQueue()
        val taken = async { q.take() }
        runCurrent()
        assertFalse(taken.isCompleted)
        q.offer(b(7), Priority.OTHER)
        runCurrent()
        assertTrue(taken.isCompleted)
        assertEquals(7, taken.await()[0].toInt())
    }
}
