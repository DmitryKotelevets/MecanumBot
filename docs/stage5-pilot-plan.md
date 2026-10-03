# Stage 5 Pilot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Drive MecanumBot from a laptop browser on the home Wi-Fi, with live video from the Pixel's camera, served by the robot app itself.

**Architecture:** Two new Android library modules. `:server` holds Ktor CIO routes plus the pure logic: `PilotHub` (one driver, many watchers, `/ws` JSON → `RobotSession`), `PilotMessages` and `MjpegWriter`. `:camera` holds CameraX → JPEG and a pure `ThermalGuard`. Neither module depends on the other: `:server` defines a `VideoSource` interface, and `RobotService` in `:app` adapts the camera to it. `RobotService` is a Foreground Service (camera|connectedDevice) that owns the server, the camera and the wake/Wi-Fi locks, so driving and video continue with the screen off. `AppGraph` keeps owning the link and the session. When the phone's own UI goes away it now releases only the local TEST/RAW sources (`RobotSession.releaseLocal()`), so a remote pilot is not stopped. `pilot-web/` is plain HTML/CSS/JS, copied into the APK's assets by a Gradle task.

**Tech Stack:**
- Build: Gradle 9.6.0, AGP 9.3.3 (built-in Kotlin), Kotlin 2.4.20, kotlinx-serialization plugin 2.4.20
- Libraries: Ktor 3.6.0 (server-core, server-cio, server-websockets), kotlinx-serialization-json 1.11.0, CameraX 1.6.2 (camera2, lifecycle), lifecycle-service 2.11.0, plus the existing coroutines, Compose and DataStore
- Tests: JUnit Jupiter (BOM 6.1.3) on Android local unit tests (`useJUnitPlatform()`), kotlinx-coroutines-test, ktor-server-test-host 3.6.0, ktor-client-websockets 3.6.0, `:fake` as a test dependency of `:server`

**Verified:** on 2026-10-03 every file in this plan was built in a scratch worktree (branch `stage5-draft`).
- **Tests run:** `:core:test` 173, `:fake:test` 29, `:server:testDebugUnitTest` 25, `:camera:testDebugUnitTest` 5, all with 0 failures. So the compile and test steps of Tasks 1–5 are known to pass. If one fails as written, suspect the environment (JDK, SDK, network for the new dependencies) before the code.
- **Compiled only:** `MjpegCamera` (Task 5) and all of Tasks 7–8 passed `:app:assembleDebug`, and the APK contains `assets/pilot/{index.html,pilot.css,pilot.js}`. Task 6's `pilot.js` passed `node --check` only.
- **Never run:** no camera was opened, no service was started, and no browser loaded the page. The manual checks in Task 9 test behaviour nobody has checked yet.

**Spec:** `docs/stage5-pilot-spec.md` (v1.0). The wire format to the ESP32 is unchanged (`protocol/PROTOCOL.md`); the executor reads the spec.

## Global Constraints

- SDK levels: minSdk 34, compileSdk 37, targetSdk 36. Bytecode target JVM 17. Package root `com.mecanumbot`.
- Gradle runs on Android Studio's bundled JDK. Every Gradle command in this plan is run from `android-app/` as `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew …`. Do not add `jvmToolchain(17)`.
- `android-app/gradle/gradle-daemon-jvm.properties` and `.vscode/` are untracked files that belong to the user. Leave them alone and never commit them. Every commit stages only the files it lists (`git add <paths>`, never `-a`).
- `:core` and `:fake` stay plain JVM with no Android dependencies. `:server`'s `PilotHub`, `PilotMessages`, `MjpegWriter`, `Video.kt` and `:camera`'s `ThermalGuard` use no Android APIs, so they run as local unit tests without Robolectric.
- `RobotSession` and `PilotHub` are not thread-safe. Every call goes through the session's dispatcher (`Dispatchers.Main.immediate` in the app). Ktor code reaches the hub only via `withContext(hubDispatcher)`.
- No path may drive a motor without a pulse. REMOTE commands go through the Arbiter's 300 ms expiry, and the ESP32 failsafe (`failsafe_ms`, 300 ms) is unchanged. STOP is instant and always STOP ×3 (`RobotSession.stop()`).
- No protocol change: `protocol/PROTOCOL.md` and `protocol/vectors.json` are untouched.
- Never call `setDTR`/`setRTS` (the USB code is not touched in this stage).
- Server: port 8080 on `0.0.0.0`, no authentication (spec §10).
- `/ws` JSON field names exactly as spec §4.1: `t`, `vx`, `vy`, `w`, `en`, `ts`, `role` (`driver`/`watcher`), `fw`, `app`, `mode`, `vm`, `pwm`, `failsafe`, `fault`, `usb`, `phase`, `active`, `rtt_ms`, `rx_fps`, `stops`, `temp_c`, `video` (`normal`/`reduced`/`off`), `video_age_ms`.
- App version string for the pilot is `"0.1"` (`AppGraph.APP_MAJOR`.`APP_MINOR`).
- All UI text is in English.

## Review Focus

These are situations the spec implies but none of its explicit requirements test directly. Each one is pinned by a test or a manual check in the task named at the end of its line.
1. **The operator switches tabs on the phone while a remote pilot drives.** Today `TestScreen` calls `session.stop()` in `onDispose`, which would STOP ×3 the pilot. Leaving the Test screen must stop only TEST/RAW driving. Pinned by `RobotSessionReleaseTest` (Task 1), the `TestScreen` change (Task 7) and manual check 6 in Task 9.
2. **A `/stream` client vanishes (tab closed, laptop asleep).** Its viewer slot must be released, or the camera runs (and heats) for nobody. Ktor notices a gone client on the next write. Pinned by `PilotServerTest` "releases the viewer once the client is gone" (Task 4) and manual check 11 in Task 9. While video is OFF nothing is written, so the slot is released only after video resumes; that is harmless and documented in the code.
3. **The camera can't start as a camera service** (CAMERA denied, or Android refuses a camera FGS because the app isn't visible). The service must fall back to `connectedDevice` and keep driving, not crash. Pinned by the `startForegroundSafely` fallback (Task 7) and manual check 10 in Task 9.
4. **A watcher is promoted to driver while its Shift key is held.** It must not start driving without a new press. Pinned server-side by the promotion test (Task 3), page-side by `releaseDeadman()` on every role change (Task 6), and by manual check 5 in Task 9.
5. **Hostile or broken input on `/ws` and static paths** (`NaN`, missing fields, unknown `t`, `/..%2F`, dot-files, nested paths). It is ignored or answered 404 and never crashes or drives. Pinned by `PilotMessagesTest` (Task 2), `PilotHubTest` "garbage is counted" (Task 3) and the static-file test (Task 4).

Deviations from the spec made while planning:
- `MjpegCamera` encodes RGBA → Bitmap → JPEG instead of YUV → NV21 → YuvImage, so CameraX handles the rotation (Task 5).
- The manifest adds `CHANGE_WIFI_STATE`, which a connectedDevice service needs on Android 14+ (Task 7).
- `TestScreen`'s dispose hooks change from `stop()` to `releaseLocal()`. The spec missed them, and without the change spec §4.3 can't hold (Task 7).
- No debug temperature injection: the thermal check is optional and natural (Task 9). ThermalGuard's logic is fully unit-tested.

Known and accepted: each `/ws` client has an outgoing buffer of 64 messages that drops the oldest. A stalled browser can lose a `status` or `pong`, but the next `tick()` resends any changed status. After a USB↔Fake switch, `status.fw` updates on the next 100 ms tick.

---

## File Structure

```
android-app/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml   (modified: new modules, plugins, libraries)
├── core/src/main/kotlin/com/mecanumbot/core/
│   ├── control/Arbiter.kt           + release(source), rawActive(now)
│   └── session/RobotSession.kt      + releaseLocal(), mode
├── core/src/test/kotlin/com/mecanumbot/core/
│   ├── control/ArbiterTest.kt       + 2 tests
│   └── session/RobotSessionReleaseTest.kt
├── server/build.gradle.kts
├── server/src/main/kotlin/com/mecanumbot/server/
│   ├── Video.kt           JpegFrame, VideoLevel, VideoSource (what the server needs from the camera)
│   ├── PilotMessages.kt   /ws JSON types, ApiStatus, PilotJson, decode/encode
│   ├── MjpegWriter.kt     multipart part framing
│   ├── PilotHub.kt        driver/watchers, /ws → session, telemetry/status building
│   └── PilotServer.kt     Ktor CIO server + pilotModule() routes
├── server/src/test/kotlin/com/mecanumbot/server/
│   ├── FakeVideo.kt, PilotMessagesTest.kt, MjpegWriterTest.kt, PilotHubTest.kt, PilotServerTest.kt
├── camera/build.gradle.kts
├── camera/src/main/kotlin/com/mecanumbot/camera/
│   ├── ThermalGuard.kt    battery °C → ThermalLevel with hysteresis (pure)
│   └── MjpegCamera.kt     CameraX ImageAnalysis → JPEG StateFlow, bound to a LifecycleOwner
├── camera/src/test/kotlin/com/mecanumbot/camera/ThermalGuardTest.kt
└── app/
    ├── build.gradle.kts                     + :server, :camera, lifecycle-service, SyncPilotWeb task
    ├── src/main/AndroidManifest.xml         + permissions, RobotService
    └── src/main/kotlin/com/mecanumbot/app/
        ├── AppGraph.kt          + sessions, pilot state, releaseLocal on pause
        ├── MainActivity.kt      + permission request, starts RobotService
        ├── CameraPreference.kt  lens + rotation in DataStore
        ├── PilotInfo.kt         what the status bar shows about the pilot
        ├── WifiAddress.kt       Wi-Fi IPv4 as a StateFlow
        ├── CameraVideo.kt       MjpegCamera + ThermalGuard → VideoSource
        ├── RobotService.kt      the Foreground Service
        └── ui/StatusBar.kt, ui/SettingsScreen.kt, ui/TestScreen.kt, ui/Root.kt   (modified)
pilot-web/index.html, pilot.css, pilot.js, README.md
CLAUDE.md, android-app/README.md   (modified)
```


### Task 1: `:core` — release local sources without stopping a remote pilot

Spec §4.3. Leaving the robot's own UI must STOP ×3 only if TEST or RAW is driving; otherwise it quietly drops TEST/RAW so REMOTE keeps driving. `mode` is exposed for the pilot's `status` message.

