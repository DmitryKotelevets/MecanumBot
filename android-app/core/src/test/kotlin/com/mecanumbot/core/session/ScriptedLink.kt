package com.mecanumbot.core.session

import com.mecanumbot.core.link.Link
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.FrameParser
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Protocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** In-memory Link: records what the session sends (decoded) and injects frames from the "ESP32". */
class ScriptedLink : Link {
    data class Sent(val frame: Frame, val priority: Priority) {
        val payload: Payload get() = FrameCodec.decode(frame)!!
    }

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state
    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
    override val incoming: Flow<Frame> = _incoming
    override val parserErrors = MutableStateFlow(0)

    private val parser = FrameParser()
    val sent = mutableListOf<Sent>()
    var sendsWhileDisconnected = 0
        private set

    override suspend fun open() = connect()
    override suspend fun close() = disconnect()

    fun connect() { _state.value = LinkState.Connected }
    fun disconnect() { _state.value = LinkState.Disconnected }

    override fun send(bytes: ByteArray, priority: Priority) {
        if (_state.value != LinkState.Connected) { sendsWhileDisconnected++; return }
        parser.feed(bytes).forEach { sent += Sent(it, priority) }
    }

    fun receive(p: Payload, seq: Int = 0) {
        check(_incoming.tryEmit(Frame(p.type, seq, FrameCodec.encodePayload(p))))
    }

    fun payloads(): List<Payload> = sent.map { it.payload }
    fun ofType(type: FrameType): List<Sent> = sent.filter { it.frame.type == type }
    fun lastMotion(): Payload =
        sent.last { it.frame.type == FrameType.DRIVE || it.frame.type == FrameType.MOTOR_RAW }.payload
}

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.newSession(link: ScriptedLink): RobotSession =
    RobotSession(link, backgroundScope, { testScheduler.currentTime }).also {
        it.start()
        runCurrent()
    }

/** Session in READY at the current virtual time, with the send log cleared. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.readySession(link: ScriptedLink, resetCount: Int = 5): RobotSession {
    val s = newSession(link)
    link.connect(); runCurrent()
    link.receive(HelloAck(Protocol.PROTO_VER, 0, 1, 1, resetCount)); runCurrent()
    check(s.state.value.phase == Phase.READY)
    link.sent.clear()
    return s
}
