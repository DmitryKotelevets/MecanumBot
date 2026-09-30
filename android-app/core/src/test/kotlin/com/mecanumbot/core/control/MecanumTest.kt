package com.mecanumbot.core.control

import com.mecanumbot.core.control.Mecanum.FL
import com.mecanumbot.core.control.Mecanum.FR
import com.mecanumbot.core.control.Mecanum.RL
import com.mecanumbot.core.control.Mecanum.RR
import com.mecanumbot.core.protocol.Config
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.sign

class MecanumTest {
    private fun signs(a: FloatArray) = a.map { it.sign.toInt() }

    @Test
    fun `vx +1 drives right - FL+ FR- RL- RR+ (PROTOCOL 2_1)`() {
        assertEquals(listOf(1, -1, -1, 1), signs(Mecanum.mix(1f, 0f, 0f)))
    }

    @Test
    fun `w +1 turns clockwise - left wheels forward, right back`() {
        val m = Mecanum.mix(0f, 0f, 1f)
        assertEquals(listOf(1, -1, 1, -1), signs(m))
    }

    @Test
    fun `vy +1 drives all wheels forward at full speed`() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), Mecanum.mix(0f, 1f, 0f))
    }

    @Test
    fun `diagonal forward-right uses FL and RR only`() {
        val m = Mecanum.mix(1f, 1f, 0f)
        assertEquals(1f, m[FL]); assertEquals(0f, m[FR]); assertEquals(0f, m[RL]); assertEquals(1f, m[RR])
    }

    @Test
    fun `normalizes so the largest wheel is 1`() {
        val m = Mecanum.mix(1f, 1f, 1f) // FL = 3 before scaling
        assertEquals(1f, m[FL], 1e-6f)
        assertEquals(-1f / 3f, m[FR], 1e-6f)
    }

    @Test
    fun `default config maps wheel speed into min_duty-max_duty`() {
        val ch = Mecanum.toChannels(floatArrayOf(1f, 0.5f, -1f, 0f), Config.DEFAULT)
        assertEquals(1f, ch[0], 1e-6f)
        assertEquals(0.15f + 0.5f * 0.85f, ch[1], 1e-6f)
        assertEquals(-1f, ch[2], 1e-6f)
        assertEquals(0f, ch[3])
    }

    @Test
    fun `dead zone below 0_02 gives zero`() {
        assertEquals(0f, Mecanum.toChannels(floatArrayOf(0.01f, 0f, 0f, 0f), Config.DEFAULT)[0])
    }

    @Test
    fun `map, invert and trim are applied per wheel`() {
        val c = Config(map = listOf(1, 0, 3, 2), invert = listOf(0, 1, 0, 0), trim = listOf(50, 100, 100, 100), minDuty = 0)
        val ch = Mecanum.toChannels(floatArrayOf(1f, 1f, 0f, 0f), c)
        assertEquals(0.5f, ch[1], 1e-6f)  // FL → channel 1, trimmed to 50 %
        assertEquals(-1f, ch[0], 1e-6f)   // FR → channel 0, inverted
    }

    @Test
    fun `drive with enable false gives zeros`() {
        assertArrayEquals(FloatArray(4), Mecanum.drive(0, 127, 0, false, Config.DEFAULT))
    }
}
