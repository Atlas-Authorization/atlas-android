// Atlas — the official Android SDK for the Atlas auth platform.
//
// Deliberately a plain Kotlin/JVM library, not an Android application module: the
// auth core is pure Kotlin (HttpURLConnection + coroutines + a hand-rolled JSON
// reader), so it needs no Android SDK to build or unit-test. The one Android-only
// piece — EncryptedSharedPreferences — is reached through the `KeyValueStore`
// seam (see TokenStore.kt), so this module stays toolchain-light while remaining
// a drop-in for an Android app. Add the `com.android.library` plugin + an
// androidx.security dependency when wiring it into a full Android build.
plugins {
    kotlin("jvm") version "2.2.20"
    `java-library`
}

group = "com.atlas"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    // No jvmToolchain pin: this box has only JDK 25, so pinning 17 would force a
    // network toolchain provision. Target 17 bytecode from whatever JDK runs Gradle.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
