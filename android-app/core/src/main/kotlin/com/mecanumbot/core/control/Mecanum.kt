package com.mecanumbot.core.control

import com.mecanumbot.core.protocol.Config
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Mecanum mix and per-wheel calibration — port of firmware lib/kinematics (DESIGN.md §4.4). */
object Mecanum {
    const val FL = 0
    const val FR = 1
    const val RL = 2
    const val RR = 3
    const val DEAD_ZONE = 0.02f

    /** Wheel speeds FL, FR, RL, RR scaled so the largest magnitude is at most 1. */
    fun mix(vx: Float, vy: Float, w: Float): FloatArray {
        val wheels = floatArrayOf(vy + vx + w, vy - vx - w, vy - vx + w, vy + vx - w)
        var m = 1f
        for (v in wheels) m = max(m, abs(v))
        return FloatArray(4) { wheels[it] / m }
    }

    /** Wheel speeds → signed duty (−1..1) per physical channel: dead zone, trim, invert, duty range, map. */
    fun toChannels(wheels: FloatArray, c: Config): FloatArray {
        val channels = FloatArray(4)
        val lo = c.minDuty / 100f
        val hi = c.maxDuty / 100f
        for (i in 0 until 4) {
            var v = wheels[i]
            if (abs(v) < DEAD_ZONE) continue
            v *= c.trim[i] / 100f
            if (c.invert[i] != 0) v = -v
            val mag = lo + min(abs(v), 1f) * (hi - lo)
            channels[c.map[i] and 3] = if (v < 0) -mag else mag
        }
        return channels
    }

    /** DRIVE command → channel duties. enable == false gives all zeros. */
    fun drive(vx: Int, vy: Int, w: Int, enable: Boolean, c: Config): FloatArray {
        val wheels = if (enable) mix(Wire.fromWire(vx), Wire.fromWire(vy), Wire.fromWire(w)) else FloatArray(4)
        return toChannels(wheels, c)
    }
}
