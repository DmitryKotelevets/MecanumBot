package com.mecanumbot.core.protocol

/** Frame types of both directions with their payload length range — PROTOCOL.md §4. */
enum class FrameType(val code: Int, val minLen: Int, val maxLen: Int) {
    HELLO(0x00, 3, 3),
    DRIVE(0x01, 4, 4),
    MOTOR_RAW(0x02, 4, 4),
    PING(0x03, 4, 4),
    CONFIG(0x04, 22, 22),
    STOP(0x05, 0, 0),
    GET_CONFIG(0x06, 0, 0),
    OTA_BEGIN(0x10, 36, 36),
    OTA_DATA(0x11, 5, 196),
    OTA_END(0x12, 0, 0),
    OTA_ABORT(0x13, 0, 0),
    WIFI_OTA_ENTER(0x20, 2, 97),
    WIFI_OTA_EXIT(0x21, 0, 0),
    REBOOT(0x7F, 0, 0),
    HELLO_ACK(0x80, 6, 6),
    TELEMETRY(0x81, 20, 20),
    ACK(0x82, 3, 3),
    LOG(0x83, 1, 200),
    PONG(0x84, 4, 4),
    CONFIG_DATA(0x85, 22, 22),
    WIFI_STATUS(0x86, 5, 5);

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): FrameType? = byCode[code]
    }
}
