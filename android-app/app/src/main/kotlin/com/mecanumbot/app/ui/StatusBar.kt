package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecanumbot.app.LinkKind
import com.mecanumbot.app.PilotInfo
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.protocol.Protocol
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.SessionEvent
import com.mecanumbot.core.session.SessionState
import com.mecanumbot.server.VideoLevel
import com.mecanumbot.usb.UsbLink
import kotlinx.coroutines.delay

private val Amber = Color(0xFFFFB300)
private val Danger = Color(0xFFD32F2F)

@Composable
fun StatusBar(
    kind: LinkKind,
    state: SessionState,
    pilot: PilotInfo,
    clock: () -> Long,
    onRequestPermission: () -> Unit,
) {
    val now by produceState(clock()) {
        while (true) {
            value = clock()
            delay(200)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (kind == LinkKind.FAKE) Badge(kind.name, Amber, Color.Black) else Badge(kind.name, MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(phaseText(state), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(detailsText(state, now), style = MaterialTheme.typography.bodySmall)
                state.telemetry?.let { t ->
                    val vm = t.telemetry.vmMv
                    Text(
                        "VM %.2f V".format(vm / 1000f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vm < VM_LOW_MV) Danger else Color.Unspecified,
                    )
                }
            }
            state.lastEvent?.let {
                Text(it.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                pilotText(pilot),
                style = MaterialTheme.typography.bodySmall,
                color = if (pilot.error != null || pilot.video == VideoLevel.OFF && pilot.running) Danger else Color.Unspecified,
            )
        }
        state.telemetry?.let { t ->
            if (t.failsafe) Badge("FAILSAFE", Danger)
            if (t.faultA) Badge("FAULT A", Danger)
            if (t.faultB) Badge("FAULT B", Danger)
            if (t.rawMode) Badge("RAW", MaterialTheme.colorScheme.secondary)
        }
        if ((state.link as? LinkState.Error)?.message == UsbLink.NO_PERMISSION) {
            OutlinedButton(onClick = onRequestPermission) { Text("Request again") }
        }
    }
}

/** Vertical STOP pill; letters are stacked so it reads in a narrow rail. Always enabled. */
@Composable
fun StopButton(onStop: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onStop,
        shape = RoundedCornerShape(percent = 50),
        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier,
    ) {
        Text("S\nT\nO\nP", fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Badge(text: String, color: Color, contentColor: Color = contentColorFor(color)) {
    Box(Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, color = contentColor, style = MaterialTheme.typography.labelMedium)
    }
}

private fun phaseText(s: SessionState): String {
    (s.link as? LinkState.Error)?.let { return it.message }
    return when (s.phase) {
        Phase.DISCONNECTED -> if (s.link == LinkState.Connecting) "Connecting…" else "Disconnected — connect ESP32"
        Phase.HANDSHAKING -> "Handshaking…"
        Phase.VERSION_MISMATCH -> "Version mismatch — update firmware/app"
        Phase.READY -> "Ready"
    }
}

private fun detailsText(s: SessionState, now: Long): String = buildString {
    s.helloAck?.let { append("fw ${it.fwMajor}.${it.fwMinor}   ") }
    s.rttMs?.let { append("RTT $it ms   ") }
    append("tx ${s.motionSentPerSec}/s")
    s.telemetry?.let { append("   rx ${it.telemetry.rxFrames}/s   age ${now - it.receivedAt} ms") }
}

private fun pilotText(p: PilotInfo): String {
    if (!p.running) return "Pilot server off"
    p.error?.let { return "Pilot $it" }
    return buildString {
        append("Pilot ${p.url ?: "— no Wi-Fi"}")
        append(if (p.driverConnected) "   driver connected" else "   no driver")
        if (p.watchers > 0) append("   ${p.watchers} watching")
        p.video?.let { append("   video ${it.name.lowercase()}") }
        p.tempC?.let { append("   %.1f °C".format(it)) }
    }
}

fun SessionEvent.message(): String = when (this) {
    SessionEvent.NotResponding -> "ESP32 not responding"
    is SessionEvent.VersionMismatch -> "ESP32 protocol $espProtoVer, app ${Protocol.PROTO_VER}: update firmware/app"
    is SessionEvent.Rebooted -> "ESP32 rebooted: reason $resetReason (boot #$resetCount)"
    is SessionEvent.EspLog -> "ESP32: $text"
}

private const val VM_LOW_MV = 3200
