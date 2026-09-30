package com.mecanumbot.core.protocol

import com.mecanumbot.core.Vectors
import com.mecanumbot.core.int
import com.mecanumbot.core.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class FrameParserTest {
    private data class Seen(val type: Int, val seq: Int, val payloadHex: String)

    private fun Frame.seen() = Seen(type.code, seq, payload.toHex())

    @TestFactory
    fun `vectors streams`(): List<DynamicTest> = Vectors.array("streams").map { s ->
        dynamicTest(s.str("name")) {
            val parser = FrameParser()
            val got = s.getValue("chunks").jsonArray.flatMap { chunk ->
                parser.feed(chunk.jsonPrimitive.content.hexToBytes()).map { it.seen() }
            }
            val expected = s.getValue("expected_frames").jsonArray.map {
                val o = it.jsonObject
                Seen(o.int("type"), o.int("seq"), o.str("payload_hex"))
            }
            assertEquals(expected, got)
            assertEquals(s.int("expected_crc_err"), parser.crcErr)
        }
    }

    @Test
    fun `one feed with 1000 bytes of garbage before a frame`() {
        val parser = FrameParser()
        val garbage = ByteArray(1000) { 0x55 }
        val frames = parser.feed(garbage + "aa0104010100400054".hexToBytes())
        assertEquals(listOf(Seen(1, 1, "01004000")), frames.map { it.seen() })
        assertEquals(0, parser.crcErr)
    }

    @Test
    fun `garbage full of stray sync bytes is rejected one by one and the frame survives`() {
        val parser = FrameParser()
        val garbage = ByteArray(600) { if (it % 2 == 0) 0xAA.toByte() else 0x55 }
        val frames = parser.feed(garbage + "aa0104010100400054".hexToBytes())
        assertEquals(listOf(Seen(1, 1, "01004000")), frames.map { it.seen() })
        assertEquals(300, parser.crcErr)
    }

    @Test
    fun `stale incomplete frame is dropped after 50 ms of silence`() {
        val parser = FrameParser()
        assertTrue(parser.feed("aa0104".hexToBytes(), now = 0).isEmpty())
        assertTrue(parser.flushStale(now = 49).isEmpty())
        assertEquals(0, parser.crcErr)
        parser.flushStale(now = 50)
        assertEquals(1, parser.crcErr)
        val frames = parser.feed("aa0104010100400054".hexToBytes(), now = 60)
        assertEquals(listOf(Seen(1, 1, "01004000")), frames.map { it.seen() })
    }

    @Test
    fun `flushing a stale long header releases a frame buffered behind it`() {
        val parser = FrameParser()
        // A LOG header with len 200 makes the parser wait for 205 bytes; a real DRIVE sits inside.
        assertTrue(parser.feed("aa83c8".hexToBytes() + "aa0104010100400054".hexToBytes(), now = 0).isEmpty())
        val frames = parser.flushStale(now = 60)
        assertEquals(listOf(Seen(1, 1, "01004000")), frames.map { it.seen() })
        assertEquals(1, parser.crcErr)
    }

    @Test
    fun `reset drops a buffered partial frame`() {
        val parser = FrameParser()
        parser.feed("aa010401".hexToBytes())
        parser.reset()
        // The tail of the dropped frame has no 0xAA and is skipped; the next frame is found.
        val frames = parser.feed("0100400054".hexToBytes() + FrameCodec.encode(Drive(1, 0, 64, 0), 2))
        assertEquals(listOf(Seen(1, 2, "01004000")), frames.map { it.seen() })
        assertEquals(0, parser.crcErr)
    }
}
