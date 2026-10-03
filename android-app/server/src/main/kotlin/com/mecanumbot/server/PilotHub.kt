package com.mecanumbot.server

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pilots (spec §4.2): the first connection drives, the rest watch; anyone may STOP. Pure, no
 * Ktor. Not thread-safe: call every method from the dispatcher the sessions run on (Main in the
 * app), like RobotSession itself.
 */
class PilotHub(
    private val sessions: StateFlow<RobotSession?>,
    private val video: VideoSource,
    private val clock: () -> Long,
    private val appVersion: String,
) {
    /** One /ws client. [send] must not block: the server drops old messages for a slow client. */
    fun interface Connection {
        fun send(text: String)
    }

    data class Summary(val driverConnected: Boolean, val watchers: Int, val ignored: Int)

    private val connections = mutableListOf<Connection>() // arrival order; the first one drives
    private val lastStatus = HashMap<Connection, StatusMsg>()
    private var ignored = 0
    private var closed = false

    private val _summary = MutableStateFlow(Summary(false, 0, 0))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    private val driver: Connection? get() = connections.firstOrNull()

    /**
     * Service stopping: from now on no pilot can drive. Call before RobotSession.stop() so a held
     * deadman can't re-drive after STOP ×3.
     */
    fun close() {
        closed = true
        sessions.value?.update(Command(0f, 0f, 0f, false, Source.REMOTE))
        connections.clear()
        lastStatus.clear()
        _summary.value = Summary(false, 0, ignored)
    }

    fun connect(c: Connection) {
        if (closed) return
        connections += c
        refresh()
    }

    fun disconnect(c: Connection) {
        val wasDriver = c == driver
        connections.remove(c)
        lastStatus.remove(c)
        // Don't wait for the arbiter's 300 ms expiry.
        if (wasDriver) sessions.value?.update(Command(0f, 0f, 0f, false, Source.REMOTE))
        refresh()
    }

    fun onText(c: Connection, text: String) {
        if (closed) return
        if (c !in connections) return
        when (val m = PilotMessages.decode(text)) {
            null -> {
                ignored++
                refresh()
            }
            is DriveMsg -> if (c == driver) {
                sessions.value?.update(Command(m.vx.unit(), m.vy.unit(), m.w.unit(), m.en, Source.REMOTE))
            }
            StopMsg -> sessions.value?.stop()
            is PingMsg -> c.send(PilotMessages.encode(PongMsg(m.ts)))
        }
    }

    /** 10 Hz from the server: status to whoever's changed, then telemetry to everyone. */
    fun tick() {
        if (closed) return
        refresh()
        val t = PilotMessages.encode(telemetry())
        connections.forEach { it.send(t) }
    }

    fun telemetry(): TelemetryMsg {
        val s = sessions.value?.state?.value
        val t = s?.telemetry
        val frame = video.frames.value
        return TelemetryMsg(
            vm = t?.let { it.telemetry.vmMv / 1000f },
            pwm = t?.telemetry?.pwm,
            failsafe = t?.failsafe,
            fault = t?.let { it.faultA || it.faultB },
            usb = s?.link == LinkState.Connected,
            phase = (s?.phase ?: Phase.DISCONNECTED).name,
            active = s?.activeSource?.name,
            rttMs = s?.rttMs,
            rxFps = t?.telemetry?.rxFrames,
            stops = s?.stops ?: 0,
            tempC = video.tempC.value,
            video = video.level.value.name.lowercase(),
            videoAgeMs = frame?.let { clock() - it.capturedAt },
        )
    }

    fun apiStatus(): ApiStatus {
        val s = sessions.value?.state?.value
        return ApiStatus(
            app = appVersion,
            fw = fw(),
            link = s?.link?.let { it::class.simpleName } ?: "None",
            phase = (s?.phase ?: Phase.DISCONNECTED).name,
            driverConnected = driver != null,
            watchers = (connections.size - 1).coerceAtLeast(0),
            video = video.level.value.name.lowercase(),
            ignored = ignored,
        )
    }

    private fun refresh() {
        connections.forEachIndexed { i, c ->
            val status = StatusMsg(if (i == 0) Role.DRIVER else Role.WATCHER, fw(), appVersion, mode())
            if (lastStatus[c] != status) {
                lastStatus[c] = status
                c.send(PilotMessages.encode(status))
            }
        }
        _summary.value = Summary(driver != null, (connections.size - 1).coerceAtLeast(0), ignored)
    }

    private fun fw(): String? = sessions.value?.state?.value?.helloAck?.let { "${it.fwMajor}.${it.fwMinor}" }

    private fun mode(): String = sessions.value?.mode?.name ?: "AUTO"

    private fun Float.unit(): Float = coerceIn(-1f, 1f)
}
