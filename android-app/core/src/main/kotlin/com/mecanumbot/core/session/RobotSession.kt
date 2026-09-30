package com.mecanumbot.core.session

import com.mecanumbot.core.control.Arbiter
import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Mode
import com.mecanumbot.core.control.Output
import com.mecanumbot.core.link.Link
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.ConfigData
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.GetConfig
import com.mecanumbot.core.protocol.Hello
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Log
import com.mecanumbot.core.protocol.MotorRaw
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Ping
import com.mecanumbot.core.protocol.Pong
import com.mecanumbot.core.protocol.Protocol
import com.mecanumbot.core.protocol.Stop
import com.mecanumbot.core.protocol.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The phone side of the protocol — PROTOCOL.md §6, spec §6. Owns seq, the handshake, the 40 Hz
 * tick through the [Arbiter], PING/RTT and STOP ×3. Not thread-safe: call it from the dispatcher
 * of [parentScope].
 */
class RobotSession(
    private val link: Link,
    parentScope: CoroutineScope,
    private val clock: () -> Long,
    private val appMajor: Int = 0,
    private val appMinor: Int = 1,
    private val arbiter: Arbiter = Arbiter(),
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private var seq = 0
    private var phaseJob: Job? = null
    private var lastResetCount: Int? = null
    private var motionSent = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { link.state.collect { onLinkState(it) } }
        scope.launch { link.incoming.collect { onFrame(it) } }
        scope.launch { link.parserErrors.collect { n -> _state.update { it.copy(phoneParserErrors = n) } } }
    }

    fun close() {
        job.cancel()
    }

    fun update(command: Command) = arbiter.update(command, clock())
    fun setRaw(m: List<Int>) = arbiter.setRaw(m, clock())
    fun clearRaw() = arbiter.clearRaw()
    fun setMode(mode: Mode) = arbiter.setMode(mode)

    /** Clears every command and sends STOP ×3 at once, in any phase while the link is up. */
    fun stop() {
        arbiter.stop()
        _state.update { it.copy(stops = it.stops + 1) }
        if (link.state.value == LinkState.Connected) repeat(3) { send(Stop, Priority.STOP) }
    }

    private fun send(p: Payload, priority: Priority) {
        link.send(FrameCodec.encode(p, seq), priority)
        seq = (seq + 1) and 0xFF
    }

    private fun onLinkState(s: LinkState) {
        _state.update { it.copy(link = s) }
        if (s == LinkState.Connected) {
            // A HELLO_ACK may already have moved us on (it can be collected before the state).
            if (_state.value.phase == Phase.DISCONNECTED) enterHandshake()
        } else if (_state.value.phase != Phase.DISCONNECTED) {
            enterDisconnected()
        }
    }

    private fun enterDisconnected() {
        phaseJob?.cancel()
        phaseJob = null
        arbiter.stop()
        _state.update {
            it.copy(phase = Phase.DISCONNECTED, telemetry = null, rttMs = null, motionSentPerSec = 0, activeSource = null)
        }
    }

    private fun enterHandshake() {
        phaseJob?.cancel()
        _state.update { it.copy(phase = Phase.HANDSHAKING) }
        phaseJob = scope.launch {
            var attempt = 0
            while (true) {
                send(Hello(Protocol.PROTO_VER, appMajor, appMinor), Priority.OTHER)
                attempt++
                delay(if (attempt <= 3) 1_000 else 3_000)
                if (attempt == 3) report(SessionEvent.NotResponding)
            }
        }
    }

    private fun enterReady() {
        phaseJob?.cancel()
        _state.update { it.copy(phase = Phase.READY, lastEvent = if (it.lastEvent == SessionEvent.NotResponding) null else it.lastEvent) }
        send(GetConfig, Priority.OTHER)
        phaseJob = scope.launch {
            launch {
                while (true) {
                    tick()
                    delay(TICK_MS)
                }
            }
            launch {
                while (true) {
                    send(Ping(clock() and 0xFFFFFFFFL), Priority.OTHER)
                    delay(1_000)
                }
            }
            launch {
                while (true) {
                    delay(1_000)
                    val n = motionSent
                    motionSent = 0
                    _state.update { it.copy(motionSentPerSec = n) }
                }
            }
        }
    }

    private fun tick() {
        val now = clock()
        when (val out = arbiter.tick(now)) {
            is Output.Drive -> send(Drive(out.flags, out.vx, out.vy, out.w), Priority.MOTION)
            is Output.Raw -> send(MotorRaw(out.m), Priority.MOTION)
        }
        motionSent++
        val active = arbiter.activeSource(now)
        if (active != _state.value.activeSource) _state.update { it.copy(activeSource = active) }
    }

    private fun onFrame(frame: Frame) {
        if (link.state.value != LinkState.Connected) return
        when (val p = FrameCodec.decode(frame)) {
            is HelloAck -> onHelloAck(p)
            is Telemetry -> _state.update { it.copy(telemetry = TelemetryState(p, clock())) }
            is Pong -> _state.update { it.copy(rttMs = (clock() - p.ts) and 0xFFFFFFFFL) }
            is ConfigData -> _state.update { it.copy(config = p.config) }
            is Log -> report(SessionEvent.EspLog(p.level, p.text))
            else -> Unit // ACK and the rest are used from stage 4 on
        }
    }

    private fun onHelloAck(ack: HelloAck) {
        val previous = lastResetCount
        lastResetCount = ack.resetCount
        _state.update { it.copy(helloAck = ack) }
        // reset_count survives reconnects: a real reboot re-enumerates USB, so it shows up here
        // during the handshake. The same count in READY is the duplicate sent on host connect.
        if (previous != null && previous != ack.resetCount) {
            stop()
            report(SessionEvent.Rebooted(ack.resetReason, ack.resetCount))
        }
        if (ack.protoVer != Protocol.PROTO_VER) {
            if (_state.value.phase != Phase.VERSION_MISMATCH) {
                phaseJob?.cancel()
                phaseJob = null
                stop()
                _state.update { it.copy(phase = Phase.VERSION_MISMATCH) }
                report(SessionEvent.VersionMismatch(ack.protoVer))
            }
            return
        }
        if (_state.value.phase != Phase.READY) enterReady()
    }

    /** ESP32 LOG lines go to [events] only, so they never hide a reboot or mismatch in lastEvent. */
    private fun report(e: SessionEvent) {
        if (e !is SessionEvent.EspLog) _state.update { it.copy(lastEvent = e) }
        _events.tryEmit(e)
    }

    companion object {
        const val TICK_MS = 25L
    }
}
