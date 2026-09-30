package com.mecanumbot.app

import android.content.Context
import android.os.SystemClock
import com.mecanumbot.core.link.Link
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.fake.FakeLink
import com.mecanumbot.usb.UsbLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hand-written wiring (spec §2). Application-scoped: the session lives as long as the process.
 * Everything runs on Main; links do their own I/O off it.
 */
class AppGraph(context: Context) {
    data class Active(val kind: LinkKind, val session: RobotSession)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock: () -> Long = { SystemClock.elapsedRealtime() }
    val usbLink = UsbLink(context, scope, clock)
    val fakeLink = FakeLink(scope, clock)

    private val preference = LinkPreference(context)
    private val switching = Mutex()

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    private val _inForeground = MutableStateFlow(false)
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    init {
        scope.launch { switching.withLock { activate(preference.load()) } }
    }

    fun select(kind: LinkKind) {
        scope.launch {
            switching.withLock {
                if (_active.value?.kind == kind) return@withLock
                preference.save(kind)
                activate(kind)
            }
        }
    }

    /** Activity onResume / onPause. Leaving the foreground always stops the robot (STOP ×3). */
    fun onForeground(visible: Boolean) {
        _inForeground.value = visible
        if (!visible) _active.value?.session?.stop()
    }

    private suspend fun activate(kind: LinkKind) {
        _active.value?.let { old ->
            old.session.stop()          // STOP ×3 is queued …
            linkOf(old.kind).close()    // … and close() writes it before closing
            old.session.close()
        }
        val link = linkOf(kind)
        val session = RobotSession(link, scope, clock, APP_MAJOR, APP_MINOR).also { it.start() }
        _active.value = Active(kind, session)
        link.open()
    }

    private fun linkOf(kind: LinkKind): Link = when (kind) {
        LinkKind.USB -> usbLink
        LinkKind.FAKE -> fakeLink
    }

    companion object {
        const val APP_MAJOR = 0
        const val APP_MINOR = 1
    }
}
