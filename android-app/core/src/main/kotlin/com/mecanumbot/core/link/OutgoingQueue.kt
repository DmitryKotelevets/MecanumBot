package com.mecanumbot.core.link

import kotlinx.coroutines.channels.Channel

/**
 * Outgoing frames by priority. An unsent MOTION frame is replaced by the newer one instead of
 * queuing (PROTOCOL.md §6). STOP
 * supersedes any unsent MOTION frame (it is dropped; the next tick sends a fresh one). Thread-safe: offered from the UI thread, taken by a writer coroutine.
 */
class OutgoingQueue(private val maxOther: Int = 64) {
    private val lock = Any()
    private val stops = ArrayDeque<ByteArray>()
    private var motion: ByteArray? = null
    private val others = ArrayDeque<ByteArray>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    fun offer(bytes: ByteArray, priority: Priority) {
        synchronized(lock) {
            when (priority) {
                Priority.STOP -> {
                    stops.addLast(bytes)
                    motion = null // STOP supersedes unsent motion; the next tick sends a fresh frame.
                }
                Priority.MOTION -> motion = bytes
                Priority.OTHER -> {
                    if (others.size >= maxOther) others.removeFirst()
                    others.addLast(bytes)
                }
            }
        }
        signal.trySend(Unit)
    }

    fun poll(): ByteArray? = synchronized(lock) {
        stops.removeFirstOrNull() ?: motion?.also { motion = null } ?: others.removeFirstOrNull()
    }

    suspend fun take(): ByteArray {
        while (true) {
            poll()?.let { return it }
            signal.receive()
        }
    }

    fun drainStops(): List<ByteArray> = synchronized(lock) { stops.toList().also { stops.clear() } }

    fun clear() = synchronized(lock) {
        stops.clear()
        motion = null
        others.clear()
    }
}
