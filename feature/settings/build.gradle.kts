import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.settings"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    // Same "distribution" dimension as :app/:core:billing — required to disambiguate this
    // module's dependency on flavor-split :core:billing (see :credential's build.gradle.kts note).
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    // Robolectric needs the merged manifest/resources on the unit-test classpath to build a real
    // Context; without this, @Config(application = ...)-less ViewModel tests fail resolving
    // resources the moment a Compose/AndroidX class they touch reaches for one.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":autofill"))
    implementation(project(":core:billing"))
    implementation(project(":core:crypto"))
    // About surfaces the breach-corpus vintage (spec 10.1). The BreachFilter binding
    // itself is provided per-flavor by :feature:health; this is only the type.
    implementation(project(":core:health"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:vault"))
    // BlobGarbageCollector only. Configuring or removing the decoy are the only two moments a blob
    // sweep is safe on a vault that has one — see SettingsViewModel.setupDuress/removeDuress — and
    // the collector lives here because :core:vault cannot reach the blob directory.
    implementation(project(":feature:files"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    // Test-only — never reaches an APK. Gives ViewModel tests a real (shadowed) Context instead
    // of an instrumented device.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
    // Compose measure/layout on the JVM. Whether a focused field ends up under the keyboard is a
    // geometry question, and geometry needs a real measure pass and nothing else. Same three
    // dependencies, and the same argument, as :feature:onboarding's PasswordStepImeScrollTest.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
