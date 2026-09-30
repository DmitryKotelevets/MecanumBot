package com.mecanumbot.core.protocol

/** CRC-8/SMBUS: poly 0x07, init 0x00, no reflect, xorout 0x00 — PROTOCOL.md §3. */
object Crc8 {
    fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1
                crc = crc and 0xFF
            }
        }
        return crc
    }
}
