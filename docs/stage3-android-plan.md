# Stage 3 Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app for the Pixel 9a that drives MecanumBot over USB from a Test screen. The whole UI also works against a simulated ESP32 (FakeLink).

**Architecture:** Four Gradle modules. `:core` and `:fake` are plain Kotlin/JVM, so the build itself keeps Android out of the logic and plain JUnit tests cover it. `:usb` (UsbLink) is a thin Android wrapper, and `:app` holds the Compose UI and the wiring. A single `RobotSession` in `:core` owns the handshake, the 40 Hz tick, PING/RTT and STOP ×3. It talks to hardware only through the `Link` interface, which both UsbLink and FakeLink implement.

**Tech Stack:**
- Build: Gradle 9.6.0, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.20
- Libraries: kotlinx-coroutines 1.11.0, Compose BOM 2026.09.00 (Material 3), DataStore 1.2.1, usb-serial-for-android 3.11.0 (JitPack)
- Tests: JUnit 6.1.3 (Jupiter), kotlinx-coroutines-test, kotlinx-serialization-json (test only, to read `vectors.json`)

**Verified:** on 2026-09-30 every file in this plan was extracted into a scratch copy and built. `:core:test` and `:fake:test` ran 182 tests with 0 failures, and `:usb:assembleDebug` and `:app:assembleDebug` succeeded. So the compile and test steps of Tasks 1–10 are known to pass, and if one fails as written, suspect the environment (JDK, SDK) before the code. Tasks 11–13 were only compiled: UsbLink never opened a port and no Compose code ran, so the manual steps in Tasks 12–14 test behaviour nobody has checked yet.

**Spec:** `docs/stage3-android-spec.md` (v1.1). The wire format and the ESP32 behaviour come from `protocol/PROTOCOL.md`; the executor reads both.

## Global Constraints

- SDK levels: minSdk 34, compileSdk 37, targetSdk 36. Bytecode target JVM 17. Package root `com.mecanumbot`.
- Gradle runs on Android Studio's bundled JDK. Every Gradle command in this plan is run from `android-app/` as `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew …`. The system JDK 24 is not supported, and no JDK 17 is installed, so do not add `jvmToolchain(17)`.
- `:core` and `:fake` have no Android dependencies. `:core` exposes kotlinx-coroutines (`api`).
- `:core` tests read `protocol/vectors.json` in place through the `vectors.path` system property. No copies, and `vectors.json` is never edited by hand.
- Never call `setDTR` or `setRTS` on the USB port.
- No path may drive a motor without a pulse. Every TEST command and every RAW value expires 300 ms after its last update, and the ESP32 failsafe covers everything else.
- STOP is instant and always sent three times. The app never ramps a stop.
- `proto_ver` = 1 (`Protocol.PROTO_VER`); app version 0.1 (`APP_MAJOR = 0`, `APP_MINOR = 1`).
- All UI text is in English.
- The working tree has unrelated uncommitted renames (DESIGN.md → docs/, schematic/ → hardware/). Every commit in this plan stages only the files it lists, via `git add <paths>` and `git commit` without `-a`.

## Review Focus

These are situations the spec implies but none of its explicit requirements test directly. Each one is pinned by a test or manual check in the task named at the end of its line.
1. **"Hold to drive" held while the Activity pauses.** A phone call arrives mid-drive: the robot must stop at once (STOP ×3), and on return the button must not still count as held. Pinned by `stop()` tests (Task 8), the pause handling in `AppGraph.onForeground` (Task 12) and the manual check in Task 13.
2. **Speed limit changed while driving.** The next DRIVE frame uses the new limit; nothing stops or jumps. Pinned by "latest command wins" (Task 8) and the manual check (Task 13).
3. **HELLO_ACK racing the link state.** A HELLO_ACK can be processed before the session has seen `Connected`, or arrive twice (the ESP32 sends one on connect and one in answer to HELLO). The session must reach READY once and never report a false reboot. Pinned by the duplicate/ordering tests (Task 8) and the FakeLink handshake test (Task 10).
4. **One `feed()` call with far more than 205 bytes of garbage before a real frame.** The parser must not overflow or lose the frame. Pinned by the parser test (Task 4).
5. **Link switched while STOP ×3 is still queued.** `close()` must deliver queued STOP frames first. Pinned by the OutgoingQueue test (Task 5) and the FakeLink test (Task 10).

---

## File Structure

```
android-app/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew, gradle/wrapper/
├── core/src/main/kotlin/com/mecanumbot/core/
│   ├── protocol/Protocol.kt      constants, Byte.u8()
│   ├── protocol/Hex.kt           ByteArray.toHex(), String.hexToBytes()
│   ├── protocol/Crc8.kt          CRC-8/SMBUS
│   ├── protocol/FrameType.kt     type codes and len table
│   ├── protocol/Frame.kt         wire frame, toBytes()
│   ├── protocol/Config.kt        CONFIG payload fields, defaults, isValid()
│   ├── protocol/Payloads.kt      typed payloads, Bytes, AckStatus, TelemetryFlags, LogLevel
│   ├── protocol/FrameCodec.kt    payload ⇄ bytes
│   ├── protocol/FrameParser.kt   PROTOCOL §3.1 receiver
│   ├── link/Link.kt              Link, LinkState, Priority
│   ├── link/OutgoingQueue.kt     STOP > MOTION > OTHER queue shared by both links
│   ├── control/Wire.kt           float ⇄ i8
│   ├── control/Command.kt        Command, Source, Mode
│   ├── control/Mecanum.kt        mix + calibration (port of firmware kinematics)
│   ├── control/Arbiter.kt        Arbiter, Output
│   └── session/{SessionState.kt, SessionEvent.kt, RobotSession.kt}
├── core/src/test/kotlin/com/mecanumbot/core/
│   ├── Vectors.kt, VectorsSmokeTest.kt
│   ├── protocol/{Crc8Test, FrameTypeTest, FrameCodecTest, FrameParserTest}.kt
│   ├── link/OutgoingQueueTest.kt
│   ├── control/{WireTest, MecanumTest, ArbiterTest}.kt
│   └── session/{ScriptedLink, RobotSessionHandshakeTest, RobotSessionReadyTest}.kt
├── fake/src/main/kotlin/com/mecanumbot/fake/{FakeEsp32, FakeLink, FaultControls}.kt
├── fake/src/test/kotlin/com/mecanumbot/fake/{FakeEsp32Test, FakeLinkTest}.kt
├── usb/src/main/kotlin/com/mecanumbot/usb/UsbLink.kt, usb/src/main/res/xml/device_filter.xml
└── app/src/main/
    ├── AndroidManifest.xml
    └── kotlin/com/mecanumbot/app/
        ├── MecanumApp.kt, AppGraph.kt, LinkPreference.kt, MainActivity.kt
        └── ui/{Root, StatusBar, SettingsScreen, TestScreen, Joystick, HoldButton}.kt
```

---

### Task 1: Gradle skeleton with a vectors smoke test

**Files:**
- Create: `android-app/settings.gradle.kts`, `android-app/build.gradle.kts`, `android-app/gradle.properties`, `android-app/gradle/libs.versions.toml`
- Create: `android-app/core/build.gradle.kts`, `android-app/fake/build.gradle.kts`, `android-app/usb/build.gradle.kts`, `android-app/app/build.gradle.kts`
- Create: `android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Protocol.kt`
- Create: `android-app/core/src/test/kotlin/com/mecanumbot/core/Vectors.kt`, `android-app/core/src/test/kotlin/com/mecanumbot/core/VectorsSmokeTest.kt`
- Create: `android-app/app/src/main/AndroidManifest.xml`, `android-app/app/src/main/kotlin/com/mecanumbot/app/MainActivity.kt` (placeholder, replaced in Task 12)
- Create (generated): `android-app/gradlew`, `android-app/gradlew.bat`, `android-app/gradle/wrapper/gradle-wrapper.jar`, `android-app/gradle/wrapper/gradle-wrapper.properties`
- Create (not committed, git-ignored): `android-app/local.properties`

**Interfaces:**
- Produces: `object Protocol { SYNC, PROTO_VER, MAX_PAYLOAD, MAX_FRAME }`, `internal fun Byte.u8(): Int`; test helpers `Vectors.root`, `Vectors.array(key)`, `Vectors.crc8()`, `JsonObject.int/long/str/ints/obj(key)`.

- [ ] **Step 1: Write the build files**

`android-app/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // usb-serial-for-android
    }
}

rootProject.name = "mecanumbot"
include(":core", ":fake", ":usb", ":app")
```

`android-app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

`android-app/gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`android-app/gradle/libs.versions.toml`:
```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.20"
coroutines = "1.11.0"
serialization = "1.11.0"
junit = "6.1.3"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
datastore = "1.2.1"
usbSerial = "3.11.0"

[libraries]
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-foundation = { module = "androidx.compose.foundation:foundation" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
usb-serial = { module = "com.github.mik3y:usb-serial-for-android", version.ref = "usbSerial" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`android-app/core/build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.serialization.json)
}

// Tests read protocol/vectors.json in place (CLAUDE.md: no copies).
val vectors = rootDir.resolve("../protocol/vectors.json")

tasks.test {
    useJUnitPlatform()
    systemProperty("vectors.path", vectors.canonicalPath)
    inputs.file(vectors)
}
```

`android-app/fake/build.gradle.kts`:
```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
```

`android-app/usb/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.mecanumbot.usb"
    compileSdk = 37
    defaultConfig { minSdk = 34 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.usb.serial)
    implementation(libs.coroutines.android)
}
```

`android-app/app/build.gradle.kts`:
```kotlin
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

