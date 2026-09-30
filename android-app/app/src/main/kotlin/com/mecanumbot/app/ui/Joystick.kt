package com.mecanumbot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** On-screen stick. Reports −1..1 per axis, y up; springs back to the centre on release. */
@Composable
fun Joystick(label: String, onChange: (Offset) -> Unit, modifier: Modifier = Modifier) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val report by rememberUpdatedState(onChange)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = MaterialTheme.colorScheme.primary

    Box(
        modifier.size(200.dp).pointerInput(Unit) {
            fun update(position: Offset) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                var v = (position - centre) / (size.width / 2f)
                val d = v.getDistance()
                if (d > 1f) v /= d
                knob = Offset(v.x, -v.y)
                report(knob)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                update(down.position)
                drag(down.id) { change ->
                    update(change.position)
                    change.consume()
                }
                knob = Offset.Zero
                report(Offset.Zero)
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val knobR = r / 3f
            drawCircle(track, radius = r)
            drawCircle(color, radius = knobR, center = center + Offset(knob.x, -knob.y) * (r - knobR))
        }
        Text(label, Modifier.align(Alignment.BottomCenter))
    }
}
