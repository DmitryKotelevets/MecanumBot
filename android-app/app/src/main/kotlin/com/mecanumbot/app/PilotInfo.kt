package com.mecanumbot.app

import com.mecanumbot.server.VideoLevel

/** What the status bar shows about the pilot server; written by RobotService. */
data class PilotInfo(
    val running: Boolean = false,
    val url: String? = null,
    val error: String? = null,
    val driverConnected: Boolean = false,
    val watchers: Int = 0,
    val video: VideoLevel? = null,
    val tempC: Float? = null,
)
