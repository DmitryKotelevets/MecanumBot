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
