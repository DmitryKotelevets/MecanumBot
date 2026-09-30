package com.mecanumbot.core.protocol

import com.mecanumbot.core.Vectors
import com.mecanumbot.core.int
import com.mecanumbot.core.str
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrameTypeTest {
    @Test
    fun `table matches vectors types`() {
        val types = Vectors.array("types")
        assertEquals(types.size, FrameType.entries.size)
        for (t in types) {
            val ft = FrameType.fromCode(t.int("type"))
            assertNotNull(ft, t.str("name"))
            assertEquals(t.str("name"), ft!!.name)
            assertEquals(t.int("min_len"), ft.minLen, ft.name)
            assertEquals(t.int("max_len"), ft.maxLen, ft.name)
        }
    }

    @Test
    fun `unknown codes are null`() {
        assertNull(FrameType.fromCode(0x55))
        assertNull(FrameType.fromCode(0xAA))
    }

    @Test
    fun `frame bytes match the stop vector`() {
        assertEquals("aa05000bf1", Frame(FrameType.STOP, 11, ByteArray(0)).toBytes().toHex())
    }

    @Test
    fun `frame rejects a payload length outside the type range`() {
        assertThrows<IllegalArgumentException> { Frame(FrameType.DRIVE, 0, ByteArray(5)) }
    }

    @Test
    fun `frames with equal content are equal`() {
        assertEquals(Frame(FrameType.PING, 7, "01000000".hexToBytes()), Frame(FrameType.PING, 7, "01000000".hexToBytes()))
    }

    @Test
    fun `hex round trip`() {
        assertEquals("00ff7faa", "00ff7faa".hexToBytes().toHex())
    }
}
