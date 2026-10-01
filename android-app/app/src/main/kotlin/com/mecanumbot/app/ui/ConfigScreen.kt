package com.mecanumbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mecanumbot.core.protocol.AckStatus
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.session.ConfigWrite
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.core.session.SessionState
import kotlin.math.roundToInt

private val WHEELS = listOf("FL", "FR", "RL", "RR")

/**
 * Editor for the ESP32 CONFIG (PROTOCOL.md §4.4). Edits stay local until Apply, which stops the
 * robot and sends CONFIG; after ACK OK the session re-reads it, and the form shows what was stored.
 */
@Composable
fun ConfigScreen(session: RobotSession, state: SessionState) {
    val ready = state.phase == Phase.READY
    val base = state.config
    if (base == null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (ready) "No config read yet" else "Connect to the ESP32 to edit its config")
            OutlinedButton(onClick = session::refreshConfig, enabled = ready) { Text("Reload") }
        }
        return
    }
    // Restarts from the ESP32's values whenever a new CONFIG_DATA arrives (after Saved or Reload).
    var draft by remember(base) { mutableStateOf(base) }
    val errors = configErrors(draft)
    val pending = state.configWrite == ConfigWrite.Pending

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Wheels", style = MaterialTheme.typography.titleMedium)
        for (i in WHEELS.indices) WheelRow(i, draft) { draft = it }

        Text("Motors", style = MaterialTheme.typography.titleMedium)
        IntSlider("max_duty", draft.maxDuty, 1..100, "%") { draft = draft.copy(maxDuty = it) }
        IntSlider("min_duty", draft.minDuty, 0..99, "%") { draft = draft.copy(minDuty = it) }
        IntSlider("slew_ms", draft.slewMs, 0..2000, "ms", step = 50) { draft = draft.copy(slewMs = it) }
        IntSlider("failsafe_ms", draft.failsafeMs, 100..1000, "ms", step = 10) { draft = draft.copy(failsafeMs = it) }
        PwmField(draft.pwmHz) { draft = draft.copy(pwmHz = it) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = draft.brake == 1, onCheckedChange = { draft = draft.copy(brake = if (it) 1 else 0) })
            Text(if (draft.brake == 1) "brake on STOP/failsafe" else "coast on STOP/failsafe")
        }

        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { session.sendConfig(draft) },
                enabled = ready && errors.isEmpty() && draft != base && !pending,
            ) { Text("Apply") }
            OutlinedButton(onClick = { draft = base }, enabled = draft != base) { Text("Revert") }
            OutlinedButton(onClick = { draft = Config.DEFAULT }, enabled = draft != Config.DEFAULT) { Text("Defaults") }
            OutlinedButton(onClick = session::refreshConfig, enabled = ready && !pending) { Text("Reload") }
        }
        configWriteText(state.configWrite)?.let { Text(it) }
        Text(
            "Apply stops the robot first. The ESP32 stores the config in NVS; it survives a reboot.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** One wheel: physical channel (M1–M4), direction and trim. */
@Composable
private fun WheelRow(i: Int, c: Config, onChange: (Config) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(WHEELS[i], Modifier.width(32.dp))
        for (ch in 0 until 4) {
            FilterChip(
                selected = c.map[i] == ch,
                onClick = { onChange(c.copy(map = assignChannel(c.map, i, ch))) },
                label = { Text("M${ch + 1}") },
            )
        }
        Switch(checked = c.invert[i] == 1, onCheckedChange = { onChange(c.copy(invert = c.invert.with(i, if (it) 1 else 0))) })
        Text("invert", Modifier.width(56.dp))
        Slider(
            value = c.trim[i].toFloat(),
            onValueChange = { onChange(c.copy(trim = c.trim.with(i, it.roundToInt()))) },
            valueRange = 50f..100f,
            modifier = Modifier.weight(1f),
        )
        Text("trim ${c.trim[i]} %", Modifier.width(88.dp))
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, unit: String, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label  $value $unit", Modifier.width(180.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PwmField(value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value >= 0) value.toString() else "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(it.toIntOrNull() ?: -1) // not a number → out of range → Apply disabled
        },
        label = { Text("pwm_hz (1000–30000)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** Gives wheel [wheel] channel [ch]; the wheel that had it takes the old one, so map stays a permutation. */
private fun assignChannel(map: List<Int>, wheel: Int, ch: Int): List<Int> {
    val other = map.indexOf(ch)
    return map.toMutableList().also {
        if (other >= 0) it[other] = map[wheel]
        it[wheel] = ch
    }
}

private fun List<Int>.with(i: Int, v: Int): List<Int> = toMutableList().also { it[i] = v }

/** Field-level messages for the rules of PROTOCOL.md §4.4 the controls can't prevent on their own. */
private fun configErrors(c: Config): List<String> {
    val errors = mutableListOf<String>()
    if (c.map.sorted() != listOf(0, 1, 2, 3)) errors += "map: each channel M1–M4 must drive exactly one wheel"
    if (c.minDuty >= c.maxDuty) errors += "min_duty must be below max_duty"
    if (c.pwmHz !in 1000..30000) errors += "pwm_hz must be 1000–30000"
    if (errors.isEmpty() && !c.isValid()) errors += "Config is out of range"
    return errors
}

private fun configWriteText(w: ConfigWrite): String? = when (w) {
    ConfigWrite.Idle -> null
    ConfigWrite.Pending -> "Sending…"
    ConfigWrite.Saved -> "Saved on the ESP32"
    ConfigWrite.NoAnswer -> "No answer from the ESP32: not confirmed"
    is ConfigWrite.Rejected -> when (w.status) {
        AckStatus.ERR -> "Rejected by the ESP32 (invalid), nothing changed"
        AckStatus.BUSY -> "ESP32 busy (OTA/Wi-Fi), nothing changed"
        else -> "Rejected by the ESP32 (status ${w.status})"
    }
}
