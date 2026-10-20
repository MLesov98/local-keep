import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.backup"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    // Same "distribution" dimension as :app/:core:billing — required to disambiguate this module's
    // dependency on flavor-split :feature:files (see :feature:vaultlist's build.gradle.kts note).
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
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
    // See feature/settings/build.gradle.kts for why this is needed alongside Robolectric.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:crypto"))
    // StrengthAnalyzer, for the export passphrase's strength floor — the same analyser and the same
    // floor :feature:onboarding and :feature:settings apply to the master password. Pure JVM, no
    // network, no Android dependency.
    implementation(project(":core:health"))
    implementation(project(":core:vault"))
    implementation(project(":core:ui"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    // Test-only — never reaches an APK. See feature/settings/build.gradle.kts for the same note.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
    // Compose UI under Robolectric, for the one arrival a scroll position decides
    // (BackupOpensOnTheImportSectionTest). The same pair :feature:itemedit declares; test and debug
    // only, so nothing reaches a release APK.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // FileBlobStore + BlobGarbageCollector: an export has to carry the encrypted media its items
    // point at, or restoring it produces a vault full of references to files that are not there.
    // Depending on a flavored module is what forces the distribution dimension declared above.
    implementation(project(":feature:files"))

    // No androidx.work. WorkManager was here for one daily reminder and injected five permissions
    // (WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, and the
    // POST_NOTIFICATIONS this module declared) into every build. See [StaleExportReminder].
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
