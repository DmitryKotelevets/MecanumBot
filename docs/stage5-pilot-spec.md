# Stage 5 — Pilot: server, camera, pilot-web

Spec version 1.0, 2026-10-03. Status: approved design, implementation not started.

Sources: DESIGN.md §5.7, §5.8, §5.10, §6, §7, §10; stage3-android-spec.md (modules, Link, Arbiter, RobotSession as built); CLAUDE.md rules. This document narrows DESIGN.md to stage 5 and records the decisions DESIGN.md leaves open. Deviations are listed in §10.

---

## 1. Goal and scope

**Goal.** The operator drives the robot from a laptop browser on the home Wi-Fi and sees video from the Pixel's camera. The page is served by the robot app.

**Done when** (DESIGN §10, stage 5):
1. `./gradlew :core:test :fake:test :server:testDebugUnitTest :camera:testDebugUnitTest` is green.
2. On FakeLink: the Mac browser drives the model with video; releasing Shift clears ENABLE; closing the tab gives a zero DRIVE (no active source) within 300 ms; a second tab is a watcher and its STOP works.
3. On the robot (wheels in the air first): driving from the Mac with video works, including with the Pixel's screen off; turning off the Mac's Wi-Fi stops the motors within 300 ms (`failsafe_ms`).

**In scope:** new Android library modules `:server` and `:camera`; `RobotService` (Foreground Service) in `:app`; `pilot-web/` (HTML + CSS + one JS file); a small `:core` addition (`Arbiter.release`, `RobotSession.releaseLocal`); thermal guard for video; status bar, settings and notification changes.

**Out of scope (later):** access token and QR code; browser Gamepad API; HTTPS; hotspot mode and `bindProcessToNetwork`; WebRTC; gamepad on the robot phone (stage 6); calibration wizard (stage 4). No protocol change: PROTOCOL.md and vectors.json are untouched.

**Assumptions.** One operator device — a laptop (Mac) on the same router as the Pixel 9a. Keyboard and mouse are primary; touch sticks work but are secondary. The home LAN is trusted (no authentication).

## 2. Modules

```
android-app/
├── core/    + Arbiter.release(source), RobotSession.releaseLocal()
├── fake/    unchanged
├── usb/     unchanged
├── server/  com.android.library → :core; Ktor CIO, websockets, kotlinx.serialization
│   ├── PilotHub       — pure: connections, one driver + watchers, /ws messages → session
│   ├── PilotMessages  — @Serializable JSON for /ws, both directions
│   ├── MjpegWriter    — pure: multipart/x-mixed-replace framing
│   └── PilotServer    — Ktor routes (§6)
├── camera/  com.android.library; CameraX
│   ├── MjpegCamera    — ImageAnalysis → JPEG → StateFlow<JpegFrame?>
│   └── ThermalGuard   — pure: battery °C → VideoLevel with hysteresis
└── app/     + RobotService, Gradle task that syncs pilot-web/ into assets
```

- `PilotHub`, `PilotMessages`, `MjpegWriter` and `ThermalGuard` use no Android APIs, so they are tested as local unit tests (`testDebugUnitTest`) without Robolectric.
- `:server` sees the rest of the app only through: the current `RobotSession` (a `StateFlow<RobotSession?>`), a lambda `(path: String) -> ByteArray?` for static files, and an interface it defines:
  ```kotlin
  interface VideoSource {
      val frames: StateFlow<JpegFrame?>      // JpegFrame(bytes, capturedAt, n) — defined in :server
      val level: StateFlow<VideoLevel>       // NORMAL, REDUCED, OFF — defined in :server
      val tempC: StateFlow<Float?>
      fun addViewer()
      fun removeViewer()
  }
  ```
  `:server` and `:camera` do not depend on each other. `:camera` has its own output types; `RobotService` in `:app` adapts `MjpegCamera` + `ThermalGuard` to `VideoSource`. Tests use a fake `VideoSource`.
- Library versions (Ktor 3.x, CameraX) are fixed in the implementation plan.

## 3. Lifetime and ownership

