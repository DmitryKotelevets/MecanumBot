package com.mecanumbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mecanumbot.app.AppGraph
import com.mecanumbot.app.CameraOptions
import com.mecanumbot.app.CameraPreference
import com.mecanumbot.app.LinkKind
import com.mecanumbot.camera.Lens
import com.mecanumbot.fake.FaultControls
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(graph: AppGraph, kind: LinkKind) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Link", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkKind.entries.forEach { k ->
                FilterChip(selected = k == kind, onClick = { graph.select(k) }, label = { Text(k.name) })
            }
        }
        CameraPanel(graph.cameraPreference)
        if (kind == LinkKind.FAKE) FaultPanel(graph.fakeLink.faults)
    }
}

@Composable
private fun CameraPanel(preference: CameraPreference) {
    val options by preference.options.collectAsState(initial = CameraOptions())
    val scope = rememberCoroutineScope()
    fun save(o: CameraOptions) {
        scope.launch { preference.save(o) }
    }

    Text("Camera (pilot video)", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(options.lens == Lens.ULTRA_WIDE, onClick = { save(options.copy(lens = Lens.ULTRA_WIDE)) }, label = { Text("Ultra-wide") })
        FilterChip(options.lens == Lens.MAIN, onClick = { save(options.copy(lens = Lens.MAIN)) }, label = { Text("Main") })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CameraPreference.ROTATIONS.forEach { r ->
            FilterChip(options.rotation == r, onClick = { save(options.copy(rotation = r)) }, label = { Text("$r°") })
        }
    }
}

@Composable
private fun FaultPanel(f: FaultControls) {
    var wrongProto by remember { mutableStateOf(f.wrongProtoVer) }
    var faultA by remember { mutableStateOf(f.faultA) }
    var faultB by remember { mutableStateOf(f.faultB) }
    var sag by remember { mutableFloatStateOf(f.vmSagMv.toFloat()) }
    var drop by remember { mutableFloatStateOf(f.dropPercent.toFloat()) }

    Text("Fake ESP32 faults", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = f::disconnect) { Text("Disconnect") }
        OutlinedButton(onClick = f::reconnect) { Text("Reconnect") }
        OutlinedButton(onClick = f::reboot) { Text("Reboot") }
        OutlinedButton(onClick = f::injectGarbage) { Text("Inject garbage") }
    }
    SwitchRow("Wrong proto_ver", wrongProto) { wrongProto = it; f.wrongProtoVer = it }
    SwitchRow("nFAULT A", faultA) { faultA = it; f.faultA = it }
    SwitchRow("nFAULT B", faultB) { faultB = it; f.faultB = it }
    Text("VM sag ${sag.roundToInt()} mV")
    Slider(value = sag, onValueChange = { sag = it; f.vmSagMv = it.roundToInt() }, valueRange = 0f..1500f)
    Text("Drop incoming ${drop.roundToInt()} %")
    Slider(value = drop, onValueChange = { drop = it; f.dropPercent = it.roundToInt() }, valueRange = 0f..100f)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