dependencies {
    implementation(project(":core"))
    implementation(project(":fake"))
    implementation(project(":usb"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
}
```

`android-app/app/src/main/AndroidManifest.xml` (placeholder; Task 12 replaces it):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="MecanumBot">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android-app/app/src/main/kotlin/com/mecanumbot/app/MainActivity.kt` (placeholder):
```kotlin
package com.mecanumbot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("MecanumBot") }
    }
}
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Protocol.kt`:
```kotlin
package com.mecanumbot.core.protocol

/** Wire constants — protocol/PROTOCOL.md §3. */
object Protocol {
    const val SYNC = 0xAA
    const val PROTO_VER = 1
    const val MAX_PAYLOAD = 200
    const val MAX_FRAME = MAX_PAYLOAD + 5
}

internal fun Byte.u8(): Int = toInt() and 0xFF
```

- [ ] **Step 2: Write the vectors loader and the failing smoke test**

`android-app/core/src/test/kotlin/com/mecanumbot/core/Vectors.kt`:
```kotlin
package com.mecanumbot.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** protocol/vectors.json, read in place (path from the vectors.path system property). */
object Vectors {
    val root: JsonObject by lazy {
        val path = System.getProperty("vectors.path") ?: error("vectors.path is not set")
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    fun array(key: String): List<JsonObject> = root.getValue(key).jsonArray.map { it.jsonObject }

    fun crc8(): List<JsonObject> = root.obj("crc8").getValue("vectors").jsonArray.map { it.jsonObject }
}

fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.ints(key: String): List<Int> = getValue(key).jsonArray.map { it.jsonPrimitive.int }
fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
```

`android-app/core/src/test/kotlin/com/mecanumbot/core/VectorsSmokeTest.kt`:
```kotlin
package com.mecanumbot.core

import com.mecanumbot.core.protocol.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VectorsSmokeTest {
    @Test
    fun `vectors proto_ver matches the app`() {
        assertEquals(Protocol.PROTO_VER, Vectors.root.int("proto_ver"))
    }
}
```

- [ ] **Step 3: Generate the Gradle wrapper and local.properties**

Gradle isn't installed system-wide, so download the distribution once into a temp dir and use it to generate the wrapper:
```bash
cd android-app
T="${TMPDIR:-/tmp}/gradle-dl" && mkdir -p "$T"
[ -x "$T/gradle-9.6.0/bin/gradle" ] || (curl -sSL -o "$T/g.zip" https://services.gradle.org/distributions/gradle-9.6.0-bin.zip && unzip -q -o "$T/g.zip" -d "$T")
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$T/gradle-9.6.0/bin/gradle" wrapper --gradle-version 9.6.0
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```
Expected: `gradlew`, `gradlew.bat` and `gradle/wrapper/` exist, and `gradle/wrapper/gradle-wrapper.properties` contains `gradle-9.6.0-bin.zip`. `local.properties` is already ignored by the root `.gitignore`.

- [ ] **Step 4: Run the smoke test and the app build**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. On the first run AGP may install Android SDK Platform 37 automatically; the license is already accepted. `core/build/test-results/test/TEST-com.mecanumbot.core.VectorsSmokeTest.xml` shows `failures="0"`.

If the property is missing (for example, the test is run from an IDE without Gradle), the test fails with `vectors.path is not set`. That is expected; always run the tests through Gradle.

- [ ] **Step 5: Commit**

```bash
git add android-app/settings.gradle.kts android-app/build.gradle.kts android-app/gradle.properties \
  android-app/gradle android-app/gradlew android-app/gradlew.bat \
  android-app/core android-app/fake/build.gradle.kts android-app/usb/build.gradle.kts android-app/app
git commit -m "Android: Gradle skeleton with core, fake, usb, app modules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: CRC-8, frame types, hex helpers, Frame

**Files:**
- Create: `android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Hex.kt`, `Crc8.kt`, `FrameType.kt`, `Frame.kt`
- Test: `android-app/core/src/test/kotlin/com/mecanumbot/core/protocol/Crc8Test.kt`, `FrameTypeTest.kt`

**Interfaces:**
- Consumes: `Protocol`, `Byte.u8()` (Task 1).
- Produces:
  - `fun ByteArray.toHex(): String`, `fun String.hexToBytes(): ByteArray`
  - `object Crc8 { fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): Int }`
  - `enum class FrameType(val code: Int, val minLen: Int, val maxLen: Int)` with entries HELLO, DRIVE, MOTOR_RAW, PING, CONFIG, STOP, GET_CONFIG, OTA_BEGIN, OTA_DATA, OTA_END, OTA_ABORT, WIFI_OTA_ENTER, WIFI_OTA_EXIT, REBOOT, HELLO_ACK, TELEMETRY, ACK, LOG, PONG, CONFIG_DATA, WIFI_STATUS, plus `FrameType.fromCode(code: Int): FrameType?`
  - `class Frame(val type: FrameType, val seq: Int, val payload: ByteArray)` with `fun toBytes(): ByteArray` and value equality

- [ ] **Step 1: Write the failing tests**

`android-app/core/src/test/kotlin/com/mecanumbot/core/protocol/Crc8Test.kt`:
```kotlin
package com.mecanumbot.core.protocol

import com.mecanumbot.core.Vectors
import com.mecanumbot.core.int
import com.mecanumbot.core.str
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class Crc8Test {
    @TestFactory
    fun `crc8 vectors`(): List<DynamicTest> = Vectors.crc8().map { v ->
        dynamicTest(v.str("name")) {
            assertEquals(v.int("crc"), Crc8.compute(v.str("data_hex").hexToBytes()))
        }
    }

    @Test
    fun `check value of 123456789 is F4`() {
        assertEquals(0xF4, Crc8.compute("123456789".encodeToByteArray()))
    }

    @Test
    fun `range overload covers only the given bytes`() {
        val data = "ff313233343536373839ff".hexToBytes()
        assertEquals(0xF4, Crc8.compute(data, 1, data.size - 1))
    }
}
```

`android-app/core/src/test/kotlin/com/mecanumbot/core/protocol/FrameTypeTest.kt`:
```kotlin
package com.mecanumbot.core.protocol

import com.mecanumbot.core.Vectors
import com.mecanumbot.core.int
import com.mecanumbot.core.str
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrameTypeTest {
    @Test
    fun `table matches vectors types`() {
        val types = Vectors.array("types")
        assertEquals(types.size, FrameType.entries.size)
        for (t in types) {
            val ft = FrameType.fromCode(t.int("type"))
            assertNotNull(ft, t.str("name"))
            assertEquals(t.str("name"), ft!!.name)
            assertEquals(t.int("min_len"), ft.minLen, ft.name)
            assertEquals(t.int("max_len"), ft.maxLen, ft.name)
        }
    }

    @Test
    fun `unknown codes are null`() {
        assertNull(FrameType.fromCode(0x55))
        assertNull(FrameType.fromCode(0xAA))
    }

    @Test
    fun `frame bytes match the stop vector`() {
        assertEquals("aa05000bf1", Frame(FrameType.STOP, 11, ByteArray(0)).toBytes().toHex())
    }

    @Test
    fun `frame rejects a payload length outside the type range`() {
        assertThrows<IllegalArgumentException> { Frame(FrameType.DRIVE, 0, ByteArray(5)) }
    }

    @Test
    fun `frames with equal content are equal`() {
        assertEquals(Frame(FrameType.PING, 7, "01000000".hexToBytes()), Frame(FrameType.PING, 7, "01000000".hexToBytes()))
    }

    @Test
    fun `hex round trip`() {
        assertEquals("00ff7faa", "00ff7faa".hexToBytes().toHex())
    }
}
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test`
Expected: compilation fails with `Unresolved reference 'Crc8'`, `'FrameType'`, `'hexToBytes'`.

- [ ] **Step 3: Implement**

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Hex.kt`:
```kotlin
package com.mecanumbot.core.protocol

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length: $length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Crc8.kt`:
```kotlin
package com.mecanumbot.core.protocol

/** CRC-8/SMBUS: poly 0x07, init 0x00, no reflect, xorout 0x00 — PROTOCOL.md §3. */
object Crc8 {
    fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 0x80 != 0) (crc shl 1) xor 0x07 else crc shl 1
                crc = crc and 0xFF
            }
        }
        return crc
    }
}
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/FrameType.kt`:
```kotlin
package com.mecanumbot.core.protocol

/** Frame types of both directions with their payload length range — PROTOCOL.md §4. */
enum class FrameType(val code: Int, val minLen: Int, val maxLen: Int) {
    HELLO(0x00, 3, 3),
    DRIVE(0x01, 4, 4),
    MOTOR_RAW(0x02, 4, 4),
    PING(0x03, 4, 4),
    CONFIG(0x04, 22, 22),
    STOP(0x05, 0, 0),
    GET_CONFIG(0x06, 0, 0),
    OTA_BEGIN(0x10, 36, 36),
    OTA_DATA(0x11, 5, 196),
    OTA_END(0x12, 0, 0),
    OTA_ABORT(0x13, 0, 0),
    WIFI_OTA_ENTER(0x20, 2, 97),
    WIFI_OTA_EXIT(0x21, 0, 0),
    REBOOT(0x7F, 0, 0),
    HELLO_ACK(0x80, 6, 6),
    TELEMETRY(0x81, 20, 20),
    ACK(0x82, 3, 3),
    LOG(0x83, 1, 200),
    PONG(0x84, 4, 4),
    CONFIG_DATA(0x85, 22, 22),
    WIFI_STATUS(0x86, 5, 5);

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: Int): FrameType? = byCode[code]
    }
}
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Frame.kt`:
```kotlin
package com.mecanumbot.core.protocol

/** One wire frame: 0xAA | type | len | seq | payload | crc8 — PROTOCOL.md §3. */
class Frame(val type: FrameType, val seq: Int, val payload: ByteArray) {
    init {
        require(payload.size in type.minLen..type.maxLen) { "$type: bad payload length ${payload.size}" }
        require(seq in 0..0xFF) { "seq out of range: $seq" }
    }

    fun toBytes(): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = Protocol.SYNC.toByte()
        out[1] = type.code.toByte()
        out[2] = payload.size.toByte()
        out[3] = seq.toByte()
        payload.copyInto(out, 4)
        out[out.size - 1] = Crc8.compute(out, 1, out.size - 1).toByte()
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Frame && type == other.type && seq == other.seq && payload.contentEquals(other.payload)

    override fun hashCode(): Int = (type.hashCode() * 31 + seq) * 31 + payload.contentHashCode()

    override fun toString(): String = "Frame($type seq=$seq payload=${payload.toHex()})"
}
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`; Crc8Test, FrameTypeTest and VectorsSmokeTest pass.

- [ ] **Step 5: Commit**

```bash
git add android-app/core/src
git commit -m "Android core: CRC-8, frame types, Frame

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Typed payloads, Config and FrameCodec

**Files:**
- Create: `android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Config.kt`, `Payloads.kt`, `FrameCodec.kt`
- Test: `android-app/core/src/test/kotlin/com/mecanumbot/core/protocol/FrameCodecTest.kt`

**Interfaces:**
- Consumes: `Frame`, `FrameType`, `hexToBytes`, `toHex` (Task 2).
- Produces:
  - `data class Config(version=1, map=[0,1,2,3], invert=[0,0,0,0], maxDuty=100, slewMs=250, trim=[100×4], brake=1, minDuty=15, failsafeMs=300, pwmHz=20000)` with `fun isValid(): Boolean` and `Config.DEFAULT`. The list fields are `List<Int>`.
  - `sealed interface Payload { val type: FrameType }`, implemented by:
    - `Hello(protoVer, appMajor, appMinor)`
    - `Drive(flags, vx, vy, w)`
    - `MotorRaw(m: List<Int>)`
    - `Ping(ts: Long)`
    - `SetConfig(config)`
    - `Stop`, `GetConfig`
    - `OtaBegin(size: Long, sha256: Bytes)`, `OtaData(offset: Long, data: Bytes)`, `OtaEnd`, `OtaAbort`
    - `WifiOtaEnter(ssid, pass)`, `WifiOtaExit`
    - `Reboot`
    - `HelloAck(protoVer, fwMajor, fwMinor, resetReason, resetCount)`
    - `Telemetry(lastSeq, flags, pwm: List<Int>, vmMv, crcErr, rxFrames, uptimeS: Long, fwMajor, fwMinor, loopMaxUs)`
    - `Ack(reqType, reqSeq, status)`
    - `Log(level, text)`
    - `Pong(ts: Long)`
    - `ConfigData(config)`
    - `WifiStatus(state, ip: List<Int>)`
  - `class Bytes(val array: ByteArray)` with value equality and `Bytes.hex(s)`.
  - `object AckStatus { OK=0, ERR=1, BUSY=2 }`, `object TelemetryFlags { FAILSAFE=1, FAULT_A=2, FAULT_B=4, RAW=8, OTA=16, WIFI=32, ENABLE=64 }`, `object LogLevel { ERROR=0, WARN=1, INFO=2, DEBUG=3 }`.
  - `object FrameCodec { fun encode(payload: Payload, seq: Int): ByteArray; fun encodePayload(payload: Payload): ByteArray; fun decode(frame: Frame): Payload? }`.

Speeds (`vx`, `vy`, `w`, `m`, `pwm`) are raw i8 values from the wire, −128…127. The codec never clamps them (PROTOCOL §2).

- [ ] **Step 1: Write the failing test**

`android-app/core/src/test/kotlin/com/mecanumbot/core/protocol/FrameCodecTest.kt`:
```kotlin
package com.mecanumbot.core.protocol

import com.mecanumbot.core.Vectors
import com.mecanumbot.core.int
import com.mecanumbot.core.ints
import com.mecanumbot.core.long
import com.mecanumbot.core.obj
import com.mecanumbot.core.str
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class FrameCodecTest {
    private val frames = Vectors.array("frames")

    @Test
    fun `every frame type has a vector`() {
        assertEquals(FrameType.entries.map { it.name }.toSet(), frames.map { it.str("type_name") }.toSet())
    }

    @TestFactory
    fun `encode fields to frame_hex`(): List<DynamicTest> = frames.map { v ->
        dynamicTest(v.str("name")) {
            val payload = payloadOf(v.str("type_name"), v.obj("fields"))
            assertEquals(v.str("payload_hex"), FrameCodec.encodePayload(payload).toHex())
            assertEquals(v.str("frame_hex"), FrameCodec.encode(payload, v.int("seq")).toHex())
        }
    }

    @TestFactory
    fun `decode payload_hex to fields`(): List<DynamicTest> = frames.map { v ->
        dynamicTest(v.str("name")) {
            val type = FrameType.fromCode(v.int("type"))!!
            val frame = Frame(type, v.int("seq"), v.str("payload_hex").hexToBytes())
            assertEquals(payloadOf(v.str("type_name"), v.obj("fields")), FrameCodec.decode(frame))
        }
    }

    @Test
    fun `WIFI_OTA_ENTER with a string running past the payload decodes to null`() {
        assertNull(FrameCodec.decode(Frame(FrameType.WIFI_OTA_ENTER, 0, "0541".hexToBytes())))
    }

    @Test
    fun `WIFI_OTA_ENTER with trailing bytes decodes to null`() {
        assertNull(FrameCodec.decode(Frame(FrameType.WIFI_OTA_ENTER, 0, "000000".hexToBytes())))
    }

    @Test
    fun `config validation follows PROTOCOL 4_4`() {
        assertTrue(Config.DEFAULT.isValid())
        assertFalse(Config(version = 2).isValid())
        assertFalse(Config(map = listOf(0, 0, 2, 3)).isValid())
        assertFalse(Config(invert = listOf(0, 2, 0, 0)).isValid())
        assertFalse(Config(trim = listOf(49, 100, 100, 100)).isValid())
        assertFalse(Config(maxDuty = 0).isValid())
        assertFalse(Config(maxDuty = 20, minDuty = 20).isValid())
        assertFalse(Config(slewMs = 2001).isValid())
        assertFalse(Config(brake = 2).isValid())
        assertFalse(Config(failsafeMs = 99).isValid())
        assertFalse(Config(pwmHz = 30001).isValid())
        assertTrue(Config(map = listOf(1, 0, 3, 2), maxDuty = 80, minDuty = 20).isValid())
    }

    private fun configOf(f: JsonObject) = Config(
        version = f.int("version"),
        map = f.ints("map"),
        invert = f.ints("invert"),
        maxDuty = f.int("max_duty"),
        slewMs = f.int("slew_ms"),
        trim = f.ints("trim"),
        brake = f.int("brake"),
        minDuty = f.int("min_duty"),
        failsafeMs = f.int("failsafe_ms"),
        pwmHz = f.int("pwm_hz"),
    )

    /** Canonical field names as gen_vectors.py writes them (PROTOCOL.md §8). */
    private fun payloadOf(typeName: String, f: JsonObject): Payload = when (typeName) {
        "HELLO" -> Hello(f.int("proto_ver"), f.int("app_major"), f.int("app_minor"))
        "DRIVE" -> Drive(f.int("flags"), f.int("vx"), f.int("vy"), f.int("w"))
        "MOTOR_RAW" -> MotorRaw(f.ints("m"))
        "PING" -> Ping(f.long("ts"))
        "CONFIG" -> SetConfig(configOf(f))
        "STOP" -> Stop
        "GET_CONFIG" -> GetConfig
        "OTA_BEGIN" -> OtaBegin(f.long("size"), Bytes.hex(f.str("sha256")))
        "OTA_DATA" -> OtaData(f.long("offset"), Bytes.hex(f.str("data")))
        "OTA_END" -> OtaEnd
        "OTA_ABORT" -> OtaAbort
        "WIFI_OTA_ENTER" -> WifiOtaEnter(f.str("ssid"), f.str("pass"))
        "WIFI_OTA_EXIT" -> WifiOtaExit
        "REBOOT" -> Reboot
        "HELLO_ACK" -> HelloAck(
            f.int("proto_ver"), f.int("fw_major"), f.int("fw_minor"), f.int("reset_reason"), f.int("reset_count"),
        )
        "TELEMETRY" -> Telemetry(
            lastSeq = f.int("last_seq"),
            flags = f.int("flags"),
            pwm = f.ints("pwm"),
            vmMv = f.int("vm_mv"),
            crcErr = f.int("crc_err"),
            rxFrames = f.int("rx_frames"),
            uptimeS = f.long("uptime_s"),
            fwMajor = f.int("fw_major"),
            fwMinor = f.int("fw_minor"),
            loopMaxUs = f.int("loop_max_us"),
        )
        "ACK" -> Ack(f.int("req_type"), f.int("req_seq"), f.int("status"))
        "LOG" -> Log(f.int("level"), f.str("text"))
        "PONG" -> Pong(f.long("ts"))
        "CONFIG_DATA" -> ConfigData(configOf(f))
        "WIFI_STATUS" -> WifiStatus(f.int("state"), f.ints("ip"))
        else -> error("unknown type $typeName")
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test --tests 'com.mecanumbot.core.protocol.FrameCodecTest'`
Expected: compilation fails with `Unresolved reference 'Hello'`, `'FrameCodec'`, `'Config'`.

- [ ] **Step 3: Implement**

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Config.kt`:
```kotlin
package com.mecanumbot.core.protocol

/** CONFIG / CONFIG_DATA payload — PROTOCOL.md §4.4. Per-wheel lists are FL, FR, RL, RR. */
data class Config(
    val version: Int = 1,
    val map: List<Int> = listOf(0, 1, 2, 3),
    val invert: List<Int> = listOf(0, 0, 0, 0),
    val maxDuty: Int = 100,
    val slewMs: Int = 250,
    val trim: List<Int> = listOf(100, 100, 100, 100),
    val brake: Int = 1,
    val minDuty: Int = 15,
    val failsafeMs: Int = 300,
    val pwmHz: Int = 20000,
) {
    /** Mirrors firmware lib/protocol validate(). */
    fun isValid(): Boolean {
        if (version != 1) return false
        if (map.size != 4 || invert.size != 4 || trim.size != 4) return false
        var seen = 0
        for (i in 0 until 4) {
            if (map[i] !in 0..3) return false
            seen = seen or (1 shl map[i])
            if (invert[i] !in 0..1) return false
            if (trim[i] !in 50..100) return false
        }
        if (seen != 0x0F) return false
        if (maxDuty !in 1..100) return false
        if (minDuty < 0 || minDuty >= maxDuty) return false
        if (slewMs !in 0..2000) return false
        if (brake !in 0..1) return false
        if (failsafeMs !in 100..1000) return false
        if (pwmHz !in 1000..30000) return false
        return true
    }

    companion object {
        val DEFAULT = Config()
    }
}
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/Payloads.kt`:
```kotlin
package com.mecanumbot.core.protocol

/** Typed payloads of every frame type — PROTOCOL.md §4. Speeds are raw i8 wire values. */
sealed interface Payload {
    val type: FrameType
}

/** Byte array with value equality, for payload fields. */
class Bytes(val array: ByteArray) {
    val size: Int get() = array.size

    override fun equals(other: Any?): Boolean = other is Bytes && array.contentEquals(other.array)
    override fun hashCode(): Int = array.contentHashCode()
    override fun toString(): String = array.toHex()

    companion object {
        fun hex(s: String) = Bytes(s.hexToBytes())
    }
}

object AckStatus {
    const val OK = 0
    const val ERR = 1
    const val BUSY = 2
}

object TelemetryFlags {
    const val FAILSAFE = 1
    const val FAULT_A = 2
    const val FAULT_B = 4
    const val RAW = 8
    const val OTA = 16
    const val WIFI = 32
    const val ENABLE = 64
}

object LogLevel {
    const val ERROR = 0
    const val WARN = 1
    const val INFO = 2
    const val DEBUG = 3
}

// --- phone → ESP32 ---

data class Hello(val protoVer: Int, val appMajor: Int, val appMinor: Int) : Payload {
    override val type: FrameType get() = FrameType.HELLO
}

/** flags: bit0 enable, bits 1..2 source. */
data class Drive(val flags: Int, val vx: Int, val vy: Int, val w: Int) : Payload {
    override val type: FrameType get() = FrameType.DRIVE
}

/** m[4]: physical channels M1–M4. */
data class MotorRaw(val m: List<Int>) : Payload {
    override val type: FrameType get() = FrameType.MOTOR_RAW
}

data class Ping(val ts: Long) : Payload {
    override val type: FrameType get() = FrameType.PING
}

data class SetConfig(val config: Config) : Payload {
    override val type: FrameType get() = FrameType.CONFIG
}

data object Stop : Payload {
    override val type: FrameType get() = FrameType.STOP
}

data object GetConfig : Payload {
    override val type: FrameType get() = FrameType.GET_CONFIG
}

data class OtaBegin(val size: Long, val sha256: Bytes) : Payload {
    override val type: FrameType get() = FrameType.OTA_BEGIN
}

data class OtaData(val offset: Long, val data: Bytes) : Payload {
    override val type: FrameType get() = FrameType.OTA_DATA
}

data object OtaEnd : Payload {
    override val type: FrameType get() = FrameType.OTA_END
}

data object OtaAbort : Payload {
    override val type: FrameType get() = FrameType.OTA_ABORT
}

data class WifiOtaEnter(val ssid: String, val pass: String) : Payload {
    override val type: FrameType get() = FrameType.WIFI_OTA_ENTER
}

data object WifiOtaExit : Payload {
    override val type: FrameType get() = FrameType.WIFI_OTA_EXIT
}

data object Reboot : Payload {
    override val type: FrameType get() = FrameType.REBOOT
}

// --- ESP32 → phone ---

data class HelloAck(
    val protoVer: Int,
    val fwMajor: Int,
    val fwMinor: Int,
    val resetReason: Int,
    val resetCount: Int,
) : Payload {
    override val type: FrameType get() = FrameType.HELLO_ACK
}

data class Telemetry(
    val lastSeq: Int,
    val flags: Int,
    val pwm: List<Int>,
    val vmMv: Int,
    val crcErr: Int,
    val rxFrames: Int,
    val uptimeS: Long,
    val fwMajor: Int,
    val fwMinor: Int,
    val loopMaxUs: Int,
) : Payload {
    override val type: FrameType get() = FrameType.TELEMETRY
}

data class Ack(val reqType: Int, val reqSeq: Int, val status: Int) : Payload {
    override val type: FrameType get() = FrameType.ACK
}

data class Log(val level: Int, val text: String) : Payload {
    override val type: FrameType get() = FrameType.LOG
}

data class Pong(val ts: Long) : Payload {
    override val type: FrameType get() = FrameType.PONG
}

data class ConfigData(val config: Config) : Payload {
    override val type: FrameType get() = FrameType.CONFIG_DATA
}

data class WifiStatus(val state: Int, val ip: List<Int>) : Payload {
    override val type: FrameType get() = FrameType.WIFI_STATUS
}
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/FrameCodec.kt`:
```kotlin
package com.mecanumbot.core.protocol

import java.io.ByteArrayOutputStream

/** Payload ⇄ bytes, little-endian — PROTOCOL.md §2, §4. */
object FrameCodec {
    fun encode(payload: Payload, seq: Int): ByteArray =
        Frame(payload.type, seq and 0xFF, encodePayload(payload)).toBytes()

    fun encodePayload(payload: Payload): ByteArray {
        val w = Writer()
        when (payload) {
            is Hello -> { w.u8(payload.protoVer); w.u8(payload.appMajor); w.u8(payload.appMinor) }
            is Drive -> { w.u8(payload.flags); w.u8(payload.vx); w.u8(payload.vy); w.u8(payload.w) }
            is MotorRaw -> payload.m.forEach(w::u8)
            is Ping -> w.u32(payload.ts)
            is SetConfig -> w.config(payload.config)
            is OtaBegin -> { w.u32(payload.size); w.bytes(payload.sha256.array) }
            is OtaData -> { w.u32(payload.offset); w.bytes(payload.data.array) }
            is WifiOtaEnter -> { w.str8(payload.ssid); w.str8(payload.pass) }
            is HelloAck -> {
                w.u8(payload.protoVer); w.u8(payload.fwMajor); w.u8(payload.fwMinor)
                w.u8(payload.resetReason); w.u16(payload.resetCount)
            }
            is Telemetry -> {
                w.u8(payload.lastSeq); w.u8(payload.flags); payload.pwm.forEach(w::u8)
                w.u16(payload.vmMv); w.u16(payload.crcErr); w.u16(payload.rxFrames); w.u32(payload.uptimeS)
                w.u8(payload.fwMajor); w.u8(payload.fwMinor); w.u16(payload.loopMaxUs)
            }
            is Ack -> { w.u8(payload.reqType); w.u8(payload.reqSeq); w.u8(payload.status) }
            is Log -> { w.u8(payload.level); w.bytes(payload.text.encodeToByteArray()) }
            is Pong -> w.u32(payload.ts)
            is ConfigData -> w.config(payload.config)
            is WifiStatus -> { w.u8(payload.state); payload.ip.forEach(w::u8) }
            Stop, GetConfig, OtaEnd, OtaAbort, WifiOtaExit, Reboot -> Unit
        }
        return w.toByteArray()
    }

    /** Null when the content doesn't fit its type (a str8 running past the end, trailing bytes). */
    fun decode(frame: Frame): Payload? = try {
        val r = Reader(frame.payload)
        val p = read(frame.type, r)
        if (r.remaining == 0) p else null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun read(type: FrameType, r: Reader): Payload = when (type) {
        FrameType.HELLO -> Hello(r.u8(), r.u8(), r.u8())
        FrameType.DRIVE -> Drive(r.u8(), r.i8(), r.i8(), r.i8())
        FrameType.MOTOR_RAW -> MotorRaw(List(4) { r.i8() })
        FrameType.PING -> Ping(r.u32())
        FrameType.CONFIG -> SetConfig(r.config())
        FrameType.STOP -> Stop
        FrameType.GET_CONFIG -> GetConfig
        FrameType.OTA_BEGIN -> OtaBegin(r.u32(), Bytes(r.bytes(32)))
        FrameType.OTA_DATA -> OtaData(r.u32(), Bytes(r.rest()))
        FrameType.OTA_END -> OtaEnd
        FrameType.OTA_ABORT -> OtaAbort
        FrameType.WIFI_OTA_ENTER -> WifiOtaEnter(r.str8(), r.str8())
        FrameType.WIFI_OTA_EXIT -> WifiOtaExit
        FrameType.REBOOT -> Reboot
        FrameType.HELLO_ACK -> HelloAck(r.u8(), r.u8(), r.u8(), r.u8(), r.u16())
        FrameType.TELEMETRY -> Telemetry(
            lastSeq = r.u8(),
            flags = r.u8(),
            pwm = List(4) { r.i8() },
            vmMv = r.u16(),
            crcErr = r.u16(),
            rxFrames = r.u16(),
            uptimeS = r.u32(),
            fwMajor = r.u8(),
            fwMinor = r.u8(),
            loopMaxUs = r.u16(),
        )
        FrameType.ACK -> Ack(r.u8(), r.u8(), r.u8())
        FrameType.LOG -> Log(r.u8(), r.rest().decodeToString())
        FrameType.PONG -> Pong(r.u32())
        FrameType.CONFIG_DATA -> ConfigData(r.config())
        FrameType.WIFI_STATUS -> WifiStatus(r.u8(), List(4) { r.u8() })
    }

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v shr 8) }
        fun u32(v: Long) { for (i in 0 until 4) u8((v shr (8 * i)).toInt()) }
        fun bytes(b: ByteArray) = out.write(b)
        fun str8(s: String) { val b = s.encodeToByteArray(); u8(b.size); bytes(b) }

        fun config(c: Config) {
            u8(c.version); c.map.forEach(::u8); c.invert.forEach(::u8); u8(c.maxDuty); u16(c.slewMs)
            c.trim.forEach(::u8); u8(c.brake); u8(c.minDuty); u16(c.failsafeMs); u16(c.pwmHz)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    /** Throws IndexOutOfBoundsException past the end. Arguments are evaluated left to right. */
    private class Reader(private val b: ByteArray) {
        private var pos = 0
        val remaining: Int get() = b.size - pos

        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun i8(): Int = b[pos++].toInt()
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = (0 until 4).fold(0L) { acc, i -> acc or (u8().toLong() shl (8 * i)) }

        fun bytes(n: Int): ByteArray {
            if (n > remaining) throw IndexOutOfBoundsException("need $n, have $remaining")
            return b.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun rest(): ByteArray = bytes(remaining)
        fun str8(): String = bytes(u8()).decodeToString()

        fun config() = Config(
            version = u8(),
            map = List(4) { u8() },
            invert = List(4) { u8() },
            maxDuty = u8(),
            slewMs = u16(),
            trim = List(4) { u8() },
            brake = u8(),
            minDuty = u8(),
            failsafeMs = u16(),
            pwmHz = u16(),
        )
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`. Every vector passes in both directions; `drive_minus_128_clamped` decodes `vx = -128` unchanged.

- [ ] **Step 5: Commit**

```bash
git add android-app/core/src
git commit -m "Android core: typed payloads, Config, FrameCodec on vectors.json

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: FrameParser (PROTOCOL §3.1)

**Files:**
- Create: `android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/FrameParser.kt`
- Test: `android-app/core/src/test/kotlin/com/mecanumbot/core/protocol/FrameParserTest.kt`

**Interfaces:**
- Consumes: `Frame`, `FrameType`, `Crc8`, `Protocol` (Tasks 1–2).
- Produces: `class FrameParser(staleMs: Long = 50)` with:
  - `fun feed(bytes: ByteArray, now: Long = 0L): List<Frame>`
  - `fun flushStale(now: Long): List<Frame>`
  - `fun reset()`
  - `val crcErr: Int`

  Not thread-safe; callers on several threads synchronize on the parser.

- [ ] **Step 1: Write the failing test**

`android-app/core/src/test/kotlin/com/mecanumbot/core/protocol/FrameParserTest.kt`:
```kotlin
package com.mecanumbot.core.protocol

import com.mecanumbot.core.Vectors
import com.mecanumbot.core.int
import com.mecanumbot.core.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class FrameParserTest {
    private data class Seen(val type: Int, val seq: Int, val payloadHex: String)

    private fun Frame.seen() = Seen(type.code, seq, payload.toHex())

    @TestFactory
    fun `vectors streams`(): List<DynamicTest> = Vectors.array("streams").map { s ->
        dynamicTest(s.str("name")) {
            val parser = FrameParser()
            val got = s.getValue("chunks").jsonArray.flatMap { chunk ->
                parser.feed(chunk.jsonPrimitive.content.hexToBytes()).map { it.seen() }
            }
            val expected = s.getValue("expected_frames").jsonArray.map {
                val o = it.jsonObject
                Seen(o.int("type"), o.int("seq"), o.str("payload_hex"))
            }
            assertEquals(expected, got)
            assertEquals(s.int("expected_crc_err"), parser.crcErr)
        }
    }

    @Test
    fun `one feed with 1000 bytes of garbage before a frame`() {
        val parser = FrameParser()
        val garbage = ByteArray(1000) { 0x55 }
        val frames = parser.feed(garbage + "aa0104010100400054".hexToBytes())
        assertEquals(listOf(Seen(1, 1, "01004000")), frames.map { it.seen() })
        assertEquals(0, parser.crcErr)
    }

    @Test
    fun `garbage full of stray sync bytes is rejected one by one and the frame survives`() {
        val parser = FrameParser()
        val garbage = ByteArray(600) { if (it % 2 == 0) 0xAA.toByte() else 0x55 }
        val frames = parser.feed(garbage + "aa0104010100400054".hexToBytes())
        assertEquals(listOf(Seen(1, 1, "01004000")), frames.map { it.seen() })
        assertEquals(300, parser.crcErr)
    }

    @Test
    fun `stale incomplete frame is dropped after 50 ms of silence`() {
        val parser = FrameParser()
        assertTrue(parser.feed("aa0104".hexToBytes(), now = 0).isEmpty())
        assertTrue(parser.flushStale(now = 49).isEmpty())
        assertEquals(0, parser.crcErr)
        parser.flushStale(now = 50)
        assertEquals(1, parser.crcErr)
        val frames = parser.feed("aa0104010100400054".hexToBytes(), now = 60)
        assertEquals(listOf(Seen(1, 1, "01004000")), frames.map { it.seen() })
    }

    @Test
    fun `flushing a stale long header releases a frame buffered behind it`() {
        val parser = FrameParser()
        // A LOG header with len 200 makes the parser wait for 205 bytes; a real DRIVE sits inside.
        assertTrue(parser.feed("aa83c8".hexToBytes() + "aa0104010100400054".hexToBytes(), now = 0).isEmpty())
        val frames = parser.flushStale(now = 60)
        assertEquals(listOf(Seen(1, 1, "01004000")), frames.map { it.seen() })
        assertEquals(1, parser.crcErr)
    }

    @Test
    fun `reset drops a buffered partial frame`() {
        val parser = FrameParser()
        parser.feed("aa010401".hexToBytes())
        parser.reset()
        // The tail of the dropped frame has no 0xAA and is skipped; the next frame is found.
        val frames = parser.feed("0100400054".hexToBytes() + FrameCodec.encode(Drive(1, 0, 64, 0), 2))
        assertEquals(listOf(Seen(1, 2, "01004000")), frames.map { it.seen() })
        assertEquals(0, parser.crcErr)
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test --tests 'com.mecanumbot.core.protocol.FrameParserTest'`
Expected: compilation fails with `Unresolved reference 'FrameParser'`.

- [ ] **Step 3: Implement**

`android-app/core/src/main/kotlin/com/mecanumbot/core/protocol/FrameParser.kt`:
```kotlin
package com.mecanumbot.core.protocol

/**
 * Receiver of PROTOCOL.md §3.1. A reject counts in [crcErr] and drops only the 0xAA, so a real
 * frame starting inside the rejected bytes is still found. After a scan the buffer holds at most
 * one incomplete frame (≤ 204 bytes), whatever the size of the fed chunk.
 */
class FrameParser(private val staleMs: Long = 50) {
    private var buf = ByteArray(0)
    private var lastByteAt = 0L

    var crcErr = 0
        private set

    fun feed(bytes: ByteArray, now: Long = 0L): List<Frame> {
        if (bytes.isNotEmpty()) {
            lastByteAt = now
            buf += bytes
        }
        return scan()
    }

    /** Rejects an incomplete frame that got no new bytes for [staleMs] (recommended in §3.1). */
    fun flushStale(now: Long): List<Frame> {
        val out = ArrayList<Frame>()
        while (buf.isNotEmpty() && now - lastByteAt >= staleMs) {
            crcErr++
            buf = buf.copyOfRange(1, buf.size)
            out += scan()
        }
        return out
    }

    fun reset() {
        buf = ByteArray(0)
    }

    private fun scan(): List<Frame> {
        val out = ArrayList<Frame>()
        var i = 0
        while (true) {
            while (i < buf.size && buf[i].u8() != Protocol.SYNC) i++
            val avail = buf.size - i
            if (avail < 2) break
            val type = FrameType.fromCode(buf[i + 1].u8())
            if (type == null) {
                crcErr++; i++; continue
            }
            if (avail < 3) break
            val len = buf[i + 2].u8()
            if (len > Protocol.MAX_PAYLOAD || len < type.minLen || len > type.maxLen) {
                crcErr++; i++; continue
            }
            if (avail < 5 + len) break
            val crcAt = i + 4 + len
            if (Crc8.compute(buf, i + 1, crcAt) != buf[crcAt].u8()) {
                crcErr++; i++; continue
            }
            out += Frame(type, buf[i + 3].u8(), buf.copyOfRange(i + 4, crcAt))
            i = crcAt + 1
        }
        buf = buf.copyOfRange(i, buf.size)
        return out
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`; all 16 stream vectors and the extra parser tests pass.

- [ ] **Step 5: Commit**

```bash
git add android-app/core/src
git commit -m "Android core: FrameParser per PROTOCOL 3.1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Link interface and OutgoingQueue

**Files:**
- Create: `android-app/core/src/main/kotlin/com/mecanumbot/core/link/Link.kt`, `OutgoingQueue.kt`
- Test: `android-app/core/src/test/kotlin/com/mecanumbot/core/link/OutgoingQueueTest.kt`

**Interfaces:**
- Consumes: `Frame` (Task 2).
- Produces:
  ```kotlin
  sealed interface LinkState { Disconnected; Connecting; Connected; data class Error(val message: String) }
  enum class Priority { STOP, MOTION, OTHER }
  interface Link {
      val state: StateFlow<LinkState>
      val incoming: Flow<Frame>
      val parserErrors: StateFlow<Int>
      suspend fun open()
      suspend fun close()        // writes queued STOP frames first
      fun send(bytes: ByteArray, priority: Priority)   // non-blocking; dropped unless Connected
  }
  class OutgoingQueue(maxOther: Int = 64) {
      fun offer(bytes: ByteArray, priority: Priority)
      fun poll(): ByteArray?
      suspend fun take(): ByteArray
      fun drainStops(): List<ByteArray>
      fun clear()
  }
  ```

- [ ] **Step 1: Write the failing test**

`android-app/core/src/test/kotlin/com/mecanumbot/core/link/OutgoingQueueTest.kt`:
```kotlin
package com.mecanumbot.core.link

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutgoingQueueTest {
    private fun b(v: Int) = byteArrayOf(v.toByte())
    private fun OutgoingQueue.pollInt(): Int? = poll()?.get(0)?.toInt()

    @Test
    fun `STOP before MOTION before OTHER`() {
        val q = OutgoingQueue()
        q.offer(b(3), Priority.OTHER)
        q.offer(b(2), Priority.MOTION)
        q.offer(b(1), Priority.STOP)
        assertEquals(listOf(1, 2, 3, null), List(4) { q.pollInt() })
    }

    @Test
    fun `newer MOTION replaces an unsent one`() {
        val q = OutgoingQueue()
        q.offer(b(1), Priority.MOTION)
        q.offer(b(2), Priority.MOTION)
        assertEquals(2, q.pollInt())
        assertNull(q.poll())
    }

    @Test
    fun `STOP frames are all kept in order`() {
        val q = OutgoingQueue()
        repeat(3) { q.offer(b(it), Priority.STOP) }
        assertEquals(listOf(0, 1, 2), List(3) { q.pollInt() })
    }

    @Test
    fun `OTHER is capped, oldest dropped`() {
        val q = OutgoingQueue(maxOther = 2)
        repeat(3) { q.offer(b(it), Priority.OTHER) }
        assertEquals(listOf(1, 2, null), List(3) { q.pollInt() })
    }

    @Test
    fun `drainStops returns only STOP frames and leaves the rest`() {
        val q = OutgoingQueue()
        q.offer(b(9), Priority.MOTION)
        repeat(3) { q.offer(b(it), Priority.STOP) }
        assertEquals(listOf(0, 1, 2), q.drainStops().map { it[0].toInt() })
        assertEquals(9, q.pollInt())
    }

    @Test
    fun `clear empties everything`() {
        val q = OutgoingQueue()
        q.offer(b(1), Priority.STOP); q.offer(b(2), Priority.MOTION); q.offer(b(3), Priority.OTHER)
        q.clear()
        assertNull(q.poll())
    }

    @Test
    fun `take suspends until a frame is offered`() = runTest {
        val q = OutgoingQueue()
        val taken = async { q.take() }
        runCurrent()
        assertFalse(taken.isCompleted)
        q.offer(b(7), Priority.OTHER)
        runCurrent()
        assertTrue(taken.isCompleted)
        assertEquals(7, taken.await()[0].toInt())
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test --tests 'com.mecanumbot.core.link.OutgoingQueueTest'`
Expected: compilation fails with `Unresolved reference 'OutgoingQueue'`.

- [ ] **Step 3: Implement**

`android-app/core/src/main/kotlin/com/mecanumbot/core/link/Link.kt`:
```kotlin
package com.mecanumbot.core.link

import com.mecanumbot.core.protocol.Frame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState
    data class Error(val message: String) : LinkState
}

/** Outgoing order: STOP > MOTION (DRIVE/MOTOR_RAW) > OTHER — PROTOCOL.md §6. */
enum class Priority { STOP, MOTION, OTHER }

/**
 * Byte transport to the ESP32. Implemented by UsbLink and FakeLink; every hardware access in the
 * app goes through it. The link does not assign seq: RobotSession encodes complete frames.
 */
interface Link {
    val state: StateFlow<LinkState>

    /** Frames accepted by the link's FrameParser. */
    val incoming: Flow<Frame>

    /** Phone-side parser rejects (the phone's crc_err). */
    val parserErrors: StateFlow<Int>

    suspend fun open()

    /** Writes any queued STOP frames first, so STOP ×3 survives a link switch. */
    suspend fun close()

    /** Non-blocking. Dropped unless the link is Connected. */
    fun send(bytes: ByteArray, priority: Priority)
}
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/link/OutgoingQueue.kt`:
```kotlin
package com.mecanumbot.core.link

import kotlinx.coroutines.channels.Channel

/**
 * Outgoing frames by priority. An unsent MOTION frame is replaced by the newer one instead of
 * queuing (PROTOCOL.md §6). Thread-safe: offered from the UI thread, taken by a writer coroutine.
 */
class OutgoingQueue(private val maxOther: Int = 64) {
    private val lock = Any()
    private val stops = ArrayDeque<ByteArray>()
    private var motion: ByteArray? = null
    private val others = ArrayDeque<ByteArray>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    fun offer(bytes: ByteArray, priority: Priority) {
        synchronized(lock) {
            when (priority) {
                Priority.STOP -> stops.addLast(bytes)
                Priority.MOTION -> motion = bytes
                Priority.OTHER -> {
                    if (others.size >= maxOther) others.removeFirst()
                    others.addLast(bytes)
                }
            }
        }
        signal.trySend(Unit)
    }

    fun poll(): ByteArray? = synchronized(lock) {
        stops.removeFirstOrNull() ?: motion?.also { motion = null } ?: others.removeFirstOrNull()
    }

    suspend fun take(): ByteArray {
        while (true) {
            poll()?.let { return it }
            signal.receive()
        }
    }

    fun drainStops(): List<ByteArray> = synchronized(lock) { stops.toList().also { stops.clear() } }

    fun clear() = synchronized(lock) {
        stops.clear()
        motion = null
        others.clear()
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android-app/core/src
git commit -m "Android core: Link interface and priority OutgoingQueue

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Wire conversion, Command and Mecanum

**Files:**
- Create: `android-app/core/src/main/kotlin/com/mecanumbot/core/control/Wire.kt`, `Command.kt`, `Mecanum.kt`
- Test: `android-app/core/src/test/kotlin/com/mecanumbot/core/control/WireTest.kt`, `MecanumTest.kt`

**Interfaces:**
- Consumes: `Config` (Task 3).
- Produces:
  - `object Wire { fun toWire(v: Float): Int; fun fromWire(v: Int): Float }`: toWire clamps to −1..1 and rounds v × 127 half away from zero, like C `roundf`; fromWire treats −128 as −127.
  - `enum class Source(val code: Int) { TEST(0), LOCAL_PAD(1), REMOTE(2) }`
  - `enum class Mode { AUTO, LOCAL_ONLY, REMOTE_ONLY }`
  - `data class Command(val vx: Float, val vy: Float, val w: Float, val enable: Boolean, val source: Source)`
  - `object Mecanum { FL, FR, RL, RR, DEAD_ZONE; fun mix(vx, vy, w): FloatArray; fun toChannels(wheels: FloatArray, c: Config): FloatArray; fun drive(vx: Int, vy: Int, w: Int, enable: Boolean, c: Config): FloatArray }`. This is a port of `firmware/lib/kinematics/src/kinematics.cpp`; channel duties are −1..1 of full PWM, indexed by physical channel.

- [ ] **Step 1: Write the failing tests**

`android-app/core/src/test/kotlin/com/mecanumbot/core/control/WireTest.kt`:
```kotlin
package com.mecanumbot.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WireTest {
    @Test
    fun `toWire rounds half away from zero and clamps`() {
        assertEquals(64, Wire.toWire(0.5f))
        assertEquals(-64, Wire.toWire(-0.5f))
        assertEquals(127, Wire.toWire(1f))
        assertEquals(127, Wire.toWire(2f))
        assertEquals(-127, Wire.toWire(-2f))
        assertEquals(0, Wire.toWire(0f))
        assertEquals(32, Wire.toWire(0.25f))
    }

    @Test
    fun `fromWire treats -128 as -127`() {
        assertEquals(-1f, Wire.fromWire(-128))
        assertEquals(1f, Wire.fromWire(127))
        assertEquals(64 / 127f, Wire.fromWire(64))
    }
}
```

`android-app/core/src/test/kotlin/com/mecanumbot/core/control/MecanumTest.kt`:
```kotlin
package com.mecanumbot.core.control

import com.mecanumbot.core.control.Mecanum.FL
import com.mecanumbot.core.control.Mecanum.FR
import com.mecanumbot.core.control.Mecanum.RL
import com.mecanumbot.core.control.Mecanum.RR
import com.mecanumbot.core.protocol.Config
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.sign

class MecanumTest {
    private fun signs(a: FloatArray) = a.map { it.sign.toInt() }

    @Test
    fun `vx +1 drives right - FL+ FR- RL- RR+ (PROTOCOL 2_1)`() {
        assertEquals(listOf(1, -1, -1, 1), signs(Mecanum.mix(1f, 0f, 0f)))
    }

    @Test
    fun `w +1 turns clockwise - left wheels forward, right back`() {
        val m = Mecanum.mix(0f, 0f, 1f)
        assertEquals(listOf(1, -1, 1, -1), signs(m))
    }

    @Test
    fun `vy +1 drives all wheels forward at full speed`() {
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), Mecanum.mix(0f, 1f, 0f))
    }

    @Test
    fun `diagonal forward-right uses FL and RR only`() {
        val m = Mecanum.mix(1f, 1f, 0f)
        assertEquals(1f, m[FL]); assertEquals(0f, m[FR]); assertEquals(0f, m[RL]); assertEquals(1f, m[RR])
    }

    @Test
    fun `normalizes so the largest wheel is 1`() {
        val m = Mecanum.mix(1f, 1f, 1f) // FL = 3 before scaling
        assertEquals(1f, m[FL], 1e-6f)
        assertEquals(-1f / 3f, m[FR], 1e-6f)
    }

    @Test
    fun `default config maps wheel speed into min_duty-max_duty`() {
        val ch = Mecanum.toChannels(floatArrayOf(1f, 0.5f, -1f, 0f), Config.DEFAULT)
        assertEquals(1f, ch[0], 1e-6f)
        assertEquals(0.15f + 0.5f * 0.85f, ch[1], 1e-6f)
        assertEquals(-1f, ch[2], 1e-6f)
        assertEquals(0f, ch[3])
    }

    @Test
    fun `dead zone below 0_02 gives zero`() {
        assertEquals(0f, Mecanum.toChannels(floatArrayOf(0.01f, 0f, 0f, 0f), Config.DEFAULT)[0])
    }

    @Test
    fun `map, invert and trim are applied per wheel`() {
        val c = Config(map = listOf(1, 0, 3, 2), invert = listOf(0, 1, 0, 0), trim = listOf(50, 100, 100, 100), minDuty = 0)
        val ch = Mecanum.toChannels(floatArrayOf(1f, 1f, 0f, 0f), c)
        assertEquals(0.5f, ch[1], 1e-6f)  // FL → channel 1, trimmed to 50 %
        assertEquals(-1f, ch[0], 1e-6f)   // FR → channel 0, inverted
    }

    @Test
    fun `drive with enable false gives zeros`() {
        assertArrayEquals(FloatArray(4), Mecanum.drive(0, 127, 0, false, Config.DEFAULT))
    }
}
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test --tests 'com.mecanumbot.core.control.*'`
Expected: compilation fails with `Unresolved reference 'Wire'`, `'Mecanum'`.

- [ ] **Step 3: Implement**

`android-app/core/src/main/kotlin/com/mecanumbot/core/control/Wire.kt`:
```kotlin
package com.mecanumbot.core.control

import kotlin.math.floor

/** −1..1 ⇄ i8 wire speed — PROTOCOL.md §2. Same rounding as firmware kin::toWire (roundf). */
object Wire {
    fun toWire(v: Float): Int {
        val r = v.coerceIn(-1f, 1f) * 127f
        return (if (r >= 0f) floor(r + 0.5f) else -floor(-r + 0.5f)).toInt()
    }

    fun fromWire(v: Int): Float = v.coerceIn(-127, 127) / 127f
}
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/control/Command.kt`:
```kotlin
package com.mecanumbot.core.control

/** Command source; the order is the arbiter priority. `code` goes into DRIVE flags bits 1..2. */
enum class Source(val code: Int) { TEST(0), LOCAL_PAD(1), REMOTE(2) }

/** Which sources the arbiter listens to. TEST is always allowed (bench screen on the robot). */
enum class Mode { AUTO, LOCAL_ONLY, REMOTE_ONLY }

/** vx right, vy forward, w clockwise, each −1..1 (PROTOCOL.md §2.1). */
data class Command(val vx: Float, val vy: Float, val w: Float, val enable: Boolean, val source: Source)
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/control/Mecanum.kt`:
```kotlin
package com.mecanumbot.core.control

import com.mecanumbot.core.protocol.Config
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Mecanum mix and per-wheel calibration — port of firmware lib/kinematics (DESIGN.md §4.4). */
object Mecanum {
    const val FL = 0
    const val FR = 1
    const val RL = 2
    const val RR = 3
    const val DEAD_ZONE = 0.02f

    /** Wheel speeds FL, FR, RL, RR scaled so the largest magnitude is at most 1. */
    fun mix(vx: Float, vy: Float, w: Float): FloatArray {
        val wheels = floatArrayOf(vy + vx + w, vy - vx - w, vy - vx + w, vy + vx - w)
        var m = 1f
        for (v in wheels) m = max(m, abs(v))
        return FloatArray(4) { wheels[it] / m }
    }

    /** Wheel speeds → signed duty (−1..1) per physical channel: dead zone, trim, invert, duty range, map. */
    fun toChannels(wheels: FloatArray, c: Config): FloatArray {
        val channels = FloatArray(4)
        val lo = c.minDuty / 100f
        val hi = c.maxDuty / 100f
        for (i in 0 until 4) {
            var v = wheels[i]
            if (abs(v) < DEAD_ZONE) continue
            v *= c.trim[i] / 100f
            if (c.invert[i] != 0) v = -v
            val mag = lo + min(abs(v), 1f) * (hi - lo)
            channels[c.map[i] and 3] = if (v < 0) -mag else mag
        }
        return channels
    }

    /** DRIVE command → channel duties. enable == false gives all zeros. */
    fun drive(vx: Int, vy: Int, w: Int, enable: Boolean, c: Config): FloatArray {
        val wheels = if (enable) mix(Wire.fromWire(vx), Wire.fromWire(vy), Wire.fromWire(w)) else FloatArray(4)
        return toChannels(wheels, c)
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android-app/core/src
git commit -m "Android core: wire conversion, Command, Mecanum mix

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Arbiter

**Files:**
- Create: `android-app/core/src/main/kotlin/com/mecanumbot/core/control/Arbiter.kt`
- Test: `android-app/core/src/test/kotlin/com/mecanumbot/core/control/ArbiterTest.kt`

**Interfaces:**
- Consumes: `Command`, `Source`, `Mode`, `Wire` (Task 6).
- Produces:
  ```kotlin
  sealed interface Output {
      data class Drive(val flags: Int, val vx: Int, val vy: Int, val w: Int) : Output
      data class Raw(val m: List<Int>) : Output
  }
  class Arbiter(timeoutMs: Long = 300) {
      val mode: Mode
      fun update(command: Command, now: Long)
      fun setRaw(m: List<Int>, now: Long)     // clamped to −127..127
      fun clearRaw()
      fun setMode(mode: Mode)
      fun stop()                             // clears RAW and every source command
      fun activeSource(now: Long): Source?
      fun tick(now: Long): Output
  }
  ```
  A source is active when the mode allows it, it was updated less than `timeoutMs` ago, and its command has `enable = true`. Priority follows `Source` order. With no active source, `tick` returns the zero pulse `Output.Drive(0, 0, 0, 0)`.

- [ ] **Step 1: Write the failing test**

`android-app/core/src/test/kotlin/com/mecanumbot/core/control/ArbiterTest.kt`:
```kotlin
package com.mecanumbot.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArbiterTest {
    private val pulse = Output.Drive(0, 0, 0, 0)
    private fun cmd(source: Source, vy: Float = 0.5f, enable: Boolean = true) = Command(0f, vy, 0f, enable, source)

    @Test
    fun `no source gives the zero pulse`() {
        assertEquals(pulse, Arbiter().tick(0))
    }

    @Test
    fun `flags carry enable and source like the vectors`() {
        val a = Arbiter()
        a.update(Command(-1f, -1f, -1f, true, Source.REMOTE), 0)
        assertEquals(Output.Drive(5, -127, -127, -127), a.tick(0))   // drive_remote_full_negative
        a.update(Command(0f, 0f, 1f, true, Source.LOCAL_PAD), 0)
        assertEquals(Output.Drive(3, 0, 0, 127), a.tick(0))          // drive_local_pad_turn
        a.update(cmd(Source.TEST), 0)
        assertEquals(Output.Drive(1, 0, 64, 0), a.tick(0))           // drive_forward_half
    }

    @Test
    fun `TEST beats LOCAL_PAD beats REMOTE`() {
        val a = Arbiter()
        a.update(cmd(Source.REMOTE), 0)
        a.update(cmd(Source.LOCAL_PAD), 0)
        assertEquals(Source.LOCAL_PAD, a.activeSource(0))
        a.update(cmd(Source.TEST), 0)
        assertEquals(Source.TEST, a.activeSource(0))
    }

    @Test
    fun `source expires 300 ms after its last update`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST), 1000)
        assertEquals(Source.TEST, a.activeSource(1299))
        assertNull(a.activeSource(1300))
        assertEquals(pulse, a.tick(1300))
    }

    @Test
    fun `enable false is not active and lets a lower source through`() {
        val a = Arbiter()
        a.update(cmd(Source.LOCAL_PAD), 0)
        a.update(cmd(Source.TEST, enable = false), 0)
        assertEquals(Source.LOCAL_PAD, a.activeSource(0))
    }

    @Test
    fun `latest command wins - a new speed limit applies on the next tick`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST, vy = 0.5f), 0)
        a.update(cmd(Source.TEST, vy = 0.25f), 0)
        assertEquals(Output.Drive(1, 0, 32, 0), a.tick(0))
    }

    @Test
    fun `LOCAL_ONLY ignores REMOTE, REMOTE_ONLY ignores LOCAL_PAD, TEST always allowed`() {
        val a = Arbiter()
        a.update(cmd(Source.REMOTE), 0)
        a.setMode(Mode.LOCAL_ONLY)
        assertNull(a.activeSource(0))
        a.update(cmd(Source.LOCAL_PAD), 0)
        a.setMode(Mode.REMOTE_ONLY)
        assertEquals(Source.REMOTE, a.activeSource(0))
        a.update(cmd(Source.TEST), 0)
        assertEquals(Source.TEST, a.activeSource(0))
        a.setMode(Mode.LOCAL_ONLY)
        assertEquals(Source.TEST, a.activeSource(0))
    }

    @Test
    fun `RAW overrides DRIVE, is clamped and expires after 300 ms`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST), 0)
        a.setRaw(listOf(200, -200, 64, 0), 0)
        assertEquals(Output.Raw(listOf(127, -127, 64, 0)), a.tick(100))
        a.update(cmd(Source.TEST), 250)
        assertEquals(Output.Drive(1, 0, 64, 0), a.tick(300))  // RAW is stale at 300, TEST is fresh
    }

    @Test
    fun `clearRaw returns to DRIVE`() {
        val a = Arbiter()
        a.setRaw(listOf(10, 10, 10, 10), 0)
        a.clearRaw()
        assertEquals(pulse, a.tick(0))
    }

    @Test
    fun `stop clears every source and RAW`() {
        val a = Arbiter()
        a.update(cmd(Source.TEST), 0)
        a.update(cmd(Source.REMOTE), 0)
        a.setRaw(listOf(1, 2, 3, 4), 0)
        a.stop()
        assertEquals(pulse, a.tick(1))
        assertNull(a.activeSource(1))
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test --tests 'com.mecanumbot.core.control.ArbiterTest'`
Expected: compilation fails with `Unresolved reference 'Arbiter'`, `'Output'`.

- [ ] **Step 3: Implement**

`android-app/core/src/main/kotlin/com/mecanumbot/core/control/Arbiter.kt`:
```kotlin
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
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android-app/core/src
git commit -m "Android core: Arbiter with priorities, modes and 300 ms expiry

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: RobotSession (handshake, 40 Hz tick, PING, STOP ×3, reboot detection)

**Files:**
- Create: `android-app/core/src/main/kotlin/com/mecanumbot/core/session/SessionEvent.kt`, `SessionState.kt`, `RobotSession.kt`
- Test: `android-app/core/src/test/kotlin/com/mecanumbot/core/session/ScriptedLink.kt`, `RobotSessionHandshakeTest.kt`, `RobotSessionReadyTest.kt`

**Interfaces:**
- Consumes:
  - `Link`, `LinkState`, `Priority` (Task 5)
  - `Arbiter`, `Output`, `Command`, `Mode`, `Source` (Tasks 6–7)
  - `FrameCodec` and every payload, `Protocol`, `Config`, `TelemetryFlags` (Tasks 1–3)
- Produces:
  ```kotlin
  enum class Phase { DISCONNECTED, HANDSHAKING, VERSION_MISMATCH, READY }
  sealed interface SessionEvent {
      data object NotResponding
      data class VersionMismatch(val espProtoVer: Int)
      data class Rebooted(val resetReason: Int, val resetCount: Int)
      data class EspLog(val level: Int, val text: String)
  }
  data class TelemetryState(val telemetry: Telemetry, val receivedAt: Long)   // failsafe, faultA, faultB, rawMode, otaMode, wifiMode, enabled
  data class SessionState(link, phase, helloAck: HelloAck?, telemetry: TelemetryState?, config: Config?,
                          rttMs: Long?, motionSentPerSec: Int, phoneParserErrors: Int, activeSource: Source?, lastEvent: SessionEvent?)
  class RobotSession(link: Link, parentScope: CoroutineScope, clock: () -> Long,
                     appMajor: Int = 0, appMinor: Int = 1, arbiter: Arbiter = Arbiter()) {
      val state: StateFlow<SessionState>
      val events: SharedFlow<SessionEvent>
      fun start(); fun close()
      fun update(command: Command); fun setRaw(m: List<Int>); fun clearRaw(); fun setMode(mode: Mode)
      fun stop()
      companion object { const val TICK_MS = 25L }
  }
  ```
  **Threading:** RobotSession is not thread-safe. Call it only from the dispatcher of `parentScope`: the app uses `Dispatchers.Main.immediate`, and tests use the `runTest` scheduler.

- [ ] **Step 1: Write the test double and the failing tests**

`android-app/core/src/test/kotlin/com/mecanumbot/core/session/ScriptedLink.kt`:
```kotlin
package com.mecanumbot.core.session

import com.mecanumbot.core.link.Link
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.FrameParser
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/** In-memory Link: records what the session sends (decoded) and injects frames from the "ESP32". */
class ScriptedLink : Link {
    data class Sent(val frame: Frame, val priority: Priority) {
        val payload: Payload get() = FrameCodec.decode(frame)!!
    }

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state
    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
    override val incoming: Flow<Frame> = _incoming
    override val parserErrors = MutableStateFlow(0)

    private val parser = FrameParser()
    val sent = mutableListOf<Sent>()
    var sendsWhileDisconnected = 0
        private set

    override suspend fun open() = connect()
    override suspend fun close() = disconnect()

    fun connect() { _state.value = LinkState.Connected }
    fun disconnect() { _state.value = LinkState.Disconnected }

    override fun send(bytes: ByteArray, priority: Priority) {
        if (_state.value != LinkState.Connected) { sendsWhileDisconnected++; return }
        parser.feed(bytes).forEach { sent += Sent(it, priority) }
    }

    fun receive(p: Payload, seq: Int = 0) {
        check(_incoming.tryEmit(Frame(p.type, seq, FrameCodec.encodePayload(p))))
    }

    fun payloads(): List<Payload> = sent.map { it.payload }
    fun ofType(type: FrameType): List<Sent> = sent.filter { it.frame.type == type }
    fun lastMotion(): Payload =
        sent.last { it.frame.type == FrameType.DRIVE || it.frame.type == FrameType.MOTOR_RAW }.payload
}

fun TestScope.newSession(link: ScriptedLink): RobotSession =
    RobotSession(link, backgroundScope, { testScheduler.currentTime }).also {
        it.start()
        runCurrent()
    }

/** Session in READY at the current virtual time, with the send log cleared. */
fun TestScope.readySession(link: ScriptedLink, resetCount: Int = 5): RobotSession {
    val s = newSession(link)
    link.connect(); runCurrent()
    link.receive(HelloAck(Protocol.PROTO_VER, 0, 1, 1, resetCount)); runCurrent()
    check(s.state.value.phase == Phase.READY)
    link.sent.clear()
    return s
}
```

`android-app/core/src/test/kotlin/com/mecanumbot/core/session/RobotSessionHandshakeTest.kt`:
```kotlin
package com.mecanumbot.core.session

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.Hello
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RobotSessionHandshakeTest {
    private val telemetry = Telemetry(4, 0x40, listOf(40, -40, 40, -40), 3580, 2, 40, 3600, 0, 1, 850)

    @Test
    fun `sends HELLO on connect and no motion before HELLO_ACK`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        assertEquals(listOf<Payload>(Hello(1, 0, 1)), link.payloads())
        s.update(Command(0f, 1f, 0f, true, Source.TEST))
        advanceTimeBy(900); runCurrent()
        assertEquals(Phase.HANDSHAKING, s.state.value.phase)
        assertTrue(link.ofType(FrameType.DRIVE).isEmpty())
        assertTrue(link.ofType(FrameType.MOTOR_RAW).isEmpty())
    }

    @Test
    fun `retries HELLO every second, reports not responding at 3 s, then retries every 3 s`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        advanceTimeBy(2_500); runCurrent()
        assertEquals(3, link.ofType(FrameType.HELLO).size)
        assertNull(s.state.value.lastEvent)
        advanceTimeBy(600); runCurrent() // t = 3100
        assertEquals(4, link.ofType(FrameType.HELLO).size)
        assertEquals(SessionEvent.NotResponding, s.state.value.lastEvent)
        advanceTimeBy(2_800); runCurrent() // t = 5900
        assertEquals(4, link.ofType(FrameType.HELLO).size)
        advanceTimeBy(200); runCurrent() // t = 6100
        assertEquals(5, link.ofType(FrameType.HELLO).size)
    }

    @Test
    fun `matching HELLO_ACK enters READY, asks for config, starts DRIVE and stops HELLO`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        link.receive(HelloAck(1, 0, 1, 1, 5)); runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
        assertEquals(HelloAck(1, 0, 1, 1, 5), s.state.value.helloAck)
        assertEquals(1, link.ofType(FrameType.GET_CONFIG).size)
        advanceTimeBy(100); runCurrent()
        assertTrue(link.ofType(FrameType.DRIVE).isNotEmpty())
        val hellos = link.ofType(FrameType.HELLO).size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(hellos, link.ofType(FrameType.HELLO).size)
    }

    @Test
    fun `version mismatch blocks motion`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        link.receive(HelloAck(2, 0, 1, 1, 5)); runCurrent()
        assertEquals(Phase.VERSION_MISMATCH, s.state.value.phase)
        assertEquals(SessionEvent.VersionMismatch(2), s.state.value.lastEvent)
        s.update(Command(0f, 1f, 0f, true, Source.TEST))
        advanceTimeBy(2_000); runCurrent()
        assertTrue(link.ofType(FrameType.DRIVE).isEmpty())
        assertTrue(link.ofType(FrameType.MOTOR_RAW).isEmpty())
    }

    @Test
    fun `a matching HELLO_ACK after a mismatch enters READY`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        link.receive(HelloAck(2, 0, 1, 1, 5)); runCurrent()
        link.receive(HelloAck(1, 0, 2, 1, 5)); runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
    }

    @Test
    fun `reboot across a reconnect is reported and stops the robot`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link, resetCount = 5)
        link.disconnect(); runCurrent()
        assertEquals(Phase.DISCONNECTED, s.state.value.phase)
        link.connect(); runCurrent()
        link.sent.clear()
        link.receive(HelloAck(1, 0, 1, 12, 6)); runCurrent()
        assertEquals(SessionEvent.Rebooted(12, 6), s.state.value.lastEvent)
        val stops = link.ofType(FrameType.STOP)
        assertEquals(3, stops.size)
        assertTrue(stops.all { it.priority == Priority.STOP })
        assertEquals(Phase.READY, s.state.value.phase)
    }

    @Test
    fun `same reset_count after a reconnect is not a reboot`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link, resetCount = 5)
        link.disconnect(); runCurrent()
        link.connect(); runCurrent()
        link.receive(HelloAck(1, 0, 1, 1, 5)); runCurrent()
        assertNull(s.state.value.lastEvent)
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
        assertEquals(Phase.READY, s.state.value.phase)
    }

    @Test
    fun `duplicate HELLO_ACK in READY is ignored and driving continues`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link, resetCount = 5)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        link.receive(HelloAck(1, 0, 1, 1, 5)); runCurrent()
        advanceTimeBy(30); runCurrent()
        assertNull(s.state.value.lastEvent)
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
        assertEquals(Drive(1, 0, 64, 0), link.lastMotion())
    }

    @Test
    fun `reboot while READY sends STOP x3 and clears commands`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link, resetCount = 5)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        link.receive(HelloAck(1, 0, 1, 3, 6)); runCurrent()
        assertEquals(SessionEvent.Rebooted(3, 6), s.state.value.lastEvent)
        assertEquals(3, link.ofType(FrameType.STOP).size)
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `link loss resets to DISCONNECTED and the tick stops`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        link.receive(telemetry); runCurrent()
        assertNotNull(s.state.value.telemetry)
        link.disconnect(); runCurrent()
        assertEquals(Phase.DISCONNECTED, s.state.value.phase)
        assertNull(s.state.value.telemetry)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, link.sendsWhileDisconnected)
        link.sent.clear()
        link.connect(); runCurrent()
        assertEquals(listOf<Payload>(Hello(1, 0, 1)), link.payloads())
    }
}
```

`android-app/core/src/test/kotlin/com/mecanumbot/core/session/RobotSessionReadyTest.kt`:
```kotlin
package com.mecanumbot.core.session

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.ConfigData
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.Log
import com.mecanumbot.core.protocol.MotorRaw
import com.mecanumbot.core.protocol.Ping
import com.mecanumbot.core.protocol.Pong
import com.mecanumbot.core.protocol.Telemetry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RobotSessionReadyTest {
    private val telemetry = Telemetry(4, 0x40, listOf(40, -40, 40, -40), 3580, 2, 40, 3600, 0, 1, 850)

    @Test
    fun `DRIVE is sent at 40 Hz`() = runTest {
        val link = ScriptedLink()
        readySession(link)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(link.ofType(FrameType.DRIVE).size in 39..41, "${link.ofType(FrameType.DRIVE).size}")
    }

    @Test
    fun `without an active source every DRIVE is the zero pulse`() = runTest {
        val link = ScriptedLink()
        readySession(link)
        advanceTimeBy(500); runCurrent()
        assertTrue(link.ofType(FrameType.DRIVE).all { it.payload == Drive(0, 0, 0, 0) })
        assertTrue(link.ofType(FrameType.DRIVE).all { it.priority == Priority.MOTION })
    }

    @Test
    fun `TEST command drives and expires 300 ms after the last update`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(1, 0, 64, 0), link.lastMotion())
        assertEquals(Source.TEST, s.state.value.activeSource)
        advanceTimeBy(300); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
        assertNull(s.state.value.activeSource)
    }

    @Test
    fun `latest command wins - a new speed limit applies on the next frame`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 0.5f, 0f, true, Source.TEST))
        advanceTimeBy(30); runCurrent()
        s.update(Command(0f, 0.25f, 0f, true, Source.TEST))
        advanceTimeBy(25); runCurrent()
        assertEquals(Drive(1, 0, 32, 0), link.lastMotion())
        assertTrue(link.ofType(FrameType.STOP).isEmpty())
    }

    @Test
    fun `RAW sends MOTOR_RAW until it goes stale`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.setRaw(listOf(127, -127, 64, 0))
        advanceTimeBy(30); runCurrent()
        assertEquals(MotorRaw(listOf(127, -127, 64, 0)), link.lastMotion())
        advanceTimeBy(300); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `stop sends STOP x3 at once, then the tick sends the zero pulse`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        s.update(Command(0f, 1f, 0f, true, Source.TEST))
        advanceTimeBy(30); runCurrent()
        link.sent.clear()
        s.stop()
        assertEquals(List(3) { FrameType.STOP }, link.sent.map { it.frame.type })
        assertTrue(link.sent.all { it.priority == Priority.STOP })
        advanceTimeBy(30); runCurrent()
        assertEquals(Drive(0, 0, 0, 0), link.lastMotion())
    }

    @Test
    fun `stop while handshaking still sends STOP x3, while disconnected sends nothing`() = runTest {
        val link = ScriptedLink()
        val s = newSession(link)
        link.connect(); runCurrent()
        link.sent.clear()
        s.stop()
        assertEquals(3, link.ofType(FrameType.STOP).size)
        link.disconnect(); runCurrent()
        s.stop()
        assertEquals(0, link.sendsWhileDisconnected)
    }

    @Test
    fun `PING every second and RTT from the matching PONG`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        advanceTimeBy(1_001); runCurrent()
        val ping = link.ofType(FrameType.PING).last().payload as Ping
        assertEquals(1_000L, ping.ts)
        advanceTimeBy(6); runCurrent() // t = 1007
        link.receive(Pong(ping.ts)); runCurrent()
        assertEquals(7L, s.state.value.rttMs)
    }

    @Test
    fun `telemetry, config and log reach state and events`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        val events = mutableListOf<SessionEvent>()
        backgroundScope.launch { s.events.collect { events += it } }
        runCurrent()
        link.receive(telemetry)
        link.receive(ConfigData(Config(failsafeMs = 500)))
        link.receive(Log(2, "boot ok"))
        runCurrent()
        val t = s.state.value.telemetry!!
        assertEquals(telemetry, t.telemetry)
        assertTrue(t.enabled)
        assertFalse(t.failsafe)
        assertEquals(500, s.state.value.config!!.failsafeMs)
        assertEquals(listOf<SessionEvent>(SessionEvent.EspLog(2, "boot ok")), events)
        assertNull(s.state.value.lastEvent) // LOG lines don't replace the status-bar event
    }

    @Test
    fun `motion frames per second are reported`() = runTest {
        val link = ScriptedLink()
        val s = readySession(link)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(s.state.value.motionSentPerSec in 39..41, "${s.state.value.motionSentPerSec}")
    }

    @Test
    fun `seq increases by one per frame and wraps after 255`() = runTest {
        val link = ScriptedLink()
        readySession(link)
        advanceTimeBy(8_000); runCurrent()
        val seqs = link.sent.map { it.frame.seq }
        assertTrue(seqs.size > 256)
        seqs.zipWithNext().forEach { (a, b) -> assertEquals((a + 1) and 0xFF, b) }
    }
}
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test --tests 'com.mecanumbot.core.session.*'`
Expected: compilation fails with `Unresolved reference 'RobotSession'`, `'Phase'`, `'SessionEvent'`.

- [ ] **Step 3: Implement**

`android-app/core/src/main/kotlin/com/mecanumbot/core/session/SessionEvent.kt`:
```kotlin
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
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/session/SessionState.kt`:
```kotlin
package com.mecanumbot.core.session

