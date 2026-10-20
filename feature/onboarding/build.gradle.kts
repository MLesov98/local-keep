import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.onboarding"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
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
    // classpath has neither and every Compose test fails before it renders anything. Same reason
    // :core:ui carries it.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:crypto"))
    // Live zxcvbn strength scoring and the EFF passphrase suggestion on the create-password
    // step (UI/UX spec 3.2). Both already existed; onboarding simply could not reach them.
    implementation(project(":core:generator"))
    implementation(project(":core:health"))
    implementation(project(":core:ui"))
    implementation(project(":core:vault"))
    // QR encoding for the Recovery Kit export. Pure-JVM computation, no network and no Play
    // Services, so unlike :feature:itemedit's scanner this is safe in BOTH flavors — the kit is a
    // core security feature and must work in the permission-free `foss` build too.
    implementation(libs.zxing.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ProcessLifecycleOwner: stands in for the missing activity-result callback that pairs
    // VaultLockManager.beginExternalPicker/endExternalPicker around the Done step's Settings
    // excursions (openAutofillSettings/openCredentialProviderSettings) — see OnboardingViewModel's
    // settingsExcursionObserver, regression 4-2.
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)

    // Compose measure/layout on the JVM. StepColumn's frame is a geometry problem — whether the
    // form slot is given any height at all at a large font scale on a narrow window — and geometry
    // needs a real measure pass and nothing else. Same argument, and the same three dependencies,
    // as :core:ui's LkDialogBoundsTest.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // RecoveryKitScreen reads one StateFlow and calls back into its ViewModel; a mock with the
    // state stubbed renders it without a vault behind it. Test-only.
    testImplementation(libs.mockito.kotlin)
}
