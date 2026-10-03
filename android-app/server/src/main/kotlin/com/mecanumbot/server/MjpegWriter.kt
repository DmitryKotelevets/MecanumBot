package com.mecanumbot.server

/** multipart/x-mixed-replace framing for GET /stream (spec §6). */
object MjpegWriter {
    const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=frame"

    fun part(jpeg: ByteArray): ByteArray {
        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
        return header.toByteArray(Charsets.US_ASCII) + jpeg + CRLF
    }

    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
}
