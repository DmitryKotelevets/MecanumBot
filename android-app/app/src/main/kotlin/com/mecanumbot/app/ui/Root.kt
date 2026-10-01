package com.mecanumbot.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mecanumbot.app.AppGraph

private enum class Section(val label: String, val icon: ImageVector) {
    DRIVE("Drive", Icons.Filled.PlayArrow),
    RAW("Raw", Icons.Filled.Build),
    CONFIG("Config", Icons.Filled.Edit),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
fun Root(graph: AppGraph) {
    val active by graph.active.collectAsStateWithLifecycle()
    val current = active ?: return
    key(current.session) {
        val state by current.session.state.collectAsStateWithLifecycle()
        var section by rememberSaveable { mutableStateOf(Section.DRIVE) }
        // Surface sets the content color (onBackground) for all text; it fills behind the system bars
        // while the content stays clear of bars, cutout and IME.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                // Side rail: landscape has spare width, so navigation costs no height. Insets are already applied above.
                NavigationRail(windowInsets = WindowInsets(0)) {
                    var itemsHeight by remember { mutableStateOf(0.dp) }
                    val density = LocalDensity.current
                    Column(Modifier.onSizeChanged { itemsHeight = with(density) { it.height.toDp() } }) {
                        for (s in Section.entries) {
                            NavigationRailItem(
                                selected = section == s,
                                onClick = { section = s },
                                icon = { Icon(s.icon, contentDescription = null) },
                                label = { Text(s.label) },
                            )
                        }
                    }
                    // STOP in the bottom-left corner: as tall as the items above, shrinking if the screen is short.
                    Box(Modifier.weight(1f).padding(bottom = 8.dp), contentAlignment = Alignment.BottomCenter) {
                        StopButton(
                            onStop = { current.session.stop() },
                            modifier = Modifier.width(64.dp).heightIn(max = itemsHeight).fillMaxHeight(),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    StatusBar(
                        kind = current.kind,
                        state = state,
                        clock = graph.clock,
                        onRequestPermission = { graph.usbLink.requestPermission() },
                    )
                    when (section) {
                        Section.SETTINGS -> SettingsScreen(graph, current.kind)
                        Section.CONFIG -> ConfigScreen(current.session, state)
                        else -> TestScreen(current.session, state, graph.inForeground, raw = section == Section.RAW)
                    }
                }
            }
        }
    }
}
