package com.mecanumbot.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class Lens { ULTRA_WIDE, MAIN }

/** One JPEG. [n] increases by one per frame; [capturedAt] uses the clock passed to [MjpegCamera]. */
class CameraFrame(val bytes: ByteArray, val capturedAt: Long, val n: Long)

/**
 * CameraX → JPEG for the MJPEG stream (spec §5). The camera is bound to [owner] (the service) only
 * while [setActive] is true and the level is not OFF, so nobody watching costs nothing. Setters
 * must be called on the main thread; frames are encoded on a private thread.
 */
class MjpegCamera(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _frames = MutableStateFlow<CameraFrame?>(null)
    val frames: StateFlow<CameraFrame?> = _frames.asStateFlow()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var provider: ProcessCameraProvider? = null
    private var loading = false
    private var analysis: ImageAnalysis? = null
    private var released = false

    private var active = false
    private var level = ThermalLevel.NORMAL
    private var lens = Lens.ULTRA_WIDE
    private var rotationDegrees = 0

    @Volatile private var minIntervalMs = 1_000L / NORMAL_FPS
    @Volatile private var lastEmitAt = Long.MIN_VALUE / 2
    @Volatile private var n = 0L
    @Volatile private var encodeErrorLogged = false

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        rebind()
    }

    fun setLevel(level: ThermalLevel) {
        if (this.level == level) return
        this.level = level
        rebind()
    }

    /** [rotationDegrees] is 0, 90, 180 or 270: how the mount turns the picture. */
    fun setOptions(lens: Lens, rotationDegrees: Int) {
        if (this.lens == lens && this.rotationDegrees == rotationDegrees) return
        this.lens = lens
        this.rotationDegrees = rotationDegrees
        rebind()
    }

    fun release() {
        released = true
        unbind()
        executor.shutdown()
    }

    private fun rebind() {
        if (released) return
        val p = provider
        if (p == null) {
            if (!loading) {
                loading = true
                scope.launch {
                    try {
                        provider = ProcessCameraProvider.awaitInstance(context)
                        rebind()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get ProcessCameraProvider", e)
                        loading = false
                    }
                }
            }
            return
        }
        unbind()
        if (!active || level == ThermalLevel.OFF) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        val reduced = level == ThermalLevel.REDUCED
        val size = if (reduced) Size(320, 240) else Size(640, 480)
        minIntervalMs = 1_000L / if (reduced) REDUCED_FPS else NORMAL_FPS
        encodeErrorLogged = false
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true) // CameraX rotates the buffer, so the JPEG is upright
            .setTargetRotation(surfaceRotation(rotationDegrees))
            .build()
        a.setAnalyzer(executor) { image -> analyze(a, image) }
        synchronized(lock) {
            analysis = a
        }
        val camera = try {
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, a)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Failed to bind camera: state error", e)
            synchronized(lock) {
                analysis = null
            }
            return
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to bind camera: argument error", e)
            synchronized(lock) {
                analysis = null
            }
            return
        }
        // Pixels expose the ultra-wide through the back logical camera's zoom below 1×.
        if (lens == Lens.ULTRA_WIDE) {
            val observer = object : Observer<androidx.camera.core.ZoomState> {
                override fun onChanged(state: androidx.camera.core.ZoomState?) {
                    if (state != null) {
                        camera.cameraControl.setZoomRatio(state.minZoomRatio)
                        camera.cameraInfo.zoomState.removeObserver(this)
                    }
                }
            }
            camera.cameraInfo.zoomState.observe(owner, observer)
        } else {
            camera.cameraControl.setZoomRatio(1f)
        }
    }

    private fun unbind() {
        synchronized(lock) {
            analysis?.clearAnalyzer()
            val a = analysis
            analysis = null
            _frames.value = null
            a?.let { provider?.unbind(it) }
        }
    }

    private fun analyze(a: ImageAnalysis, image: ImageProxy) {
        image.use {
            val now = clock()
            if (now - lastEmitAt < minIntervalMs) return
            lastEmitAt = now
            var bitmap: Bitmap? = null
            try {
                bitmap = it.toBitmap()
                val out = ByteArrayOutputStream(48 * 1024)
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                synchronized(lock) {
                    if (analysis === a) _frames.value = CameraFrame(out.toByteArray(), now, ++n)
                }
            } catch (e: Exception) {
                if (!encodeErrorLogged) {
                    Log.w(TAG, "Failed to encode frame", e)
                    encodeErrorLogged = true
                }
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun surfaceRotation(degrees: Int): Int = when (degrees) {
        90 -> Surface.ROTATION_90
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_270
        else -> Surface.ROTATION_0
    }

    companion object {
        private const val TAG = "MjpegCamera"
        const val JPEG_QUALITY = 60
        const val NORMAL_FPS = 15
        const val REDUCED_FPS = 8
    }
}
