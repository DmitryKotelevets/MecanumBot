package com.mecanumbot.core.protocol

/** CONFIG / CONFIG_DATA payload — PROTOCOL.md §4.4. Per-wheel lists are FL, FR, RL, RR. */
data class Config(
    val version: Int = 1,
    val map: List<Int> = listOf(0, 1, 2, 3),
    val invert: List<Int> = listOf(0, 0, 0, 0),
    val maxDuty: Int = 100,
    val slewMs: Int = 250,
    val trim: List<Int> = listOf(100, 100, 100, 100),
    val brake: Int = 1,
    val minDuty: Int = 15,
    val failsafeMs: Int = 300,
    val pwmHz: Int = 20000,
) {
    /** Mirrors firmware lib/protocol validate(). */
    fun isValid(): Boolean {
        if (version != 1) return false
        if (map.size != 4 || invert.size != 4 || trim.size != 4) return false
        var seen = 0
        for (i in 0 until 4) {
            if (map[i] !in 0..3) return false
            seen = seen or (1 shl map[i])
            if (invert[i] !in 0..1) return false
            if (trim[i] !in 50..100) return false
        }
        if (seen != 0x0F) return false
        if (maxDuty !in 1..100) return false
        if (minDuty < 0 || minDuty >= maxDuty) return false
        if (slewMs !in 0..2000) return false
        if (brake !in 0..1) return false
        if (failsafeMs !in 100..1000) return false
        if (pwmHz !in 1000..30000) return false
        return true
    }

    companion object {
        val DEFAULT = Config()
    }
}
