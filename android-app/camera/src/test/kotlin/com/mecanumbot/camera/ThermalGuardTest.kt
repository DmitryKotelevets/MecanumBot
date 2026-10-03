package com.mecanumbot.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThermalGuardTest {
    private fun ThermalGuard.feed(vararg temps: Float): List<ThermalLevel> = temps.map { update(it) }

    @Test
    fun `starts normal and stays normal below 40`() {
        assertEquals(listOf(ThermalLevel.NORMAL, ThermalLevel.NORMAL), ThermalGuard().feed(25f, 39.9f))
    }

    @Test
    fun `reduces at 40 and turns off at 45`() {
        assertEquals(listOf(ThermalLevel.REDUCED, ThermalLevel.REDUCED, ThermalLevel.OFF), ThermalGuard().feed(40f, 44.9f, 45f))
    }

    @Test
    fun `one step per reading even when very hot`() {
        assertEquals(listOf(ThermalLevel.REDUCED, ThermalLevel.OFF), ThermalGuard().feed(60f, 60f))
    }

    @Test
    fun `off needs below 43 to come back, reduced needs below 38`() {
        val g = ThermalGuard()
        g.feed(40f, 45f)
        assertEquals(
            listOf(ThermalLevel.OFF, ThermalLevel.REDUCED, ThermalLevel.REDUCED, ThermalLevel.NORMAL),
            g.feed(43f, 42.9f, 38f, 37.9f),
        )
    }

    @Test
    fun `cooling from off steps through reduced`() {
        val g = ThermalGuard()
        g.feed(40f, 45f)
        assertEquals(listOf(ThermalLevel.REDUCED, ThermalLevel.NORMAL), g.feed(20f, 20f))
    }
}
