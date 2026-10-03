package com.mecanumbot.server

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MjpegWriterTest {
    @Test
    fun `part is boundary, headers, bytes, CRLF`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val expected = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n".toByteArray() + jpeg + "\r\n".toByteArray()
        assertArrayEquals(expected, MjpegWriter.part(jpeg))
    }

    @Test
    fun `content type names the same boundary`() {
        assertEquals("multipart/x-mixed-replace; boundary=frame", MjpegWriter.CONTENT_TYPE)
    }
}
