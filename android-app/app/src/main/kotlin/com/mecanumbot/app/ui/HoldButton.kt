package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Deadman button: reports true while a finger is on it. Any cancel or disable reports false. */
@Composable
fun HoldButton(text: String, enabled: Boolean, onHoldChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val report by rememberUpdatedState(onHoldChange)
    val background = when {
        pressed -> Color(0xFF2E7D32)
        enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.DarkGray
    }
    Box(
        modifier
            .size(width = 200.dp, height = 96.dp)
            .background(background, RoundedCornerShape(16.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    report(true)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                        report(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (enabled) text else "$text (disabled)")
    }
}
