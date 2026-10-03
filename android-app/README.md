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
