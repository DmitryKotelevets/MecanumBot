package com.mecanumbot.camera

enum class ThermalLevel { NORMAL, REDUCED, OFF }

/**
 * Battery temperature → video level (spec §5.1). Up at 40 / 45 °C, down below 43 / 38 °C, one
 * step per reading. Pure; the caller reads the temperature every 5 s. Never affects driving.
 */
class ThermalGuard {
    var level: ThermalLevel = ThermalLevel.NORMAL
        private set

    fun update(tempC: Float): ThermalLevel {
        level = when (level) {
            ThermalLevel.NORMAL -> if (tempC >= REDUCE_AT) ThermalLevel.REDUCED else ThermalLevel.NORMAL
            ThermalLevel.REDUCED -> when {
                tempC >= OFF_AT -> ThermalLevel.OFF
                tempC < NORMAL_BELOW -> ThermalLevel.NORMAL
                else -> ThermalLevel.REDUCED
            }
            ThermalLevel.OFF -> if (tempC < REDUCED_BELOW) ThermalLevel.REDUCED else ThermalLevel.OFF
        }
        return level
    }

    companion object {
        const val REDUCE_AT = 40f
        const val OFF_AT = 45f
        const val REDUCED_BELOW = 43f
        const val NORMAL_BELOW = 38f
    }
}
