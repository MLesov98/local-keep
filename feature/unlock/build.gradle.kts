import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.unlock"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Declared solely so this module can depend on :feature:backup, which is flavoured — an
    // unflavoured consumer of a flavoured library is a variant ambiguity AGP cannot resolve.
    // :feature:itemedit carries the same dimension for the same reason. The unlock UI is identical
    // in both flavours; nothing here is flavour-specific, and there are no per-flavour source sets.
    // Added 2026-09-13 for F34's SAF restore, which reads a user-picked .vault through
    // VaultImporter.readContainerBytes. Renames this module's test tasks to the flavoured spelling
    // (testFossDebugUnitTest, connectedFossDebugAndroidTest) — .gitlab-ci.yml updated with it, and
    // checkInstrumentedModulesCovered fails the build if those two ever drift apart again.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    buildFeatures {
        compose = true
    }

    // Robolectric inflates the merged manifest and resolves resources; without this the unit-test
    // classpath has neither and every Compose test fails before it renders anything.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    implementation(project(":core:crypto"))
    implementation(project(":core:ui"))
    implementation(project(":core:vault"))
    // F34: the pre-unlock restore reads a user-picked .vault via VaultImporter.readContainerBytes.
    // VaultBundle is internal to :feature:backup, so the format cannot be re-implemented here.
    implementation(project(":feature:backup"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    // The Recovery Kit's "Scan QR code" button (RecoveryKit.parseQrCode wiring, 2026-09-10) checks
    // its own runtime CAMERA permission before opening :core:ui's QrScannerDialog.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric + Compose, the same harness :feature:vaultlist uses for its dialog tests. Added
    // 2026-09-13 for RestoreConfirmDialogNotDismissibleWhileWorkingTest: the decision under test is
    // "which controls does this dialog offer", which only a rendered semantics tree can answer.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // PreUnlockRestoreScreen takes an UnlockViewModel it only ever calls back into, so a mock is
    // enough to render it — constructing the real one needs a vault. Test-only.
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(project(":core:vault"))
    // F34's instrumented tests construct a real VaultImporter to drive the picked-file half of
    // the restore, and its constructor names FileBlobStore. Test-only: nothing in main touches it.
    androidTestImplementation(project(":feature:files"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
}
