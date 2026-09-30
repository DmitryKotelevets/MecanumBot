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
