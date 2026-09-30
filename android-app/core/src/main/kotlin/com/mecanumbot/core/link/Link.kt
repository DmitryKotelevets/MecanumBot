package com.mecanumbot.core.link

import com.mecanumbot.core.protocol.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState
    data class Error(val message: String) : LinkState
}

/** Outgoing order: STOP > MOTION (DRIVE/MOTOR_RAW) > OTHER — PROTOCOL.md §6. */
enum class Priority { STOP, MOTION, OTHER }

/**
 * Byte transport to the ESP32. Implemented by UsbLink and FakeLink; every hardware access in the
 * app goes through it. The link does not assign seq: RobotSession encodes complete frames.
 */
interface Link {
    val state: StateFlow<LinkState>

    /** Frames accepted by the link's FrameParser. */
    val incoming: Flow<Frame>

    /** Phone-side parser rejects (the phone's crc_err). */
    val parserErrors: StateFlow<Int>

    suspend fun open()

    /** Writes any queued STOP frames first, so STOP ×3 survives a link switch. */
    suspend fun close()

    /** Non-blocking. Dropped unless the link is Connected. */
    fun send(bytes: ByteArray, priority: Priority)
}
