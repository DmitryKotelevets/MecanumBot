package com.mecanumbot.server

import kotlinx.coroutines.flow.StateFlow

/** One encoded camera frame. [n] increases by one per frame; [capturedAt] uses the app clock (ms). */
class JpegFrame(val bytes: ByteArray, val capturedAt: Long, val n: Long)

enum class VideoLevel { NORMAL, REDUCED, OFF }

/**
 * What the server needs from the camera (spec §2). The app adapts the camera and the thermal guard
 * to it. Implementations must be thread-safe: Ktor calls them from its own threads.
 */
interface VideoSource {
    /** Latest frame, or null while the camera is idle or off. */
    val frames: StateFlow<JpegFrame?>
    val level: StateFlow<VideoLevel>
    val tempC: StateFlow<Float?>

    /** A /stream client or snapshot request started; the camera runs while the count is above 0. */
    fun addViewer()
    fun removeViewer()
}
