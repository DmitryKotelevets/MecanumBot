package com.mecanumbot.core

import com.mecanumbot.core.protocol.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VectorsSmokeTest {
    @Test
    fun `vectors proto_ver matches the app`() {
        assertEquals(Protocol.PROTO_VER, Vectors.root.int("proto_ver"))
    }
}
