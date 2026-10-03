package com.mecanumbot.core.control

/** What the 40 Hz tick sends: DRIVE (possibly the zero pulse) or MOTOR_RAW. Values are i8 wire speeds. */
sealed interface Output {
    data class Drive(val flags: Int, val vx: Int, val vy: Int, val w: Int) : Output
    data class Raw(val m: List<Int>) : Output
}

/**
 * Picks the command to send — DESIGN.md §5.4. Pure; time comes in as `now` (ms). Every command
 * and RAW value expires [timeoutMs] after its last update, so a frozen caller cannot hold the
 * motors. STOP ×3 itself is sent by RobotSession; [stop] only clears.
 */
class Arbiter(private val timeoutMs: Long = 300) {
    private class Stamped<T>(val value: T, val at: Long)

    private val commands = HashMap<Source, Stamped<Command>>()
    private var raw: Stamped<List<Int>>? = null

    var mode: Mode = Mode.AUTO
        private set

    fun update(command: Command, now: Long) {
        commands[command.source] = Stamped(command, now)
    }

    fun setRaw(m: List<Int>, now: Long) {
        require(m.size == 4) { "MOTOR_RAW needs 4 channels" }
        raw = Stamped(m.map { it.coerceIn(-127, 127) }, now)
    }

    fun clearRaw() {
        raw = null
    }

    fun setMode(mode: Mode) {
        this.mode = mode
    }

    fun stop() {
        commands.clear()
        raw = null
    }

    /** Forgets [source]'s command at once, without waiting for it to expire. */
    fun release(source: Source) {
        commands.remove(source)
    }

    /** True while a RAW value is fresh, i.e. the next [tick] sends MOTOR_RAW. */
    fun rawActive(now: Long): Boolean = raw?.let { now - it.at < timeoutMs } ?: false

    fun activeSource(now: Long): Source? = Source.entries.firstOrNull { s ->
        val c = commands[s]
        allowed(s) && c != null && now - c.at < timeoutMs && c.value.enable
    }

    fun tick(now: Long): Output {
        raw?.let { if (now - it.at < timeoutMs) return Output.Raw(it.value) else raw = null }
        val source = activeSource(now) ?: return Output.Drive(0, 0, 0, 0)
        val c = commands.getValue(source).value
        return Output.Drive(1 or (source.code shl 1), Wire.toWire(c.vx), Wire.toWire(c.vy), Wire.toWire(c.w))
    }

    private fun allowed(s: Source): Boolean = when (mode) {
        Mode.AUTO -> true
        Mode.LOCAL_ONLY -> s != Source.REMOTE
        Mode.REMOTE_ONLY -> s != Source.LOCAL_PAD
    }
}
