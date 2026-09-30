package com.mecanumbot.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WireTest {
    @Test
    fun `toWire rounds half away from zero and clamps`() {
        assertEquals(64, Wire.toWire(0.5f))
        assertEquals(-64, Wire.toWire(-0.5f))
        assertEquals(127, Wire.toWire(1f))
        assertEquals(127, Wire.toWire(2f))
        assertEquals(-127, Wire.toWire(-2f))
        assertEquals(0, Wire.toWire(0f))
        assertEquals(32, Wire.toWire(0.25f))
    }

    @Test
    fun `fromWire treats -128 as -127`() {
        assertEquals(-1f, Wire.fromWire(-128))
        assertEquals(1f, Wire.fromWire(127))
        assertEquals(64 / 127f, Wire.fromWire(64))
    }
}
