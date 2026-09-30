# Stage 3 — Android app: core, FakeLink, USB, Test screen

Spec version 1.1, 2026-09-30. Status: approved design, implementation not started.

Sources: DESIGN.md §5, §7, §10; protocol/PROTOCOL.md (source of truth for the wire format and ESP32 behaviour); CLAUDE.md rules. This document narrows DESIGN.md §5 to stage 3 and records the decisions DESIGN.md leaves open. Where it deviates from DESIGN.md, the deviation is listed in §10.

---

## 1. Goal and scope

**Goal.** The robot drives from the Pixel 9a over USB, controlled from the Test screen. The whole UI also works without the ESP32, on FakeLink.

**Done when** (DESIGN §10, stage 3):
1. `./gradlew :core:test :fake:test` is green.
2. On FakeLink the app drives the model, shows failsafe after the drive button is released, and handles reboot and wrong `proto_ver` injected from FaultControls.
3. On the real robot (wheels in the air) the wheels turn from the sticks and from the RAW sliders; pulling the cable stops the motors within `failsafe_ms` (300 ms).

**In scope:** modules `:core`, `:fake`, `:usb`, `:app`; full Arbiter logic (only the TEST source is fed); Test screen core set; minimal settings.

**Out of scope (later stages):** server and web pilot (5), camera (5), gamepad input (6), calibration wizard (4), OTA screen (7), heading hold, session log, Foreground Service (5), preset maneuvers, LOG/hex console, "simulate disconnect" with failsafe timing, live gamepad values. The interfaces below leave room for them; no code for them is written now.

**Assumptions.** Single user, single device (Pixel 9a, Android 15+). Built with Android Studio's bundled JBR 21 (the system JDK 24 is not used by Gradle).

## 2. Modules

```
android-app/
├── settings.gradle.kts
├── gradle/libs.versions.toml     — version catalog
├── core/   kotlin("jvm"), no Android dependencies
│   ├── protocol/  Crc8, FrameType (type + len table), Frame + typed payloads,
│   │              FrameCodec (encode/decode), FrameParser (PROTOCOL §3.1)
│   ├── link/      interface Link, LinkState, Priority
│   ├── control/   Command, Source, Mode, Arbiter, Mecanum (mix)
│   └── session/   RobotSession, SessionState, TelemetryState, SessionEvent
├── fake/   kotlin("jvm"), depends on :core
│   └── FakeLink (implements Link), FakeEsp32 (firmware model), FaultControls
├── usb/    com.android.library, depends on :core and usb-serial-for-android
│   └── UsbLink (implements Link), res/xml/device_filter.xml
└── app/    com.android.application, depends on :core, :fake, :usb
    └── MainActivity, AppGraph, TestScreen, StatusBar, SettingsScreen, LinkPreference
```

- `:core` and `:fake` are plain JVM modules, so the build enforces the CLAUDE.md rule that core has no Android dependencies, and both are tested with plain JVM tests.
- Dependency wiring: a hand-written `AppGraph` created in `Application`. No DI framework.
- SDK: minSdk 34, compileSdk 37 (required by the current Compose BOM), targetSdk 36. AGP 9 with built-in Kotlin, Kotlin 2.x, current Compose BOM (exact versions fixed in the implementation plan). Bytecode target JVM 17; Gradle runs on Android Studio's JBR 21.
- Modules `server`, `camera`, `input`, `sensors` from DESIGN §5.2 are created in their stages.

## 3. Protocol in :core

- **Types.** `FrameType` enumerates every type from PROTOCOL §4.1 and §4.2 with `minLen`/`maxLen`. `Frame(type, seq, payload: ByteArray)` is the wire-level frame. Typed payloads (`Hello`, `Drive`, `MotorRaw`, `Ping`, `Config`, `Stop`, …, `HelloAck`, `Telemetry`, `Ack`, `Log`, `Pong`, `ConfigData`, `WifiStatus`) cover every type in both directions, since FakeLink encodes ESP32→phone frames and the codec tests use both.
- **FrameCodec.** `encode(payload, seq): ByteArray` and `decode(frame): Payload`. Little-endian, i8 speeds returned raw (−128 is not clamped by the codec, PROTOCOL §2). Field names follow `vectors.json` (for example `MotorRaw.m[4]`).
- **FrameParser.** `feed(bytes): List<Frame>` and a `crcErr` counter, implementing PROTOCOL §3.1 exactly: header check of `type` and `len`, on reject drop only the `0xAA` and rescan the buffered bytes, buffer of one maximum frame (205 bytes). The recommended 50 ms incomplete-frame timeout is exposed as `flushStale(now)` and called by the links.
- **Speeds.** `Float → i8`: clamp to −1..1, `round(v × 127)`.
- **Vectors.** `:core` tests read `protocol/vectors.json` in place: Gradle passes the absolute path as a system property (`vectors.path`), parsed with kotlinx.serialization (test dependency only). No copies.

