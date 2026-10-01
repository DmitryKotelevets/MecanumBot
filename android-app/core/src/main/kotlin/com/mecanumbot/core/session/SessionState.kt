package com.mecanumbot.core.session

import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Telemetry
import com.mecanumbot.core.protocol.TelemetryFlags

enum class Phase { DISCONNECTED, HANDSHAKING, VERSION_MISMATCH, READY }

/** Outcome of the last [RobotSession.sendConfig]. */
sealed interface ConfigWrite {
    data object Idle : ConfigWrite
    data object Pending : ConfigWrite

    /** ACK OK: applied and stored in NVS; the session re-reads CONFIG_DATA. */
    data object Saved : ConfigWrite

    /** ACK ERR (invalid for the firmware) or BUSY (OTA/Wi-Fi mode): nothing changed. */
    data class Rejected(val status: Int) : ConfigWrite

    /** No ACK in time, or the link went down while waiting. */
    data object NoAnswer : ConfigWrite
}

data class TelemetryState(val telemetry: Telemetry, val receivedAt: Long) {
    val failsafe: Boolean get() = has(TelemetryFlags.FAILSAFE)
    val faultA: Boolean get() = has(TelemetryFlags.FAULT_A)
    val faultB: Boolean get() = has(TelemetryFlags.FAULT_B)
    val rawMode: Boolean get() = has(TelemetryFlags.RAW)
    val otaMode: Boolean get() = has(TelemetryFlags.OTA)
    val wifiMode: Boolean get() = has(TelemetryFlags.WIFI)
    val enabled: Boolean get() = has(TelemetryFlags.ENABLE)

    private fun has(bit: Int) = telemetry.flags and bit != 0
}

data class SessionState(
    val link: LinkState = LinkState.Disconnected,
    val phase: Phase = Phase.DISCONNECTED,
    val helloAck: HelloAck? = null,
    val telemetry: TelemetryState? = null,
    val config: Config? = null,
    val rttMs: Long? = null,
    /** DRIVE/MOTOR_RAW frames sent during the last second; compare with telemetry rx_frames. */
    val motionSentPerSec: Int = 0,
    val phoneParserErrors: Int = 0,
    val activeSource: Source? = null,
    val lastEvent: SessionEvent? = null,
    /** Incremented by every stop(); the UI uses it to drop held input so motion needs a new press. */
    val stops: Int = 0,
    val configWrite: ConfigWrite = ConfigWrite.Idle,
)
