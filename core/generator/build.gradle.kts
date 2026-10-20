import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.localkeep.core.generator"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
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
    api(project(":core:common"))

    // Test-only: pins the suggested passphrase against the strength floor onboarding enforces.
    // Deliberately not `implementation` — the generator must not depend on the analyzer at runtime.
    testImplementation(project(":core:health"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
