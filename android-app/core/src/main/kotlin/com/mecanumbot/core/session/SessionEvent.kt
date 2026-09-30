package com.mecanumbot.core.session

/** Things the UI shows as "last event". Text is formatted by the app. */
sealed interface SessionEvent {
    /** No HELLO_ACK after 3 HELLOs; the session keeps retrying. */
    data object NotResponding : SessionEvent

    data class VersionMismatch(val espProtoVer: Int) : SessionEvent

    /** reset_count changed: the ESP32 restarted (`resetReason` = esp_reset_reason()). */
    data class Rebooted(val resetReason: Int, val resetCount: Int) : SessionEvent

    data class EspLog(val level: Int, val text: String) : SessionEvent
}