import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Telemetry
import com.mecanumbot.core.protocol.TelemetryFlags

enum class Phase { DISCONNECTED, HANDSHAKING, VERSION_MISMATCH, READY }

data class TelemetryState(val telemetry: Telemetry, val receivedAt: Long) {
    val failsafe: Boolean get() = has(TelemetryFlags.FAILSAFE)
    val faultA: Boolean get() = has(TelemetryFlags.FAULT_A)
    val faultB: Boolean get() = has(TelemetryFlags.FAULT_B)
    val rawMode: Boolean get() = has(TelemetryFlags.RAW)
    val otaMode: Boolean get() = has(TelemetryFlags.OTA)
    val wifiMode: Boolean get() = has(TelemetryFlags.WIFI)
    val enabled: Boolean get() = has(TelemetryFlags.ENABLE)

    private fun has(bit: Int) = telemetry.flags and bit != 0
}

data class SessionState(
    val link: LinkState = LinkState.Disconnected,
    val phase: Phase = Phase.DISCONNECTED,
    val helloAck: HelloAck? = null,
    val telemetry: TelemetryState? = null,
    val config: Config? = null,
    val rttMs: Long? = null,
    /** DRIVE/MOTOR_RAW frames sent during the last second; compare with telemetry rx_frames. */
    val motionSentPerSec: Int = 0,
    val phoneParserErrors: Int = 0,
    val activeSource: Source? = null,
    val lastEvent: SessionEvent? = null,
)
```

`android-app/core/src/main/kotlin/com/mecanumbot/core/session/RobotSession.kt`:
```kotlin
package com.mecanumbot.core.session

