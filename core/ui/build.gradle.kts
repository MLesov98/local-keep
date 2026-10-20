import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.localkeep.core.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
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

    // Robolectric inflates the merged manifest and resolves resources; without this the unit-test
    // classpath has neither and every Compose test fails before it renders anything.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core:common"))
    api(libs.androidx.fragment)
    // Re-auth prompt for revealing high-value secrets (RevealAuth).
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.ktx)

    // ProGate (14) is this module's first @Composable — :core:ui does NOT depend on :core:billing;
    // callers pass an already-collected Boolean (entitlement.isPro.collectAsStateWithLifecycle()).
    //
    // Same shape for `rememberExternalPickerLauncher`: it needs the activity-result APIs, but not
    // :core:vault — the caller supplies the two lifecycle lambdas.
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // QrScanner.kt (moved from :feature:itemedit 2026-09-10): the TOTP secret entry and the
    // Recovery Kit unlock flow both need to read a QR code back, and this is the one module both
    // already depend on.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // The contrast audit runs on a plain JVM: LkColors is a data class of Compose `Color`, which is
    // a value class over a ULong and needs no Android runtime to read channels off.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)

    // Compose measure/layout on the JVM, for the assertions that are about geometry rather than
    // pixels: a dialog's height bound and whether its actions can be scrolled to. Those need a
    // real measure pass and nothing else — no GPU, no device — so they belong here rather than in
    // androidTest, where they would only run when someone has a device attached.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // The focus ring is a drawing, and the only honest way to test a drawing is to look at the
    // pixels — the app's own windows are FLAG_SECURE, so a screenshot off the device is always black.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
    // DeviceReadyRule: a sleeping or keyguard-locked device fails fast with its own message,
    // instead of every Compose assertion failing as a layout defect. See test/device.
    androidTestImplementation(project(":test:device"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
