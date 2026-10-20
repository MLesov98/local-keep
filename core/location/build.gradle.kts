import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.localkeep.core.location"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        // Only ever exercised by the field-measurement harness, which is why there is no
        // androidTest source set anywhere else in this module. Nothing here reaches an app APK.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

// Deliberately short. This module owns one platform call and the rule that decides whether its
// answer is acceptable — no Compose, no vault, no display strings, and in particular no
// `com.google.android.gms:play-services-location`. The Play flavour's dependency graph already
// contains that artifact transitively through billing; `checkNoNetworkingDeps` (which enrols this
// module) is what proves it never arrives here, in either flavour.
dependencies {
    api(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // The field harness only. It talks to the real GNSS receiver, so it is guarded by an
    // instrumentation argument and skips itself on any ordinary `connectedAndroidTest` run.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
}
