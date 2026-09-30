package com.mecanumbot.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TestScreen(session: RobotSession, state: SessionState, inForeground: StateFlow<Boolean>) {
    Text("Test screen: ${state.phase}")
}
