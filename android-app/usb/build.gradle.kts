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