## 4. Link

```kotlin
interface Link {
    val state: StateFlow<LinkState>          // Disconnected, Connecting, Connected, Error(message)
    val incoming: Flow<Frame>                // frames accepted by the link's FrameParser
    val parserErrors: StateFlow<Int>         // phone-side crc_err
    suspend fun open()
    suspend fun close()                      // writes queued STOP frames first
    fun send(bytes: ByteArray, priority: Priority)   // non-blocking
}
enum class Priority { STOP, MOTION, OTHER }
```

- Links move bytes; they don't assign `seq`. `RobotSession` builds and encodes every frame, so `seq` handling is identical for UsbLink and FakeLink.
- Outgoing queue per link, ordered STOP > MOTION > OTHER. A MOTION frame not yet written is replaced by the newer one instead of queuing (PROTOCOL §6).
- Each link runs its own `FrameParser`, so FakeLink exercises the codec and parser end to end.
- `close()` delivers any queued STOP frames before closing, so STOP ×3 survives a link switch.

## 5. Arbiter (:core)

Pure, driven by `now: Long` (ms). Implements DESIGN §5.4 completely; stage 3 feeds only `Source.TEST`.

- `update(command, now)`: stores the command for `command.source` with its timestamp. `Command(vx, vy, w: Float, enable: Boolean, source: Source)`.
- `setRaw(m: List<Int>, now)` / `clearRaw()`: RAW mode; values are i8 per physical channel. RAW is also subject to the 300 ms freshness rule: the Test screen must refresh it, so a frozen UI can't hold the motors.
- `setMode(Mode)`: `AUTO`, `LOCAL_ONLY` (TEST and LOCAL_PAD), `REMOTE_ONLY` (TEST and REMOTE). TEST is always allowed, because it is the on-robot bench screen.
- `stop()`: clears RAW and all source commands. STOP ×3 itself is sent by `RobotSession.stop()`, not produced by `tick`.
- `tick(now): Output` — `Raw(m)` or `Drive(flags, vx, vy, w)`. Active source: highest priority TEST > LOCAL_PAD > REMOTE among those allowed by the mode, updated less than 300 ms ago, with `enable = true`. No active source: `Drive` with zeros and enable=0 (the pulse). DRIVE `flags` carry enable in bit0 and the source in bits 1..2 (PROTOCOL §4.1).
- The speed limit is applied by the caller (Test screen) before `update`.

`Mecanum` in `:core/control` implements the mix and calibration from DESIGN §4.4 / PROTOCOL §2.1 (map, invert, trim, max_duty, min_duty, dead zone). It is used by FakeEsp32 to produce `pwm`; the app itself never mixes, the ESP32 does.

## 6. RobotSession (:core)

Created with a `Link`, a `CoroutineScope`, a clock `() -> Long` and the app version. Exposes `state: StateFlow<SessionState>` and `events: Flow<SessionEvent>`; commands: `update(Command)`, `setRaw`, `clearRaw`, `stop()`.

**Phases:**

| Phase | Entered | Behaviour |
|---|---|---|
| `Disconnected` | link not connected | no tick; state and arbiter reset |
| `Handshaking` | link `Connected` | send HELLO (`proto_ver=1`, app version); resend every 1 s, up to 3 times; then event "ESP32 not responding", keep retrying every 3 s |
| `VersionMismatch` | HELLO_ACK with other `proto_ver` | only HELLO, PING, GET_CONFIG sent; UI shows "Update firmware/app" |
| `Ready` | HELLO_ACK with matching `proto_ver` | 40 Hz tick, PING 1 Hz, GET_CONFIG once |

**In `Ready`:**
- Every 25 ms: `arbiter.tick(now)` → encode → `link.send` with `Priority.MOTION`.
- `stop()`: `arbiter.stop()`, then three STOP frames with `Priority.STOP` immediately (whenever the link is connected, in any phase); the tick continues with zero DRIVE.
- PING every 1 s with `ts = now`; RTT from the matching PONG.
- GET_CONFIG once after the handshake; `failsafe_ms` and `max_duty` from CONFIG_DATA are kept in the state for display.
- No DRIVE, MOTOR_RAW, CONFIG or OTA_* is sent before `Ready` (PROTOCOL §5.1).

**Reboot detection.** The last `reset_count` is kept across link reconnects. Any HELLO_ACK (in any phase) whose `reset_count` differs from the last known value emits "ESP32 rebooted: reason N" and calls `stop()`. On real hardware a reboot re-enumerates USB, so it is usually seen during `Handshaking`. A HELLO_ACK in `Ready` with the same `reset_count` is the duplicate the ESP32 sends on host connect (PROTOCOL §5.6) and is ignored. Every HELLO_ACK re-checks `proto_ver`.

