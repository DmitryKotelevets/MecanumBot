package com.mecanumbot.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** /ws JSON (spec §4.1). The `t` field is the message type. */
val PilotJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed interface Inbound

/** vx right, vy forward, w clockwise, −1..1 (clamped by the hub); [en] is the deadman. */
@Serializable
@SerialName("drive")
data class DriveMsg(val vx: Float, val vy: Float, val w: Float, val en: Boolean) : Inbound

@Serializable
@SerialName("stop")
data object StopMsg : Inbound

@Serializable
@SerialName("ping")
data class PingMsg(val ts: Long) : Inbound

@Serializable
sealed interface Outbound

@Serializable
enum class Role {
    @SerialName("driver") DRIVER,
    @SerialName("watcher") WATCHER,
}

@Serializable
@SerialName("status")
data class StatusMsg(val role: Role, val fw: String?, val app: String, val mode: String) : Outbound

/** Nullable fields are null until the first TELEMETRY frame (or the first video frame / battery reading). */
@Serializable
@SerialName("telemetry")
data class TelemetryMsg(
    val vm: Float?,
    val pwm: List<Int>?,
    val failsafe: Boolean?,
    val fault: Boolean?,
    val usb: Boolean,
    val phase: String,
    val active: String?,
    @SerialName("rtt_ms") val rttMs: Long?,
    @SerialName("rx_fps") val rxFps: Int?,
    val stops: Int,
    @SerialName("temp_c") val tempC: Float?,
    val video: String,
    @SerialName("video_age_ms") val videoAgeMs: Long?,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    val charging: Boolean? = null,
) : Outbound

@Serializable
@SerialName("pong")
data class PongMsg(val ts: Long) : Outbound

/** GET /api/status. */
@Serializable
data class ApiStatus(
    val app: String,
    val fw: String?,
    val link: String,
    val phase: String,
    @SerialName("driver_connected") val driverConnected: Boolean,
    val watchers: Int,
    val video: String,
    val ignored: Int,
)

object PilotMessages {
    /** Null for anything that is not a valid inbound message; the caller counts it and moves on. */
    fun decode(text: String): Inbound? = try {
        PilotJson.decodeFromString(Inbound.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encode(msg: Outbound): String = PilotJson.encodeToString(Outbound.serializer(), msg)
}
