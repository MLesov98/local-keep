import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared instrumented-test infrastructure.
//
// This module ships nothing and no `main` source set depends on it: every consumer declares it as
// `androidTestImplementation`, so it never reaches an APK. It exists because an `androidTest` source
// set cannot be depended on by another module's `androidTest` source set — the only way for twelve
// instrumented modules to share one JUnit rule is a module of their own, and this repo had none
// (the guardrails in the root build read source; nothing shared code across device suites).
//
// It is an Android library, not a JVM one like `:test:fuzz`, because what it checks is the device
// itself — `PowerManager`, `KeyguardManager` — through the instrumentation's target context.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.localkeep.test.device"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
}

dependencies {
    // `TestRule`, `Statement`, `Description`.
    api(libs.junit)
    // `InstrumentationRegistry` — the only way to reach the target context from a rule.
    implementation(libs.androidx.test.runner)
}