ESP32 LOG frames go to `events` only; they don't replace the last event shown in the status bar.

**`SessionState`:** link state, phase, fw version, `reset_reason`/`reset_count`, last `TelemetryState` (decoded flags, `pwm[4]`, `vm_mv`, `crc_err`, `rx_frames`, `uptime_s`, `loop_max_us`), telemetry age, RTT, DRIVE/MOTOR_RAW frames sent in the last second, phone-side parser errors, active source, last event.

## 7. FakeLink and FakeEsp32 (:fake)

`FakeLink` implements `Link`: bytes sent by the session go through a parser into `FakeEsp32`; frames produced by `FakeEsp32` are encoded and fed back through the link's parser into `incoming`. It runs on the session's scope and clock, so tests use virtual time.

**FakeEsp32** models PROTOCOL §5:
- Modes DRIVE, RAW, OTA, WIFI with the transitions of §5.1; DRIVE/MOTOR_RAW ignored and CONFIG/OTA_BEGIN/WIFI_OTA_ENTER answered BUSY in the other mode.
- Failsafe per §5.2: active at start, reset by any valid DRIVE or MOTOR_RAW, fires after `failsafe_ms`, instant stop.
- STOP per §5.3 with ACK OK; HELLO → HELLO_ACK; PING → PONG; GET_CONFIG → CONFIG_DATA.
- CONFIG validated per §4.4: any invalid field → ACK ERR and not applied; otherwise applied and ACK OK. Stored in memory for the life of the FakeLink.
- OTA_*: plausible ACKs following §5.4 (offset continuity, size check, no real flashing; OTA_END with the right size → ACK OK and a simulated reboot). WIFI_*: ACK plus WIFI_STATUS 1 → 2 with a fixed IP; WIFI_OTA_EXIT → state 0.
- HELLO_ACK sent on connect; `reset_count` starts at 1.
- TELEMETRY at 10 Hz: `pwm` from `Mecanum` with the current config (no slew), `vm_mv` = 3600 minus a sag proportional to total |pwm| (about 150 mV at full load), `crc_err` from its parser, `rx_frames` counted over the last second, real `uptime_s`, fw 0.1.
- LOG frames at info level on mode changes and failsafe transitions.

**FaultControls** (exposed in the app when FakeLink is selected):

| Control | Effect |
|---|---|
| Disconnect / Reconnect | link state Disconnected, then Connected with a HELLO_ACK |
| Reboot | `reset_count++`, `uptime_s` reset, failsafe active, unsolicited HELLO_ACK |
| Wrong proto_ver | HELLO_ACK reports `proto_ver = 2` |
| nFAULT A / B | sets telemetry flag bit1 / bit2 |
| VM sag | extra sag in mV (slider 0–1500) |
| Drop incoming | percentage of phone→ESP32 frames discarded |
| Inject garbage | writes fixed garbage bytes with stray `0xAA`s into both directions |

## 8. UsbLink (:usb)

- Finds the device by VID `0x303A`, PID `0x1001`; requests USB permission when needed; opens CDC-ACM with `usb-serial-for-android` at 115200. **Never calls `setDTR` or `setRTS`.**
- Reading: `SerialInputOutputManager` → `FrameParser.feed` → `incoming`. `flushStale` every 50 ms.
- Writing: a single writer coroutine drains the priority queue. MOTION frames are dropped, not queued, when the port isn't ready.
- Reconnect: listens to `USB_DEVICE_ATTACHED` / `USB_DEVICE_DETACHED`; after detach or an I/O error closes the port and retries every 1 s while the app is running.
- Manifest: `uses-feature android.hardware.usb.host`; `intent-filter` for `USB_DEVICE_ATTACHED` with `device_filter.xml`, so plugging the cable in opens the app.
- Permission denied → `LinkState.Error("No USB permission")`; the UI offers "Request again".

## 9. App (:app)

One Activity, Compose, landscape, `FLAG_KEEP_SCREEN_ON`. All UI text in English.

**Link selection.** An explicit USB / Fake switch, persisted in DataStore, default USB. No automatic fallback. Switching sends STOP ×3 on the old link if it is connected, closes it, and opens the new one.

**Status bar** (always visible):
- link: `USB` or `FAKE` (amber background);
- phase: Handshaking / Ready / Version mismatch / Disconnected / error text;
- fw version, RTT, DRIVE sent per second vs `rx_frames`;
- `vm` in volts, red below 3.2 V;
- badges for failsafe, fault A, fault B, raw mode;
- last event;
- a large **STOP** button, enabled in every state.