import com.mecanumbot.core.control.Arbiter
import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Mode
import com.mecanumbot.core.control.Output
import com.mecanumbot.core.link.Link
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.ConfigData
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.GetConfig
import com.mecanumbot.core.protocol.Hello
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Log
import com.mecanumbot.core.protocol.MotorRaw
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Ping
import com.mecanumbot.core.protocol.Pong
import com.mecanumbot.core.protocol.Protocol
import com.mecanumbot.core.protocol.Stop
import com.mecanumbot.core.protocol.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The phone side of the protocol — PROTOCOL.md §6, spec §6. Owns seq, the handshake, the 40 Hz
 * tick through the [Arbiter], PING/RTT and STOP ×3. Not thread-safe: call it from the dispatcher
 * of [parentScope].
 */
class RobotSession(
    private val link: Link,
    parentScope: CoroutineScope,
    private val clock: () -> Long,
    private val appMajor: Int = 0,
    private val appMinor: Int = 1,
    private val arbiter: Arbiter = Arbiter(),
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private var seq = 0
    private var phaseJob: Job? = null
    private var lastResetCount: Int? = null
    private var motionSent = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { link.state.collect { onLinkState(it) } }
        scope.launch { link.incoming.collect { onFrame(it) } }
        scope.launch { link.parserErrors.collect { n -> _state.update { it.copy(phoneParserErrors = n) } } }
    }

    fun close() {
        job.cancel()
    }

    fun update(command: Command) = arbiter.update(command, clock())
    fun setRaw(m: List<Int>) = arbiter.setRaw(m, clock())
    fun clearRaw() = arbiter.clearRaw()
    fun setMode(mode: Mode) = arbiter.setMode(mode)

    /** Clears every command and sends STOP ×3 at once, in any phase while the link is up. */
    fun stop() {
        arbiter.stop()
        if (link.state.value == LinkState.Connected) repeat(3) { send(Stop, Priority.STOP) }
    }

    private fun send(p: Payload, priority: Priority) {
        link.send(FrameCodec.encode(p, seq), priority)
        seq = (seq + 1) and 0xFF
    }

    private fun onLinkState(s: LinkState) {
        _state.update { it.copy(link = s) }
        if (s == LinkState.Connected) {
            // A HELLO_ACK may already have moved us on (it can be collected before the state).
            if (_state.value.phase == Phase.DISCONNECTED) enterHandshake()
        } else if (_state.value.phase != Phase.DISCONNECTED) {
            enterDisconnected()
        }
    }

    private fun enterDisconnected() {
        phaseJob?.cancel()
        phaseJob = null
        arbiter.stop()
        _state.update {
            it.copy(phase = Phase.DISCONNECTED, telemetry = null, rttMs = null, motionSentPerSec = 0, activeSource = null)
        }
    }

    private fun enterHandshake() {
        phaseJob?.cancel()
        _state.update { it.copy(phase = Phase.HANDSHAKING) }
        phaseJob = scope.launch {
            var attempt = 0
            while (true) {
                send(Hello(Protocol.PROTO_VER, appMajor, appMinor), Priority.OTHER)
                attempt++
                delay(if (attempt <= 3) 1_000 else 3_000)
                if (attempt == 3) report(SessionEvent.NotResponding)
            }
        }
    }

    private fun enterReady() {
        phaseJob?.cancel()
        _state.update { it.copy(phase = Phase.READY) }
        send(GetConfig, Priority.OTHER)
        phaseJob = scope.launch {
            launch {
                while (true) {
                    tick()
                    delay(TICK_MS)
                }
            }
            launch {
                while (true) {
                    send(Ping(clock() and 0xFFFFFFFFL), Priority.OTHER)
                    delay(1_000)
                }
            }
            launch {
                while (true) {
                    delay(1_000)
                    val n = motionSent
                    motionSent = 0
                    _state.update { it.copy(motionSentPerSec = n) }
                }
            }
        }
    }

    private fun tick() {
        val now = clock()
        when (val out = arbiter.tick(now)) {
            is Output.Drive -> send(Drive(out.flags, out.vx, out.vy, out.w), Priority.MOTION)
            is Output.Raw -> send(MotorRaw(out.m), Priority.MOTION)
        }
        motionSent++
        val active = arbiter.activeSource(now)
        if (active != _state.value.activeSource) _state.update { it.copy(activeSource = active) }
    }

    private fun onFrame(frame: Frame) {
        if (link.state.value != LinkState.Connected) return
        when (val p = FrameCodec.decode(frame)) {
            is HelloAck -> onHelloAck(p)
            is Telemetry -> _state.update { it.copy(telemetry = TelemetryState(p, clock())) }
            is Pong -> _state.update { it.copy(rttMs = (clock() - p.ts) and 0xFFFFFFFFL) }
            is ConfigData -> _state.update { it.copy(config = p.config) }
            is Log -> report(SessionEvent.EspLog(p.level, p.text))
            else -> Unit // ACK and the rest are used from stage 4 on
        }
    }

    private fun onHelloAck(ack: HelloAck) {
        val previous = lastResetCount
        lastResetCount = ack.resetCount
        _state.update { it.copy(helloAck = ack) }
        // reset_count survives reconnects: a real reboot re-enumerates USB, so it shows up here
        // during the handshake. The same count in READY is the duplicate sent on host connect.
        if (previous != null && previous != ack.resetCount) {
            stop()
            report(SessionEvent.Rebooted(ack.resetReason, ack.resetCount))
        }
        if (ack.protoVer != Protocol.PROTO_VER) {
            if (_state.value.phase != Phase.VERSION_MISMATCH) {
                phaseJob?.cancel()
                phaseJob = null
                arbiter.stop()
                _state.update { it.copy(phase = Phase.VERSION_MISMATCH) }
                report(SessionEvent.VersionMismatch(ack.protoVer))
            }
            return
        }
        if (_state.value.phase != Phase.READY) enterReady()
    }

    /** ESP32 LOG lines go to [events] only, so they never hide a reboot or mismatch in lastEvent. */
    private fun report(e: SessionEvent) {
        if (e !is SessionEvent.EspLog) _state.update { it.copy(lastEvent = e) }
        _events.tryEmit(e)
    }

    companion object {
        const val TICK_MS = 25L
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test`
Expected: `BUILD SUCCESSFUL`, with 10 handshake and 11 ready tests passing.

If the retry test is off by one attempt, read the kotlinx-coroutines-test rule before changing the code: `advanceTimeBy(n)` runs tasks scheduled strictly before `currentTime + n`, and `runCurrent()` then runs the ones due exactly now. The test times (2500, 3100, 5900, 6100) avoid those boundaries on purpose.

- [ ] **Step 5: Commit**

```bash
git add android-app/core/src
git commit -m "Android core: RobotSession with handshake, 40 Hz tick, STOP x3, reboot detection

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: FakeEsp32 (firmware model)

**Files:**
- Create: `android-app/fake/src/main/kotlin/com/mecanumbot/fake/FakeEsp32.kt`
- Test: `android-app/fake/src/test/kotlin/com/mecanumbot/fake/FakeEsp32Test.kt`

**Interfaces:**
- Consumes: payloads, `FrameCodec`, `Frame`, `Config`, `AckStatus`, `TelemetryFlags`, `LogLevel`, `Protocol` (Tasks 1–3); `Mecanum`, `Wire` (Task 6).
- Produces:
  ```kotlin
  class FakeEsp32(emit: (Payload) -> Unit) {
      enum class Mode { DRIVE, RAW, OTA, WIFI }
      val mode: Mode; val config: Config; val resetCount: Int; val lastSeq: Int; val stopsReceived: Int
      var protoVer: Int; var faultA: Boolean; var faultB: Boolean; var extraSagMv: Int; var crcErr: Int
      fun boot(now: Long)             // resetCount++ (first boot → 1), failsafe active, DRIVE mode
      fun announce()                  // emits HELLO_ACK
      fun onFrame(frame: Frame, now: Long)
      fun tick(now: Long)             // telemetry every 100 ms, failsafe log, OTA timeout, Wi-Fi connect, reboot
      fun failsafeActive(now: Long): Boolean
      fun pwm(now: Long): List<Int>
      companion object { FW_MAJOR = 0, FW_MINOR = 1, RESET_REASON_SW = 3, VM_NOMINAL_MV = 3600, FULL_LOAD_SAG_MV = 150, FAKE_IP = [192,168,4,2] }
  }
  ```
  It models PROTOCOL §5 on a clock passed in as `now`, with no coroutines. `boot()` must be called before any other call.

- [ ] **Step 1: Write the failing test**

`android-app/fake/src/test/kotlin/com/mecanumbot/fake/FakeEsp32Test.kt`:
```kotlin
package com.mecanumbot.fake

import com.mecanumbot.core.protocol.Ack
import com.mecanumbot.core.protocol.AckStatus
import com.mecanumbot.core.protocol.Bytes
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.ConfigData
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.FrameType
import com.mecanumbot.core.protocol.GetConfig
import com.mecanumbot.core.protocol.Hello
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Log
import com.mecanumbot.core.protocol.MotorRaw
import com.mecanumbot.core.protocol.OtaBegin
import com.mecanumbot.core.protocol.OtaData
import com.mecanumbot.core.protocol.OtaEnd
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Ping
import com.mecanumbot.core.protocol.Pong
import com.mecanumbot.core.protocol.Reboot
import com.mecanumbot.core.protocol.SetConfig
import com.mecanumbot.core.protocol.Stop
import com.mecanumbot.core.protocol.Telemetry
import com.mecanumbot.core.protocol.TelemetryFlags
import com.mecanumbot.core.protocol.WifiOtaEnter
import com.mecanumbot.core.protocol.WifiOtaExit
import com.mecanumbot.core.protocol.WifiStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FakeEsp32Test {
    private val out = mutableListOf<Payload>()
    private val esp = FakeEsp32 { out += it }.also { it.boot(0) }
    private var seq = 0

    /** Sends a frame and returns its seq. */
    private fun send(p: Payload, now: Long): Int {
        val s = seq
        esp.onFrame(Frame(p.type, s, FrameCodec.encodePayload(p)), now)
        seq = (seq + 1) and 0xFF
        return s
    }

    private inline fun <reified T : Payload> last(): T = out.filterIsInstance<T>().last()

    private fun telemetryAt(now: Long): Telemetry {
        out.clear()
        esp.tick(now)
        return last()
    }

    private fun has(t: Telemetry, bit: Int) = t.flags and bit != 0

    @Test
    fun `first boot announces reset_count 1`() {
        esp.announce()
        assertEquals(HelloAck(1, 0, 1, FakeEsp32.RESET_REASON_SW, 1), last<HelloAck>())
    }

    @Test
    fun `HELLO is answered with HELLO_ACK, PING with PONG`() {
        send(Hello(1, 0, 1), 0)
        send(Ping(0xDEADBEEF), 0)
        assertTrue(out[0] is HelloAck)
        assertEquals(Pong(0xDEADBEEF), out[1])
    }

    @Test
    fun `failsafe is active after boot`() {
        val t = telemetryAt(0)
        assertTrue(has(t, TelemetryFlags.FAILSAFE))
        assertEquals(listOf(0, 0, 0, 0), t.pwm)
    }

    @Test
    fun `DRIVE clears failsafe and drives the mix`() {
        esp.tick(0)
        send(Drive(1, 0, 127, 0), 10)
        val t = telemetryAt(100)
        assertFalse(has(t, TelemetryFlags.FAILSAFE))
        assertTrue(has(t, TelemetryFlags.ENABLE))
        assertEquals(listOf(127, 127, 127, 127), t.pwm)
    }

    @Test
    fun `failsafe fires failsafe_ms after the last DRIVE and logs it`() {
        send(Drive(1, 0, 127, 0), 0)
        assertFalse(esp.failsafeActive(299))
        assertTrue(esp.failsafeActive(300))
        esp.tick(0)
        out.clear()
        esp.tick(300)
        assertTrue(out.contains(Log(2, "failsafe on")))
        val t = last<Telemetry>()
        assertTrue(has(t, TelemetryFlags.FAILSAFE))
        assertEquals(listOf(0, 0, 0, 0), t.pwm)
    }

    @Test
    fun `zero pulse keeps failsafe off with motors stopped`() {
        esp.tick(0)
        for (t in 0L..1000L step 25) send(Drive(0, 0, 0, 0), t)
        val t = telemetryAt(1000)
        assertFalse(has(t, TelemetryFlags.FAILSAFE))
        assertFalse(has(t, TelemetryFlags.ENABLE))
        assertEquals(listOf(0, 0, 0, 0), t.pwm)
    }

    @Test
    fun `STOP is acknowledged, zeroes output and leaves RAW mode`() {
        send(MotorRaw(listOf(127, 0, 0, 0)), 0)
        assertEquals(FakeEsp32.Mode.RAW, esp.mode)
        val s = send(Stop, 5)
        assertEquals(Ack(FrameType.STOP.code, s, AckStatus.OK), last<Ack>())
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
        assertEquals(listOf(0, 0, 0, 0), esp.pwm(10))
        assertEquals(1, esp.stopsReceived)
    }

    @Test
    fun `MOTOR_RAW sets raw mode and honours max_duty`() {
        send(SetConfig(Config(maxDuty = 80, minDuty = 15)), 0)
        assertEquals(AckStatus.OK, last<Ack>().status)
        send(MotorRaw(listOf(127, -127, 0, 64)), 0)
        val t = telemetryAt(10)
        assertTrue(has(t, TelemetryFlags.RAW))
        assertEquals(listOf(102, -102, 0, 51), t.pwm) // 64/127 × 0.8 × 127 = 51.2
    }

    @Test
    fun `invalid CONFIG is rejected and not applied`() {
        val s = send(SetConfig(Config(failsafeMs = 50)), 0)
        assertEquals(Ack(FrameType.CONFIG.code, s, AckStatus.ERR), last<Ack>())
        send(GetConfig, 0)
        assertEquals(ConfigData(Config.DEFAULT), last<ConfigData>())
    }

    @Test
    fun `OTA flow with duplicate and wrong offsets, then reboot`() {
        assertEquals(AckStatus.OK, run { send(OtaBegin(10, Bytes(ByteArray(32))), 0); last<Ack>().status })
        assertEquals(FakeEsp32.Mode.OTA, esp.mode)
        send(Drive(1, 0, 127, 0), 1)
        assertEquals(listOf(0, 0, 0, 0), esp.pwm(1))
        send(SetConfig(Config.DEFAULT), 1)
        assertEquals(AckStatus.BUSY, last<Ack>().status)
        send(OtaData(0, Bytes(ByteArray(6))), 2); assertEquals(AckStatus.OK, last<Ack>().status)
        send(OtaData(0, Bytes(ByteArray(6))), 3); assertEquals(AckStatus.OK, last<Ack>().status) // lost ACK repeat
        send(OtaData(3, Bytes(ByteArray(3))), 4); assertEquals(AckStatus.ERR, last<Ack>().status)
        send(OtaData(6, Bytes(ByteArray(4))), 5); assertEquals(AckStatus.OK, last<Ack>().status)
        send(OtaEnd, 6); assertEquals(AckStatus.OK, last<Ack>().status)
        out.clear()
        esp.tick(206)
        assertEquals(2, last<HelloAck>().resetCount)
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
    }

    @Test
    fun `OTA_END with missing bytes fails and returns to DRIVE`() {
        send(OtaBegin(10, Bytes(ByteArray(32))), 0)
        send(OtaData(0, Bytes(ByteArray(4))), 1)
        send(OtaEnd, 2)
        assertEquals(AckStatus.ERR, last<Ack>().status)
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
    }

    @Test
    fun `OTA_BEGIN with size 0 is an error`() {
        send(OtaBegin(0, Bytes(ByteArray(32))), 0)
        assertEquals(AckStatus.ERR, last<Ack>().status)
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
    }

    @Test
    fun `OTA times out after 5 s without data`() {
        send(OtaBegin(10, Bytes(ByteArray(32))), 0)
        esp.tick(4_999)
        assertEquals(FakeEsp32.Mode.OTA, esp.mode)
        esp.tick(5_000)
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
    }

    @Test
    fun `Wi-Fi OTA - ACK, connecting, connected, BUSY for OTA, exit, saved credentials`() {
        send(WifiOtaEnter("", ""), 0)
        assertEquals(AckStatus.ERR, last<Ack>().status) // nothing saved yet
        out.clear()
        send(WifiOtaEnter("net", "pw"), 0)
        // ACK first, then WIFI_STATUS "connecting" (PROTOCOL.md §4.1); a mode LOG may come before both.
        val ack = out.indexOfFirst { it is Ack }
        val connecting = out.indexOf(WifiStatus(1, listOf(0, 0, 0, 0)))
        assertEquals(AckStatus.OK, (out[ack] as Ack).status)
        assertTrue(ack in 0 until connecting)
        esp.tick(500)
        assertEquals(WifiStatus(2, FakeEsp32.FAKE_IP), last<WifiStatus>())
        send(OtaBegin(10, Bytes(ByteArray(32))), 600)
        assertEquals(AckStatus.BUSY, last<Ack>().status)
        send(WifiOtaExit, 700)
        assertEquals(WifiStatus(0, listOf(0, 0, 0, 0)), last<WifiStatus>())
        assertEquals(FakeEsp32.Mode.DRIVE, esp.mode)
        send(WifiOtaEnter("", ""), 800)
        assertEquals(AckStatus.OK, last<Ack>().status)
    }

    @Test
    fun `REBOOT restarts - reset_count grows, uptime and failsafe reset`() {
        send(Drive(1, 0, 127, 0), 5_000)
        send(Reboot, 5_000)
        out.clear()
        esp.tick(5_000)
        assertEquals(2, last<HelloAck>().resetCount)
        val t = telemetryAt(5_100) // the reboot tick already sent one; the next is due 100 ms later
        assertEquals(0L, t.uptimeS)
        assertTrue(has(t, TelemetryFlags.FAILSAFE))
    }

    @Test
    fun `rx_frames counts the last second, VM sags with load and fault`() {
        esp.tick(0)
        for (t in 0L until 1000L step 25) send(Drive(1, 0, 127, 0), t)
        val t = telemetryAt(1000)
        assertEquals(39, t.rxFrames) // the frame sent at t = 0 has left the 1 s window
        assertEquals(FakeEsp32.VM_NOMINAL_MV - FakeEsp32.FULL_LOAD_SAG_MV, t.vmMv)
        esp.extraSagMv = 500
        esp.faultA = true
        val t2 = telemetryAt(1100)
        assertEquals(FakeEsp32.VM_NOMINAL_MV - FakeEsp32.FULL_LOAD_SAG_MV - 500, t2.vmMv)
        assertTrue(has(t2, TelemetryFlags.FAULT_A))
        assertFalse(has(t2, TelemetryFlags.FAULT_B))
    }

    @Test
    fun `wrong proto_ver is reported in HELLO_ACK`() {
        esp.protoVer = 2
        send(Hello(1, 0, 1), 0)
        assertEquals(2, last<HelloAck>().protoVer)
    }

    @Test
    fun `frames of the ESP32 direction are ignored`() {
        send(Telemetry(0, 0, listOf(0, 0, 0, 0), 0, 0, 0, 0, 0, 1, 0), 0)
        assertTrue(out.isEmpty())
    }
}
```

In the rx_frames test, `telemetryAt(1000)` first prunes entries at least 1000 ms old, so the frame at t = 0 drops out and 39 of the 40 frames remain.

- [ ] **Step 2: Run the test and see it fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :fake:test`
Expected: compilation fails with `Unresolved reference 'FakeEsp32'`.

- [ ] **Step 3: Implement**

`android-app/fake/src/main/kotlin/com/mecanumbot/fake/FakeEsp32.kt`:
```kotlin
package com.mecanumbot.fake

import com.mecanumbot.core.control.Mecanum
import com.mecanumbot.core.control.Wire
import com.mecanumbot.core.protocol.Ack
import com.mecanumbot.core.protocol.AckStatus
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.ConfigData
import com.mecanumbot.core.protocol.Drive
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.GetConfig
import com.mecanumbot.core.protocol.Hello
import com.mecanumbot.core.protocol.HelloAck
import com.mecanumbot.core.protocol.Log
import com.mecanumbot.core.protocol.LogLevel
import com.mecanumbot.core.protocol.MotorRaw
import com.mecanumbot.core.protocol.OtaAbort
import com.mecanumbot.core.protocol.OtaBegin
import com.mecanumbot.core.protocol.OtaData
import com.mecanumbot.core.protocol.OtaEnd
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.Ping
import com.mecanumbot.core.protocol.Pong
import com.mecanumbot.core.protocol.Protocol
import com.mecanumbot.core.protocol.Reboot
import com.mecanumbot.core.protocol.SetConfig
import com.mecanumbot.core.protocol.Stop
import com.mecanumbot.core.protocol.Telemetry
import com.mecanumbot.core.protocol.TelemetryFlags
import com.mecanumbot.core.protocol.WifiOtaEnter
import com.mecanumbot.core.protocol.WifiOtaExit
import com.mecanumbot.core.protocol.WifiStatus
import kotlin.math.abs

/**
 * Model of the ESP32 firmware — PROTOCOL.md §5. Pure: time comes in as `now` (ms), replies go
 * out through [emit]. OTA writes nothing; Wi-Fi "connects" after 500 ms with [FAKE_IP].
 */
class FakeEsp32(private val emit: (Payload) -> Unit) {
    enum class Mode { DRIVE, RAW, OTA, WIFI }

    var mode = Mode.DRIVE
        private set
    var config = Config.DEFAULT
        private set
    var resetCount = 0
        private set
    var lastSeq = 0
        private set
    var stopsReceived = 0
        private set

    var protoVer = Protocol.PROTO_VER
    var faultA = false
    var faultB = false
    var extraSagMv = 0

    /** ESP32-side parser rejects; set by the link that owns the parser. */
    var crcErr = 0

    private class Ota(val size: Long) {
        var received = 0L
        var lastChunk = -1L
        var lastAt = 0L
    }

    private var bootAt = 0L
    private var lastFeed: Long? = null
    private var failsafeOn = true
    private var drive = Drive(0, 0, 0, 0)
    private var raw = ZEROS
    private val rxTimes = ArrayDeque<Long>()
    private var nextTelemetryAt = 0L
    private var ota: Ota? = null
    private var wifiConnectAt: Long? = null
    private var savedWifi: Pair<String, String>? = null
    private var rebootAt: Long? = null

    fun boot(now: Long) {
        resetCount = (resetCount + 1) and 0xFFFF
        bootAt = now
        lastFeed = null
        failsafeOn = true
        mode = Mode.DRIVE
        drive = Drive(0, 0, 0, 0)
        raw = ZEROS
        rxTimes.clear()
        nextTelemetryAt = now
        ota = null
        wifiConnectAt = null
        rebootAt = null
    }

    fun announce() = emit(HelloAck(protoVer, FW_MAJOR, FW_MINOR, RESET_REASON_SW, resetCount))

    fun failsafeActive(now: Long): Boolean = lastFeed?.let { now - it >= config.failsafeMs } ?: true

    fun onFrame(frame: Frame, now: Long) {
        rxTimes.addLast(now)
        val p = FrameCodec.decode(frame) ?: return
        val halted = mode == Mode.OTA || mode == Mode.WIFI
        when (p) {
            is Hello -> announce()
            is Drive -> if (!halted) {
                drive = p
                setMode(Mode.DRIVE)
                lastSeq = frame.seq
                feed(now)
            }
            is MotorRaw -> if (!halted) {
                raw = p.m
                setMode(Mode.RAW)
                lastSeq = frame.seq
                feed(now)
            }
            is Ping -> emit(Pong(p.ts))
            is SetConfig -> ack(
                frame,
                when {
                    halted -> AckStatus.BUSY
                    !p.config.isValid() -> AckStatus.ERR
                    else -> { config = p.config; AckStatus.OK }
                },
            )
            Stop -> {
                stopsReceived++
                drive = Drive(0, 0, 0, 0)
                raw = ZEROS
                if (mode == Mode.RAW) setMode(Mode.DRIVE)
                ack(frame, AckStatus.OK)
            }
            GetConfig -> emit(ConfigData(config))
            is OtaBegin -> ack(
                frame,
                when {
                    mode == Mode.WIFI -> AckStatus.BUSY
                    p.size == 0L -> AckStatus.ERR
                    else -> {
                        ota = Ota(p.size).also { it.lastAt = now }
                        setMode(Mode.OTA)
                        AckStatus.OK
                    }
                },
            )
            is OtaData -> ack(frame, otaData(p, now))
            OtaEnd -> ack(frame, otaEnd(now))
            OtaAbort -> {
                if (mode == Mode.OTA) {
                    ota = null
                    setMode(Mode.DRIVE)
                }
                ack(frame, AckStatus.OK)
            }
            is WifiOtaEnter -> {
                val status = wifiEnter(p, now)
                ack(frame, status)
                if (status == AckStatus.OK) emit(WifiStatus(1, ZEROS))
            }
            WifiOtaExit -> {
                ack(frame, AckStatus.OK)
                if (mode == Mode.WIFI) wifiOff()
            }
            Reboot -> rebootAt = now
            else -> Unit // ESP32 → phone types: accepted by the parser, ignored (PROTOCOL.md §3.1)
        }
    }

    fun tick(now: Long) {
        rebootAt?.let {
            if (now >= it) {
                boot(now)
                announce()
            }
        }
        ota?.let {
            if (now - it.lastAt >= OTA_TIMEOUT_MS) {
                ota = null
                setMode(Mode.DRIVE)
                log(LogLevel.WARN, "ota timeout")
            }
        }
        wifiConnectAt?.let {
            if (now >= it) {
                wifiConnectAt = null
                emit(WifiStatus(2, FAKE_IP))
            }
        }
        if (!failsafeOn && failsafeActive(now)) {
            failsafeOn = true
            log(LogLevel.INFO, "failsafe on")
        }
        while (rxTimes.isNotEmpty() && now - rxTimes.first() >= 1_000) rxTimes.removeFirst()
        if (now >= nextTelemetryAt) {
            emit(telemetry(now))
            nextTelemetryAt = now + TELEMETRY_PERIOD_MS
        }
    }

    /** Output per physical channel (i8), as TELEMETRY pwm[4] reports it. No slew is modelled. */
    fun pwm(now: Long): List<Int> {
        if (mode == Mode.OTA || mode == Mode.WIFI || failsafeActive(now)) return ZEROS
        val channels = when (mode) {
            Mode.RAW -> FloatArray(4) { Wire.fromWire(raw[it]) * config.maxDuty / 100f }
            else -> Mecanum.drive(drive.vx, drive.vy, drive.w, drive.flags and 1 != 0, config)
        }
        return channels.map { Wire.toWire(it) }
    }

    private fun telemetry(now: Long): Telemetry {
        val pwm = pwm(now)
        val failsafe = failsafeActive(now)
        var flags = 0
        if (failsafe) flags = flags or TelemetryFlags.FAILSAFE
        if (faultA) flags = flags or TelemetryFlags.FAULT_A
        if (faultB) flags = flags or TelemetryFlags.FAULT_B
        if (mode == Mode.RAW) flags = flags or TelemetryFlags.RAW
        if (mode == Mode.OTA) flags = flags or TelemetryFlags.OTA
        if (mode == Mode.WIFI) flags = flags or TelemetryFlags.WIFI
        if (mode == Mode.DRIVE && !failsafe && drive.flags and 1 != 0) flags = flags or TelemetryFlags.ENABLE
        val load = pwm.sumOf { abs(it) }
        val vm = (VM_NOMINAL_MV - load * FULL_LOAD_SAG_MV / (4 * 127) - extraSagMv).coerceAtLeast(0)
        return Telemetry(
            lastSeq = lastSeq,
            flags = flags,
            pwm = pwm,
            vmMv = vm,
            crcErr = crcErr.coerceAtMost(0xFFFF),
            rxFrames = rxTimes.size.coerceAtMost(0xFFFF),
            uptimeS = (now - bootAt) / 1_000,
            fwMajor = FW_MAJOR,
            fwMinor = FW_MINOR,
            loopMaxUs = LOOP_MAX_US,
        )
    }

    private fun feed(now: Long) {
        lastFeed = now
        if (failsafeOn) {
            failsafeOn = false
            log(LogLevel.INFO, "failsafe off")
        }
    }

    private fun otaData(p: OtaData, now: Long): Int {
        val o = ota ?: return AckStatus.BUSY
        o.lastAt = now
        return when (p.offset) {
            o.received ->
                if (o.received + p.data.size > o.size) {
                    AckStatus.ERR
                } else {
                    o.lastChunk = o.received
                    o.received += p.data.size
                    AckStatus.OK
                }
            o.lastChunk -> AckStatus.OK // our ACK was lost; the phone repeated the chunk
            else -> AckStatus.ERR
        }
    }

    private fun otaEnd(now: Long): Int {
        val o = ota ?: return AckStatus.BUSY
        ota = null
        return if (o.received == o.size) {
            rebootAt = now + 200
            AckStatus.OK
        } else {
            setMode(Mode.DRIVE)
            AckStatus.ERR
        }
    }

    private fun wifiEnter(p: WifiOtaEnter, now: Long): Int {
        if (mode == Mode.OTA) return AckStatus.BUSY
        val creds = if (p.ssid.isEmpty() && p.pass.isEmpty()) savedWifi ?: return AckStatus.ERR else p.ssid to p.pass
        savedWifi = creds
        setMode(Mode.WIFI)
        wifiConnectAt = now + WIFI_CONNECT_MS
        return AckStatus.OK
    }

    private fun wifiOff() {
        wifiConnectAt = null
        setMode(Mode.DRIVE)
        emit(WifiStatus(0, ZEROS))
    }

    private fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        log(LogLevel.INFO, "mode ${m.name}")
    }

    private fun ack(frame: Frame, status: Int) = emit(Ack(frame.type.code, frame.seq, status))

    private fun log(level: Int, text: String) = emit(Log(level, text))

    companion object {
        const val FW_MAJOR = 0
        const val FW_MINOR = 1
        const val RESET_REASON_SW = 3 // ESP_RST_SW
        const val VM_NOMINAL_MV = 3600
        const val FULL_LOAD_SAG_MV = 150
        const val LOOP_MAX_US = 850
        const val TELEMETRY_PERIOD_MS = 100L
        const val OTA_TIMEOUT_MS = 5_000L
        const val WIFI_CONNECT_MS = 500L
        val FAKE_IP = listOf(192, 168, 4, 2)
        private val ZEROS = listOf(0, 0, 0, 0)
    }
}
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :fake:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android-app/fake/src
git commit -m "Android fake: FakeEsp32 firmware model

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: FakeLink and FaultControls

