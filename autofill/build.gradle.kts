import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.autofill"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // F-47: SaveConfirmActivity and its unlock-to-save step are now built with the Compose design
    // system (LkTheme/LkScreenHeader/LkFormField/LkButtons) instead of hand-built Views, so this
    // module needs Compose — it did not before.
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

    // Robolectric needs the merged manifest/resources on the unit-test classpath to build a real
    // Context — the ConfirmDomainActivity intent/extras round-trip test needs one to obtain real
    // AutofillIds and Intent extras rather than platform stubs.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:common"))
    // Pure-JVM, SecureRandom only. The generated-password suggestion has to produce the same
    // password the in-app generator would, so it uses the same generator rather than a second one.
    implementation(project(":core:generator"))
    implementation(project(":core:model"))
    // Interface only — the flavour-specific implementations live in :core:billing and are bound by
    // the app. Cards are capped rather than gated on the free tier, so the save path has to be able
    // to ask which tier it is applying the cap for.
    implementation(project(":core:billing-api"))
    implementation(project(":core:vault"))
    implementation(project(":core:totp"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // SaveConfirmActivity's `setContent {}` (F-47) — the rest of the app's design system.
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    // For the ConfirmDomainActivity intent()/fieldsFromExtras() round trip: a real Context and real
    // AutofillIds are needed to prove the extras survive a genuine Intent/Bundle marshal, not a
    // platform stub that would pass regardless of what putExtra/getParcelableExtra actually do.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Compose measure/render on the JVM, for SaveConfirmScreenContentTest — proving the Compose
    // content hosts the same string-resource keys the old View-based screen did (F-47).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // Concealment on this surface can only be proved on a device: the reveal gate runs real Argon2
    // and the lookup goes through the live lock manager and an encrypted vault.
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
}
