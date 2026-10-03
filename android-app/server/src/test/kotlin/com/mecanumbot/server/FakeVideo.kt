package com.mecanumbot.server

import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

class FakeVideo : VideoSource {
    override val frames = MutableStateFlow<JpegFrame?>(null)
    override val level = MutableStateFlow(VideoLevel.NORMAL)
    override val tempC = MutableStateFlow<Float?>(31.5f)
    val viewers = AtomicInteger()

    override fun addViewer() {
        viewers.incrementAndGet()
    }

    override fun removeViewer() {
        viewers.decrementAndGet()
    }
}