**Files:**
- Create: `android-app/fake/src/main/kotlin/com/mecanumbot/fake/FakeLink.kt`, `FaultControls.kt`
- Test: `android-app/fake/src/test/kotlin/com/mecanumbot/fake/FakeLinkTest.kt`

**Interfaces:**
- Consumes: `Link`, `LinkState`, `Priority`, `OutgoingQueue` (Task 5); `FrameParser`, `FrameCodec` (Tasks 3–4); `FakeEsp32` (Task 9); `RobotSession`, `Phase`, `SessionEvent` (Task 8, tests only).
- Produces:
  ```kotlin
  class FakeLink(scope: CoroutineScope, clock: () -> Long, random: Random = Random.Default) : Link {
      val esp: FakeEsp32
      val faults: FaultControls
  }
  class FaultControls {
      fun disconnect(); fun reconnect(); fun reboot(); fun injectGarbage()
      var wrongProtoVer: Boolean; var faultA: Boolean; var faultB: Boolean; var vmSagMv: Int; var dropPercent: Int
  }
  ```
  FakeLink runs on the scope's dispatcher: a writer coroutine delivers queued bytes to the model, and a 10 ms ticker drives `esp.tick`. Bytes go through a real `FrameParser` in both directions. The ESP32 boots on the first `open()` only; later opens behave like a USB reopen and just re-announce.

