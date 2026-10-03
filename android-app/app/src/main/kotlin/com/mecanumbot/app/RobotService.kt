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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mecanumbot.camera.MjpegCamera
import com.mecanumbot.server.PilotHub
import com.mecanumbot.server.PilotServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private var camera: MjpegCamera? = null
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
        val hub = PilotHub(graph.sessions, video, graph.clock, "${AppGraph.APP_MAJOR}.${AppGraph.APP_MINOR}")
        val s = PilotServer(hub, video, ::asset, Dispatchers.Main.immediate)
        server = s
        lifecycleScope.launch(Dispatchers.IO) {
            val error = try {
                s.start()
                null
            } catch (e: IOException) { // port 8080 busy, no network …
                "server: ${e.message}"
            }
            graph.pilot.update { it.copy(error = error) } // update{}: the Main collector below writes too
        }
        if (cam != null) {
            lifecycleScope.launch { graph.cameraPreference.options.collect { cam.setOptions(it.lens, it.rotation) } }
        }
        lifecycleScope.launch {
            while (true) {
                batteryTempC()?.let(video::onTemperature)
                delay(TEMP_PERIOD_MS)
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
            } catch (_: SecurityException) { // CAMERA revoked
            } catch (_: IllegalStateException) { // ForegroundServiceStartNotAllowedException: app not visible
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

    private fun batteryTempC(): Float? {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    companion object {
        const val ACTION_STOP = "com.mecanumbot.action.STOP_PILOT"
        private const val CHANNEL = "pilot"
        private const val NOTIFICATION_ID = 1
        private const val TEMP_PERIOD_MS = 5_000L
    }
}
