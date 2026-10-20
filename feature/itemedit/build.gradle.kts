import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.itemedit"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:billing"))
    implementation(project(":core:common"))
    implementation(project(":core:generator"))
    // zxcvbn, for the strength meter under a typed password. Already in the APK via
    // feature:onboarding and feature:health, so this adds a module edge and no weight — and it is
    // the only honest way to score a password the user typed rather than one this app generated.
    implementation(project(":core:health"))
    implementation(project(":core:location"))
    implementation(project(":core:model"))
    implementation(project(":core:totp"))
    implementation(project(":core:ui"))
    implementation(project(":core:vault"))
    // The camera, the Exif stripper and the blob store all live here. Added 2026-08-19 so the
    // Identity editor can capture a document photo itself instead of routing the user through
    // the attachments screen to reach the same three pieces. No cycle: :feature:files depends on
    // no feature module, and :feature:backup already takes this same dependency.
    implementation(project(":feature:files"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // The state class itself is plain Kotlin, so the rules it carries — what counts as a valid card
    // expiry — are testable on a JVM even though the ViewModel below is not.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // Instrumented, not JVM, and not by preference. ItemEditViewModel needs a real VaultRepository,
    // which needs a real VaultLockManager, whose KeystoreManager opens AndroidKeyStore in its
    // constructor — absent on a JVM and unimplemented under Robolectric. :core:vault already hit
    // exactly this and resolved it the same way (VaultLockManagerInstrumentedTest).
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))

    // Present, and currently unusable (an open follow-up). Compose UI tests
    // in *this* module never reach idle: a bare `LkScreen { Text("x") }` and a bare `LkTextField`
    // both time out with "pending recompositions", while the identical composables pass in
    // :core:ui's own androidTest on the same emulator, and a plain `Text` passes here. So the cause
    // is the module's test setup rather than either composable. Left wired so that whoever picks
    // that up does not have to rediscover the dependency list; nothing depends on it today.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
