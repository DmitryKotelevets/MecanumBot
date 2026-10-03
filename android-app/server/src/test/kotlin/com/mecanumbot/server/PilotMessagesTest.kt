package com.mecanumbot.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PilotMessagesTest {
    @Test
    fun `decodes every inbound message`() {
        assertEquals(DriveMsg(0f, 0.5f, -0.25f, true), PilotMessages.decode("""{"t":"drive","vx":0.0,"vy":0.5,"w":-0.25,"en":true}"""))
        assertEquals(StopMsg, PilotMessages.decode("""{"t":"stop"}"""))
        assertEquals(PingMsg(1234), PilotMessages.decode("""{"t":"ping","ts":1234}"""))
    }

    @Test
    fun `extra fields are ignored, like DESIGN's old seq`() {
        assertEquals(DriveMsg(0f, 1f, 0f, false), PilotMessages.decode("""{"t":"drive","seq":7,"vx":0,"vy":1,"w":0,"en":false}"""))
    }

    @Test
    fun `anything invalid decodes to null`() {
        listOf(
            "",
            "not json",
            "[]",
            "{}",
            """{"t":"fly"}""",
            """{"t":"drive","vx":0,"vy":0,"w":0}""",
            """{"t":"drive","vx":"fast","vy":0,"w":0,"en":true}""",
            """{"t":"drive","vx":NaN,"vy":0,"w":0,"en":true}""",
            """{"t":"ping"}""",
            """{"t":"drive","vx":0,"vy":0,"w":0,"en":true""",
        ).forEach { assertNull(PilotMessages.decode(it), it) }
    }

    @Test
    fun `encodes outbound messages with t and snake_case fields`() {
        assertEquals(
            """{"t":"status","role":"driver","fw":"0.1","app":"0.1","mode":"AUTO"}""",
            PilotMessages.encode(StatusMsg(Role.DRIVER, "0.1", "0.1", "AUTO")),
        )
        assertEquals("""{"t":"pong","ts":5}""", PilotMessages.encode(PongMsg(5)))
        assertTrue(
            PilotMessages.encode(
                TelemetryMsg(null, null, null, null, false, "READY", null, null, null, 0, null, "normal", null, batteryPct = 76, charging = true),
            ).endsWith(""""battery_pct":76,"charging":true}"""),
        )
        assertEquals(
            """{"t":"telemetry","vm":null,"pwm":null,"failsafe":null,"fault":null,"usb":false,"phase":"DISCONNECTED",""" +
                """"active":null,"rtt_ms":null,"rx_fps":null,"stops":0,"temp_c":null,"video":"off","video_age_ms":null,"battery_pct":null,"charging":null}""",
            PilotMessages.encode(TelemetryMsg(null, null, null, null, false, "DISCONNECTED", null, null, null, 0, null, "off", null)),
        )
    }
}
