package com.mecanumbot.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.mecanumbot.core.link.Link
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.link.OutgoingQueue
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameParser
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CDC-ACM link to the ESP32-C6 (spec §8). DTR/RTS are never touched: toggling them can put the
 * C6 into its bootloader. Reads arrive on the SerialInputOutputManager thread; writes go through
 * one writer coroutine on Dispatchers.IO. Everything else runs on [scope] (Main).
 */
class UsbLink(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : Link {
    private val context = context.applicationContext
    private val usb = this.context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber(ProbeTable().addProduct(VID, PID, CdcAcmSerialDriver::class.java))

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    override val incoming: Flow<Frame> = _incoming.asSharedFlow()

    private val _parserErrors = MutableStateFlow(0)
    override val parserErrors: StateFlow<Int> = _parserErrors.asStateFlow()

    private val parser = FrameParser()
    private val queue = OutgoingQueue()

    @Volatile private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var writer: Job? = null
    private var loop: Job? = null
    private var permissionAsked = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scope.launch {
                    permissionAsked = false
                    closePort()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scope.launch { tryOpen() }
                ACTION_PERMISSION -> scope.launch {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        tryOpen()
                    } else {
                        _state.value = LinkState.Error(NO_PERMISSION)
                    }
                }
            }
        }
    }

    override suspend fun open() {
        if (loop != null) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(ACTION_PERMISSION)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        loop = scope.launch {
            launch {
                while (isActive) {
                    if (port == null) tryOpen()
                    delay(RETRY_MS)
                }
            }
            launch {
                while (isActive) {
                    delay(STALE_CHECK_MS)
                    val frames = synchronized(parser) { parser.flushStale(clock()) }
                    frames.forEach { _incoming.tryEmit(it) }
                }
            }
        }
    }

    override suspend fun close() {
        loop?.cancel()
        loop = null
        runCatching { context.unregisterReceiver(receiver) }
        closePort()
    }

    override fun send(bytes: ByteArray, priority: Priority) {
        if (port != null) queue.offer(bytes, priority)
    }

    /** Asks for USB permission again after the user denied it. */
    fun requestPermission() {
        permissionAsked = false
        scope.launch { tryOpen() }
    }

    private fun tryOpen() {
        if (port != null) return
        val driver = prober.findAllDrivers(usb).firstOrNull()
        if (driver == null) {
            _state.value = LinkState.Disconnected
            return
        }
        val device = driver.device
        if (!usb.hasPermission(device)) {
            if (!permissionAsked) {
                permissionAsked = true
                askPermission(device)
            }
            return
        }
        _state.value = LinkState.Connecting
        val connection = usb.openDevice(device)
        if (connection == null) {
            _state.value = LinkState.Error("Cannot open USB device")
            return
        }
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // No setDTR/setRTS here or anywhere: see the class comment.
        } catch (e: IOException) {
            runCatching { p.close() }
            _state.value = LinkState.Error("USB: ${e.message}")
            return
        }
        synchronized(parser) { parser.reset() }
        queue.clear()
        port = p
        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val frames = synchronized(parser) { parser.feed(data, clock()) }
                frames.forEach { _incoming.tryEmit(it) }
                _parserErrors.value = parser.crcErr
            }

            override fun onRunError(e: Exception) {
                scope.launch { closePort(p) }
            }
        }
        io = SerialInputOutputManager(p, listener).also { it.start() }
        writer = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val bytes = queue.take()
                try {
                    p.write(bytes, WRITE_TIMEOUT_MS)
                } catch (e: IOException) {
                    scope.launch { closePort(p) }
                    break
                }
            }
        }
        permissionAsked = false
        _state.value = LinkState.Connected
    }

    /** Writes queued STOP frames, then closes. The retry loop reopens the port if the link is still open. */
    private fun closePort(expected: UsbSerialPort? = null) {
        val p = port
        if (expected != null && p !== expected) return
        if (p == null) {
            _state.value = LinkState.Disconnected
            return
        }
        port = null
        queue.drainStops().forEach { runCatching { p.write(it, WRITE_TIMEOUT_MS) } }
        writer?.cancel()
        writer = null
        io?.stop()
        io = null
        runCatching { p.close() }
        queue.clear()
        _state.value = LinkState.Disconnected
    }

    private fun askPermission(device: UsbDevice) {
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(device, pending)
    }

    companion object {
        const val VID = 0x303A
        const val PID = 0x1001
        const val NO_PERMISSION = "No USB permission"
        private const val BAUD = 115200
        private const val ACTION_PERMISSION = "com.mecanumbot.usb.PERMISSION"
        private const val RETRY_MS = 1_000L
        private const val STALE_CHECK_MS = 50L
        private const val WRITE_TIMEOUT_MS = 100
    }
}