**Test screen**, two tabs:
- **Drive:** left stick (vx, vy), right stick (w), speed limit slider 0.1–1.0 reset to 0.3 on entering the screen, **Hold to drive** button. While the button is held, the stick state times the limit is sent to the Arbiter as `Source.TEST` on every UI frame (at least every 25 ms, whether or not it changed). Releasing the button sends `enable = false`. Sticks without the button do nothing.
- **Raw:** four sliders M1–M4 (−127…127) and a **Center** button. While the tab is visible the slider values are refreshed to `setRaw` every 25 ms; leaving the tab calls `stop()`.
- **Telemetry panel** under both tabs: `pwm[4]` as bars, every TELEMETRY field, decoded flags, ESP32 and phone parser errors.

**Settings:** the USB / Fake switch; FaultControls when Fake is selected.

**Safety paths** (CLAUDE.md: no path may drive a motor without a pulse):
- Motion exists only while the UI keeps refreshing it; the Arbiter drops TEST and RAW after 300 ms, the ESP32 failsafe covers everything else.
- Activity `onPause` → `session.stop()` (STOP ×3); the Test screen is disabled until `onResume`.
- Leaving the Test screen or the Raw tab → `stop()`.
- STOP and failsafe are instant; the app never ramps a stop.

**Errors:**
- no USB device: phase Disconnected, "Connect ESP32";
- permission denied: see §8;
- no HELLO_ACK: event "ESP32 not responding", handshake keeps retrying;
- proto mismatch: "Update firmware/app", motion controls disabled;
- app crash: nothing special; the ESP32 failsafe stops the motors (DESIGN §7).

## 10. Deviations from DESIGN.md

| DESIGN.md | This spec | Reason |
|---|---|---|
| minSdk 26 (§5.1) | minSdk 34 | single device (Pixel 9a, Android 15+); no compatibility code |
| Russian screen names (§5.9) | English UI text | decision 2026-09-30 |
| FakeLink chosen "in settings or when no device" (§5.5) | explicit switch only | an automatic fallback could hide a cable fault while you think you're driving the robot |
| DESIGN §5.4 arbiter emits STOP ×3 | `RobotSession.stop()` sends STOP ×3; the Arbiter only clears | keeps the Arbiter's output a pure function of commands and time |
| Foreground Service holds USB (§5.10) | application-scoped `AppGraph` | the service is needed with the server in stage 5; `RobotSession` has no Android dependencies and moves into it unchanged |
| module `fake` (§5.2) | plain JVM module | testable with JVM tests; no Android APIs needed |

## 11. Testing

**`:core:test`** (JUnit 5, kotlinx-coroutines-test):
- `Crc8`: every `crc8.vectors[]` entry.
- `FrameType`: table matches `types[]`.
- `FrameCodec`: every `frames[]` entry — encode `fields` → `frame_hex`, decode `frame_hex` → `fields`.
- `FrameParser`: every `streams[]` entry — feed `chunks` one call each, compare `expected_frames` and `expected_crc_err`; plus `flushStale`.
- `Arbiter`: priority order, 300 ms timeout per source, modes, zero pulse, RAW freshness, `stop()` clears everything, flags encoding, float → i8.
- `Mecanum`: PROTOCOL §2.1 signs (`vx=+1` → FL+, FR−, RL−, RR+; `w=+1` → left forward, right back), normalization, map/invert/trim, dead zone.
- `RobotSession` with a scripted in-memory `Link` on virtual time: no motion frames before a matching HELLO_ACK; HELLO retry; version mismatch blocks motion; 40 Hz cadence (40 ± 1 frames per virtual second); zero pulse without a source; STOP ×3 sent immediately on `stop()`; reboot detection across reconnects and duplicate HELLO_ACK ignored; RTT from PONG; reset to Disconnected on link loss.

**`:fake:test`:**
- FakeEsp32 against PROTOCOL §5: failsafe fires after `failsafe_ms` and clears on the next DRIVE; STOP → ACK OK; invalid CONFIG → ERR and unchanged CONFIG_DATA; BUSY in OTA/WIFI; OTA offset handling; telemetry at 10 Hz with pwm from the mix.
- Every FaultControl changes what the session observes (e.g. Reboot → reboot event; Wrong proto_ver → VersionMismatch).
- End to end: `RobotSession` + `FakeLink` drive for 1 s with enable=1, stop pulsing, telemetry shows failsafe within `failsafe_ms` + one telemetry period.

**Manual:**
- `:app` on FakeLink: every item in §9, every FaultControl.
- `:usb` + `:app` on the robot, wheels in the air, VM switch at hand: 8 directions from the sticks, each RAW channel both ways, STOP, release of Hold to drive, cable pull → motors stop, ESP32 reset → "ESP32 rebooted" event and recovery.

`:usb` and `:app` have no unit tests in this stage (thin wrappers, as with the firmware hardware layer). CI is not part of stage 3; the command to add later is `./gradlew :core:test :fake:test`.
