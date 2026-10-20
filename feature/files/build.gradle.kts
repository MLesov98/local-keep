import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.files"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The flavours still exist because :app has them and a library must match the dimension, but
    // capture-to-vault is no longer split across them.
    //
    // Phase 16 made it play-only under the heading "foss asks for no camera". That heading was
    // already untrue when it was written — :feature:itemedit declares CAMERA in its own `main` for
    // TOTP QR scanning, so the merged foss manifest has carried it since 2026-07-29, and the
    // `checkFlavorPermissions` allowlist lists it for both flavours. The split therefore bought no
    // permission reduction at all; what it bought was a `Unit`-returning stub, so the free build
    // silently had no camera route into the vault. Reversed 2026-08-19 when the Identity type
    // gained a photograph-the-document flow, whose entire point is the camera.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    // See feature/settings/build.gradle.kts for why this is needed alongside Robolectric.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui")) // design system: theme, containers, indicators
    implementation(project(":core:vault"))
    implementation(project(":core:crypto"))
    implementation(project(":core:billing-api")) // Entitlement (Pro gate) — interface only, no networking (§I)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Both flavours — see the flavor note above. :feature:itemedit already ships these to foss.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    // Test-only — never reaches an APK. See feature/settings/build.gradle.kts for the same note.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
    // Compose UI under Robolectric, for the one defect a source read cannot see: whether the Files
    // screen can be scrolled to its last line in a constrained window (AttachmentPickerScrollsTest).
    // The same pair :feature:backup declares; test and debug only, so nothing reaches a release APK.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Instrumented only. Decoding an encrypted video is the one thing in this module a JVM test
    // cannot speak to at all: MediaPlayer/MediaMetadataRetriever are platform services, and the
    // whole question is whether the platform's own decoder can read through our MediaDataSource.
    // Same wall, same answer as :feature:itemedit — FileItemEditViewModel needs a real
    // VaultRepository, which needs a VaultLockManager, whose KeystoreManager opens AndroidKeyStore
    // in its constructor. Absent on a JVM, unimplemented under Robolectric.
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
}
