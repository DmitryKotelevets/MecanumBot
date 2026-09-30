package com.mecanumbot.core.protocol

/** One wire frame: 0xAA | type | len | seq | payload | crc8 — PROTOCOL.md §3. */
class Frame(val type: FrameType, val seq: Int, val payload: ByteArray) {
    init {
        require(payload.size in type.minLen..type.maxLen) { "$type: bad payload length ${payload.size}" }
        require(seq in 0..0xFF) { "seq out of range: $seq" }
    }

    fun toBytes(): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = Protocol.SYNC.toByte()
        out[1] = type.code.toByte()
        out[2] = payload.size.toByte()
        out[3] = seq.toByte()
        payload.copyInto(out, 4)
        out[out.size - 1] = Crc8.compute(out, 1, out.size - 1).toByte()
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && seq == other.seq && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (type.hashCode() * 31 + seq) * 31 + payload.contentHashCode()

    override fun toString(): String = "Frame($type seq=$seq payload=${payload.toHex()})"
}