- [ ] **Step 1: Write the failing test**

`android-app/fake/src/test/kotlin/com/mecanumbot/fake/FakeLinkTest.kt`:
```kotlin
package com.mecanumbot.fake

import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.link.Link
import com.mecanumbot.core.protocol.Config
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.Telemetry
import com.mecanumbot.core.protocol.TelemetryFlags
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.core.session.SessionEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class FakeLinkTest {
    private fun TestScope.fakeLink() = FakeLink(backgroundScope, { testScheduler.currentTime }, Random(1))

    private fun TestScope.session(link: Link) =
        RobotSession(link, backgroundScope, { testScheduler.currentTime }).also { it.start(); runCurrent() }

    private suspend fun TestScope.ready(link: FakeLink): RobotSession {
        val s = session(link)
        link.open(); runCurrent()
        advanceTimeBy(150); runCurrent()
        check(s.state.value.phase == Phase.READY) { "not READY: ${s.state.value}" }
        return s
    }

    private fun TestScope.collectTelemetry(link: FakeLink): MutableList<Telemetry> {
        val list = mutableListOf<Telemetry>()
        backgroundScope.launch { link.incoming.collect { f -> (FrameCodec.decode(f) as? Telemetry)?.let { list += it } } }
        runCurrent()
        return list
    }

    private suspend fun TestScope.driveFor(s: RobotSession, ms: Long) {
        repeat((ms / 25).toInt()) {
            s.update(Command(0f, 1f, 0f, true, Source.TEST))
            advanceTimeBy(25); runCurrent()
        }
    }

    @Test
    fun `handshake reaches READY with telemetry and config`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        assertEquals(1, s.state.value.helloAck!!.resetCount)
        assertNotNull(s.state.value.telemetry)
        assertEquals(Config.DEFAULT, s.state.value.config)
        assertNull(s.state.value.lastEvent)
    }

    @Test
    fun `driving clears failsafe, closing the session trips it within failsafe_ms plus one telemetry period`() = runTest {
        val link = fakeLink()
        val telemetry = collectTelemetry(link)
        val s = ready(link)
        driveFor(s, 1_000)
        val driving = telemetry.last()
        assertEquals(0, driving.flags and TelemetryFlags.FAILSAFE)
        assertEquals(listOf(127, 127, 127, 127), driving.pwm)
        s.close() // the app died: no more pulses
        telemetry.clear()
        advanceTimeBy(300 + 100 + 20); runCurrent()
        val last = telemetry.last()
        assertTrue(last.flags and TelemetryFlags.FAILSAFE != 0)
        assertEquals(listOf(0, 0, 0, 0), last.pwm)
    }

    @Test
    fun `releasing the drive button keeps the pulse - motors stop without failsafe`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        driveFor(s, 500)
        advanceTimeBy(600); runCurrent()
        val t = s.state.value.telemetry!!
        assertFalse(t.failsafe)
        assertFalse(t.enabled)
        assertEquals(listOf(0, 0, 0, 0), t.telemetry.pwm)
    }

    @Test
    fun `STOP x3 queued before close reaches the ESP32`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        s.stop()
        link.close()
        assertEquals(3, link.esp.stopsReceived)
    }

    @Test
    fun `Reboot fault is reported and the session sends STOP x3`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.reboot(); runCurrent()
        assertEquals(SessionEvent.Rebooted(FakeEsp32.RESET_REASON_SW, 2), s.state.value.lastEvent)
        advanceTimeBy(50); runCurrent()
        assertEquals(3, link.esp.stopsReceived)
    }

    @Test
    fun `Wrong proto_ver switches to VERSION_MISMATCH and back`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.wrongProtoVer = true; runCurrent()
        assertEquals(Phase.VERSION_MISMATCH, s.state.value.phase)
        link.faults.wrongProtoVer = false; runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
    }

    @Test
    fun `Disconnect and Reconnect return to READY without a reboot event`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.disconnect(); runCurrent()
        assertEquals(Phase.DISCONNECTED, s.state.value.phase)
        link.faults.reconnect(); runCurrent()
        advanceTimeBy(100); runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
        assertNull(s.state.value.lastEvent)
    }

    @Test
    fun `dropping every frame trips the ESP32 failsafe while the session stays READY`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.dropPercent = 100
        advanceTimeBy(500); runCurrent()
        assertEquals(Phase.READY, s.state.value.phase)
        assertTrue(s.state.value.telemetry!!.failsafe)
    }

    @Test
    fun `garbage is counted by the phone parser and telemetry keeps flowing`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.injectGarbage()
        advanceTimeBy(300); runCurrent()
        assertTrue(s.state.value.phoneParserErrors >= 1)
        assertEquals(Phase.READY, s.state.value.phase)
        assertTrue(testScheduler.currentTime - s.state.value.telemetry!!.receivedAt <= 110)
    }

    @Test
    fun `nFAULT and VM sag appear in telemetry`() = runTest {
        val link = fakeLink()
        val s = ready(link)
        link.faults.faultA = true
        link.faults.vmSagMv = 500
        advanceTimeBy(150); runCurrent()
        val t = s.state.value.telemetry!!
        assertTrue(t.faultA)
        assertFalse(t.faultB)
        assertEquals(FakeEsp32.VM_NOMINAL_MV - 500, t.telemetry.vmMv)
    }
}
```

