package com.mecanumbot.server

import com.mecanumbot.core.control.Source
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.fake.FakeLink
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Routes end to end on real time: one thread plays Main, FakeLink plays the ESP32. */
class PilotServerTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val clock = { System.currentTimeMillis() }
    private val video = FakeVideo()
    private val files = mapOf(
        "index.html" to "<!doctype html>".toByteArray(),
        "pilot.js" to "1".toByteArray(),
        "pilot.css" to "a{}".toByteArray(),
        "notes.txt" to "x".toByteArray(),
    )

    @AfterEach
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private suspend fun readySession(): RobotSession = withContext(main) {
        val link = FakeLink(scope, clock)
        val s = RobotSession(link, scope, clock).also { it.start() }
        link.open()
        withTimeout(2_000) { s.state.first { it.phase == Phase.READY } }
        s
    }

    private fun ApplicationTestBuilder.pilot(session: RobotSession?): PilotHub {
        val hub = PilotHub(MutableStateFlow(session), video, clock, "0.1")
        application { pilotModule(hub, video, files::get, main) }
        return hub
    }

    @Test
    fun `ws drive reaches the session as REMOTE, second socket watches`() = testApplication {
        val session = readySession()
        pilot(session)
        val client = createClient { install(WebSockets) }
        client.webSocket("/ws") {
            val first = (incoming.receive() as Frame.Text).readText()
            assertTrue(first.contains(""""role":"driver""""), first)
            val driving = launch {
                while (true) {
                    send(Frame.Text("""{"t":"drive","vx":0,"vy":0.5,"w":0,"en":true}"""))
                    delay(25)
                }
            }
            withTimeout(1_000) { session.state.first { it.activeSource == Source.REMOTE } }
            client.webSocket("/ws") {
                val second = (incoming.receive() as Frame.Text).readText()
                assertTrue(second.contains(""""role":"watcher""""), second)
            }
            driving.cancel()
        }
        // Driver socket closed: REMOTE is released without waiting for expiry.
        withTimeout(250) { session.state.first { it.activeSource == null } }
    }

    @Test
    fun `ws telemetry arrives at 10 Hz`() = testApplication {
        pilot(readySession())
        val client = createClient { install(WebSockets) }
        client.webSocket("/ws") {
            var telemetry = 0
            withTimeout(1_000) {
                while (telemetry < 3) {
                    val text = (incoming.receive() as Frame.Text).readText()
                    if (text.startsWith("""{"t":"telemetry"""")) telemetry++
                }
            }
        }
    }

    @Test
    fun `snapshot is 503 without video, then the latest frame`() = testApplication {
        pilot(null)
        val none = client.get("/snapshot.jpg")
        assertEquals(HttpStatusCode.ServiceUnavailable, none.status)
        assertEquals(0, video.viewers.get())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2)
        video.frames.value = JpegFrame(jpeg, clock(), 1)
        val ok = client.get("/snapshot.jpg")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(ContentType.Image.JPEG, ok.contentType())
        assertArrayEquals(jpeg, ok.bodyAsBytes())
    }

    @Test
    fun `stream sends MJPEG parts and releases the viewer once the client is gone`() {
        // The in-memory test engine buffers responses, so this one runs the real CIO engine.
        val port = ServerSocket(0).use { it.localPort }
        val hub = PilotHub(MutableStateFlow(null), video, clock, "0.1")
        val server = PilotServer(hub, video, files::get, main, port).also { it.start() }
        try {
            val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 0xFF.toByte(), 0xD9.toByte())
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                assertTrue(head.contains("multipart/x-mixed-replace; boundary=frame"), head.toString())
                waitUntil { video.viewers.get() == 1 }
                video.frames.value = JpegFrame(jpeg, clock(), 1)
                // Chunked transfer: the part arrives inside a chunk, so look for it in the raw bytes.
                val part = String(MjpegWriter.part(jpeg), Charsets.ISO_8859_1)
                val body = StringBuilder()
                val buf = ByteArray(256)
                while (part !in body) {
                    val n = input.read(buf)
                    check(n > 0) { "stream ended: $body" }
                    body.append(String(buf, 0, n, Charsets.ISO_8859_1))
                }
            }
            // A closed client is noticed on the next write, so keep producing frames as the camera does.
            var n = 2L
            waitUntil {
                video.frames.value = JpegFrame(jpeg, clock(), n++)
                video.viewers.get() == 0
            }
        } finally {
            server.stop()
        }
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 2_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `static files have their content types, everything else is 404`() = testApplication {
        pilot(null)
        assertEquals(ContentType.Text.Html, client.get("/").contentType()?.withoutParameters())
        assertEquals("<!doctype html>", client.get("/").bodyAsText())
        assertEquals(ContentType.Text.JavaScript, client.get("/pilot.js").contentType()?.withoutParameters())
        assertEquals(ContentType.Text.CSS, client.get("/pilot.css").contentType()?.withoutParameters())
        for (path in listOf("/missing.js", "/notes.txt", "/..%2Findex.html", "/.hidden.js", "/a/pilot.js")) {
            assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
        }
    }

    @Test
    fun `api status reports the hub`() = testApplication {
        pilot(readySession())
        val body = client.get("/api/status").bodyAsText()
        assertEquals(
            """{"app":"0.1","fw":"0.1","link":"Connected","phase":"READY","driver_connected":false,"watchers":0,"video":"normal","ignored":0}""",
            body,
        )
    }
}
