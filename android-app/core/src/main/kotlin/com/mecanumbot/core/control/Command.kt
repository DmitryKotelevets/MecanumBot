package com.mecanumbot.core.control

/** Command source; the order is the arbiter priority. `code` goes into DRIVE flags bits 1..2. */
enum class Source(val code: Int) { TEST(0), LOCAL_PAD(1), REMOTE(2) }

/** Which sources the arbiter listens to. TEST is always allowed (bench screen on the robot). */
enum class Mode { AUTO, LOCAL_ONLY, REMOTE_ONLY }

/** vx right, vy forward, w clockwise, each −1..1 (PROTOCOL.md §2.1). */
data class Command(val vx: Float, val vy: Float, val w: Float, val enable: Boolean, val source: Source)
