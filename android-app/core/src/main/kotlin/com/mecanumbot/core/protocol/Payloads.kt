package com.mecanumbot.core.protocol

/** Typed payloads of every frame type — PROTOCOL.md §4. Speeds are raw i8 wire values. */
sealed interface Payload {
    val type: FrameType
}

/** Byte array with value equality, for payload fields. */
class Bytes(val array: ByteArray) {
    val size: Int get() = array.size

    override fun equals(other: Any?): Boolean = other is Bytes && array.contentEquals(other.array)
    override fun hashCode(): Int = array.contentHashCode()
    override fun toString(): String = array.toHex()

    companion object {
        fun hex(s: String) = Bytes(s.hexToBytes())
    }
}

object AckStatus {
    const val OK = 0
    const val ERR = 1
    const val BUSY = 2
}

object TelemetryFlags {
    const val FAILSAFE = 1
    const val FAULT_A = 2
    const val FAULT_B = 4
    const val RAW = 8
    const val OTA = 16
    const val WIFI = 32
    const val ENABLE = 64
}

object LogLevel {
    const val ERROR = 0
    const val WARN = 1
    const val INFO = 2
    const val DEBUG = 3
}

// --- phone → ESP32 ---

data class Hello(val protoVer: Int, val appMajor: Int, val appMinor: Int) : Payload {
    override val type: FrameType get() = FrameType.HELLO
}

/** flags: bit0 enable, bits 1..2 source. */
data class Drive(val flags: Int, val vx: Int, val vy: Int, val w: Int) : Payload {
    override val type: FrameType get() = FrameType.DRIVE
}

/** m[4]: physical channels M1–M4. */
data class MotorRaw(val m: List<Int>) : Payload {
    override val type: FrameType get() = FrameType.MOTOR_RAW
}

data class Ping(val ts: Long) : Payload {
    override val type: FrameType get() = FrameType.PING
}

data class SetConfig(val config: Config) : Payload {
    override val type: FrameType get() = FrameType.CONFIG
}

data object Stop : Payload {
    override val type: FrameType get() = FrameType.STOP
}

data object GetConfig : Payload {
    override val type: FrameType get() = FrameType.GET_CONFIG
}

data class OtaBegin(val size: Long, val sha256: Bytes) : Payload {
    override val type: FrameType get() = FrameType.OTA_BEGIN
}

data class OtaData(val offset: Long, val data: Bytes) : Payload {
    override val type: FrameType get() = FrameType.OTA_DATA
}

data object OtaEnd : Payload {
    override val type: FrameType get() = FrameType.OTA_END
}

data object OtaAbort : Payload {
    override val type: FrameType get() = FrameType.OTA_ABORT
}

data class WifiOtaEnter(val ssid: String, val pass: String) : Payload {
    override val type: FrameType get() = FrameType.WIFI_OTA_ENTER
}

data object WifiOtaExit : Payload {
    override val type: FrameType get() = FrameType.WIFI_OTA_EXIT
}

data object Reboot : Payload {
    override val type: FrameType get() = FrameType.REBOOT
}

// --- ESP32 → phone ---

data class HelloAck(
    val protoVer: Int,
    val fwMajor: Int,
    val fwMinor: Int,
    val resetReason: Int,
    val resetCount: Int,
) : Payload {
    override val type: FrameType get() = FrameType.HELLO_ACK
}

data class Telemetry(
    val lastSeq: Int,
    val flags: Int,
    val pwm: List<Int>,
    val vmMv: Int,
    val crcErr: Int,
    val rxFrames: Int,
    val uptimeS: Long,
    val fwMajor: Int,
    val fwMinor: Int,
    val loopMaxUs: Int,
) : Payload {
    override val type: FrameType get() = FrameType.TELEMETRY
}

data class Ack(val reqType: Int, val reqSeq: Int, val status: Int) : Payload {
    override val type: FrameType get() = FrameType.ACK
}

data class Log(val level: Int, val text: String) : Payload {
    override val type: FrameType get() = FrameType.LOG
}

data class Pong(val ts: Long) : Payload {
    override val type: FrameType get() = FrameType.PONG
}

data class ConfigData(val config: Config) : Payload {
    override val type: FrameType get() = FrameType.CONFIG_DATA
}

data class WifiStatus(val state: Int, val ip: List<Int>) : Payload {
    override val type: FrameType get() = FrameType.WIFI_STATUS
}
