package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mecanumbot.app.AppGraph

@Composable
fun Root(graph: AppGraph) {
    val active by graph.active.collectAsStateWithLifecycle()
    val current = active ?: return
    key(current.session) {
        val state by current.session.state.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            StatusBar(
                kind = current.kind,
                state = state,
                clock = graph.clock,
                onStop = { current.session.stop() },
                onRequestPermission = { graph.usbLink.requestPermission() },
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Test") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
            }
            when (tab) {
                0 -> TestScreen(current.session, state, graph.inForeground)
                else -> SettingsScreen(graph, current.kind)
            }
        }
    }
}
