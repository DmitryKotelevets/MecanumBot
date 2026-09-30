package com.mecanumbot.core.protocol

import com.mecanumbot.core.Vectors
import com.mecanumbot.core.int
import com.mecanumbot.core.str
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class Crc8Test {
    @TestFactory
    fun `crc8 vectors`(): List<DynamicTest> = Vectors.crc8().map { v ->
        dynamicTest(v.str("name")) {
            assertEquals(v.int("crc"), Crc8.compute(v.str("data_hex").hexToBytes()))
        }
    }

    @Test
    fun `check value of 123456789 is F4`() {
        assertEquals(0xF4, Crc8.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun `range overload covers only the given bytes`() {
        val data = "ff313233343536373839ff".hexToBytes()
        assertEquals(0xF4, Crc8.compute(data, 1, data.size - 1))
    }
}