**Files:**
- Modify: `android-app/core/src/main/kotlin/com/mecanumbot/core/control/Arbiter.kt` (after `stop()`)
- Modify: `android-app/core/src/main/kotlin/com/mecanumbot/core/session/RobotSession.kt` (after `setMode`, before `sendConfig`'s KDoc; one import)
- Modify: `android-app/core/src/test/kotlin/com/mecanumbot/core/control/ArbiterTest.kt`
- Create: `android-app/core/src/test/kotlin/com/mecanumbot/core/session/RobotSessionReleaseTest.kt`

**Interfaces:**
- Consumes: existing `Arbiter`, `RobotSession`, `ScriptedLink`/`readySession()` test helpers.
- Produces: `Arbiter.release(source: Source)`, `Arbiter.rawActive(now: Long): Boolean`, `RobotSession.releaseLocal()`, `RobotSession.mode: Mode` (read-only property).

- [ ] **Step 1: Write the failing tests**

Append inside `class ArbiterTest` (before the final `}`), and add the imports `org.junit.jupiter.api.Assertions.assertFalse` and `org.junit.jupiter.api.Assertions.assertTrue` if they are missing:

```kotlin
    @Test
    fun `release forgets one source at once and leaves the others`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST), 0)
        a.update(cmd(Source.REMOTE), 0)
        a.release(Source.TEST)
        assertEquals(Source.REMOTE, a.activeSource(1))
        a.release(Source.REMOTE)
        assertNull(a.activeSource(1))
    }

    @Test
    fun `rawActive follows the RAW expiry`() {
        val a = Arbiter()
        assertFalse(a.rawActive(0))
        a.setRaw(listOf(1, 2, 3, 4), 1000)
        assertTrue(a.rawActive(1299))
        assertFalse(a.rawActive(1300))
    }
```

Create `RobotSessionReleaseTest.kt`:

`android-app/core/src/test/kotlin/com/mecanumbot/core/session/RobotSessionReleaseTest.kt`:

```kotlin
package com.mecanumbot.core.session

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Mode
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.FrameType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RobotSessionReleaseTest {
    @Test
    fun `releaseLocal with TEST driving sends STOP x3 and clears REMOTE too`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        s.update(Command(0f, 0.5f, 0f, true, Source.REMOTE))
        advanceTimeBy(30); runCurrent()
        s.releaseLocal()
        assertEquals(3, link.ofType(FrameType.STOP).size)
        assertEquals(1, s.state.value.stops)
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `releaseLocal with RAW driving sends STOP x3`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.setRaw(listOf(50, 50, 50, 50))
        advanceTimeBy(30); runCurrent()
        s.releaseLocal()
        assertEquals(3, link.ofType(FrameType.STOP).size)
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `releaseLocal with only REMOTE active sends no STOP and REMOTE keeps driving`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 0.5f, 0f, false, Source.TEST)) // Test screen open, deadman not held
        s.update(Command(0f, 0.5f, 0f, true, Source.REMOTE))
        advanceTimeBy(30); runCurrent()
        s.releaseLocal()
        advanceTimeBy(25); runCurrent()
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
        assertEquals(0, s.state.value.stops)
        assertEquals(Source.REMOTE, s.state.value.activeSource)
        assertEquals(Drive(1 or (Source.REMOTE.code shl 1), 0, 64, 0), link.lastMotion())
    }

    @Test
    fun `releaseLocal with nothing active is quiet`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.releaseLocal()
        advanceTimeBy(30); runCurrent()
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `mode reflects setMode`() = runTest {
        val s = readySession(ScriptedLink())
        assertEquals(Mode.AUTO, s.mode)
        s.setMode(Mode.LOCAL_ONLY)
        assertEquals(Mode.LOCAL_ONLY, s.mode)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:test`
Expected: compilation FAILS with `Unresolved reference 'release'`, `'rawActive'`, `'releaseLocal'`, `'mode'`.

- [ ] **Step 3: Implement**

In `Arbiter.kt`, directly after `fun stop() { … }`:

```kotlin
    /** Forgets [source]'s command at once, without waiting for it to expire. */
    fun release(source: Source) {
        commands.remove(source)
    }

    /** True while a RAW value is fresh, i.e. the next [tick] sends MOTOR_RAW. */
    fun rawActive(now: Long): Boolean = raw?.let { now - it.at < timeoutMs } ?: false
```

In `RobotSession.kt`, add `import com.mecanumbot.core.control.Source` after the `Output` import. Directly after `fun setMode(mode: Mode) = arbiter.setMode(mode)` add:

```kotlin
    val mode: Mode get() = arbiter.mode
```

Directly before the KDoc of `sendConfig` (`/** Stops the robot, then sends CONFIG; …`) add:

```kotlin
    /**
     * The robot's own UI went away (Activity paused, Test screen left). If TEST or RAW is driving,
     * that is a stop (STOP ×3, everything cleared). Otherwise TEST and RAW are dropped quietly and
     * a remote pilot keeps driving (stage 5 spec §4.3).
     */
    fun releaseLocal() {
        val now = clock()
        if (arbiter.rawActive(now) || arbiter.activeSource(now) == Source.TEST) {
            stop()
        } else {
            arbiter.release(Source.TEST)
            arbiter.clearRaw()
        }
    }

```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL. `core/build/test-results/test/TEST-com.mecanumbot.core.session.RobotSessionReleaseTest.xml` shows `tests="5" failures="0"`; `ArbiterTest` shows `tests="12" failures="0"`.

- [ ] **Step 5: Commit**

```bash
git add android-app/core/src/main/kotlin/com/mecanumbot/core/control/Arbiter.kt \
        android-app/core/src/main/kotlin/com/mecanumbot/core/session/RobotSession.kt \
        android-app/core/src/test/kotlin/com/mecanumbot/core/control/ArbiterTest.kt \
        android-app/core/src/test/kotlin/com/mecanumbot/core/session/RobotSessionReleaseTest.kt
git commit -m "Android core: releaseLocal keeps a remote pilot driving when the local UI goes away"
```

---

### Task 2: `:server` module, `/ws` messages and MJPEG framing

Spec §2, §4.1, §6. Creates the module and the pure building blocks the hub and routes use.

**Files:**
- Modify: `android-app/gradle/libs.versions.toml`, `android-app/settings.gradle.kts`, `android-app/build.gradle.kts`
- Create: `android-app/server/build.gradle.kts`
- Create: `android-app/server/src/main/kotlin/com/mecanumbot/server/Video.kt`
- Create: `android-app/server/src/main/kotlin/com/mecanumbot/server/PilotMessages.kt`
- Create: `android-app/server/src/main/kotlin/com/mecanumbot/server/MjpegWriter.kt`
- Test: `android-app/server/src/test/kotlin/com/mecanumbot/server/PilotMessagesTest.kt`, `MjpegWriterTest.kt`

**Interfaces:**
- Consumes: `:core` (api), `:fake` (tests only, used from Task 3).
- Produces:
  - `class JpegFrame(bytes: ByteArray, capturedAt: Long, n: Long)`, `enum VideoLevel { NORMAL, REDUCED, OFF }`
  - `interface VideoSource { val frames: StateFlow<JpegFrame?>; val level: StateFlow<VideoLevel>; val tempC: StateFlow<Float?>; fun addViewer(); fun removeViewer() }`
  - `sealed interface Inbound`: `DriveMsg(vx, vy, w: Float, en: Boolean)`, `StopMsg` (object), `PingMsg(ts: Long)`
  - `sealed interface Outbound`: `StatusMsg(role: Role, fw: String?, app: String, mode: String)`, `TelemetryMsg(…13 fields…)`, `PongMsg(ts: Long)`; `enum Role { DRIVER, WATCHER }`
  - `ApiStatus(app, fw, link, phase, driverConnected, watchers, video, ignored)`, `val PilotJson: Json`
  - `PilotMessages.decode(text: String): Inbound?`, `PilotMessages.encode(msg: Outbound): String`
  - `MjpegWriter.CONTENT_TYPE`, `MjpegWriter.part(jpeg: ByteArray): ByteArray`

- [ ] **Step 1: Gradle wiring**

In `gradle/libs.versions.toml`, under `[versions]` after `usbSerial`:

```toml
ktor = "3.6.0"
camerax = "1.6.2"
lifecycleService = "2.11.0"
```

Under `[libraries]` before `usb-serial`:

```toml
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-cio = { module = "io.ktor:ktor-server-cio", version.ref = "ktor" }
ktor-server-websockets = { module = "io.ktor:ktor-server-websockets", version.ref = "ktor" }
ktor-server-test-host = { module = "io.ktor:ktor-server-test-host", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
lifecycle-service = { module = "androidx.lifecycle:lifecycle-service", version.ref = "lifecycleService" }
```

Under `[plugins]` before `kotlin-compose`:

```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

In `build.gradle.kts` (root) add to `plugins { }`:

```kotlin
    alias(libs.plugins.kotlin.serialization) apply false
```

In `settings.gradle.kts` change the include line to:

```kotlin
include(":core", ":fake", ":usb", ":server", ":app")
```

Create `server/build.gradle.kts`:

`android-app/server/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mecanumbot.server"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
}

