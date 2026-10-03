package com.mecanumbot.app

import com.mecanumbot.camera.MjpegCamera
import com.mecanumbot.camera.ThermalGuard
import com.mecanumbot.camera.ThermalLevel
import com.mecanumbot.server.JpegFrame
import com.mecanumbot.server.VideoLevel
import com.mecanumbot.server.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adapts [MjpegCamera] + [ThermalGuard] to the server's [VideoSource] (spec §2). [camera] is null
 * when the service could not start as a camera service: video then stays OFF. [mainScope] runs on
 * Main; viewer changes from Ktor threads are posted there.
 */
class CameraVideo(private val camera: MjpegCamera?, private val mainScope: CoroutineScope) : VideoSource {
    private val guard = ThermalGuard()
    private val viewers = AtomicInteger()

    override val frames: StateFlow<JpegFrame?> =
        (camera?.frames?.map { f -> f?.let { JpegFrame(it.bytes, it.capturedAt, it.n) } } ?: flowOf(null))
            .stateIn(mainScope, SharingStarted.Eagerly, null)

    private val _level = MutableStateFlow(if (camera == null) VideoLevel.OFF else VideoLevel.NORMAL)
    override val level: StateFlow<VideoLevel> = _level.asStateFlow()

    private val _tempC = MutableStateFlow<Float?>(null)
    override val tempC: StateFlow<Float?> = _tempC.asStateFlow()

    override fun addViewer() {
        viewers.incrementAndGet()
        sync()
    }

    override fun removeViewer() {
        viewers.decrementAndGet()
        sync()
    }

    /** Main thread, every 5 s from RobotService. */
    fun onTemperature(celsius: Float) {
        _tempC.value = celsius
        val cam = camera ?: return
        val t = guard.update(celsius)
        _level.value = when (t) {
            ThermalLevel.NORMAL -> VideoLevel.NORMAL
            ThermalLevel.REDUCED -> VideoLevel.REDUCED
            ThermalLevel.OFF -> VideoLevel.OFF
        }
        cam.setLevel(t)
    }

    // Reads the count when it runs on Main, so racing add/remove calls settle on the right state.
    private fun sync() {
        val cam = camera ?: return
        mainScope.launch { cam.setActive(viewers.get() > 0) }
    }
}
