package com.mecanumbot.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mecanumbot.camera.MjpegCamera
import com.mecanumbot.server.PhoneBattery
import com.mecanumbot.server.PilotHub
import com.mecanumbot.server.PilotServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Foreground Service for the pilot (spec §3): owns the server, the camera and the wake/Wi-Fi
 * locks, so remote driving and video continue with the screen off. The link and the session stay
 * in AppGraph. Stopped only by the notification's Stop action.
 */
class RobotService : LifecycleService() {
    private val graph: AppGraph get() = (application as MecanumApp).graph

    private var started = false
    private var cameraAllowed = false
    private var server: PilotServer? = null
    private var hub: PilotHub? = null
    private var camera: MjpegCamera? = null
    private val phoneBattery = MutableStateFlow<PhoneBattery?>(null)
    private lateinit var wifi: WifiAddress
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pilot server", NotificationManager.IMPORTANCE_LOW),
        )
        wifi = WifiAddress(this).also { it.start() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MecanumBot:pilot")
            .apply { setReferenceCounted(false) }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MecanumBot:pilot")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            hub?.close() // fence off pilots first, or a held deadman re-drives after STOP ×3
            graph.active.value?.session?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService() must be answered with startForeground(). The camera type
        // is decided once, on the first start: later starts keep whatever the service runs as.
        val camera = startForegroundSafely(notification(graph.pilot.value.url), wantCamera = if (started) cameraAllowed else hasCamera())
        if (!started) {
            started = true
            cameraAllowed = camera
            startPilot()
        }
        return START_NOT_STICKY
    }

    private fun startPilot() {
        wakeLock.acquire()
        wifiLock.acquire()
        val cam = if (cameraAllowed) MjpegCamera(this, this, lifecycleScope, graph.clock) else null
        camera = cam
        val video = CameraVideo(cam, lifecycleScope)
        val hub = PilotHub(graph.sessions, video, graph.clock, "${AppGraph.APP_MAJOR}.${AppGraph.APP_MINOR}", phoneBattery)
        this.hub = hub
        val s = PilotServer(hub, video, ::asset, Dispatchers.Main.immediate)
        server = s
        lifecycleScope.launch(Dispatchers.IO) {
            // A restart can find the old socket still holding 8080 for a moment: retry. Destroying
            // the service cancels this coroutine, and a stopped server's start() does nothing.
            var error: String? = null
            for (attempt in 0..START_RETRIES) {
                if (attempt > 0) delay(START_RETRY_MS)
                error = try {
                    s.start()
                    null
                } catch (e: IOException) { // port 8080 busy, no network …
                    "server: ${e.message}"
                }
                if (error == null || server !== s) break
            }
            if (lifecycle.currentState != Lifecycle.State.DESTROYED) graph.pilot.update { it.copy(error = error) } // update{}: the Main collector below writes too
        }
        if (cam != null) {
            lifecycleScope.launch { graph.cameraPreference.options.collect { cam.setOptions(it.lens, it.rotation) } }
        }
        lifecycleScope.launch {
            while (true) {
                val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                battery?.let(::tempC)?.let(video::onTemperature)
                phoneBattery.value = battery?.let(::readBattery)
                delay(BATTERY_PERIOD_MS)
            }
        }
        lifecycleScope.launch {
            combine(wifi.address, hub.summary, video.level, video.tempC) { ip, summary, level, temp ->
                PilotInfo(
                    running = true,
                    url = ip?.let { "http://$it:${PilotServer.PORT}" },
                    driverConnected = summary.driverConnected,
                    watchers = summary.watchers,
                    video = level,
                    tempC = temp,
                )
            }.collect { live ->
                val urlChanged = live.url != graph.pilot.value.url
                graph.pilot.update { live.copy(error = it.error) }
                if (urlChanged) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(live.url))
            }
        }
    }

    override fun onDestroy() {
        hub?.close()
        hub = null
        server?.let { s -> Thread { s.stop() }.start() } // stop() blocks up to 1 s
        server = null
        camera?.release()
        camera = null
        wifi.stop()
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        graph.pilot.value = PilotInfo()
        super.onDestroy()
    }

    /**
     * Camera type needs the CAMERA permission and a visible app; without either, fall back to
     * connectedDevice so driving still works and video stays off (spec §3). Returns true when the
     * service runs with the camera type.
     */
    private fun startForegroundSafely(n: Notification, wantCamera: Boolean): Boolean {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (wantCamera) {
            try {
                startForeground(NOTIFICATION_ID, n, device or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                return true
            } catch (e: SecurityException) { // CAMERA revoked
                Log.w(TAG, "camera service type refused, video off", e)
            } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException: app not visible
                Log.w(TAG, "camera service type refused, video off", e)
            }
        }
        startForeground(NOTIFICATION_ID, n, device)
        return false
    }

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun notification(url: String?): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RobotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MecanumBot")
            .setContentText(url?.let { "Pilot at $it" } ?: "Pilot server — no Wi-Fi")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun asset(name: String): ByteArray? = try {
        assets.open("pilot/$name").use { it.readBytes() }
    } catch (_: IOException) {
        null
    }

    private fun tempC(battery: Intent): Float? {
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private fun readBattery(battery: Intent): PhoneBattery? {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return PhoneBattery(level * 100 / scale, charging)
    }

    companion object {
        const val ACTION_STOP = "com.mecanumbot.action.STOP_PILOT"
        private const val TAG = "RobotService"
        private const val CHANNEL = "pilot"
        private const val NOTIFICATION_ID = 1
        private const val BATTERY_PERIOD_MS = 5_000L
        private const val START_RETRIES = 5
        private const val START_RETRY_MS = 500L
    }
}
