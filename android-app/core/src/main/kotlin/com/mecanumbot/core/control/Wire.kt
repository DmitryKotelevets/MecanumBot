package com.mecanumbot.core.control

import kotlin.math.floor

/** −1..1 ⇄ i8 wire speed — PROTOCOL.md §2. Same rounding as firmware kin::toWire (roundf). */
object Wire {
    fun toWire(v: Float): Int {
        val r = v.coerceIn(-1f, 1f) * 127f
        return (if (r >= 0f) floor(r + 0.5f) else -floor(-r + 0.5f)).toInt()
    }

    fun fromWire(v: Int): Float = v.coerceIn(-127, 127) / 127f
}