- [ ] **Step 2: Run the test and see it fail**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :fake:test --tests 'com.mecanumbot.fake.FakeLinkTest'`
Expected: compilation fails with `Unresolved reference 'FakeLink'`.

- [ ] **Step 3: Implement**

`android-app/fake/src/main/kotlin/com/mecanumbot/fake/FakeLink.kt`:
```kotlin
package com.mecanumbot.fake

import com.mecanumbot.core.link.Link
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.link.OutgoingQueue
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameCodec
import com.mecanumbot.core.protocol.FrameParser
import com.mecanumbot.core.protocol.Payload
import com.mecanumbot.core.protocol.hexToBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Link to a simulated ESP32 ([FakeEsp32]). Frames travel as bytes through real parsers in both
 * directions, so the UI and the codec are exercised exactly as with UsbLink.
 */
class FakeLink(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) : Link {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    override val incoming: Flow<Frame> = _incoming.asSharedFlow()

    private val _parserErrors = MutableStateFlow(0)
    override val parserErrors: StateFlow<Int> = _parserErrors.asStateFlow()

    private val phoneParser = FrameParser()
    private val espParser = FrameParser()
    private val queue = OutgoingQueue()
    private var espSeq = 0
    private var booted = false
    private var job: Job? = null

    internal var dropPercent = 0

    val esp = FakeEsp32(::fromEsp)
    val faults = FaultControls(this)

    override suspend fun open() = connect()

    override suspend fun close() = disconnect()

    override fun send(bytes: ByteArray, priority: Priority) {
        if (_state.value == LinkState.Connected) queue.offer(bytes, priority)
    }

    internal fun now(): Long = clock()

    internal fun connect() {
        if (_state.value == LinkState.Connected) return
        if (!booted) {
            esp.boot(clock())
            booted = true
        }
        _state.value = LinkState.Connected
        job = scope.launch {
            launch { while (true) toEsp(queue.take()) }
            launch {
                while (true) {
                    val t = clock()
                    esp.tick(t)
                    phoneParser.flushStale(t).forEach(::deliver)
                    espParser.flushStale(t).forEach { esp.onFrame(it, t) }
                    delay(TICK_MS)
                }
            }
        }
        esp.announce() // the firmware sends HELLO_ACK when the host connects (PROTOCOL.md §5.6)
    }

    internal fun disconnect() {
        if (_state.value != LinkState.Connected) return
        queue.drainStops().forEach(::toEsp)
        job?.cancel()
        job = null
        queue.clear()
        _state.value = LinkState.Disconnected
    }

    internal fun injectGarbage() {
        toPhone(GARBAGE)
        espParser.feed(GARBAGE, clock()).forEach { esp.onFrame(it, clock()) }
        esp.crcErr = espParser.crcErr
    }

    private fun toEsp(bytes: ByteArray) {
        if (dropPercent > 0 && random.nextInt(100) < dropPercent) return
        val t = clock()
        espParser.feed(bytes, t).forEach { esp.onFrame(it, t) }
        esp.crcErr = espParser.crcErr
    }

    private fun fromEsp(p: Payload) {
        if (_state.value != LinkState.Connected) return
        val bytes = FrameCodec.encode(p, espSeq)
        espSeq = (espSeq + 1) and 0xFF
        toPhone(bytes)
    }

    private fun toPhone(bytes: ByteArray) {
        phoneParser.feed(bytes, clock()).forEach(::deliver)
        _parserErrors.value = phoneParser.crcErr
    }

    private fun deliver(frame: Frame) {
        _incoming.tryEmit(frame)
    }

    private companion object {
        const val TICK_MS = 10L

        /** Unknown type after a sync byte, then a stray sync byte right before the next real frame. */
        val GARBAGE = "aa55001337aa".hexToBytes()
    }
}
```

`android-app/fake/src/main/kotlin/com/mecanumbot/fake/FaultControls.kt`:
```kotlin
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
```

- [ ] **Step 4: Run the tests and see them pass**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test :fake:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add android-app/fake/src
git commit -m "Android fake: FakeLink with fault injection, end-to-end session tests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: UsbLink

**Files:**
- Create: `android-app/usb/src/main/kotlin/com/mecanumbot/usb/UsbLink.kt`
- Create: `android-app/usb/src/main/res/xml/device_filter.xml`

**Interfaces:**
- Consumes: `Link`, `LinkState`, `Priority`, `OutgoingQueue` (Task 5); `FrameParser`, `Frame` (Tasks 2, 4).
- Produces:
  ```kotlin
  class UsbLink(context: Context, scope: CoroutineScope, clock: () -> Long = { SystemClock.elapsedRealtime() }) : Link {
      fun requestPermission()   // "Request again" after a denial
      companion object { VID = 0x303A, PID = 0x1001, NO_PERMISSION = "No USB permission" }
  }
  ```
  The resource `@xml/device_filter` matches the ESP32-C6 USB Serial/JTAG. `open()` starts a 1 s retry loop that reopens the port after a detach or an I/O error, until `close()`.

This is a thin hardware wrapper with no unit tests (same as the firmware's `lib/link`). It is verified by compiling it here and by the hardware checks in Task 14.

- [ ] **Step 1: Write the device filter**

`android-app/usb/src/main/res/xml/device_filter.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ESP32-C6 USB Serial/JTAG: VID 0x303A, PID 0x1001 (PROTOCOL.md §1). -->
<resources>
    <usb-device vendor-id="12346" product-id="4097" />
</resources>
```

- [ ] **Step 2: Implement UsbLink**

`android-app/usb/src/main/kotlin/com/mecanumbot/usb/UsbLink.kt`:
```kotlin
package com.mecanumbot.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.mecanumbot.core.link.Link
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.link.OutgoingQueue
import com.mecanumbot.core.link.Priority
import com.mecanumbot.core.protocol.Frame
import com.mecanumbot.core.protocol.FrameParser
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * CDC-ACM link to the ESP32-C6 (spec §8). DTR/RTS are never touched: toggling them can put the
 * C6 into its bootloader. Reads arrive on the SerialInputOutputManager thread; writes go through
 * one writer coroutine on Dispatchers.IO. Everything else runs on [scope] (Main).
 */
class UsbLink(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : Link {
    private val context = context.applicationContext
    private val usb = this.context.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber(ProbeTable().addProduct(VID, PID, CdcAcmSerialDriver::class.java))

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    override val incoming: Flow<Frame> = _incoming.asSharedFlow()

    private val _parserErrors = MutableStateFlow(0)
    override val parserErrors: StateFlow<Int> = _parserErrors.asStateFlow()

    private val parser = FrameParser()
    private val queue = OutgoingQueue()

    @Volatile private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var writer: Job? = null
    private var loop: Job? = null
    private var permissionAsked = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scope.launch { closePort() }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scope.launch { tryOpen() }
                ACTION_PERMISSION -> scope.launch {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        tryOpen()
                    } else {
                        _state.value = LinkState.Error(NO_PERMISSION)
                    }
                }
            }
        }
    }

    private val listener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            val frames = synchronized(parser) { parser.feed(data, clock()) }
            frames.forEach { _incoming.tryEmit(it) }
            _parserErrors.value = parser.crcErr
        }

        override fun onRunError(e: Exception) {
            scope.launch { closePort() }
        }
    }

    override suspend fun open() {
        if (loop != null) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(ACTION_PERMISSION)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        loop = scope.launch {
            launch {
                while (isActive) {
                    if (port == null) tryOpen()
                    delay(RETRY_MS)
                }
            }
            launch {
                while (isActive) {
                    delay(STALE_CHECK_MS)
                    val frames = synchronized(parser) { parser.flushStale(clock()) }
                    frames.forEach { _incoming.tryEmit(it) }
                }
            }
        }
    }

    override suspend fun close() {
        loop?.cancel()
        loop = null
        runCatching { context.unregisterReceiver(receiver) }
        closePort()
    }

    override fun send(bytes: ByteArray, priority: Priority) {
        if (port != null) queue.offer(bytes, priority)
    }

    /** Asks for USB permission again after the user denied it. */
    fun requestPermission() {
        permissionAsked = false
        scope.launch { tryOpen() }
    }

    private fun tryOpen() {
        if (port != null) return
        val driver = prober.findAllDrivers(usb).firstOrNull()
        if (driver == null) {
            _state.value = LinkState.Disconnected
            return
        }
        val device = driver.device
        if (!usb.hasPermission(device)) {
            if (!permissionAsked) {
                permissionAsked = true
                askPermission(device)
            }
            return
        }
        _state.value = LinkState.Connecting
        val connection = usb.openDevice(device)
        if (connection == null) {
            _state.value = LinkState.Error("Cannot open USB device")
            return
        }
        val p = driver.ports[0]
        try {
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // No setDTR/setRTS here or anywhere: see the class comment.
        } catch (e: IOException) {
            runCatching { p.close() }
            _state.value = LinkState.Error("USB: ${e.message}")
            return
        }
        synchronized(parser) { parser.reset() }
        queue.clear()
        port = p
        io = SerialInputOutputManager(p, listener).also { it.start() }
        writer = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val bytes = queue.take()
                try {
                    p.write(bytes, WRITE_TIMEOUT_MS)
                } catch (e: IOException) {
                    scope.launch { closePort() }
                    break
                }
            }
        }
        _state.value = LinkState.Connected
    }

    /** Writes queued STOP frames, then closes. The retry loop reopens the port if the link is still open. */
    private fun closePort() {
        val p = port
        if (p == null) {
            _state.value = LinkState.Disconnected
            return
        }
        port = null
        queue.drainStops().forEach { runCatching { p.write(it, WRITE_TIMEOUT_MS) } }
        writer?.cancel()
        writer = null
        io?.stop()
        io = null
        runCatching { p.close() }
        queue.clear()
        _state.value = LinkState.Disconnected
    }

    private fun askPermission(device: UsbDevice) {
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(device, pending)
    }

    companion object {
        const val VID = 0x303A
        const val PID = 0x1001
        const val NO_PERMISSION = "No USB permission"
        private const val BAUD = 115200
        private const val ACTION_PERMISSION = "com.mecanumbot.usb.PERMISSION"
        private const val RETRY_MS = 1_000L
        private const val STALE_CHECK_MS = 50L
        private const val WRITE_TIMEOUT_MS = 100
    }
}
```

- [ ] **Step 3: Build and confirm no DTR/RTS calls**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :usb:assembleDebug && grep -rnE "setDTR|setRTS" usb/src || echo "no DTR/RTS calls"`
Expected: `BUILD SUCCESSFUL`, then `no DTR/RTS calls`. The only match allowed is the comment text `No setDTR/setRTS here`; if grep prints that line, it's fine.

- [ ] **Step 4: Commit**

```bash
git add android-app/usb/src
git commit -m "Android usb: UsbLink over CDC-ACM with reconnect, no DTR/RTS

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: App shell — graph, link switch, status bar, settings

**Files:**
- Create: `android-app/app/src/main/kotlin/com/mecanumbot/app/MecanumApp.kt`, `AppGraph.kt`, `LinkPreference.kt`
- Modify (replace placeholder): `android-app/app/src/main/kotlin/com/mecanumbot/app/MainActivity.kt`, `android-app/app/src/main/AndroidManifest.xml`
- Create: `android-app/app/src/main/kotlin/com/mecanumbot/app/ui/Root.kt`, `StatusBar.kt`, `SettingsScreen.kt`
- Create (temporary stub, replaced in Task 13): `android-app/app/src/main/kotlin/com/mecanumbot/app/ui/TestScreen.kt`

**Interfaces:**
- Consumes:
  - `RobotSession`, `SessionState`, `Phase`, `SessionEvent` (Task 8)
  - `FakeLink`, `FaultControls` (Task 10)
  - `UsbLink` (Task 11)
  - `LinkState`, `Protocol`
- Produces:
  - `enum class LinkKind { USB, FAKE }`
  - `class AppGraph(context)` with:
    - `scope`, `clock`, `usbLink`, `fakeLink`
    - `active: StateFlow<AppGraph.Active?>`, where `data class Active(val kind: LinkKind, val session: RobotSession)`
    - `inForeground: StateFlow<Boolean>`
    - `fun select(kind: LinkKind)`, `fun onForeground(visible: Boolean)`
    - `APP_MAJOR`, `APP_MINOR`
  - `@Composable fun TestScreen(session: RobotSession, state: SessionState, inForeground: StateFlow<Boolean>)`: Task 13 fills it in.

UI code is checked by building and by running it on FakeLink (Task 14); it has no unit tests in this stage.

- [ ] **Step 1: Write the app graph and preference**

`android-app/app/src/main/kotlin/com/mecanumbot/app/LinkPreference.kt`:
```kotlin
package com.mecanumbot.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