dependencies {
    api(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.serialization.json)

    testImplementation(project(":fake"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}
```

- [ ] **Step 2: Write the failing tests**

`android-app/server/src/test/kotlin/com/mecanumbot/server/PilotMessagesTest.kt`:

```kotlin
package com.mecanumbot.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PilotMessagesTest {
    @Test
    fun `decodes every inbound message`() {
        assertEquals(DriveMsg(0f, 0.5f, -0.25f, true), PilotMessages.decode("""{"t":"drive","vx":0.0,"vy":0.5,"w":-0.25,"en":true}"""))
        assertEquals(StopMsg, PilotMessages.decode("""{"t":"stop"}"""))
        assertEquals(PingMsg(1234), PilotMessages.decode("""{"t":"ping","ts":1234}"""))
    }

    @Test
    fun `extra fields are ignored, like DESIGN's old seq`() {
        assertEquals(DriveMsg(0f, 1f, 0f, false), PilotMessages.decode("""{"t":"drive","seq":7,"vx":0,"vy":1,"w":0,"en":false}"""))
    }

    @Test
    fun `anything invalid decodes to null`() {
        listOf(
            "",
            "not json",
            "[]",
            "{}",
            """{"t":"fly"}""",
            """{"t":"drive","vx":0,"vy":0,"w":0}""",
            """{"t":"drive","vx":"fast","vy":0,"w":0,"en":true}""",
            """{"t":"drive","vx":NaN,"vy":0,"w":0,"en":true}""",
            """{"t":"ping"}""",
            """{"t":"drive","vx":0,"vy":0,"w":0,"en":true""",
        ).forEach { assertNull(PilotMessages.decode(it), it) }
    }

    @Test
    fun `encodes outbound messages with t and snake_case fields`() {
        assertEquals(
            """{"t":"status","role":"driver","fw":"0.1","app":"0.1","mode":"AUTO"}""",
            PilotMessages.encode(StatusMsg(Role.DRIVER, "0.1", "0.1", "AUTO")),
        )
        assertEquals("""{"t":"pong","ts":5}""", PilotMessages.encode(PongMsg(5)))
        assertEquals(
            """{"t":"telemetry","vm":null,"pwm":null,"failsafe":null,"fault":null,"usb":false,"phase":"DISCONNECTED",""" +
                """"active":null,"rtt_ms":null,"rx_fps":null,"stops":0,"temp_c":null,"video":"off","video_age_ms":null}""",
            PilotMessages.encode(TelemetryMsg(null, null, null, null, false, "DISCONNECTED", null, null, null, 0, null, "off", null)),
        )
    }
}
```

`android-app/server/src/test/kotlin/com/mecanumbot/server/MjpegWriterTest.kt`:

```kotlin
package com.mecanumbot.server

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MjpegWriterTest {
    @Test
    fun `part is boundary, headers, bytes, CRLF`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val expected = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n".toByteArray() + jpeg + "\r\n".toByteArray()
        assertArrayEquals(expected, MjpegWriter.part(jpeg))
    }

    @Test
    fun `content type names the same boundary`() {
        assertEquals("multipart/x-mixed-replace; boundary=frame", MjpegWriter.CONTENT_TYPE)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :server:testDebugUnitTest`
Expected: compilation FAILS (`Unresolved reference 'PilotMessages'`, `'MjpegWriter'`). The first run also downloads Ktor.

- [ ] **Step 4: Implement**

`Video.kt`:

`android-app/server/src/main/kotlin/com/mecanumbot/server/Video.kt`:

```kotlin
package com.mecanumbot.server

import kotlinx.coroutines.flow.StateFlow

/** One encoded camera frame. [n] increases by one per frame; [capturedAt] uses the app clock (ms). */
class JpegFrame(val bytes: ByteArray, val capturedAt: Long, val n: Long)

enum class VideoLevel { NORMAL, REDUCED, OFF }

/**
 * What the server needs from the camera (spec §2). The app adapts the camera and the thermal guard
 * to it. Implementations must be thread-safe: Ktor calls them from its own threads.
 */
interface VideoSource {
    /** Latest frame, or null while the camera is idle or off. */
    val frames: StateFlow<JpegFrame?>
    val level: StateFlow<VideoLevel>
    val tempC: StateFlow<Float?>

    /** A /stream client or snapshot request started; the camera runs while the count is above 0. */
    fun addViewer()
    fun removeViewer()
}
```

`PilotMessages.kt`:

`android-app/server/src/main/kotlin/com/mecanumbot/server/PilotMessages.kt`:

```kotlin
package com.mecanumbot.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** /ws JSON (spec §4.1). The `t` field is the message type. */
val PilotJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed interface Inbound

/** vx right, vy forward, w clockwise, −1..1 (clamped by the hub); [en] is the deadman. */
@Serializable
@SerialName("drive")
data class DriveMsg(val vx: Float, val vy: Float, val w: Float, val en: Boolean) : Inbound

@Serializable
@SerialName("stop")
data object StopMsg : Inbound

@Serializable
@SerialName("ping")
data class PingMsg(val ts: Long) : Inbound

@Serializable
sealed interface Outbound

@Serializable
enum class Role {
    @SerialName("driver") DRIVER,
    @SerialName("watcher") WATCHER,
}

@Serializable
@SerialName("status")
data class StatusMsg(val role: Role, val fw: String?, val app: String, val mode: String) : Outbound

/** Nullable fields are null until the first TELEMETRY frame (or the first video frame). */
@Serializable
@SerialName("telemetry")
data class TelemetryMsg(
    val vm: Float?,
    val pwm: List<Int>?,
    val failsafe: Boolean?,
    val fault: Boolean?,
    val usb: Boolean,
    val phase: String,
    val active: String?,
    @SerialName("rtt_ms") val rttMs: Long?,
    @SerialName("rx_fps") val rxFps: Int?,
    val stops: Int,
    @SerialName("temp_c") val tempC: Float?,
    val video: String,
    @SerialName("video_age_ms") val videoAgeMs: Long?,
) : Outbound

@Serializable
@SerialName("pong")
data class PongMsg(val ts: Long) : Outbound

/** GET /api/status. */
@Serializable
data class ApiStatus(
    val app: String,
    val fw: String?,
    val link: String,
    val phase: String,
    @SerialName("driver_connected") val driverConnected: Boolean,
    val watchers: Int,
    val video: String,
    val ignored: Int,
)

object PilotMessages {
    /** Null for anything that is not a valid inbound message; the caller counts it and moves on. */
    fun decode(text: String): Inbound? = try {
        PilotJson.decodeFromString(Inbound.serializer(), text)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encode(msg: Outbound): String = PilotJson.encodeToString(Outbound.serializer(), msg)
}
```

`MjpegWriter.kt`:

`android-app/server/src/main/kotlin/com/mecanumbot/server/MjpegWriter.kt`:

```kotlin
package com.mecanumbot.server

/** multipart/x-mixed-replace framing for GET /stream (spec §6). */
object MjpegWriter {
    const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=frame"

    fun part(jpeg: ByteArray): ByteArray {
        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
        return header.toByteArray(Charsets.US_ASCII) + jpeg + CRLF
    }

    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :server:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `PilotMessagesTest` 4 tests, `MjpegWriterTest` 2 tests, 0 failures (`server/build/test-results/testDebugUnitTest/`).

- [ ] **Step 6: Commit**

```bash
git add android-app/gradle/libs.versions.toml android-app/settings.gradle.kts android-app/build.gradle.kts \
        android-app/server/build.gradle.kts \
        android-app/server/src/main/kotlin/com/mecanumbot/server/Video.kt \
        android-app/server/src/main/kotlin/com/mecanumbot/server/PilotMessages.kt \
        android-app/server/src/main/kotlin/com/mecanumbot/server/MjpegWriter.kt \
        android-app/server/src/test/kotlin/com/mecanumbot/server/PilotMessagesTest.kt \
        android-app/server/src/test/kotlin/com/mecanumbot/server/MjpegWriterTest.kt
git commit -m "Android server: module, /ws JSON messages, MJPEG part framing"
```

---

### Task 3: `PilotHub` — one driver, watchers, `/ws` → session

Spec §4.2. Pure: no Ktor. Tested with a real `RobotSession` on `FakeLink` in virtual time.

**Files:**
- Create: `android-app/server/src/main/kotlin/com/mecanumbot/server/PilotHub.kt`
- Test: `android-app/server/src/test/kotlin/com/mecanumbot/server/FakeVideo.kt`, `PilotHubTest.kt`

**Interfaces:**
- Consumes: `RobotSession.update/stop/state/mode` (Task 1 for `mode`), `VideoSource`, `PilotMessages`, `TelemetryMsg`, `StatusMsg`, `ApiStatus` (Task 2).
- Produces:
  - `class PilotHub(sessions: StateFlow<RobotSession?>, video: VideoSource, clock: () -> Long, appVersion: String)`
  - `fun interface PilotHub.Connection { fun send(text: String) }`
  - `PilotHub.connect(c)`, `disconnect(c)`, `onText(c, text)`, `tick()`, `telemetry(): TelemetryMsg`, `apiStatus(): ApiStatus`
  - `data class PilotHub.Summary(driverConnected: Boolean, watchers: Int, ignored: Int)`, `val summary: StateFlow<Summary>`
  - Test helper `FakeVideo : VideoSource` with `frames`, `level`, `tempC` as `MutableStateFlow` and `viewers: AtomicInteger`

- [ ] **Step 1: Write the failing tests**

`android-app/server/src/test/kotlin/com/mecanumbot/server/FakeVideo.kt`:

```kotlin
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
```

`android-app/server/src/test/kotlin/com/mecanumbot/server/PilotHubTest.kt`:

```kotlin
package com.mecanumbot.server

import com.mecanumbot.core.control.Mode
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.fake.FakeLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class PilotHubTest {
    /** Records what the hub sends to one client. */
    private class Client : PilotHub.Connection {
        val texts = mutableListOf<String>()
        override fun send(text: String) {
            texts += text
        }
        fun statuses() = texts.filter { it.startsWith("""{"t":"status"""") }
        fun lastRole(): String? = statuses().lastOrNull()?.substringAfter(""""role":"""")?.substringBefore('"')
    }

    private fun drive(vy: Float, en: Boolean = true) = """{"t":"drive","vx":0,"vy":$vy,"w":0,"en":$en}"""

    private suspend fun TestScope.readySession(): Pair<RobotSession, FakeLink> {
        val link = FakeLink(backgroundScope, { testScheduler.currentTime }, Random(1))
        val s = RobotSession(link, backgroundScope, { testScheduler.currentTime }).also { it.start() }
        runCurrent()
        link.open(); runCurrent()
        advanceTimeBy(150); runCurrent()
        check(s.state.value.phase == Phase.READY) { "not READY: ${s.state.value}" }
        return s to link
    }

    private fun TestScope.hub(session: RobotSession?, video: VideoSource = FakeVideo()) =
        PilotHub(MutableStateFlow(session), video, { testScheduler.currentTime }, "0.1")

    @Test
    fun `first connection drives, the second watches`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        assertEquals("driver", a.lastRole())
        assertEquals("watcher", b.lastRole())
        assertEquals(PilotHub.Summary(true, 1, 0), hub.summary.value)
        assertEquals("""{"t":"status","role":"driver","fw":"0.1","app":"0.1","mode":"AUTO"}""", a.statuses().single())
    }

    @Test
    fun `driver's drive reaches the session as REMOTE`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
    }

    @Test
    fun `watcher's drive is dropped`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        Client().also(hub::connect)
        val b = Client().also(hub::connect)
        hub.onText(b, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `watcher's stop stops the robot`() = runTest {
        val (s, link) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        hub.onText(b, """{"t":"stop"}""")
        advanceTimeBy(30); runCurrent()
        assertEquals(1, s.state.value.stops)
        assertEquals(3, link.esp.stopsReceived)
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `driver leaving releases REMOTE at once and promotes the oldest watcher`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        val c = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
        hub.disconnect(a)
        advanceTimeBy(25); runCurrent() // well inside the arbiter's 300 ms
        assertNull(s.state.value.activeSource)
        assertEquals("driver", b.lastRole())
        assertEquals("watcher", c.lastRole())
        assertEquals(1, c.statuses().size) // c's role did not change, so no new status
        hub.onText(b, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
    }

    @Test
    fun `a watcher leaving does not touch the driver`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        hub.disconnect(b)
        advanceTimeBy(30); runCurrent()
        assertEquals(Source.REMOTE, s.state.value.activeSource)
        assertEquals(PilotHub.Summary(true, 0, 0), hub.summary.value)
    }

    @Test
    fun `out-of-range speeds drive like full scale`() = runTest {
        val (s, link) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        suspend fun pwmAfter(text: String): List<Int> {
            repeat(20) { hub.onText(a, text); advanceTimeBy(25); runCurrent() } // 500 ms, past slew
            return link.esp.pwm(testScheduler.currentTime)
        }
        val full = pwmAfter("""{"t":"drive","vx":-1,"vy":1,"w":0.5,"en":true}""")
        val over = pwmAfter("""{"t":"drive","vx":-5,"vy":9,"w":0.5,"en":true}""")
        assertEquals(full, over)
        assertTrue(full.any { it != 0 })
    }

    @Test
    fun `garbage is counted, the connection keeps working`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        hub.onText(a, "garbage")
        hub.onText(a, """{"t":"fly"}""")
        assertEquals(2, hub.summary.value.ignored)
        hub.onText(a, """{"t":"ping","ts":42}""")
        assertEquals("""{"t":"pong","ts":42}""", a.texts.last())
    }

    @Test
    fun `messages from an unknown connection are ignored`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val ghost = Client()
        hub.onText(ghost, drive(0.5f))
        hub.onText(ghost, """{"t":"stop"}""")
        advanceTimeBy(30); runCurrent()
        assertNull(s.state.value.activeSource)
        assertEquals(0, s.state.value.stops)
    }

    @Test
    fun `a session swap routes later commands to the new session`() = runTest {
        val (s1, _) = readySession()
        val (s2, _) = readySession()
        val sessions = MutableStateFlow<RobotSession?>(s1)
        val hub = PilotHub(sessions, FakeVideo(), { testScheduler.currentTime }, "0.1")
        val a = Client().also(hub::connect)
        sessions.value = s2
        hub.onText(a, drive(0.5f))
        advanceTimeBy(30); runCurrent()
        assertNull(s1.state.value.activeSource)
        assertEquals(Source.REMOTE, s2.state.value.activeSource)
    }

    @Test
    fun `telemetry before any session is all nulls`() = runTest {
        val video = FakeVideo().apply { tempC.value = null; level.value = VideoLevel.OFF }
        assertEquals(
            TelemetryMsg(null, null, null, null, false, "DISCONNECTED", null, null, null, 0, null, "off", null),
            hub(null, video).telemetry(),
        )
    }

    @Test
    fun `telemetry carries session, video age and temperature`() = runTest {
        val (s, _) = readySession()
        val video = FakeVideo()
        val hub = hub(s, video)
        val a = Client().also(hub::connect)
        hub.onText(a, drive(0.5f))
        advanceTimeBy(200); runCurrent() // FakeEsp32 telemetry is 10 Hz
        video.frames.value = JpegFrame(byteArrayOf(1), testScheduler.currentTime - 60, 1)
        val t = hub.telemetry()
        assertEquals(true, t.usb)
        assertEquals("READY", t.phase)
        assertEquals("REMOTE", t.active)
        assertNotNull(t.vm)
        assertEquals(4, t.pwm?.size)
        assertEquals(false, t.failsafe)
        assertEquals(60L, t.videoAgeMs)
        assertEquals(31.5f, t.tempC)
        assertEquals("normal", t.video)
    }

    @Test
    fun `tick sends telemetry to everyone and status only on change`() = runTest {
        val (s, _) = readySession()
        val hub = hub(s)
        val a = Client().also(hub::connect)
        val b = Client().also(hub::connect)
        hub.tick()
        hub.tick()
        assertEquals(2, a.texts.count { it.startsWith("""{"t":"telemetry"""") })
        assertEquals(2, b.texts.count { it.startsWith("""{"t":"telemetry"""") })
        assertEquals(1, a.statuses().size)
        s.setMode(Mode.LOCAL_ONLY)
        hub.tick()
        assertEquals(2, a.statuses().size)
        assertTrue(a.statuses().last().contains(""""mode":"LOCAL_ONLY""""))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :server:testDebugUnitTest`
Expected: compilation FAILS with `Unresolved reference 'PilotHub'`.

- [ ] **Step 3: Implement**

`android-app/server/src/main/kotlin/com/mecanumbot/server/PilotHub.kt`:

```kotlin
package com.mecanumbot.server

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pilots (spec §4.2): the first connection drives, the rest watch; anyone may STOP. Pure, no
 * Ktor. Not thread-safe: call every method from the dispatcher the sessions run on (Main in the
 * app), like RobotSession itself.
 */
class PilotHub(
    private val sessions: StateFlow<RobotSession?>,
    private val video: VideoSource,
    private val clock: () -> Long,
    private val appVersion: String,
) {
    /** One /ws client. [send] must not block: the server drops old messages for a slow client. */
    fun interface Connection {
        fun send(text: String)
    }

    data class Summary(val driverConnected: Boolean, val watchers: Int, val ignored: Int)

    private val connections = mutableListOf<Connection>() // arrival order; the first one drives
    private val lastStatus = HashMap<Connection, StatusMsg>()
    private var ignored = 0

    private val _summary = MutableStateFlow(Summary(false, 0, 0))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    private val driver: Connection? get() = connections.firstOrNull()

    fun connect(c: Connection) {
        connections += c
        refresh()
    }

    fun disconnect(c: Connection) {
        val wasDriver = c == driver
        connections.remove(c)
        lastStatus.remove(c)
        // Don't wait for the arbiter's 300 ms expiry.
        if (wasDriver) sessions.value?.update(Command(0f, 0f, 0f, false, Source.REMOTE))
        refresh()
    }

    fun onText(c: Connection, text: String) {
        if (c !in connections) return
        when (val m = PilotMessages.decode(text)) {
            null -> {
                ignored++
                refresh()
            }
            is DriveMsg -> if (c == driver) {
                sessions.value?.update(Command(m.vx.unit(), m.vy.unit(), m.w.unit(), m.en, Source.REMOTE))
            }
            StopMsg -> sessions.value?.stop()
            is PingMsg -> c.send(PilotMessages.encode(PongMsg(m.ts)))
        }
    }

    /** 10 Hz from the server: status to whoever's changed, then telemetry to everyone. */
    fun tick() {
        refresh()
        val t = PilotMessages.encode(telemetry())
        connections.forEach { it.send(t) }
    }

    fun telemetry(): TelemetryMsg {
        val s = sessions.value?.state?.value
        val t = s?.telemetry
        val frame = video.frames.value
        return TelemetryMsg(
            vm = t?.let { it.telemetry.vmMv / 1000f },
            pwm = t?.telemetry?.pwm,
            failsafe = t?.failsafe,
            fault = t?.let { it.faultA || it.faultB },
            usb = s?.link == LinkState.Connected,
            phase = (s?.phase ?: Phase.DISCONNECTED).name,
            active = s?.activeSource?.name,
            rttMs = s?.rttMs,
            rxFps = t?.telemetry?.rxFrames,
            stops = s?.stops ?: 0,
            tempC = video.tempC.value,
            video = video.level.value.name.lowercase(),
            videoAgeMs = frame?.let { clock() - it.capturedAt },
        )
    }

    fun apiStatus(): ApiStatus {
        val s = sessions.value?.state?.value
        return ApiStatus(
            app = appVersion,
            fw = fw(),
            link = s?.link?.let { it::class.simpleName } ?: "None",
            phase = (s?.phase ?: Phase.DISCONNECTED).name,
            driverConnected = driver != null,
            watchers = (connections.size - 1).coerceAtLeast(0),
            video = video.level.value.name.lowercase(),
            ignored = ignored,
        )
    }

    private fun refresh() {
        connections.forEachIndexed { i, c ->
            val status = StatusMsg(if (i == 0) Role.DRIVER else Role.WATCHER, fw(), appVersion, mode())
            if (lastStatus[c] != status) {
                lastStatus[c] = status
                c.send(PilotMessages.encode(status))
            }
        }
        _summary.value = Summary(driver != null, (connections.size - 1).coerceAtLeast(0), ignored)
    }

    private fun fw(): String? = sessions.value?.state?.value?.helloAck?.let { "${it.fwMajor}.${it.fwMinor}" }

    private fun mode(): String = sessions.value?.mode?.name ?: "AUTO"

    private fun Float.unit(): Float = coerceIn(-1f, 1f)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :server:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `PilotHubTest` 13 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add android-app/server/src/main/kotlin/com/mecanumbot/server/PilotHub.kt \
        android-app/server/src/test/kotlin/com/mecanumbot/server/FakeVideo.kt \
        android-app/server/src/test/kotlin/com/mecanumbot/server/PilotHubTest.kt
git commit -m "Android server: PilotHub with one driver, watchers, STOP from anyone"
```

---

### Task 4: `PilotServer` — Ktor routes

Spec §6. `/`, static files, `/stream`, `/snapshot.jpg`, `/api/status`, `/ws`, and the 10 Hz hub tick. Tested end to end in real time: one thread plays Main and FakeLink plays the ESP32. `/stream` runs against the real CIO engine, because the in-memory test engine buffers endless responses.

**Files:**
- Create: `android-app/server/src/main/kotlin/com/mecanumbot/server/PilotServer.kt`
- Test: `android-app/server/src/test/kotlin/com/mecanumbot/server/PilotServerTest.kt`

**Interfaces:**
- Consumes: `PilotHub` (Task 3), `VideoSource`, `MjpegWriter`, `PilotJson`, `ApiStatus` (Task 2).
- Produces:
  - `class PilotServer(hub: PilotHub, video: VideoSource, assets: (String) -> ByteArray?, hubDispatcher: CoroutineDispatcher, port: Int = PilotServer.PORT)` with `start()`, `stop()`, `PilotServer.PORT = 8080`
  - `fun Application.pilotModule(hub, video, assets, hubDispatcher)` (used by tests and by `PilotServer`)
  - `assets(name)` receives a bare file name such as `index.html` (no `pilot/` prefix, no slashes)

- [ ] **Step 1: Write the failing tests**

`android-app/server/src/test/kotlin/com/mecanumbot/server/PilotServerTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :server:testDebugUnitTest`
Expected: compilation FAILS with `Unresolved reference 'pilotModule'` and `'PilotServer'`.

- [ ] **Step 3: Implement**

`android-app/server/src/main/kotlin/com/mecanumbot/server/PilotServer.kt`:

```kotlin
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.time.Duration.Companion.seconds

/**
 * The pilot's HTTP server (spec §6): Ktor CIO on 0.0.0.0:[port]. [assets] maps a file name under
 * assets/pilot/ to its bytes (null = missing). Every PilotHub call is made on [hubDispatcher].
 */
class PilotServer(
    private val hub: PilotHub,
    private val video: VideoSource,
    private val assets: (String) -> ByteArray?,
    private val hubDispatcher: CoroutineDispatcher,
    private val port: Int = PORT,
) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            pilotModule(hub, video, assets, hubDispatcher)
        }.start(wait = false)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 200, timeoutMillis = 1_000)
        engine = null
    }

    companion object {
        const val PORT = 8080
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
            val out = Channel<String>(64, BufferOverflow.DROP_OLDEST)
            val conn = PilotHub.Connection { out.trySend(it) }
            withContext(hubDispatcher) { hub.connect(conn) }
            val writer = launch { for (text in out) send(Frame.Text(text)) }
            try {
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :server:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `PilotServerTest` 6 tests, 0 failures (25 in the module). These tests use real time and take about 10 s.

- [ ] **Step 5: Commit**

```bash
git add android-app/server/src/main/kotlin/com/mecanumbot/server/PilotServer.kt \
        android-app/server/src/test/kotlin/com/mecanumbot/server/PilotServerTest.kt
git commit -m "Android server: Ktor routes for the pilot, MJPEG stream, snapshot, /ws"
```

---


### Task 5: `:camera` module — ThermalGuard and MjpegCamera

Spec §5, §5.1. `ThermalGuard` is pure and test-driven. `MjpegCamera` is a thin CameraX wrapper that is only compiled here; it runs for the first time in Task 9.

**Files:**
- Modify: `android-app/settings.gradle.kts`
- Create: `android-app/camera/build.gradle.kts`
- Create: `android-app/camera/src/main/kotlin/com/mecanumbot/camera/ThermalGuard.kt`
- Create: `android-app/camera/src/main/kotlin/com/mecanumbot/camera/MjpegCamera.kt`
- Test: `android-app/camera/src/test/kotlin/com/mecanumbot/camera/ThermalGuardTest.kt`

**Interfaces:**
- Consumes: catalog entries `camera-camera2`, `camera-lifecycle` (Task 2).
- Produces:
  - `enum ThermalLevel { NORMAL, REDUCED, OFF }`; `class ThermalGuard { val level; fun update(tempC: Float): ThermalLevel }`
  - `enum Lens { ULTRA_WIDE, MAIN }`; `class CameraFrame(bytes: ByteArray, capturedAt: Long, n: Long)`
  - `class MjpegCamera(context: Context, owner: LifecycleOwner, scope: CoroutineScope, clock: () -> Long)` with `frames: StateFlow<CameraFrame?>`, `setActive(Boolean)`, `setLevel(ThermalLevel)`, `setOptions(lens: Lens, rotationDegrees: Int)`, `release()`. Its setters run on the main thread.

- [ ] **Step 1: Module wiring**

In `settings.gradle.kts`:

```kotlin
include(":core", ":fake", ":usb", ":server", ":camera", ":app")
```

`android-app/camera/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mecanumbot.camera"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.all { it.useJUnitPlatform() } }
}

dependencies {
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
```

- [ ] **Step 2: Write the failing test**

`android-app/camera/src/test/kotlin/com/mecanumbot/camera/ThermalGuardTest.kt`:

```kotlin
package com.mecanumbot.camera

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThermalGuardTest {
    private fun ThermalGuard.feed(vararg temps: Float): List<ThermalLevel> = temps.map { update(it) }

    @Test
    fun `starts normal and stays normal below 40`() {
        assertEquals(listOf(ThermalLevel.NORMAL, ThermalLevel.NORMAL), ThermalGuard().feed(25f, 39.9f))
    }

    @Test
    fun `reduces at 40 and turns off at 45`() {
        assertEquals(listOf(ThermalLevel.REDUCED, ThermalLevel.REDUCED, ThermalLevel.OFF), ThermalGuard().feed(40f, 44.9f, 45f))
    }

    @Test
    fun `one step per reading even when very hot`() {
        assertEquals(listOf(ThermalLevel.REDUCED, ThermalLevel.OFF), ThermalGuard().feed(60f, 60f))
    }

    @Test
    fun `off needs below 43 to come back, reduced needs below 38`() {
        val g = ThermalGuard()
        g.feed(40f, 45f)
        assertEquals(
            listOf(ThermalLevel.OFF, ThermalLevel.REDUCED, ThermalLevel.REDUCED, ThermalLevel.NORMAL),
            g.feed(43f, 42.9f, 38f, 37.9f),
        )
    }

    @Test
    fun `cooling from off steps through reduced`() {
        val g = ThermalGuard()
        g.feed(40f, 45f)
        assertEquals(listOf(ThermalLevel.REDUCED, ThermalLevel.NORMAL), g.feed(20f, 20f))
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :camera:testDebugUnitTest`
Expected: compilation FAILS with `Unresolved reference 'ThermalGuard'`.

- [ ] **Step 4: Implement ThermalGuard**

`android-app/camera/src/main/kotlin/com/mecanumbot/camera/ThermalGuard.kt`:

```kotlin
package com.mecanumbot.camera

enum class ThermalLevel { NORMAL, REDUCED, OFF }

/**
 * Battery temperature → video level (spec §5.1). Up at 40 / 45 °C, down below 43 / 38 °C, one
 * step per reading. Pure; the caller reads the temperature every 5 s. Never affects driving.
 */
class ThermalGuard {
    var level: ThermalLevel = ThermalLevel.NORMAL
        private set

    fun update(tempC: Float): ThermalLevel {
        level = when (level) {
            ThermalLevel.NORMAL -> if (tempC >= REDUCE_AT) ThermalLevel.REDUCED else ThermalLevel.NORMAL
            ThermalLevel.REDUCED -> when {
                tempC >= OFF_AT -> ThermalLevel.OFF
                tempC < NORMAL_BELOW -> ThermalLevel.NORMAL
                else -> ThermalLevel.REDUCED
            }
            ThermalLevel.OFF -> if (tempC < REDUCED_BELOW) ThermalLevel.REDUCED else ThermalLevel.OFF
        }
        return level
    }

    companion object {
        const val REDUCE_AT = 40f
        const val OFF_AT = 45f
        const val REDUCED_BELOW = 43f
        const val NORMAL_BELOW = 38f
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :camera:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `ThermalGuardTest` 5 tests, 0 failures.

- [ ] **Step 6: Implement MjpegCamera**

This deviates slightly from the spec's "YUV → NV21 → YuvImage": it uses RGBA output with `setOutputImageRotationEnabled(true)`, so CameraX rotates the buffer for the mount rotation, and `ImageProxy.toBitmap()` + `Bitmap.compress(JPEG, 60)`. The result (JPEG, quality 60, 640×480 at 15 fps or 320×240 at 8 fps) is the same, and no hand-written NV21 rotation is needed. The ultra-wide lens is selected by setting the back camera's zoom to its minimum ratio: Pixels expose the ultra-wide through the logical camera's zoom below 1×. Where the minimum is 1×, this is the main camera, which is the spec's fallback.

`android-app/camera/src/main/kotlin/com/mecanumbot/camera/MjpegCamera.kt`:

```kotlin
package com.mecanumbot.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class Lens { ULTRA_WIDE, MAIN }

/** One JPEG. [n] increases by one per frame; [capturedAt] uses the clock passed to [MjpegCamera]. */
class CameraFrame(val bytes: ByteArray, val capturedAt: Long, val n: Long)

/**
 * CameraX → JPEG for the MJPEG stream (spec §5). The camera is bound to [owner] (the service) only
 * while [setActive] is true and the level is not OFF, so nobody watching costs nothing. Setters
 * must be called on the main thread; frames are encoded on a private thread.
 */
class MjpegCamera(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _frames = MutableStateFlow<CameraFrame?>(null)
    val frames: StateFlow<CameraFrame?> = _frames.asStateFlow()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var loading = false
    private var analysis: ImageAnalysis? = null

    private var active = false
    private var level = ThermalLevel.NORMAL
    private var lens = Lens.ULTRA_WIDE
    private var rotationDegrees = 0

    @Volatile private var minIntervalMs = 1_000L / NORMAL_FPS
    @Volatile private var lastEmitAt = Long.MIN_VALUE / 2
    @Volatile private var n = 0L

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        rebind()
    }

    fun setLevel(level: ThermalLevel) {
        if (this.level == level) return
        this.level = level
        rebind()
    }

    /** [rotationDegrees] is 0, 90, 180 or 270: how the mount turns the picture. */
    fun setOptions(lens: Lens, rotationDegrees: Int) {
        if (this.lens == lens && this.rotationDegrees == rotationDegrees) return
        this.lens = lens
        this.rotationDegrees = rotationDegrees
        rebind()
    }

    fun release() {
        unbind()
        executor.shutdown()
    }

    private fun rebind() {
        val p = provider
        if (p == null) {
            if (!loading) {
                loading = true
                scope.launch {
                    provider = ProcessCameraProvider.awaitInstance(context)
                    rebind()
                }
            }
            return
        }
        unbind()
        if (!active || level == ThermalLevel.OFF) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

        val reduced = level == ThermalLevel.REDUCED
        val size = if (reduced) Size(320, 240) else Size(640, 480)
        minIntervalMs = 1_000L / if (reduced) REDUCED_FPS else NORMAL_FPS
        val a = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true) // CameraX rotates the buffer, so the JPEG is upright
            .setTargetRotation(surfaceRotation(rotationDegrees))
            .build()
        a.setAnalyzer(executor, ::analyze)
        val camera = try {
            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, a)
        } catch (_: IllegalStateException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        analysis = a
        // Pixels expose the ultra-wide through the back logical camera's zoom below 1×.
        val ratio = if (lens == Lens.ULTRA_WIDE) camera.cameraInfo.zoomState.value?.minZoomRatio ?: 1f else 1f
        camera.cameraControl.setZoomRatio(ratio)
    }

    private fun unbind() {
        analysis?.clearAnalyzer()
        analysis = null
        provider?.unbindAll()
        _frames.value = null
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            val now = clock()
            if (now - lastEmitAt < minIntervalMs) return
            lastEmitAt = now
            val bitmap = it.toBitmap()
            val out = ByteArrayOutputStream(48 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            if (analysis != null) _frames.value = CameraFrame(out.toByteArray(), now, ++n)
        }
    }

    private fun surfaceRotation(degrees: Int): Int = when (degrees) {
        90 -> Surface.ROTATION_90
        180 -> Surface.ROTATION_180
        270 -> Surface.ROTATION_270
        else -> Surface.ROTATION_0
    }

    companion object {
        const val JPEG_QUALITY = 60
        const val NORMAL_FPS = 15
        const val REDUCED_FPS = 8
    }
}
```

- [ ] **Step 7: Compile**

Run: `./gradlew :camera:compileDebugKotlin :camera:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Note that `awaitInstance` is an extension and needs its own import (`androidx.camera.lifecycle.awaitInstance`).

- [ ] **Step 8: Commit**

```bash
git add android-app/settings.gradle.kts android-app/camera/build.gradle.kts \
        android-app/camera/src/main/kotlin/com/mecanumbot/camera/ThermalGuard.kt \
        android-app/camera/src/main/kotlin/com/mecanumbot/camera/MjpegCamera.kt \
        android-app/camera/src/test/kotlin/com/mecanumbot/camera/ThermalGuardTest.kt
git commit -m "Android camera: CameraX to JPEG for MJPEG, thermal guard with hysteresis"
```

---

### Task 6: `pilot-web/` — the operator's page

Spec §4.4, §7. Plain HTML/CSS/JS. Every safety rule lives in a few named functions in `pilot.js`, which is where a reviewer should look:
- `pressDeadman()` works only for the driver.
- `releaseDeadman()` sends one `en:false`. It is called on Shift up, Hold up, blur, hidden tab, socket close, role change, and a rise in `telemetry.stops`.
- The 25 ms send loop sends `drive` only while `state.deadman` is set.
- `stop()` is reachable for watchers too.
- The speed limit is forced back to 30 % on load.

**Files:**
- Create: `pilot-web/index.html`, `pilot-web/pilot.css`, `pilot-web/pilot.js`
- Modify: `pilot-web/README.md` (replace the whole file)

**Interfaces:**
- Consumes: `/ws` JSON (Task 2, spec §4.1), `GET /stream` (Task 4). The page uses relative URLs and `ws://${location.host}/ws`, so it works from whatever address serves it.
- Produces: the three files that Task 7's `syncPilotWeb` copies into `assets/pilot/`; `PilotServer` serves them by bare file name.

- [ ] **Step 1: Write the files**

`pilot-web/index.html`:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>MecanumBot Pilot</title>
  <link rel="stylesheet" href="pilot.css">
</head>
<body>
  <header id="bar">
    <span id="conn" class="pill">offline</span>
    <span id="role" class="pill">—</span>
    <span>RTT <b id="rtt">—</b></span>
    <span>VM <b id="vm">—</b></span>
    <span>Active <b id="active">—</b></span>
    <span>Phone <b id="temp">—</b></span>
    <span>Video <b id="video">—</b></span>
    <span class="help">Shift = drive · WASD move · Q/E turn · Space = STOP</span>
  </header>

  <main id="view">
    <img id="stream" alt="robot camera">
    <div id="banners"></div>
  </main>

  <footer id="controls">
    <div class="stick" id="left-stick" data-axes="xy"><div class="knob"></div><span>move</span></div>
    <div class="middle">
      <label class="limit">Speed limit <b id="limit-value">30 %</b>
        <input id="limit" type="range" min="10" max="100" step="5" value="30" autocomplete="off">
      </label>
      <div class="buttons">
        <button id="hold" class="drive-control">Hold to drive</button>
        <button id="stop">STOP</button>
      </div>
    </div>
    <div class="stick" id="right-stick" data-axes="x"><div class="knob"></div><span>turn</span></div>
  </footer>

  <script src="pilot.js"></script>
</body>
</html>
```

`pilot-web/pilot.css`:

```css
:root {
  --bg: #111418;
  --panel: #1c2128;
  --text: #e6e9ee;
  --muted: #8b95a3;
  --accent: #3b82f6;
  --ok: #22c55e;
  --warn: #f59e0b;
  --danger: #ef4444;
}

* { box-sizing: border-box; }

html, body {
  margin: 0;
  height: 100%;
  background: var(--bg);
  color: var(--text);
  font: 14px/1.3 system-ui, -apple-system, sans-serif;
  overflow: hidden;
  user-select: none;
}

body { display: flex; flex-direction: column; }

#bar {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  align-items: center;
  padding: 6px 12px;
  background: var(--panel);
}
#bar .help { margin-left: auto; color: var(--muted); }
.pill { padding: 2px 8px; border-radius: 10px; background: #333a44; }
.pill.ok { background: var(--ok); color: #000; }
.pill.bad { background: var(--danger); }
.pill.driver { background: var(--accent); }

#view {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #000;
  border: 3px solid transparent;
}
#view.lagging { border-color: var(--danger); }
#stream { max-width: 100%; max-height: 100%; object-fit: contain; }

#banners {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: center;
}
.banner { padding: 6px 14px; border-radius: 6px; background: var(--warn); color: #000; font-weight: 600; }
.banner.danger { background: var(--danger); color: #fff; }

#controls {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 10px 16px;
  background: var(--panel);
}
.middle { display: flex; flex-direction: column; align-items: center; gap: 10px; }
.limit { display: flex; gap: 10px; align-items: center; }
.buttons { display: flex; gap: 12px; }
button {
  font: inherit;
  font-weight: 700;
  border: 0;
  border-radius: 8px;
  padding: 14px 28px;
  cursor: pointer;
  touch-action: none;
}
#hold { background: #2b3440; color: var(--text); }
#hold.on { background: var(--ok); color: #000; }
#stop { background: var(--danger); color: #fff; font-size: 18px; }

.stick {
  position: relative;
  width: 140px;
  height: 140px;
  border-radius: 50%;
  background: #262d36;
  touch-action: none;
}
.stick span { position: absolute; bottom: -2px; width: 100%; text-align: center; color: var(--muted); font-size: 12px; }
.knob {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 52px;
  height: 52px;
  margin: -26px 0 0 -26px;
  border-radius: 50%;
  background: #4b5563;
}

body.watcher .stick, body.watcher .drive-control, body.watcher #limit {
  opacity: 0.35;
  pointer-events: none;
}
```

`pilot-web/pilot.js`:

```javascript
// MecanumBot pilot (stage 5 spec §4.4, §7). No build step, no libraries.
// Safety: drive frames go out only while the deadman (Shift or Hold) is down; anything odd releases it.
'use strict';

const $ = (id) => document.getElementById(id);

const SEND_MS = 25;              // 40 Hz while the deadman is held
const PING_MS = 1000;
const RTT_LAG_MS = 200;          // red border
const VIDEO_LAG_MS = 1000;       // red border
const VIDEO_STALE_MS = 3000;     // reload <img>
const DEFAULT_LIMIT = 30;        // %; reset on every page load

const state = {
  ws: null,
  backoff: 500,
  connected: false,
  role: null,            // 'driver' | 'watcher'
  mode: 'AUTO',
  deadman: false,
  keys: new Set(),
  stick: { vx: 0, vy: 0, w: 0 },
  lastInput: 'keys',     // the last input touched wins
  limit: DEFAULT_LIMIT / 100,
  stops: null,
  rtt: null,
  telemetry: null,
  lastStreamLoad: 0,
};

// ---------- WebSocket ----------

function connect() {
  const ws = new WebSocket(`ws://${location.host}/ws`);
  state.ws = ws;
  ws.onopen = () => {
    state.connected = true;
    state.backoff = 500;
    render();
  };
  ws.onmessage = (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch { return; }
    if (m.t === 'status') onStatus(m);
    else if (m.t === 'telemetry') onTelemetry(m);
    else if (m.t === 'pong') state.rtt = Date.now() - m.ts;
    render();
  };
  ws.onclose = () => {
    if (state.ws !== ws) return;
    state.connected = false;
    state.role = null;
    state.stops = null;
    releaseDeadman();
    render();
    setTimeout(connect, state.backoff);
    state.backoff = Math.min(state.backoff * 2, 5000);
  };
}

function send(msg) {
  if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(msg));
}

function onStatus(m) {
  if (m.role !== state.role) releaseDeadman(); // a new role never inherits a held deadman
  state.role = m.role;
  state.mode = m.mode;
}

function onTelemetry(m) {
  // A STOP from anywhere (another tab, the robot's own screen) needs a new press to drive again.
  if (state.stops !== null && m.stops > state.stops) releaseDeadman();
  state.stops = m.stops;
  state.telemetry = m;
  checkVideo(m);
}

// ---------- Driving ----------

const isDriver = () => state.connected && state.role === 'driver';

function pressDeadman() {
  if (!isDriver()) return;
  state.deadman = true;
  render();
}

function releaseDeadman() {
  const was = state.deadman;
  state.deadman = false;
  if (was && isDriver()) send({ t: 'drive', vx: 0, vy: 0, w: 0, en: false });
  render();
}

function stop() {
  state.deadman = false;
  send({ t: 'stop' });
  render();
}

function axis(plus, minus) {
  return (state.keys.has(plus) ? 1 : 0) - (state.keys.has(minus) ? 1 : 0);
}

function command() {
  const raw = state.lastInput === 'keys'
    ? { vx: axis('KeyD', 'KeyA'), vy: axis('KeyW', 'KeyS'), w: axis('KeyE', 'KeyQ') }
    : state.stick;
  const k = state.limit;
  return { vx: raw.vx * k, vy: raw.vy * k, w: raw.w * k };
}

setInterval(() => {
  if (!state.deadman) return;
  if (!isDriver()) { state.deadman = false; render(); return; }
  const c = command();
  send({ t: 'drive', vx: round(c.vx), vy: round(c.vy), w: round(c.w), en: true });
}, SEND_MS);

setInterval(() => send({ t: 'ping', ts: Date.now() }), PING_MS);

const round = (v) => Math.round(v * 1000) / 1000;

// ---------- Keyboard ----------

const MOVE_KEYS = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'KeyQ', 'KeyE']);

window.addEventListener('keydown', (e) => {
  if (e.code === 'Space') { e.preventDefault(); stop(); return; }
  if (e.key === 'Shift') { if (!e.repeat) pressDeadman(); return; }
  if (MOVE_KEYS.has(e.code)) {
    e.preventDefault();
    state.keys.add(e.code);
    state.lastInput = 'keys';
  }
});

window.addEventListener('keyup', (e) => {
  if (e.key === 'Shift') { releaseDeadman(); return; }
  state.keys.delete(e.code);
});

function dropEverything() {
  state.keys.clear();
  releaseDeadman();
}
window.addEventListener('blur', dropEverything);
document.addEventListener('visibilitychange', () => { if (document.hidden) dropEverything(); });

// ---------- Buttons ----------

const hold = $('hold');
hold.addEventListener('pointerdown', (e) => { hold.setPointerCapture(e.pointerId); pressDeadman(); });
for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) hold.addEventListener(ev, releaseDeadman);

$('stop').addEventListener('click', stop);

const limit = $('limit');
limit.value = DEFAULT_LIMIT; // browsers may restore the old value; the spec wants 30 % on every load
limit.addEventListener('input', () => { state.limit = Number(limit.value) / 100; render(); });

// ---------- Sticks (mouse and touch) ----------

function setupStick(el) {
  const knob = el.querySelector('.knob');
  const both = el.dataset.axes === 'xy';
  const move = (e) => {
    const r = el.getBoundingClientRect();
    const radius = r.width / 2;
    let x = (e.clientX - r.left - radius) / radius;
    let y = (e.clientY - r.top - radius) / radius;
    const len = Math.hypot(x, y);
    if (len > 1) { x /= len; y /= len; }
    if (!both) y = 0;
    knob.style.transform = `translate(${x * radius * 0.7}px, ${y * radius * 0.7}px)`;
    if (both) { state.stick.vx = x; state.stick.vy = -y; } else { state.stick.w = x; }
    state.lastInput = 'sticks';
  };
  const end = () => {
    knob.style.transform = '';
    if (both) { state.stick.vx = 0; state.stick.vy = 0; } else { state.stick.w = 0; }
  };
  el.addEventListener('pointerdown', (e) => { el.setPointerCapture(e.pointerId); move(e); });
  el.addEventListener('pointermove', (e) => { if (el.hasPointerCapture(e.pointerId)) move(e); });
  for (const ev of ['pointerup', 'pointercancel', 'lostpointercapture']) el.addEventListener(ev, end);
}
setupStick($('left-stick'));
setupStick($('right-stick'));

// ---------- Video ----------

const stream = $('stream');

function loadStream() {
  state.lastStreamLoad = Date.now();
  stream.src = `/stream?ts=${state.lastStreamLoad}`;
}

stream.addEventListener('error', () => setTimeout(loadStream, 1000));

function checkVideo(m) {
  const stale = m.video !== 'off' && (m.video_age_ms === null || m.video_age_ms > VIDEO_STALE_MS);
  if (stale && Date.now() - state.lastStreamLoad > 5000) loadStream();
}

// ---------- Rendering ----------

function text(id, value) { $(id).textContent = value; }

function render() {
  const t = state.telemetry;
  document.body.classList.toggle('watcher', state.role === 'watcher');

  const conn = $('conn');
  conn.textContent = state.connected ? 'online' : 'offline';
  conn.className = 'pill ' + (state.connected ? 'ok' : 'bad');
  const role = $('role');
  role.textContent = state.role ?? '—';
  role.className = 'pill' + (state.role === 'driver' ? ' driver' : '');

  text('rtt', state.rtt === null ? '—' : `${state.rtt} ms`);
  text('vm', t && t.vm !== null ? `${t.vm.toFixed(2)} V` : '—');
  text('active', t && t.active ? t.active : '—');
  text('temp', t && t.temp_c !== null ? `${t.temp_c.toFixed(1)} °C` : '—');
  text('video', t ? t.video : '—');
  text('limit-value', `${Math.round(state.limit * 100)} %`);
  hold.classList.toggle('on', state.deadman);

  const lagging = (state.rtt !== null && state.rtt > RTT_LAG_MS) ||
    (t && t.video !== 'off' && t.video_age_ms !== null && t.video_age_ms > VIDEO_LAG_MS);
  $('view').classList.toggle('lagging', Boolean(lagging));

  renderBanners(t);
}

function renderBanners(t) {
  const list = [];
  if (!state.connected) list.push(['Disconnected — reconnecting…', true]);
  else {
    if (state.role === 'watcher') list.push(['Watching — another pilot is driving', false]);
    if (state.mode === 'LOCAL_ONLY') list.push(['Remote control is off on the robot (LOCAL_ONLY)', true]);
    if (t) {
      if (t.phase !== 'READY') list.push([`Robot not ready: ${t.phase}`, true]);
      if (t.failsafe) list.push(['FAILSAFE — motors stopped', true]);
      if (t.fault) list.push(['Motor driver FAULT', true]);
      const temp = t.temp_c === null ? '' : ` — phone at ${t.temp_c.toFixed(1)} °C`;
      if (t.video === 'reduced') list.push([`Video reduced${temp}`, false]);
      if (t.video === 'off') list.push([`Video off${temp}`, true]);
    }
  }
  const box = $('banners');
  box.replaceChildren(...list.map(([msg, danger]) => {
    const div = document.createElement('div');
    div.className = 'banner' + (danger ? ' danger' : '');
    div.textContent = msg;
    return div;
  }));
}

loadStream();
connect();
render();
```

`pilot-web/README.md`:

```markdown
# pilot-web

Operator's remote: `index.html` + `pilot.css` + `pilot.js`, no build step, no libraries (stage 5 spec §7).

Delivery: the `syncPilotWeb` Gradle task in `android-app/app` copies this folder (without this README) into the APK's
assets under `pilot/`. There is no checked-in copy; edit the files here and rebuild the app.

Open `http://<phone-ip>:8080/` from a laptop on the same Wi-Fi (the address is in the app's status bar and notification).
The first tab to connect drives; other tabs watch, and anyone can STOP.

| Input | Action |
|---|---|
| Shift (hold) or "Hold to drive" | deadman: drive frames are sent only while it is held |
| W / S | forward / back |
| A / D | left / right (strafe) |
| Q / E | turn counter-clockwise / clockwise |
| Space or STOP | STOP ×3 on the robot |
| Left / right stick (mouse or touch) | move / turn |
| Speed limit | scales every axis; 30 % on every page load |

The deadman is released on Shift up, window blur, hidden tab, socket loss, a role change, and any STOP (from any tab
or the robot's own screen). After that, driving needs a new press.
```

- [ ] **Step 2: Syntax check**

Run: `node --check pilot-web/pilot.js`
Expected: no output, exit 0. If `node` is not installed, skip this step: Task 9 loads the page in a browser anyway.

- [ ] **Step 3: Commit**

```bash
git add pilot-web/index.html pilot-web/pilot.css pilot-web/pilot.js pilot-web/README.md
git commit -m "Pilot web: video, sticks, keyboard, deadman, STOP, telemetry banners"
```

---

### Task 7: `:app` — RobotService, wiring, local release on pause

Spec §3, §4.3, §6, §8. After this task the app serves the pilot and drives from the browser. The status bar and settings follow in Task 8.

**Files:**
- Modify: `android-app/app/build.gradle.kts` (whole file below)
- Modify: `android-app/app/src/main/AndroidManifest.xml` (whole file below)
- Modify: `android-app/app/src/main/kotlin/com/mecanumbot/app/AppGraph.kt` (whole file below)
- Modify: `android-app/app/src/main/kotlin/com/mecanumbot/app/MainActivity.kt` (whole file below)
- Modify: `android-app/app/src/main/kotlin/com/mecanumbot/app/LinkPreference.kt` (one line)
- Modify: `android-app/app/src/main/kotlin/com/mecanumbot/app/ui/TestScreen.kt` (three `onDispose`)
- Create: `android-app/app/src/main/kotlin/com/mecanumbot/app/CameraPreference.kt`, `PilotInfo.kt`, `WifiAddress.kt`, `CameraVideo.kt`, `RobotService.kt`

**Interfaces:**
- Consumes: `RobotSession.releaseLocal()` (Task 1); `PilotHub`, `PilotServer`, `VideoSource`, `JpegFrame`, `VideoLevel` (Tasks 2–4); `MjpegCamera`, `ThermalGuard`, `ThermalLevel`, `Lens` (Task 5); `pilot-web/` (Task 6).
- Produces (used by Task 8):
  - `AppGraph.sessions: StateFlow<RobotSession?>`, `AppGraph.cameraPreference: CameraPreference`, `AppGraph.pilot: MutableStateFlow<PilotInfo>`
  - `data class PilotInfo(running, url, error, driverConnected, watchers, video: VideoLevel?, tempC: Float?)`
  - `data class CameraOptions(lens: Lens = ULTRA_WIDE, rotation: Int = 0)`; `CameraPreference.options: Flow<CameraOptions>`, `save(options)`, `CameraPreference.ROTATIONS`
  - `internal val Context.settings` (the app's single DataStore; LinkPreference and CameraPreference share it)

- [ ] **Step 1: Stop the Test screen from cutting off a remote pilot**

In `ui/TestScreen.kt`, all three `DisposableEffect(session) { onDispose { session.stop() } }` become `session.releaseLocal()`. In `TestScreen`:

```kotlin
    // Leaving the Test screen stops TEST/RAW driving but not a remote pilot (stage 5 spec §4.3).
    DisposableEffect(session) { onDispose { session.releaseLocal() } }
```

In `DriveTab`:

```kotlin
    DisposableEffect(session) { onDispose { session.releaseLocal() } }
```

In `RawTab`:

```kotlin
    DisposableEffect(session) { onDispose { session.releaseLocal() } } // leaving the Raw tab
```

Also change the comment above `if (raw) RawTab(…)` to `// Switching Drive <-> Raw disposes the other tab, and each tab releases its source on dispose.`

Behaviour to keep in mind: while the **Raw** tab is open and enabled it sends zero MOTOR_RAW at 40 Hz (stage 3 design), and RAW overrides every DRIVE source. So a remote pilot cannot drive while the phone shows the Raw tab, and pausing the app there is a STOP ×3. The Drive tab sends `enable=false` when not held, so a remote pilot drives normally while it is open.

- [ ] **Step 2: Share the DataStore, add the small app classes**

In `LinkPreference.kt` replace `private val Context.settings by preferencesDataStore(name = "settings")` with:

```kotlin
/** The app's one DataStore; a second delegate for the same file would crash at runtime. */
internal val Context.settings by preferencesDataStore(name = "settings")
```

`android-app/app/src/main/kotlin/com/mecanumbot/app/CameraPreference.kt`:

```kotlin
package com.mecanumbot.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mecanumbot.camera.Lens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class CameraOptions(val lens: Lens = Lens.ULTRA_WIDE, val rotation: Int = 0)

/** Camera lens and mount rotation (spec §5). Defaults: ultra-wide, 0°. */
class CameraPreference(context: Context) {
    private val store = context.applicationContext.settings
    private val lensKey = stringPreferencesKey("camera_lens")
    private val rotationKey = intPreferencesKey("camera_rotation")

    val options: Flow<CameraOptions> = store.data.map { p ->
        CameraOptions(
            lens = p[lensKey]?.let { runCatching { Lens.valueOf(it) }.getOrNull() } ?: Lens.ULTRA_WIDE,
            rotation = p[rotationKey]?.takeIf { it in ROTATIONS } ?: 0,
        )
    }.distinctUntilChanged()

    suspend fun save(options: CameraOptions) {
        store.edit {
            it[lensKey] = options.lens.name
            it[rotationKey] = options.rotation
        }
    }

    companion object {
        val ROTATIONS = listOf(0, 90, 180, 270)
    }
}
```

`android-app/app/src/main/kotlin/com/mecanumbot/app/PilotInfo.kt`:

```kotlin
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
```

`android-app/app/src/main/kotlin/com/mecanumbot/app/WifiAddress.kt`:

```kotlin
package com.mecanumbot.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/** The phone's IPv4 address on Wi-Fi, or null without Wi-Fi (spec §6). */
class WifiAddress(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _address = MutableStateFlow<String?>(null)
    val address: StateFlow<String?> = _address.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            _address.value = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }?.hostAddress
        }

        override fun onLost(network: Network) {
            _address.value = null
        }
    }

    fun start() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(request, callback)
    }

    fun stop() {
        cm.unregisterNetworkCallback(callback)
    }
}
```

`android-app/app/src/main/kotlin/com/mecanumbot/app/CameraVideo.kt`:

```kotlin
package com.mecanumbot.app

import com.mecanumbot.camera.MjpegCamera
import com.mecanumbot.camera.ThermalGuard
import com.mecanumbot.camera.ThermalLevel
import com.mecanumbot.server.JpegFrame
import com.mecanumbot.server.VideoLevel
import com.mecanumbot.server.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adapts [MjpegCamera] + [ThermalGuard] to the server's [VideoSource] (spec §2). [camera] is null
 * when the service could not start as a camera service: video then stays OFF. [mainScope] runs on
 * Main; viewer changes from Ktor threads are posted there.
 */
class CameraVideo(private val camera: MjpegCamera?, private val mainScope: CoroutineScope) : VideoSource {
    private val guard = ThermalGuard()
    private val viewers = AtomicInteger()

    override val frames: StateFlow<JpegFrame?> =
        (camera?.frames?.map { f -> f?.let { JpegFrame(it.bytes, it.capturedAt, it.n) } } ?: flowOf(null))
            .stateIn(mainScope, SharingStarted.Eagerly, null)

    private val _level = MutableStateFlow(if (camera == null) VideoLevel.OFF else VideoLevel.NORMAL)
    override val level: StateFlow<VideoLevel> = _level.asStateFlow()

    private val _tempC = MutableStateFlow<Float?>(null)
    override val tempC: StateFlow<Float?> = _tempC.asStateFlow()

    override fun addViewer() {
        viewers.incrementAndGet()
        sync()
    }

    override fun removeViewer() {
        viewers.decrementAndGet()
        sync()
    }

    /** Main thread, every 5 s from RobotService. */
    fun onTemperature(celsius: Float) {
        _tempC.value = celsius
        val cam = camera ?: return
        val t = guard.update(celsius)
        _level.value = when (t) {
            ThermalLevel.NORMAL -> VideoLevel.NORMAL
            ThermalLevel.REDUCED -> VideoLevel.REDUCED
            ThermalLevel.OFF -> VideoLevel.OFF
        }
        cam.setLevel(t)
    }

    // Reads the count when it runs on Main, so racing add/remove calls settle on the right state.
    private fun sync() {
        val cam = camera ?: return
        mainScope.launch { cam.setActive(viewers.get() > 0) }
    }
}
```

- [ ] **Step 3: AppGraph — sessions for the hub, pilot state, release on pause**

`android-app/app/src/main/kotlin/com/mecanumbot/app/AppGraph.kt`:

```kotlin
package com.mecanumbot.app

import android.content.Context
import android.os.SystemClock
import com.mecanumbot.core.link.Link
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.fake.FakeLink
import com.mecanumbot.usb.UsbLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hand-written wiring (spec §2). Application-scoped: the session lives as long as the process.
 * Everything runs on Main; links do their own I/O off it.
 */
class AppGraph(context: Context) {
    data class Active(val kind: LinkKind, val session: RobotSession)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock: () -> Long = { SystemClock.elapsedRealtime() }
    val usbLink = UsbLink(context, scope, clock)
    val fakeLink = FakeLink(scope, clock)

    private val preference = LinkPreference(context)
    private val switching = Mutex()

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    /** The current session, for the pilot server (it follows USB ↔ Fake switches). */
    val sessions: StateFlow<RobotSession?> = _active.map { it?.session }.stateIn(scope, SharingStarted.Eagerly, null)

    val cameraPreference = CameraPreference(context)

    /** Written by RobotService; shown on the status bar. */
    val pilot = MutableStateFlow(PilotInfo())

    private val _inForeground = MutableStateFlow(false)
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    init {
        scope.launch { switching.withLock { activate(preference.load()) } }
    }

    fun select(kind: LinkKind) {
        scope.launch {
            switching.withLock {
                if (_active.value?.kind == kind) return@withLock
                preference.save(kind)
                activate(kind)
            }
        }
    }

    /**
     * Activity onResume / onPause. Leaving the foreground stops TEST/RAW driving (STOP ×3) but lets
     * a remote pilot keep driving with the screen off (stage 5 spec §4.3).
     */
    fun onForeground(visible: Boolean) {
        _inForeground.value = visible
        if (!visible) _active.value?.session?.releaseLocal()
    }

    private suspend fun activate(kind: LinkKind) {
        _active.value?.let { old ->
            old.session.stop()          // STOP ×3 is queued …
            linkOf(old.kind).close()    // … and close() writes it before closing
            old.session.close()
        }
        val link = linkOf(kind)
        val session = RobotSession(link, scope, clock, APP_MAJOR, APP_MINOR).also { it.start() }
        _active.value = Active(kind, session)
        link.open()
    }

    private fun linkOf(kind: LinkKind): Link = when (kind) {
        LinkKind.USB -> usbLink
        LinkKind.FAKE -> fakeLink
    }

    companion object {
        const val APP_MAJOR = 0
        const val APP_MINOR = 1
    }
}
```

- [ ] **Step 4: RobotService**

Points a reviewer should check against the spec:
- `startForegroundSafely` falls back to `connectedDevice` when the camera type is refused (Review Focus 3).
- The camera type is decided on the first start only.
- The Stop action sends `session.stop()` before `stopSelf()`.
- `PilotServer.start()` runs on `Dispatchers.IO`, and a bind failure goes to `PilotInfo.error`, not a crash.
- `stop()` runs on a plain thread because it blocks for up to 1 s.

`android-app/app/src/main/kotlin/com/mecanumbot/app/RobotService.kt`:

```kotlin
package com.mecanumbot.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.mecanumbot.camera.MjpegCamera
import com.mecanumbot.server.PilotHub
import com.mecanumbot.server.PilotServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Foreground Service for the pilot (spec §3): owns the server, the camera and the wake/Wi-Fi
 * locks, so remote driving and video continue with the screen off. The link and the session stay
 * in AppGraph. Stopped only by the notification's Stop action.
 */
class RobotService : LifecycleService() {
    private val graph: AppGraph get() = (application as MecanumApp).graph

    private var started = false
    private var cameraAllowed = false
    private var server: PilotServer? = null
    private var camera: MjpegCamera? = null
    private lateinit var wifi: WifiAddress
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pilot server", NotificationManager.IMPORTANCE_LOW),
        )
        wifi = WifiAddress(this).also { it.start() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MecanumBot:pilot")
            .apply { setReferenceCounted(false) }
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MecanumBot:pilot")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            graph.active.value?.session?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService() must be answered with startForeground(). The camera type
        // is decided once, on the first start: later starts keep whatever the service runs as.
        val camera = startForegroundSafely(notification(graph.pilot.value.url), wantCamera = if (started) cameraAllowed else hasCamera())
        if (!started) {
            started = true
            cameraAllowed = camera
            startPilot()
        }
        return START_NOT_STICKY
    }

    private fun startPilot() {
        wakeLock.acquire()
        wifiLock.acquire()
        val cam = if (cameraAllowed) MjpegCamera(this, this, lifecycleScope, graph.clock) else null
        camera = cam
        val video = CameraVideo(cam, lifecycleScope)
        val hub = PilotHub(graph.sessions, video, graph.clock, "${AppGraph.APP_MAJOR}.${AppGraph.APP_MINOR}")
        val s = PilotServer(hub, video, ::asset, Dispatchers.Main.immediate)
        server = s
        lifecycleScope.launch(Dispatchers.IO) {
            val error = try {
                s.start()
                null
            } catch (e: IOException) { // port 8080 busy, no network …
                "server: ${e.message}"
            }
            graph.pilot.value = graph.pilot.value.copy(error = error)
        }
        if (cam != null) {
            lifecycleScope.launch { graph.cameraPreference.options.collect { cam.setOptions(it.lens, it.rotation) } }
        }
        lifecycleScope.launch {
            while (true) {
                batteryTempC()?.let(video::onTemperature)
                delay(TEMP_PERIOD_MS)
            }
        }
        lifecycleScope.launch {
            combine(wifi.address, hub.summary, video.level, video.tempC) { ip, summary, level, temp ->
                graph.pilot.value.copy(
                    running = true,
                    url = ip?.let { "http://$it:${PilotServer.PORT}" },
                    driverConnected = summary.driverConnected,
                    watchers = summary.watchers,
                    video = level,
                    tempC = temp,
                )
            }.collect { info ->
                val urlChanged = info.url != graph.pilot.value.url
                graph.pilot.value = info
                if (urlChanged) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(info.url))
            }
        }
    }

    override fun onDestroy() {
        server?.let { s -> Thread { s.stop() }.start() } // stop() blocks up to 1 s
        server = null
        camera?.release()
        camera = null
        wifi.stop()
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        graph.pilot.value = PilotInfo()
        super.onDestroy()
    }

    /**
     * Camera type needs the CAMERA permission and a visible app; without either, fall back to
     * connectedDevice so driving still works and video stays off (spec §3). Returns true when the
     * service runs with the camera type.
     */
    private fun startForegroundSafely(n: Notification, wantCamera: Boolean): Boolean {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (wantCamera) {
            try {
                startForeground(NOTIFICATION_ID, n, device or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                return true
            } catch (_: SecurityException) { // CAMERA revoked
            } catch (_: IllegalStateException) { // ForegroundServiceStartNotAllowedException: app not visible
            }
        }
        startForeground(NOTIFICATION_ID, n, device)
        return false
    }

    private fun hasCamera(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun notification(url: String?): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RobotService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MecanumBot")
            .setContentText(url?.let { "Pilot at $it" } ?: "Pilot server — no Wi-Fi")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun asset(name: String): ByteArray? = try {
        assets.open("pilot/$name").use { it.readBytes() }
    } catch (_: IOException) {
        null
    }

    private fun batteryTempC(): Float? {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    companion object {
        const val ACTION_STOP = "com.mecanumbot.action.STOP_PILOT"
        private const val CHANNEL = "pilot"
        private const val NOTIFICATION_ID = 1
        private const val TEMP_PERIOD_MS = 5_000L
    }
}
```

- [ ] **Step 5: MainActivity — permissions and service start**

`android-app/app/src/main/kotlin/com/mecanumbot/app/MainActivity.kt`:

```kotlin
package com.mecanumbot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.mecanumbot.app.ui.Root

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as MecanumApp).graph
    private var askingPermissions = false

    // Whatever the answer, start the service: without CAMERA it runs without video (spec §3).
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askingPermissions = false
        startRobotService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) { Root(graph) }
        }
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && savedInstanceState == null) {
            askingPermissions = true
            permissions.launch(missing.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        graph.onForeground(true)
        // A camera service may only be started while the app is visible; starting again is harmless.
        if (!askingPermissions) startRobotService()
    }

    override fun onPause() {
        graph.onForeground(false) // a call or notification stops local driving; a remote pilot continues
        super.onPause()
    }

    private fun startRobotService() {
        startForegroundService(Intent(this, RobotService::class.java))
    }
}
```

- [ ] **Step 6: Manifest**

`CHANGE_WIFI_STATE` is not in the spec's list. It is a normal (auto-granted) permission, and Android 14+ requires one permission from a fixed list (this is the simplest) before a `connectedDevice` foreground service may start.

`android-app/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />
    <uses-feature android:name="android.hardware.camera.any" android:required="false" />

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <!-- Normal permission; also satisfies the connectedDevice service-type prerequisite (Android 14+). -->
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".MecanumApp"
        android:label="MecanumBot"
        android:theme="@style/Theme.MecanumBot">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="sensorLandscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- Plugging the ESP32 in opens the app and grants USB permission. -->
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>
            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>

        <service
            android:name=".RobotService"
            android:exported="false"
            android:foregroundServiceType="camera|connectedDevice" />
    </application>
</manifest>
```

- [ ] **Step 7: Gradle — dependencies and the pilot-web sync**

`android-app/app/build.gradle.kts`:

```kotlin
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mecanumbot.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mecanumbot"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

/** Copies pilot-web/ (spec §7) into generated assets under pilot/; no copy is checked in. */
abstract class SyncPilotWeb : DefaultTask() {
    @get:InputDirectory
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun sync() {
        fs.sync {
            from(source) { exclude("README.md") }
            into(output.dir("pilot"))
        }
    }
}

val syncPilotWeb = tasks.register<SyncPilotWeb>("syncPilotWeb") {
    source.set(rootProject.layout.projectDirectory.dir("../pilot-web"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncPilotWeb, SyncPilotWeb::output)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":fake"))
    implementation(project(":usb"))
    implementation(project(":server"))
    implementation(project(":camera"))
    implementation(libs.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
}
```

- [ ] **Step 8: Build and check the APK carries the pilot**

Run: `./gradlew :app:assembleDebug && unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/pilot`
Expected: BUILD SUCCESSFUL, and exactly three entries: `assets/pilot/index.html`, `assets/pilot/pilot.css`, `assets/pilot/pilot.js` (no README).

Run: `./gradlew :core:test :fake:test :server:testDebugUnitTest :camera:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (core 173, fake 29, server 25, camera 5 tests; 0 failures).

- [ ] **Step 9: Commit**

```bash
git add android-app/app/build.gradle.kts android-app/app/src/main/AndroidManifest.xml \
        android-app/app/src/main/kotlin/com/mecanumbot/app/AppGraph.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/MainActivity.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/LinkPreference.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/CameraPreference.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/PilotInfo.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/WifiAddress.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/CameraVideo.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/RobotService.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/ui/TestScreen.kt
git commit -m "Android app: RobotService serves the pilot with video; pause releases only local driving"
```

---

### Task 8: `:app` UI — pilot status and camera settings

Spec §8. The status bar gets a pilot line (URL, driver, watchers, video level, temperature) and Settings gets lens and rotation.

**Files:**
- Modify: `android-app/app/src/main/kotlin/com/mecanumbot/app/ui/StatusBar.kt` (whole file below)
- Modify: `android-app/app/src/main/kotlin/com/mecanumbot/app/ui/Root.kt` (two lines)
- Modify: `android-app/app/src/main/kotlin/com/mecanumbot/app/ui/SettingsScreen.kt` (whole file below)

**Interfaces:**
- Consumes: `AppGraph.pilot`, `PilotInfo`, `AppGraph.cameraPreference`, `CameraOptions`, `CameraPreference.ROTATIONS` (Task 7); `Lens` (Task 5); `VideoLevel` (Task 2).
- Produces: `StatusBar(kind, state, pilot: PilotInfo, clock, onRequestPermission)`.

- [ ] **Step 1: StatusBar**

`android-app/app/src/main/kotlin/com/mecanumbot/app/ui/StatusBar.kt`:

```kotlin
package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecanumbot.app.LinkKind
import com.mecanumbot.app.PilotInfo
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.protocol.Protocol
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.SessionEvent
import com.mecanumbot.core.session.SessionState
import com.mecanumbot.server.VideoLevel
import com.mecanumbot.usb.UsbLink
import kotlinx.coroutines.delay

private val Amber = Color(0xFFFFB300)
private val Danger = Color(0xFFD32F2F)

@Composable
fun StatusBar(
    kind: LinkKind,
    state: SessionState,
    pilot: PilotInfo,
    clock: () -> Long,
    onRequestPermission: () -> Unit,
) {
    val now by produceState(clock()) {
        while (true) {
            value = clock()
            delay(200)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (kind == LinkKind.FAKE) Badge(kind.name, Amber, Color.Black) else Badge(kind.name, MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(phaseText(state), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(detailsText(state, now), style = MaterialTheme.typography.bodySmall)
                state.telemetry?.let { t ->
                    val vm = t.telemetry.vmMv
                    Text(
                        "VM %.2f V".format(vm / 1000f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (vm < VM_LOW_MV) Danger else Color.Unspecified,
                    )
                }
            }
            state.lastEvent?.let {
                Text(it.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Text(
                pilotText(pilot),
                style = MaterialTheme.typography.bodySmall,
                color = if (pilot.error != null || pilot.video == VideoLevel.OFF && pilot.running) Danger else Color.Unspecified,
            )
        }
        state.telemetry?.let { t ->
            if (t.failsafe) Badge("FAILSAFE", Danger)
            if (t.faultA) Badge("FAULT A", Danger)
            if (t.faultB) Badge("FAULT B", Danger)
            if (t.rawMode) Badge("RAW", MaterialTheme.colorScheme.secondary)
        }
        if ((state.link as? LinkState.Error)?.message == UsbLink.NO_PERMISSION) {
            OutlinedButton(onClick = onRequestPermission) { Text("Request again") }
        }
    }
}

/** Vertical STOP pill; letters are stacked so it reads in a narrow rail. Always enabled. */
@Composable
fun StopButton(onStop: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onStop,
        shape = RoundedCornerShape(percent = 50),
        colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier,
    ) {
        Text("S\nT\nO\nP", fontSize = 22.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Badge(text: String, color: Color, contentColor: Color = contentColorFor(color)) {
    Box(Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, color = contentColor, style = MaterialTheme.typography.labelMedium)
    }
}

private fun phaseText(s: SessionState): String {
    (s.link as? LinkState.Error)?.let { return it.message }
    return when (s.phase) {
        Phase.DISCONNECTED -> if (s.link == LinkState.Connecting) "Connecting…" else "Disconnected — connect ESP32"
        Phase.HANDSHAKING -> "Handshaking…"
        Phase.VERSION_MISMATCH -> "Version mismatch — update firmware/app"
        Phase.READY -> "Ready"
    }
}

private fun detailsText(s: SessionState, now: Long): String = buildString {
    s.helloAck?.let { append("fw ${it.fwMajor}.${it.fwMinor}   ") }
    s.rttMs?.let { append("RTT $it ms   ") }
    append("tx ${s.motionSentPerSec}/s")
    s.telemetry?.let { append("   rx ${it.telemetry.rxFrames}/s   age ${now - it.receivedAt} ms") }
}

private fun pilotText(p: PilotInfo): String {
    if (!p.running) return "Pilot server off"
    p.error?.let { return "Pilot $it" }
    return buildString {
        append("Pilot ${p.url ?: "— no Wi-Fi"}")
        append(if (p.driverConnected) "   driver connected" else "   no driver")
        if (p.watchers > 0) append("   ${p.watchers} watching")
        p.video?.let { append("   video ${it.name.lowercase()}") }
        p.tempC?.let { append("   %.1f °C".format(it)) }
    }
}

fun SessionEvent.message(): String = when (this) {
    SessionEvent.NotResponding -> "ESP32 not responding"
    is SessionEvent.VersionMismatch -> "ESP32 protocol $espProtoVer, app ${Protocol.PROTO_VER}: update firmware/app"
    is SessionEvent.Rebooted -> "ESP32 rebooted: reason $resetReason (boot #$resetCount)"
    is SessionEvent.EspLog -> "ESP32: $text"
}

private const val VM_LOW_MV = 3200
```

- [ ] **Step 2: Root**

In `ui/Root.kt`, after `val state by current.session.state.collectAsStateWithLifecycle()` add:

```kotlin
        val pilot by graph.pilot.collectAsStateWithLifecycle()
```

and pass it to `StatusBar`:

```kotlin
                    StatusBar(
                        kind = current.kind,
                        state = state,
                        pilot = pilot,
                        clock = graph.clock,
                        onRequestPermission = { graph.usbLink.requestPermission() },
                    )
```

- [ ] **Step 3: SettingsScreen**

`android-app/app/src/main/kotlin/com/mecanumbot/app/ui/SettingsScreen.kt`:

```kotlin
package com.mecanumbot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mecanumbot.app.AppGraph
import com.mecanumbot.app.CameraOptions
import com.mecanumbot.app.CameraPreference
import com.mecanumbot.app.LinkKind
import com.mecanumbot.camera.Lens
import com.mecanumbot.fake.FaultControls
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(graph: AppGraph, kind: LinkKind) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Link", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkKind.entries.forEach { k ->
                FilterChip(selected = k == kind, onClick = { graph.select(k) }, label = { Text(k.name) })
            }
        }
        CameraPanel(graph.cameraPreference)
        if (kind == LinkKind.FAKE) FaultPanel(graph.fakeLink.faults)
    }
}

@Composable
private fun CameraPanel(preference: CameraPreference) {
    val options by preference.options.collectAsState(initial = CameraOptions())
    val scope = rememberCoroutineScope()
    fun save(o: CameraOptions) {
        scope.launch { preference.save(o) }
    }

    Text("Camera (pilot video)", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(options.lens == Lens.ULTRA_WIDE, onClick = { save(options.copy(lens = Lens.ULTRA_WIDE)) }, label = { Text("Ultra-wide") })
        FilterChip(options.lens == Lens.MAIN, onClick = { save(options.copy(lens = Lens.MAIN)) }, label = { Text("Main") })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CameraPreference.ROTATIONS.forEach { r ->
            FilterChip(options.rotation == r, onClick = { save(options.copy(rotation = r)) }, label = { Text("$r°") })
        }
    }
}

@Composable
private fun FaultPanel(f: FaultControls) {
    var wrongProto by remember { mutableStateOf(f.wrongProtoVer) }
    var faultA by remember { mutableStateOf(f.faultA) }
    var faultB by remember { mutableStateOf(f.faultB) }
    var sag by remember { mutableFloatStateOf(f.vmSagMv.toFloat()) }
    var drop by remember { mutableFloatStateOf(f.dropPercent.toFloat()) }

    Text("Fake ESP32 faults", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = f::disconnect) { Text("Disconnect") }
        OutlinedButton(onClick = f::reconnect) { Text("Reconnect") }
        OutlinedButton(onClick = f::reboot) { Text("Reboot") }
        OutlinedButton(onClick = f::injectGarbage) { Text("Inject garbage") }
    }
    SwitchRow("Wrong proto_ver", wrongProto) { wrongProto = it; f.wrongProtoVer = it }
    SwitchRow("nFAULT A", faultA) { faultA = it; f.faultA = it }
    SwitchRow("nFAULT B", faultB) { faultB = it; f.faultB = it }
    Text("VM sag ${sag.roundToInt()} mV")
    Slider(value = sag, onValueChange = { sag = it; f.vmSagMv = it.roundToInt() }, valueRange = 0f..1500f)
    Text("Drop incoming ${drop.roundToInt()} %")
    Slider(value = drop, onValueChange = { drop = it; f.dropPercent = it.roundToInt() }, valueRange = 0f..100f)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src/main/kotlin/com/mecanumbot/app/ui/StatusBar.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/ui/Root.kt \
        android-app/app/src/main/kotlin/com/mecanumbot/app/ui/SettingsScreen.kt
git commit -m "Android app: pilot line on the status bar, camera lens and rotation in Settings"
```

---


### Task 9: Docs and end-to-end verification

Spec §1 "Done when", §9, §11 (manual). This is the first time the camera, the service and the page run together.

**Files:**
- Modify: `CLAUDE.md` (lines 7–8)
- Modify: `android-app/README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: the stage 5 "Done when" evidence.

- [ ] **Step 1: CLAUDE.md**

Replace line 7 and line 8 with:

```markdown
- android-app/ — Kotlin/Compose, модули core, fake, usb, server, camera, app. Тесты: `cd android-app && ./gradlew :core:test :fake:test :server:testDebugUnitTest :camera:testDebugUnitTest` (JAVA_HOME = JBR из Android Studio, см. android-app/README.md).
- pilot-web/ — статика пульта; Gradle-задача `syncPilotWeb` копирует её в assets APK (`pilot/`), копию в репозитории не держать.
```

- [ ] **Step 2: android-app/README.md**

Replace the first paragraph and the test command so the file reads:

````markdown
# android-app

Robot app (Kotlin, Compose). Stage 3: `docs/stage3-android-spec.md`; stage 5 (pilot): `docs/stage5-pilot-spec.md`.

Modules: `:core` (protocol, arbiter, session; pure Kotlin), `:fake` (FakeLink, simulated ESP32; pure Kotlin),
`:usb` (UsbLink), `:server` (Ktor pilot server), `:camera` (CameraX → MJPEG), `:app` (UI, RobotService).
Gradle runs on Android Studio's JDK:

    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    ./gradlew :core:test :fake:test :server:testDebugUnitTest :camera:testDebugUnitTest
    ./gradlew :app:assembleDebug         # APK in app/build/outputs/apk/debug/
    adb install -r app/build/outputs/apk/debug/app-debug.apk

`local.properties` (not committed) needs `sdk.dir=$HOME/Library/Android/sdk`.

Without the ESP32: Settings → FAKE. The amber FAKE badge is always visible; fault injection is under Settings.

Pilot: on start the app asks for Camera and Notifications, then runs `RobotService` (notification "Pilot at
http://…:8080"). Open that URL from a laptop on the same Wi-Fi. The service keeps driving and video going with the
screen off; only the notification's Stop ends it (opening the app starts it again). Without the Camera permission
the pilot works without video.
````

- [ ] **Step 3: Full test run**

Run (from `android-app/`): `./gradlew :core:test :fake:test :server:testDebugUnitTest :camera:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 0 failures. This is "Done when" 1.

- [ ] **Step 4: Manual — FakeLink, Mac browser** ("Done when" 2)

Install the APK, open the app, grant Camera and Notifications, and pick Settings → FAKE. Open the URL from the status bar in a browser on the Mac. Check each item and write down anything that differs:
1. The video shows. Video ultra-wide vs main and 0/90/180/270 from Settings change the picture within a second or two.
2. Shift + W: the page's Active shows `REMOTE`, and the app's Test screen telemetry shows ENABLE. Releasing Shift clears ENABLE. Switching to another window (blur) while holding Shift also releases.
3. Space during a drive: STOP; the drive stops and needs a new Shift press.
4. Closing the tab while driving: Active is gone and the zero DRIVE resumes within 300 ms (watch the app's telemetry).
5. A second tab gets the "Watching" banner with greyed sticks; its STOP stops the first tab's drive. Closing the first tab promotes the second, and holding Shift through the promotion does not drive until Shift is pressed again (Review Focus 4).
6. On the phone, switch from Drive to Settings to Config and back while the browser drives: the drive is not interrupted (Review Focus 1). Then hold "Hold to drive" on the phone and press Home: STOP ×3, and the pilot's deadman is released.
7. Turn the phone screen off while driving from the browser: driving and video continue.
8. Throttle the Mac's Wi-Fi (or walk away from the router): the red border appears when RTT > 200 ms or the video is older than 1 s.
9. Notification Stop: the robot stops, the page shows Disconnected, and the status bar says "Pilot server off". Opening the app starts the service again.
10. Deny Camera (Android Settings → Apps → MecanumBot → Permissions), reopen the app: the pilot still drives and `video` is `off` (Review Focus 3).
11. Close the tab and wait 10 s with the screen on: the camera turns off (no recent-camera-use green dot) once no one is watching (Review Focus 2).

- [ ] **Step 5: Manual — robot, wheels in the air, VM switch at hand** ("Done when" 3)

1. Settings → USB. Drive all 8 directions from the keyboard, and from the sticks with the mouse.
2. While driving, turn off the Mac's Wi-Fi: the motors stop within 300 ms (arbiter expiry, or the ESP32 failsafe if the phone stalls).
3. Turn the Pixel's screen off while driving: still controllable, video still live.
4. Thermal (optional, only if the phone gets warm on its own): the banner shows "Video reduced" at 40 °C and video stops at 45 °C. No temperature injection is built in; do not heat the phone on purpose.

- [ ] **Step 6: Commit the docs**

```bash
git add CLAUDE.md android-app/README.md
git commit -m "Docs: stage 5 modules, test command, pilot-web delivery"
```
