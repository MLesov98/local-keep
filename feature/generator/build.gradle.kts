import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.generator"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }
    // Robolectric inflates the merged manifest and resolves resources; without this the unit-test
    // classpath has neither and a Compose test fails before it renders anything. Same reason
    // :feature:onboarding and :core:ui carry it.
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:generator"))
    implementation(project(":core:ui"))
    // The generated value is a plaintext candidate password held in UI state, so this screen has to
    // watch the lock like every other holder of decrypted material does.
    implementation(project(":core:vault"))
    implementation(libs.kotlinx.coroutines.android)
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
    // VaultLockManager's real construction opens AndroidKeyStore, which does not exist on the JVM;
    // only its lockState flow matters here. Same reason feature/settings mocks it.
    testImplementation(libs.mockito.kotlin)
    // Compose measure/layout on the JVM. Whether a focused field ends up under the keyboard is a
    // geometry question, and geometry needs a real measure pass and nothing else. Same three
    // dependencies, and the same argument, as :feature:onboarding's PasswordStepImeScrollTest.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
