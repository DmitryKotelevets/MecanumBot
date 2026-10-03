package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.core.session.SessionState
import com.mecanumbot.core.session.TelemetryState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DEFAULT_LIMIT = 0.3f

@Composable
fun TestScreen(session: RobotSession, state: SessionState, inForeground: StateFlow<Boolean>, raw: Boolean) {
    val foreground by inForeground.collectAsStateWithLifecycle()
    val enabled = foreground && state.phase == Phase.READY
    // Leaving the Test screen stops TEST/RAW driving but not a remote pilot (stage 5 spec §4.3).
    DisposableEffect(session) { onDispose { session.releaseLocal() } }

    Row(Modifier.fillMaxSize().padding(8.dp)) {
        Column(Modifier.weight(2f)) {
            // Switching Drive <-> Raw disposes the other tab, and each tab releases its source on dispose.
            if (raw) RawTab(session, enabled, state.stops, inForeground)
            else DriveTab(session, enabled, state.stops, inForeground)
        }
        TelemetryPanel(state, Modifier.weight(1f))
    }
}

@Composable
private fun DriveTab(session: RobotSession, enabled: Boolean, stops: Int, inForeground: StateFlow<Boolean>) {
    var limit by remember { mutableFloatStateOf(DEFAULT_LIMIT) } // reset to 30 % on entering the screen
    var left by remember { mutableStateOf(Offset.Zero) }
    var right by remember { mutableStateOf(Offset.Zero) }
    var holding by remember { mutableStateOf(false) }

    DisposableEffect(session) { onDispose { session.releaseLocal() } }
    // STOP (from anywhere) or losing READY/foreground drops the hold: motion needs a new press.
    LaunchedEffect(enabled, stops) { holding = false }
    LaunchedEffect(holding, enabled) {
        if (!holding || !enabled) {
            session.update(Command(0f, 0f, 0f, false, Source.TEST))
            return@LaunchedEffect
        }
        val armedAt = session.state.value.stops
        // Exit at once on STOP or background, without waiting for recomposition.
        while (session.state.value.stops == armedAt && inForeground.value) {
            // Reads the current stick and limit state on every frame: a new limit applies next frame.
            session.update(Command(left.x * limit, left.y * limit, right.x * limit, true, Source.TEST))
            delay(RobotSession.TICK_MS)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Joystick("Move", onChange = { left = it })
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Speed limit ${(limit * 100).roundToInt()} %")
            Slider(
                value = limit,
                onValueChange = { limit = it },
                valueRange = 0.1f..1f,
                modifier = Modifier.width(200.dp),
            )
            HoldButton("Hold to drive", enabled, resetKey = stops, onHoldChange = { holding = it })
        }
        Joystick("Turn", onChange = { right = Offset(it.x, 0f) })
    }
}

@Composable
private fun RawTab(session: RobotSession, enabled: Boolean, stops: Int, inForeground: StateFlow<Boolean>) {
    val values = remember { mutableStateListOf(0, 0, 0, 0) }

    DisposableEffect(session) { onDispose { session.releaseLocal() } } // leaving the Raw tab
    LaunchedEffect(enabled, stops) {
        // STOP, background or a lost session zero the sliders: motion needs a new slider move.
        for (i in 0 until 4) values[i] = 0
        if (!enabled) {
            session.clearRaw()
            return@LaunchedEffect
        }
        val armedAt = session.state.value.stops
        while (session.state.value.stops == armedAt && inForeground.value) {
            session.setRaw(values.toList())
            delay(RobotSession.TICK_MS)
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until 4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("M${i + 1}", Modifier.width(40.dp))
                // key(stops): STOP tears down the slider and its gesture, so a finger still
                // dragging cannot write its position back and undo the STOP; a new touch is needed.
                key(stops) {
                    Slider(
                        value = values[i].toFloat(),
                        onValueChange = { values[i] = it.roundToInt() },
                        valueRange = -127f..127f,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("${values[i]}", Modifier.width(48.dp))
            }
        }
        OutlinedButton(onClick = { for (i in 0 until 4) values[i] = 0 }) { Text("Center") }
    }
}

@Composable
private fun TelemetryPanel(state: SessionState, modifier: Modifier) {
    Column(modifier.padding(start = 8.dp).verticalScroll(rememberScrollState())) {
        Text("Telemetry", style = MaterialTheme.typography.titleSmall)
        val t = state.telemetry
        if (t == null) {
            Text("no telemetry")
            return@Column
        }
        val tm = t.telemetry
        tm.pwm.forEachIndexed { i, v -> PwmBar("M${i + 1}", v) }
        Field("flags", flagsText(t))
        Field("vm_mv", tm.vmMv)
        Field("last_seq", tm.lastSeq)
        Field("crc_err (ESP32)", tm.crcErr)
        Field("crc_err (phone)", state.phoneParserErrors)
        Field("rx_frames", tm.rxFrames)
        Field("uptime_s", tm.uptimeS)
        Field("fw", "${tm.fwMajor}.${tm.fwMinor}")
        Field("loop_max_us", tm.loopMaxUs)
        state.config?.let {
            Field("failsafe_ms", it.failsafeMs)
            Field("max_duty", "${it.maxDuty} %")
        }
    }
}

@Composable
private fun PwmBar(label: String, value: Int) {
    val color = if (value >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(32.dp))
        Box(Modifier.weight(1f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(abs(value) / 127f).background(color))
        }
        Text("$value", Modifier.width(40.dp).padding(start = 4.dp))
    }
}

@Composable
private fun Field(name: String, value: Any) {
    Row {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text("$value", style = MaterialTheme.typography.bodySmall)
    }
}

private fun flagsText(t: TelemetryState): String = listOf(
    "failsafe" to t.failsafe,
    "fault A" to t.faultA,
    "fault B" to t.faultB,
    "raw" to t.rawMode,
    "ota" to t.otaMode,
    "wifi" to t.wifiMode,
    "enable" to t.enabled,
).filter { it.second }.joinToString { it.first }.ifEmpty { "—" }
