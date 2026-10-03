package com.mecanumbot.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * The pilot's HTTP server (spec §6): Ktor CIO on 0.0.0.0:[port]. [assets] maps a file name under
 * assets/pilot/ to its bytes (null = missing). Every PilotHub call is made on [hubDispatcher].
 * Single use: once stopped it never starts again. start/stop may be called from any thread.
 * [start] throws an IOException if the port can't be bound and may then be called again.
 */
class PilotServer(
    private val hub: PilotHub,
    private val video: VideoSource,
    private val assets: (String) -> ByteArray?,
    private val hubDispatcher: CoroutineDispatcher,
    private val port: Int = PORT,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private var stopped = false

    @OptIn(DelicateCoroutinesApi::class) // what the scope-less embeddedServer() uses anyway
    @Synchronized
    fun start() {
        if (stopped || engine != null) return
        val e = GlobalScope.embeddedServer(CIO, port = port, host = "0.0.0.0", parentCoroutineContext = BIND_FAILURE) {
            pilotModule(hub, video, assets, hubDispatcher)
        }
        try {
            e.start(wait = false) // CIO waits for the bind and throws if it fails
        } catch (t: Throwable) {
            e.stop(gracePeriodMillis = 0, timeoutMillis = 200)
            // CIO wraps the BindException in a JobCancellationException.
            throw generateSequence(t) { it.cause }.filterIsInstance<IOException>().firstOrNull() ?: t
        }
        engine = e
    }

    @Synchronized
    fun stop() {
        stopped = true
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        engine = null
    }

    companion object {
        const val PORT = 8080

        /**
         * CIO's server job also reports a failed bind as an uncaught exception (on Android, a
         * crash); start() rethrows it, so drop it here. Anything else stays uncaught.
         */
        private val BIND_FAILURE = CoroutineExceptionHandler { _, e ->
            if (e !is IOException) Thread.currentThread().let { it.uncaughtExceptionHandler?.uncaughtException(it, e) }
        }
    }
}

fun Application.pilotModule(
    hub: PilotHub,
    video: VideoSource,
    assets: (String) -> ByteArray?,
    hubDispatcher: CoroutineDispatcher,
) {
    install(WebSockets) {
        pingPeriod = 2.seconds // a vanished laptop stops being the driver within ~4–6 s
        timeout = 4.seconds
        maxFrameSize = 4096
    }
    launch(hubDispatcher) {
        while (isActive) {
            hub.tick()
            delay(100)
        }
    }
    routing {
        get("/") { call.respondAsset("index.html", assets) }
        get("/stream") {
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-store")
            call.respondBytesWriter(ContentType.parse(MjpegWriter.CONTENT_TYPE)) {
                video.addViewer()
                try {
                    // StateFlow is conflated: a slow client skips frames instead of queueing them.
                    // A client that left is noticed on the next write, which ends this collect.
                    video.frames.filterNotNull().distinctUntilChangedBy { it.n }.collect { f ->
                        writeFully(MjpegWriter.part(f.bytes))
                        flush()
                    }
                } finally {
                    video.removeViewer()
                }
            }
        }
        get("/snapshot.jpg") {
            video.addViewer()
            val frame = try {
                withTimeoutOrNull(1_000) { video.frames.filterNotNull().first() }
            } finally {
                video.removeViewer()
            }
            call.response.header(HttpHeaders.CacheControl, "no-cache, no-store")
            if (frame == null) call.respondText("no video", status = HttpStatusCode.ServiceUnavailable)
            else call.respondBytes(frame.bytes, ContentType.Image.JPEG)
        }
        get("/api/status") {
            val status = withContext(hubDispatcher) { hub.apiStatus() }
            call.respondText(PilotJson.encodeToString(ApiStatus.serializer(), status), ContentType.Application.Json)
        }
        webSocket("/ws") {
            // Another site open in the same browser must not drive (no Origin: not a browser).
            val origin = call.request.headers[HttpHeaders.Origin]
            if (origin != null && origin.substringAfter("://") != call.request.headers[HttpHeaders.Host]) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin"))
                return@webSocket
            }
            val out = Channel<String>(64, BufferOverflow.DROP_OLDEST)
            val conn = PilotHub.Connection { out.trySend(it) }
            val writer = launch { for (text in out) send(Frame.Text(text)) }
            try {
                withContext(hubDispatcher) { hub.connect(conn) }
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        withContext(hubDispatcher) { hub.onText(conn, text) }
                    }
                }
            } finally {
                writer.cancel()
                withContext(NonCancellable + hubDispatcher) { hub.disconnect(conn) }
            }
        }
        get("/{file}") { call.respondAsset(call.parameters["file"].orEmpty(), assets) }
    }
}

private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

private suspend fun RoutingCall.respondAsset(name: String, assets: (String) -> ByteArray?) {
    val type = contentTypeOf(name)
    val bytes = if (SAFE_NAME.matches(name) && ".." !in name && type != null) assets(name) else null
    if (bytes == null || type == null) respondText("not found", status = HttpStatusCode.NotFound)
    else respondBytes(bytes, type)
}

internal fun contentTypeOf(name: String): ContentType? = when (name.substringAfterLast('.', "").lowercase()) {
    "html" -> ContentType.Text.Html.withParameter("charset", "utf-8")
    "css" -> ContentType.Text.CSS.withParameter("charset", "utf-8")
    "js" -> ContentType.Text.JavaScript.withParameter("charset", "utf-8")
    "svg" -> ContentType.Image.SVG
    "png" -> ContentType.Image.PNG
    "ico" -> ContentType("image", "x-icon")
    else -> null
}
