package com.mecanumbot.core.protocol

/** Wire constants — protocol/PROTOCOL.md §3. */
object Protocol {
    const val SYNC = 0xAA
    const val PROTO_VER = 1
    const val MAX_PAYLOAD = 200
    const val MAX_FRAME = MAX_PAYLOAD + 5
}

internal fun Byte.u8(): Int = toInt() and 0xFF
