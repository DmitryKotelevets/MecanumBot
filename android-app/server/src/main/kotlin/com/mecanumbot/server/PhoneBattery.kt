package com.mecanumbot.server

/** The robot phone's battery, for the pilot's telemetry. [percent] is 0–100. */
data class PhoneBattery(val percent: Int, val charging: Boolean)