enum class LinkKind { USB, FAKE }

private val Context.settings by preferencesDataStore(name = "settings")

/** The USB / Fake choice (spec §9). Default USB; no automatic fallback. */
class LinkPreference(context: Context) {
    private val store = context.applicationContext.settings
    private val key = stringPreferencesKey("link")

    suspend fun load(): LinkKind =
        store.data.first()[key]?.let { runCatching { LinkKind.valueOf(it) }.getOrNull() } ?: LinkKind.USB

    suspend fun save(kind: LinkKind) {
        store.edit { it[key] = kind.name }
    }
}
```

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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Activity onResume / onPause. Leaving the foreground always stops the robot (STOP ×3). */
    fun onForeground(visible: Boolean) {
        _inForeground.value = visible
        if (!visible) _active.value?.session?.stop()
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

`android-app/app/src/main/kotlin/com/mecanumbot/app/MecanumApp.kt`:
```kotlin
package com.mecanumbot.app

import android.app.Application

class MecanumApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
```

- [ ] **Step 2: Write the Activity and manifest**

`android-app/app/src/main/kotlin/com/mecanumbot/app/MainActivity.kt` (replace the placeholder):
```kotlin
package com.mecanumbot.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.mecanumbot.app.ui.Root

class MainActivity : ComponentActivity() {
    private val graph: AppGraph get() = (application as MecanumApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Root(graph) }
        }
    }

    override fun onResume() {
        super.onResume()
        graph.onForeground(true)
    }

    override fun onPause() {
        graph.onForeground(false) // a call or notification must stop the robot (DESIGN.md §5.4)
        super.onPause()
    }
}
```

`android-app/app/src/main/AndroidManifest.xml` (replace):
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.usb.host" android:required="true" />

    <application
        android:name=".MecanumApp"
        android:label="MecanumBot"
        android:theme="@android:style/Theme.Material.NoActionBar">

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
    </application>
</manifest>
```

- [ ] **Step 3: Write Root, StatusBar, SettingsScreen and the TestScreen stub**

`android-app/app/src/main/kotlin/com/mecanumbot/app/ui/Root.kt`:
```kotlin
package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mecanumbot.app.AppGraph

@Composable
fun Root(graph: AppGraph) {
    val active by graph.active.collectAsStateWithLifecycle()
    val current = active ?: return
    key(current.session) {
        val state by current.session.state.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            StatusBar(
                kind = current.kind,
                state = state,
                clock = graph.clock,
                onStop = { current.session.stop() },
                onRequestPermission = { graph.usbLink.requestPermission() },
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Test") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
            }
            when (tab) {
                0 -> TestScreen(current.session, state, graph.inForeground)
                else -> SettingsScreen(graph, current.kind)
            }
        }
    }
}
```

`android-app/app/src/main/kotlin/com/mecanumbot/app/ui/StatusBar.kt`:
```kotlin
package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mecanumbot.app.LinkKind
import com.mecanumbot.core.link.LinkState
import com.mecanumbot.core.protocol.Protocol
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.SessionEvent
import com.mecanumbot.core.session.SessionState
import com.mecanumbot.usb.UsbLink
import kotlinx.coroutines.delay

private val Amber = Color(0xFFFFB300)
private val Danger = Color(0xFFD32F2F)

@Composable
fun StatusBar(
    kind: LinkKind,
    state: SessionState,
    clock: () -> Long,
    onStop: () -> Unit,
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
        Badge(kind.name, if (kind == LinkKind.FAKE) Amber else MaterialTheme.colorScheme.primary)
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
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Color.White),
            modifier = Modifier.size(width = 140.dp, height = 64.dp),
        ) {
            Text("STOP", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, color = Color.Black, style = MaterialTheme.typography.labelMedium)
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

fun SessionEvent.message(): String = when (this) {
    SessionEvent.NotResponding -> "ESP32 not responding"
    is SessionEvent.VersionMismatch -> "ESP32 protocol $espProtoVer, app ${Protocol.PROTO_VER}: update firmware/app"
    is SessionEvent.Rebooted -> "ESP32 rebooted: reason $resetReason (boot #$resetCount)"
    is SessionEvent.EspLog -> "ESP32: $text"
}

private const val VM_LOW_MV = 3200
```

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mecanumbot.app.AppGraph
import com.mecanumbot.app.LinkKind
import com.mecanumbot.fake.FaultControls
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
        if (kind == LinkKind.FAKE) FaultPanel(graph.fakeLink.faults)
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

`android-app/app/src/main/kotlin/com/mecanumbot/app/ui/TestScreen.kt` (stub; Task 13 replaces it):
```kotlin
package com.mecanumbot.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.core.session.SessionState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun TestScreen(session: RobotSession, state: SessionState, inForeground: StateFlow<Boolean>) {
    Text("Test screen: ${state.phase}")
}
```

- [ ] **Step 4: Build**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test :fake:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Smoke run on FakeLink (if a phone or emulator is attached)**

Run: `adb install -r android-app/app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.mecanumbot/com.mecanumbot.app.MainActivity`
Expected: the app opens in landscape with status "Disconnected — connect ESP32" and the USB badge. Settings → FAKE: the badge turns amber `FAKE`, the phase reaches "Ready", and fw 0.1, RTT and tx ~40/s are shown. If no device is attached, skip this step and note it in the task report.

- [ ] **Step 6: Commit**

```bash
git add android-app/app/src
git commit -m "Android app: graph, link switch, status bar, settings with fault controls

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Test screen — sticks, hold to drive, RAW sliders, telemetry

**Files:**
- Create: `android-app/app/src/main/kotlin/com/mecanumbot/app/ui/Joystick.kt`, `HoldButton.kt`
- Modify (replace stub): `android-app/app/src/main/kotlin/com/mecanumbot/app/ui/TestScreen.kt`

**Interfaces:**
- Consumes: `RobotSession.update/setRaw/stop`, `RobotSession.TICK_MS`, `SessionState`, `Phase` (Task 8); `Command`, `Source` (Task 6); `AppGraph.inForeground` (Task 12).
- Produces:
  - `@Composable fun Joystick(label: String, onChange: (Offset) -> Unit, modifier: Modifier = Modifier)`: the offset is −1..1 with y pointing up, and returns to (0, 0) on release.
  - `@Composable fun HoldButton(text: String, enabled: Boolean, onHoldChange: (Boolean) -> Unit, modifier: Modifier = Modifier)`.

Safety rules this screen implements (spec §9):
- **Drive tab:** while "Hold to drive" is held, the screen sends the stick values times the speed limit every 25 ms, and releasing the button sends `enable = false`.
- **Raw tab:** while it is visible, the screen refreshes the RAW values every 25 ms.
- **Leaving:** leaving the screen or either tab calls `stop()`.
- **Background or not READY:** when the app goes to the background or the session isn't READY, the controls are disabled and holding is reset.

- [ ] **Step 1: Write the Joystick and HoldButton**

`android-app/app/src/main/kotlin/com/mecanumbot/app/ui/Joystick.kt`:
```kotlin
package com.mecanumbot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** On-screen stick. Reports −1..1 per axis, y up; springs back to the centre on release. */
@Composable
fun Joystick(label: String, onChange: (Offset) -> Unit, modifier: Modifier = Modifier) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val report by rememberUpdatedState(onChange)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = MaterialTheme.colorScheme.primary

    Box(
        modifier.size(200.dp).pointerInput(Unit) {
            fun update(position: Offset) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                var v = (position - centre) / (size.width / 2f)
                val d = v.getDistance()
                if (d > 1f) v /= d
                knob = Offset(v.x, -v.y)
                report(knob)
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                update(down.position)
                drag(down.id) { change ->
                    update(change.position)
                    change.consume()
                }
                knob = Offset.Zero
                report(Offset.Zero)
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val knobR = r / 3f
            drawCircle(track, radius = r)
            drawCircle(color, radius = knobR, center = center + Offset(knob.x, -knob.y) * (r - knobR))
        }
        Text(label, Modifier.align(Alignment.BottomCenter))
    }
}
```

`android-app/app/src/main/kotlin/com/mecanumbot/app/ui/HoldButton.kt`:
```kotlin
package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Deadman button: reports true while a finger is on it. Any cancel or disable reports false. */
@Composable
fun HoldButton(text: String, enabled: Boolean, onHoldChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val report by rememberUpdatedState(onHoldChange)
    val background = when {
        pressed -> Color(0xFF2E7D32)
        enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.DarkGray
    }
    Box(
        modifier
            .size(width = 200.dp, height = 96.dp)
            .background(background, RoundedCornerShape(16.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    report(true)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                        report(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (enabled) text else "$text (disabled)")
    }
}
```

- [ ] **Step 2: Write the Test screen**

`android-app/app/src/main/kotlin/com/mecanumbot/app/ui/TestScreen.kt` (replace the stub):
```kotlin
package com.mecanumbot.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mecanumbot.core.control.Command
import com.mecanumbot.core.control.Source
import com.mecanumbot.core.session.Phase
import com.mecanumbot.core.session.RobotSession
import com.mecanumbot.core.session.SessionState
import com.mecanumbot.core.session.TelemetryState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DEFAULT_LIMIT = 0.3f

@Composable
fun TestScreen(session: RobotSession, state: SessionState, inForeground: StateFlow<Boolean>) {
    val foreground by inForeground.collectAsStateWithLifecycle()
    val enabled = foreground && state.phase == Phase.READY
    var tab by remember { mutableIntStateOf(0) }
    DisposableEffect(session) { onDispose { session.stop() } } // leaving the Test screen

    Row(Modifier.fillMaxSize().padding(8.dp)) {
        Column(Modifier.weight(2f)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Drive") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Raw") })
            }
            when (tab) {
                0 -> DriveTab(session, enabled)
                else -> RawTab(session, enabled)
            }
        }
        TelemetryPanel(state, Modifier.weight(1f))
    }
}

@Composable
private fun DriveTab(session: RobotSession, enabled: Boolean) {
    var limit by remember { mutableFloatStateOf(DEFAULT_LIMIT) } // reset to 30 % on entering the screen
    var left by remember { mutableStateOf(Offset.Zero) }
    var right by remember { mutableStateOf(Offset.Zero) }
    var holding by remember { mutableStateOf(false) }

    DisposableEffect(session) { onDispose { session.stop() } }
    LaunchedEffect(enabled) { if (!enabled) holding = false }
    LaunchedEffect(holding, enabled) {
        if (!holding || !enabled) {
            session.update(Command(0f, 0f, 0f, false, Source.TEST))
            return@LaunchedEffect
        }
        while (true) {
            // Reads the current stick and limit state on every frame: a new limit applies next frame.
            session.update(Command(left.x * limit, left.y * limit, right.x * limit, true, Source.TEST))
            delay(RobotSession.TICK_MS)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Joystick("Move", onChange = { left = it })
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Speed limit ${(limit * 100).roundToInt()} %")
            Slider(
                value = limit,
                onValueChange = { limit = it },
                valueRange = 0.1f..1f,
                modifier = Modifier.width(200.dp),
            )
            HoldButton("Hold to drive", enabled, onHoldChange = { holding = it })
        }
        Joystick("Turn", onChange = { right = Offset(it.x, 0f) })
    }
}

@Composable
private fun RawTab(session: RobotSession, enabled: Boolean) {
    val values = remember { mutableStateListOf(0, 0, 0, 0) }

    DisposableEffect(session) { onDispose { session.stop() } } // leaving the Raw tab
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            session.setRaw(values.toList())
            delay(RobotSession.TICK_MS)
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until 4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("M${i + 1}", Modifier.width(40.dp))
                Slider(
                    value = values[i].toFloat(),
                    onValueChange = { values[i] = it.roundToInt() },
                    valueRange = -127f..127f,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                Text("${values[i]}", Modifier.width(48.dp))
            }
        }
        OutlinedButton(onClick = { for (i in 0 until 4) values[i] = 0 }) { Text("Center") }
    }
}

@Composable
private fun TelemetryPanel(state: SessionState, modifier: Modifier) {
    Column(modifier.padding(start = 8.dp).verticalScroll(rememberScrollState())) {
        Text("Telemetry", style = MaterialTheme.typography.titleSmall)
        val t = state.telemetry
        if (t == null) {
            Text("no telemetry")
            return@Column
        }
        val tm = t.telemetry
        tm.pwm.forEachIndexed { i, v -> PwmBar("M${i + 1}", v) }
        Field("flags", flagsText(t))
        Field("vm_mv", tm.vmMv)
        Field("last_seq", tm.lastSeq)
        Field("crc_err (ESP32)", tm.crcErr)
        Field("crc_err (phone)", state.phoneParserErrors)
        Field("rx_frames", tm.rxFrames)
        Field("uptime_s", tm.uptimeS)
        Field("fw", "${tm.fwMajor}.${tm.fwMinor}")
        Field("loop_max_us", tm.loopMaxUs)
        state.config?.let {
            Field("failsafe_ms", it.failsafeMs)
            Field("max_duty", "${it.maxDuty} %")
        }
    }
}

@Composable
private fun PwmBar(label: String, value: Int) {
    val color = if (value >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(32.dp))
        Box(Modifier.weight(1f).height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(abs(value) / 127f).background(color))
        }
        Text("$value", Modifier.width(40.dp).padding(start = 4.dp))
    }
}

@Composable
private fun Field(name: String, value: Any) {
    Row {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text("$value", style = MaterialTheme.typography.bodySmall)
    }
}

private fun flagsText(t: TelemetryState): String = listOf(
    "failsafe" to t.failsafe,
    "fault A" to t.faultA,
    "fault B" to t.faultB,
    "raw" to t.rawMode,
    "ota" to t.otaMode,
    "wifi" to t.wifiMode,
    "enable" to t.enabled,
).filter { it.second }.joinToString { it.first }.ifEmpty { "—" }
```

- [ ] **Step 3: Build**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test :fake:test :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Manual check on FakeLink**

Install as in Task 12 Step 5 and select FAKE in Settings. Then check each item and write down the result:
1. **Drive tab entry.** The speed limit shows 30 %, and the "Hold to drive" button is enabled only in Ready.
2. **Sticks without the button.** Moving the sticks without holding does nothing: pwm stays 0 and `enable` is absent from flags.
3. **Driving forward.** Hold the button and push the left stick up: all four pwm bars read about +38 (0.3 × 127), and flags show `enable`.
4. **Limit change mid-drive.** Still holding, move the speed-limit slider to 100 %: the bars rise to 127 on the next frames, with no stop and no FAILSAFE badge.
5. **Release.** Let go of the button: the bars drop to 0 and no FAILSAFE badge appears (the pulse keeps running).
6. **Background mid-drive.** Hold and drive, then press Home. Come back: the robot is stopped (pwm 0), the button is not held, and a new press is needed.
7. **Raw tab.** Move M1: bar M1 follows and flags show `raw`. Switch to the Drive tab: all bars go to 0 (STOP).
8. **STOP button.** Tap STOP while driving: the bars go to 0 immediately.
9. **FaultControls, one at a time.**
   - Disconnect: the phase changes to Disconnected.
   - Reconnect: the phase returns to Ready.
   - Reboot: the event reads "ESP32 rebooted: reason 3 (boot #2)".
   - Wrong proto_ver on: "Version mismatch" and the controls are disabled. Off: back to Ready.
   - nFAULT A: the FAULT A badge appears.
   - VM sag 1000: VM drops below 3.2 V and turns red.
   - Drop 100 %: the FAILSAFE badge appears.
   - Inject garbage: crc_err (phone) goes up.

If no device is attached, report that this step is still pending; don't mark it done.

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src
git commit -m "Android app: Test screen with sticks, hold to drive, RAW sliders, telemetry

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Docs and hardware check

**Files:**
- Modify: `android-app/README.md`, `CLAUDE.md` (the Android line under «Компоненты»)

**Interfaces:**
- Consumes: everything above.
- Produces: build/test instructions matching the actual commands, and the stage 3 hardware check result.

- [ ] **Step 1: Update android-app/README.md**

Replace the file with:
```markdown
# android-app

Robot app (Kotlin, Compose). Stage 3: `docs/stage3-android-spec.md`, plan `docs/stage3-android-plan.md`.

Modules: `:core` (protocol, arbiter, session; pure Kotlin), `:fake` (FakeLink, simulated ESP32; pure Kotlin),
`:usb` (UsbLink), `:app` (UI). Gradle runs on Android Studio's JDK:

    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    ./gradlew :core:test :fake:test      # unit tests (read ../protocol/vectors.json)
    ./gradlew :app:assembleDebug         # APK in app/build/outputs/apk/debug/
    adb install -r app/build/outputs/apk/debug/app-debug.apk

`local.properties` (not committed) needs `sdk.dir=$HOME/Library/Android/sdk`.

Without the ESP32: Settings → FAKE. The amber FAKE badge is always visible; fault injection is under Settings.
```

- [ ] **Step 2: Update the Android line in CLAUDE.md**

In `CLAUDE.md` replace:
```
- android-app/ — Kotlin/Compose. Тесты core: `./gradlew :core:test`.
```
with:
```
- android-app/ — Kotlin/Compose, модули core, fake, usb, app. Тесты: `cd android-app && ./gradlew :core:test :fake:test` (JAVA_HOME = JBR из Android Studio, см. android-app/README.md).
```

- [ ] **Step 3: Run the full build once more**

Run: `cd android-app && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :core:test :fake:test :app:assembleDebug && python3 ../protocol/gen_vectors.py --check`
Expected: `BUILD SUCCESSFUL`, and the vectors check passes (nothing in this stage touched vectors.json).

- [ ] **Step 4: Commit the docs**

```bash
git add android-app/README.md CLAUDE.md
git commit -m "Android: README and CLAUDE.md test commands

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Hardware check (with the user, wheels in the air)**

This needs the robot, so ask the user to run it and report back. Checklist:
1. **Setup.** VM switch within reach, wheels off the ground, ESP32 flashed with the current firmware (`pio run -e esp32c6 -t upload`).
2. **Plug in.** The app opens (USB_DEVICE_ATTACHED) or asks for permission. After granting, the status reads Ready with fw 0.1. The ESP32 does **not** reset when the port opens: uptime keeps growing and there is no "rebooted" event.
3. **Drive tab, 30 % limit.** Check all 8 directions (forward, back, left, right, both turns, two diagonals) for the correct wheel directions. Wrong wheels are expected before calibration (stage 4): note which wheel is wrong, don't fix the wiring.
4. **Raw tab.** Each of M1–M4 turns both ways.
5. **Stop paths.**
   - STOP stops at once.
   - Releasing "Hold to drive" stops.
   - Home stops.
6. **Cable pull while driving.** The motors stop within 300 ms (ESP32 failsafe). Plug back in: the app returns to Ready by itself.
7. **ESP32 reset button while connected.** The event reads "ESP32 rebooted" and the app recovers.

Record the results. They are the stage 3 "done" criterion (spec §1). Stage 3 is done only when every item passes or has a noted, understood exception.
