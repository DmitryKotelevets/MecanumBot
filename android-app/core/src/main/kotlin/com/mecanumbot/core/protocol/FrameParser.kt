package com.mecanumbot.core.protocol

/**
 * Receiver of PROTOCOL.md §3.1. A reject counts in [crcErr] and drops only the 0xAA, so a real
 * frame starting inside the rejected bytes is still found. After a scan the buffer holds at most
 * one incomplete frame (≤ 204 bytes), whatever the size of the fed chunk.
 */
class FrameParser(private val staleMs: Long = 50) {
    private var buf = ByteArray(0)
    private var lastByteAt = 0L

    var crcErr = 0
        private set

    fun feed(bytes: ByteArray, now: Long = 0L): List<Frame> {
        if (bytes.isNotEmpty()) {
            lastByteAt = now
            buf += bytes
        }
        return scan()
    }

    /** Rejects an incomplete frame that got no new bytes for [staleMs] (recommended in §3.1). */
    fun flushStale(now: Long): List<Frame> {
        val out = ArrayList<Frame>()
        while (buf.isNotEmpty() && now - lastByteAt >= staleMs) {
            crcErr++
            buf = buf.copyOfRange(1, buf.size)
            out += scan()
        }
        return out
    }

    fun reset() {
        buf = ByteArray(0)
    }

    private fun scan(): List<Frame> {
        val out = ArrayList<Frame>()
        var i = 0
        while (true) {
            while (i < buf.size && buf[i].u8() != Protocol.SYNC) i++
            val avail = buf.size - i
            if (avail < 2) break
            val type = FrameType.fromCode(buf[i + 1].u8())
            if (type == null) {
                crcErr++; i++; continue
            }
            if (avail < 3) break
            val len = buf[i + 2].u8()
            if (len > Protocol.MAX_PAYLOAD || len < type.minLen || len > type.maxLen) {
                crcErr++; i++; continue
            }
            if (avail < 5 + len) break
            val crcAt = i + 4 + len
            if (Crc8.compute(buf, i + 1, crcAt) != buf[crcAt].u8()) {
                crcErr++; i++; continue
            }
            out += Frame(type, buf[i + 3].u8(), buf.copyOfRange(i + 4, crcAt))
            i = crcAt + 1
        }
        buf = buf.copyOfRange(i, buf.size)
        return out
    }
}
