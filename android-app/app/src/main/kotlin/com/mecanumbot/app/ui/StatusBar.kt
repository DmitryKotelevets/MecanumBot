package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecanumbot.app.LinkKind
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.protocol.Protocol
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.SessionEvent
import com.mecanumbot.core.session.SessionState
import com.mecanumbot.usb.UsbLink
import kotlinx.coroutines.delay

private val Amber = Color(0xFFFFB300)
private val Danger = Color(0xFFD32F2F)

@Composable
fun StatusBar(
    kind: LinkKind,
    state: SessionState,
    clock: () -> Long,
    onStop: () -> Unit,
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
        Badge(kind.name, if (kind == LinkKind.FAKE) Amber else MaterialTheme.colorScheme.primary)
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
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
            modifier = Modifier.size(width = 140.dp, height = 64.dp),
        ) {
            Text("STOP", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, color = Color.Black, style = MaterialTheme.typography.labelMedium)
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

fun SessionEvent.message(): String = when (this) {
    SessionEvent.NotResponding -> "ESP32 not responding"
    is SessionEvent.VersionMismatch -> "ESP32 protocol $espProtoVer, app ${Protocol.PROTO_VER}: update firmware/app"
    is SessionEvent.Rebooted -> "ESP32 rebooted: reason $resetReason (boot #$resetCount)"
    is SessionEvent.EspLog -> "ESP32: $text"
}

private const val VM_LOW_MV = 3200