- `AppGraph` (application-scoped) keeps owning the links and the active `RobotSession`, as in stage 3.
- `RobotService : LifecycleService` is a Foreground Service with type `camera|connectedDevice`. It owns `PilotServer`, `MjpegCamera` (bound to the service lifecycle), a `PARTIAL_WAKE_LOCK` and a `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock.
- Start: `MainActivity` requests `CAMERA` and `POST_NOTIFICATIONS`, then starts the service from the foreground (Android 14+ requires a camera FGS to start while the app is visible). Without `CAMERA`, the service starts with type `connectedDevice` only and video is `off`.
- Stop: only the notification's "Stop" action. It calls `session.stop()` (STOP ×3), stops the server and camera, releases the locks and calls `stopSelf()`. Closing or pausing the Activity does not stop the service.
- Notification: "MecanumBot — pilot at http://<ip>:8080", Stop action.

## 4. Remote control and safety

### 4.1 `/ws` messages (JSON)

```
pilot → robot  {"t":"drive","vx":0.0,"vy":0.5,"w":0.0,"en":true}   40 Hz while the deadman is held
               {"t":"stop"}
               {"t":"ping","ts":1234}
robot → pilot  {"t":"status","role":"driver"|"watcher","fw":"0.1","app":"0.1","mode":"AUTO"}
               {"t":"telemetry","vm":3.58,"pwm":[40,40,40,40],"failsafe":false,"fault":false,
                "usb":true,"phase":"READY","active":"REMOTE","rtt_ms":4,"rx_fps":40,
                "stops":0,"temp_c":31.5,"video":"normal","video_age_ms":60}
               {"t":"pong","ts":1234}
