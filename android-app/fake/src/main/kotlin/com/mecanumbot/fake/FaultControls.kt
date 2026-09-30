package com.mecanumbot.fake

import com.mecanumbot.core.protocol.Protocol

/** Fault injection for FakeLink (spec §7). Call from the FakeLink scope's dispatcher. */
class FaultControls internal constructor(private val link: FakeLink) {
    fun disconnect() = link.disconnect()

    fun reconnect() = link.connect()

    fun reboot() {
        link.esp.boot(link.now())
        link.esp.announce()
    }

    fun injectGarbage() = link.injectGarbage()

    /** Takes effect at once: the model re-announces with the new proto_ver. */
    var wrongProtoVer: Boolean
        get() = link.esp.protoVer != Protocol.PROTO_VER
        set(value) {
            link.esp.protoVer = if (value) Protocol.PROTO_VER + 1 else Protocol.PROTO_VER
            link.esp.announce()
        }

    var faultA: Boolean
        get() = link.esp.faultA
        set(value) { link.esp.faultA = value }

    var faultB: Boolean
        get() = link.esp.faultB
        set(value) { link.esp.faultB = value }

    var vmSagMv: Int
        get() = link.esp.extraSagMv
        set(value) { link.esp.extraSagMv = value.coerceIn(0, 3600) }

    /** Share of phone → ESP32 frames discarded, 0–100 %. */
    var dropPercent: Int
        get() = link.dropPercent
        set(value) { link.dropPercent = value.coerceIn(0, 100) }
}
