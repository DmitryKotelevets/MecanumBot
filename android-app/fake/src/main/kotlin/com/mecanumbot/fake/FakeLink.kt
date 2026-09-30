package com.mecanumbot.fake

import com.mecanumbot.core.link.Link
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.link.OutgoingQueue
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.FrameParser
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Link to a simulated ESP32 ([FakeEsp32]). Frames travel as bytes through real parsers in both
 * directions, so the UI and the codec are exercised exactly as with UsbLink.
 */
class FakeLink(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) : Link {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    override val incoming: Flow<Frame> = _incoming.asSharedFlow()

    private val _parserErrors = MutableStateFlow(0)
    override val parserErrors: StateFlow<Int> = _parserErrors.asStateFlow()

    private val phoneParser = FrameParser()
    private val espParser = FrameParser()
    private val queue = OutgoingQueue()
    private var espSeq = 0
    private var booted = false
    private var job: Job? = null

    internal var dropPercent = 0

    val esp = FakeEsp32(::fromEsp)
    val faults = FaultControls(this)

    override suspend fun open() = connect()

    override suspend fun close() = disconnect()

    override fun send(bytes: ByteArray, priority: Priority) {
        if (_state.value == LinkState.Connected) queue.offer(bytes, priority)
    }

    internal fun now(): Long = clock()

    internal fun connect() {
        if (_state.value == LinkState.Connected) return
        if (!booted) {
            esp.boot(clock())
            booted = true
        }
        _state.value = LinkState.Connected
        job = scope.launch {
            launch { while (true) toEsp(queue.take()) }
            launch {
                while (true) {
                    val t = clock()
                    esp.tick(t)
                    phoneParser.flushStale(t).forEach(::deliver)
                    espParser.flushStale(t).forEach { esp.onFrame(it, t) }
                    delay(TICK_MS)
                }
            }
        }
        esp.announce() // the firmware sends HELLO_ACK when the host connects (PROTOCOL.md §5.6)
    }

    internal fun disconnect() {
        if (_state.value != LinkState.Connected) return
        queue.drainStops().forEach(::toEsp)
        job?.cancel()
        job = null
        queue.clear()
        _state.value = LinkState.Disconnected
    }

    internal fun injectGarbage() {
        toPhone(GARBAGE)
        espParser.feed(GARBAGE, clock()).forEach { esp.onFrame(it, clock()) }
        esp.crcErr = espParser.crcErr
    }

    private fun toEsp(bytes: ByteArray) {
        if (dropPercent > 0 && random.nextInt(100) < dropPercent) return
        val t = clock()
        espParser.feed(bytes, t).forEach { esp.onFrame(it, t) }
        esp.crcErr = espParser.crcErr
    }

    private fun fromEsp(p: Payload) {
        if (_state.value != LinkState.Connected) return
        val bytes = FrameCodec.encode(p, espSeq)
        espSeq = (espSeq + 1) and 0xFF
        toPhone(bytes)
    }

    private fun toPhone(bytes: ByteArray) {
        phoneParser.feed(bytes, clock()).forEach(::deliver)
        _parserErrors.value = phoneParser.crcErr
    }

    private fun deliver(frame: Frame) {
        _incoming.tryEmit(frame)
    }

    private companion object {
        const val TICK_MS = 10L

        /** Unknown type after a sync byte, then a stray sync byte right before the next real frame. */
        val GARBAGE = "aa55001337aa".hexToBytes()
    }
}