```

- `vx`, `vy`, `w` are −1..1 (clamped by the server), axes as in PROTOCOL §2.1. `en` is the deadman.
- `status` is sent on connect and whenever the role, the arbiter mode, or the firmware version changes. `fw` is `null` before HELLO_ACK.
- `telemetry` is sent at 10 Hz to every connection. `vm`, `pwm`, `failsafe`, `fault`, `rx_fps` are `null` before the first TELEMETRY frame. `usb` is "link Connected"; `phase` is `SessionState.phase`; `active` is `SessionState.activeSource` or `null`; `stops` is `SessionState.stops`.
- `video_age_ms` is the time since the latest JPEG was produced, or `null` when there is none.
- Unknown `t`, malformed JSON, or wrong field types: the message is ignored and a counter (`/api/status`) is incremented. The connection is never closed for that.

### 4.2 PilotHub

- The first connection becomes the **driver**; later ones are **watchers**.
- When the driver disconnects, the oldest watcher is promoted and receives a new `status`. It must press the deadman again (the page never carries a held deadman across a role change).
- `drive` from the driver → `session.update(Command(vx, vy, w, en, Source.REMOTE))`. `drive` from a watcher is dropped.
- `stop` from **any** connection → `session.stop()` (STOP ×3, all sources cleared).
- When the driver's socket closes, the hub sends `update(enable = false, REMOTE)` immediately rather than waiting for the arbiter's 300 ms expiry.
- The hub follows the active session from `AppGraph`. On a USB ↔ Fake switch it drops nothing: new commands go to the new session, and the next `status`/`telemetry` reflect it.
- The arbiter mode (`AUTO / LOCAL_ONLY / REMOTE_ONLY`) is applied by the Arbiter as today; in `LOCAL_ONLY` remote commands are accepted but have no effect, and `status.mode` tells the pilot why.

### 4.3 Local sources on pause (`:core` change)

- `Arbiter.release(source)`: forgets the command for that source.
- `RobotSession.releaseLocal()`: if the active output is TEST or RAW → `stop()` (STOP ×3, as in stage 3). Otherwise it clears TEST and RAW silently, and REMOTE keeps driving.
- `AppGraph.onForeground(false)` calls `releaseLocal()` instead of `stop()`. A phone call or notification therefore stops bench driving from the Test screen but does not interrupt a remote drive.

### 4.4 Safety on the pilot page

- `drive` is sent only while the deadman (Shift or the on-page Hold button) is down, at 40 Hz regardless of change. Releasing it sends one `en:false`.
- `blur`, `visibilitychange` (hidden) and socket close release the deadman.
- When `telemetry.stops` increases (a STOP from anywhere, including the robot's own UI), the page releases the deadman: motion needs a new press.
- The speed limit (slider) scales vx, vy, w in the browser; it resets to 30 % on every page load.
- Space or the STOP button sends `stop`. STOP is active for watchers too.
- WebSocket reconnects with backoff 0.5 s → 5 s. After a reconnect the page never resumes driving on its own.
- Unchanged below the page: the arbiter's 300 ms expiry and the ESP32 failsafe (`failsafe_ms`). A frozen browser or a Wi-Fi drop stops the robot within 300 ms at either layer.

## 5. Video (:camera)

- CameraX `ImageAnalysis`, `STRATEGY_KEEP_ONLY_LATEST`, YUV_420_888 → NV21 → `YuvImage.compressToJpeg(quality 60)`.
- Normal: 640×480, frames dropped to 15 fps. Reduced: 320×240, 8 fps.
- Bound to `RobotService`'s lifecycle, so it works with the screen off.
- The analyzer is attached only while the viewer count > 0 (open `/stream` clients plus in-flight `/snapshot.jpg` requests). The first frame after the first viewer arrives in roughly 300–500 ms.
- Output: `StateFlow<JpegFrame?>`, `JpegFrame(bytes: ByteArray, capturedAt: Long, n: Long)`. Set to `null` when the camera is unbound.
- Settings (DataStore, Settings screen): lens — ultra-wide (default) or main; if the ultra-wide is not available, main is used. Rotation 0 / 90 / 180 / 270 (default 0), applied as the target rotation. A change rebinds the camera.

### 5.1 ThermalGuard

- Battery temperature from the sticky `ACTION_BATTERY_CHANGED` (`EXTRA_TEMPERATURE`), read every 5 s by `RobotService`.
- `VideoLevel`: `NORMAL → REDUCED` at ≥ 40 °C, `REDUCED → OFF` at ≥ 45 °C; back `OFF → REDUCED` below 43 °C, `REDUCED → NORMAL` below 38 °C. One step per reading.
- `OFF` unbinds the camera (`ThermalGuard` has its own level enum in `:camera`; the adapter maps it to the server's `VideoLevel`); `/stream` clients stay connected and simply receive no new frames.
- The level and `temp_c` are in `/ws` telemetry and on the app's status bar. Temperature never blocks driving.

## 6. Server (:server)

Ktor CIO on `0.0.0.0:8080`.

| Route | Behaviour |
|---|---|
| `GET /` and `GET /<file>` | static files from `assets/pilot/` via the lambda; content type from the extension (`html`, `css`, `js`, `svg`, `png`, `ico`); 404 otherwise; `index.html` for `/` |
| `GET /stream` | `multipart/x-mixed-replace; boundary=frame`. Per-client coroutine collects the frame StateFlow (conflated): a slow client skips frames, never buffers them or slows others. Increments the viewer count while open |
| `GET /snapshot.jpg` | latest frame; waits up to 1 s for one if the camera was idle; 503 if none |
| `WS /ws` | PilotHub (§4) |
| `GET /api/status` | JSON: app and fw versions, link state, phase, driver connected, watcher count, video level, ignored-message count |

- `MjpegWriter` produces each part: `--frame\r\nContent-Type: image/jpeg\r\nContent-Length: N\r\n\r\n<bytes>\r\n`.
- The displayed URL uses the Wi-Fi IPv4 address from `ConnectivityManager` (`LinkProperties` of the Wi-Fi network); "no Wi-Fi" when there is none.

## 7. pilot-web/

Files: `index.html`, `pilot.css`, `pilot.js`. No build step, no libraries.

- Layout (laptop first): video fills the window; top bar — connection, role, RTT, VM, failsafe/fault, temperature, video level; bottom — speed limit, STOP, Hold button, two on-screen sticks (Pointer Events, mouse and touch).
- Keyboard: W/S → vy ±, A/D → vx ∓/±, Q/E → w (counter-clockwise / clockwise), Shift — deadman, Space — STOP. If both keyboard and a stick are active, the last input touched wins.
- Video: `<img src="/stream">`. On `error`, or when `video_age_ms` stays > 3 s, the src is reset with a cache-busting query.
- Latency indicator: red border around the video when RTT (ping once a second over `/ws`) > 200 ms or `video_age_ms` > 1000.
- Watcher: drive controls greyed out, STOP active, banner "Watching — another pilot is driving".
- Banners for: disconnected/reconnecting, `phase != READY`, failsafe, fault, video reduced/off with temperature, `mode` that excludes REMOTE.
- Delivery: a Gradle `Sync` task in `:app` copies `pilot-web/` (excluding `README.md`) into `build/generated/pilotAssets/pilot/`, registered as a generated assets directory. There is no checked-in copy under `android-app/app/src/main/assets/`.

## 8. App (:app)

- **Status bar:** pilot URL, driver connected yes/no, watcher count, video level and battery temperature.
- **Settings:** camera lens and rotation (§5).
- **Manifest:** permissions `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`; `<service android:foregroundServiceType="camera|connectedDevice">`. Cleartext HTTP is server-side only, so no network security config change is needed.
- `MainActivity.onPause` → `graph.onForeground(false)` → `releaseLocal()` (§4.3).

## 9. Docs to update

- `pilot-web/README.md`: files, keyboard map, how it is delivered (Gradle sync).
- `CLAUDE.md`: pilot-web line (copied by a Gradle task, not by hand); test command with `:server:testDebugUnitTest :camera:testDebugUnitTest`.
- `android-app/README.md`: the service, permissions, the pilot URL.

## 10. Deviations from DESIGN.md

| DESIGN.md | This spec | Reason |
|---|---|---|
| Access token in a QR code (§5.7) | none; anyone on the LAN can drive (one driver at a time) | laptop on a trusted home LAN; a laptop can't scan the QR. Deferred |
| `seq` in `drive` (§5.7) | dropped | nothing reads it; freshness is the arbiter's 300 ms expiry |
| onPause → STOP ×3 and stop tick (§5.4, §5.10) | onPause releases TEST/RAW only; STOP ×3 only if they were driving; REMOTE continues | decision 2026-10-03: remote driving with the screen off saves battery and heat; the deadman and both 300 ms layers still protect the robot |
| Camera needs the Activity (§5.10) | camera bound to the Foreground Service lifecycle | needed for driving with the screen off |
| Server listens on the Wi-Fi address with `bindProcessToNetwork` (§5.7) | `0.0.0.0`, no network binding | home router only; needed for the hotspot, deferred with it |
| Gamepad API in the pilot (§6) | deferred | not needed for the first laptop drives; plain HTTP may block it |
| Video always encoded | encoded only while someone views | no CPU and heat cost when nobody watches |
| Thermal levels "lower resolution/fps" (§5.8) | 320×240 at 8 fps; off at 45 °C; 2 °C hysteresis | concrete values |
| Watcher's commands ignored (§5.7) | watcher `drive` ignored, watcher `stop` honoured | a STOP button that does nothing is a trap |
| Modules `server`, `camera` | Android library modules (logic classes Android-free) | decision 2026-10-03: simpler build; logic still has local unit tests |

## 11. Testing

**`:core:test`** (added):
- `Arbiter.release`: the released source stops being active at once; other sources unaffected.
- `RobotSession.releaseLocal`: with TEST active → STOP ×3 sent and all cleared; with RAW active → same; with only REMOTE active → no STOP, REMOTE still the active source on the next tick.

**`:server:testDebugUnitTest`:**
- `PilotMessages`: JSON round-trip of every message; malformed JSON, unknown `t`, wrong types → ignored and counted; out-of-range speeds clamped.
- `PilotHub` with a fake session: first connection is driver, second is watcher; watcher `drive` dropped; watcher `stop` → `session.stop()`; driver close → `enable=false` update and the oldest watcher promoted with a new `status`; session swap routes later commands to the new session; telemetry built from `SessionState` with `null`s before TELEMETRY.
- `MjpegWriter`: exact part bytes.
- Ktor `testApplication`: `/ws` drive reaches a `RobotSession` on `FakeLink` and `activeSource` becomes REMOTE; second socket gets `role: watcher`; `/snapshot.jpg` 503 with no frame, 200 with `image/jpeg` after one; static content types; 404 for a missing file; `/api/status` fields.

**`:camera:testDebugUnitTest`:**
- `ThermalGuard`: thresholds, hysteresis, one step per reading.

**Manual (pilot checklist):**
- FakeLink, Mac browser: video visible; Shift + W drives (telemetry `active: REMOTE`, ENABLE set); releasing Shift clears ENABLE; window blur releases; Space stops; closing the tab → zero DRIVE within 300 ms; second tab is a watcher, its STOP works, it becomes driver when the first tab closes and needs a new Shift press; a STOP from the app's Test screen releases the pilot's deadman; latency border appears when Wi-Fi is throttled.
- App: notification shows the URL; Stop in the notification stops everything; Settings lens/rotation change the stream; screen off — driving and video continue; Test screen in use → pressing Home sends STOP ×3.
- Robot, wheels in the air, VM switch at hand: 8 directions from the keyboard and sticks; Mac Wi-Fi off → motors stop within 300 ms; screen off on the Pixel while driving — still controllable.
- Thermal: inject temperatures in a debug build (or warm the phone) and check the reduced/off banners.

`RobotService`, `MjpegCamera` and `PilotServer`'s Android glue have no unit tests (thin wrappers, as with `:usb`).
