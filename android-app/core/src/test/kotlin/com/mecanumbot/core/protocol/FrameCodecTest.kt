package com.mecanumbot.core.protocol

import com.mecanumbot.core.Vectors
import com.mecanumbot.core.int
import com.mecanumbot.core.ints
import com.mecanumbot.core.long
import com.mecanumbot.core.obj
import com.mecanumbot.core.str
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class FrameCodecTest {
    private val frames = Vectors.array("frames")

    @Test
    fun `every frame type has a vector`() {
        assertEquals(FrameType.entries.map { it.name }.toSet(), frames.map { it.str("type_name") }.toSet())
    }

    @TestFactory
    fun `encode fields to frame_hex`(): List<DynamicTest> = frames.map { v ->
        dynamicTest(v.str("name")) {
            val payload = payloadOf(v.str("type_name"), v.obj("fields"))
            assertEquals(v.str("payload_hex"), FrameCodec.encodePayload(payload).toHex())
            assertEquals(v.str("frame_hex"), FrameCodec.encode(payload, v.int("seq")).toHex())
        }
    }

    @TestFactory
    fun `decode payload_hex to fields`(): List<DynamicTest> = frames.map { v ->
        dynamicTest(v.str("name")) {
            val type = FrameType.fromCode(v.int("type"))!!
            val frame = Frame(type, v.int("seq"), v.str("payload_hex").hexToBytes())
            assertEquals(payloadOf(v.str("type_name"), v.obj("fields")), FrameCodec.decode(frame))
        }
    }

    @Test
    fun `WIFI_OTA_ENTER with a string running past the payload decodes to null`() {
        assertNull(FrameCodec.decode(Frame(FrameType.WIFI_OTA_ENTER, 0, "0541".hexToBytes())))
    }

    @Test
    fun `WIFI_OTA_ENTER with trailing bytes decodes to null`() {
        assertNull(FrameCodec.decode(Frame(FrameType.WIFI_OTA_ENTER, 0, "000000".hexToBytes())))
    }

    @Test
    fun `config validation follows PROTOCOL 4_4`() {
        assertTrue(Config.DEFAULT.isValid())
        assertFalse(Config(version = 2).isValid())
        assertFalse(Config(map = listOf(0, 0, 2, 3)).isValid())
        assertFalse(Config(invert = listOf(0, 2, 0, 0)).isValid())
        assertFalse(Config(trim = listOf(49, 100, 100, 100)).isValid())
        assertFalse(Config(maxDuty = 0).isValid())
        assertFalse(Config(maxDuty = 20, minDuty = 20).isValid())
        assertFalse(Config(slewMs = 2001).isValid())
        assertFalse(Config(brake = 2).isValid())
        assertFalse(Config(failsafeMs = 99).isValid())
        assertFalse(Config(pwmHz = 30001).isValid())
        assertTrue(Config(map = listOf(1, 0, 3, 2), maxDuty = 80, minDuty = 20).isValid())
    }

    private fun configOf(f: JsonObject) = Config(
        version = f.int("version"),
        map = f.ints("map"),
        invert = f.ints("invert"),
        maxDuty = f.int("max_duty"),
        slewMs = f.int("slew_ms"),
        trim = f.ints("trim"),
        brake = f.int("brake"),
        minDuty = f.int("min_duty"),
        failsafeMs = f.int("failsafe_ms"),
        pwmHz = f.int("pwm_hz"),
    )

    /** Canonical field names as gen_vectors.py writes them (PROTOCOL.md §8). */
    private fun payloadOf(typeName: String, f: JsonObject): Payload = when (typeName) {
        "HELLO" -> Hello(f.int("proto_ver"), f.int("app_major"), f.int("app_minor"))
        "DRIVE" -> Drive(f.int("flags"), f.int("vx"), f.int("vy"), f.int("w"))
        "MOTOR_RAW" -> MotorRaw(f.ints("m"))
        "PING" -> Ping(f.long("ts"))
        "CONFIG" -> SetConfig(configOf(f))
        "STOP" -> Stop
        "GET_CONFIG" -> GetConfig
        "OTA_BEGIN" -> OtaBegin(f.long("size"), Bytes.hex(f.str("sha256")))
        "OTA_DATA" -> OtaData(f.long("offset"), Bytes.hex(f.str("data")))
        "OTA_END" -> OtaEnd
        "OTA_ABORT" -> OtaAbort
        "WIFI_OTA_ENTER" -> WifiOtaEnter(f.str("ssid"), f.str("pass"))
        "WIFI_OTA_EXIT" -> WifiOtaExit
        "REBOOT" -> Reboot
        "HELLO_ACK" -> HelloAck(
            f.int("proto_ver"), f.int("fw_major"), f.int("fw_minor"), f.int("reset_reason"), f.int("reset_count"),
        )
        "TELEMETRY" -> Telemetry(
            lastSeq = f.int("last_seq"),
            flags = f.int("flags"),
            pwm = f.ints("pwm"),
            vmMv = f.int("vm_mv"),
            crcErr = f.int("crc_err"),
            rxFrames = f.int("rx_frames"),
            uptimeS = f.long("uptime_s"),
            fwMajor = f.int("fw_major"),
            fwMinor = f.int("fw_minor"),
            loopMaxUs = f.int("loop_max_us"),
        )
        "ACK" -> Ack(f.int("req_type"), f.int("req_seq"), f.int("status"))
        "LOG" -> Log(f.int("level"), f.str("text"))
        "PONG" -> Pong(f.long("ts"))
        "CONFIG_DATA" -> ConfigData(configOf(f))
        "WIFI_STATUS" -> WifiStatus(f.int("state"), f.ints("ip"))
        else -> error("unknown type $typeName")
    }
}
