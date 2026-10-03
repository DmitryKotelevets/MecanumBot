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
