import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.localkeep.passkeytest"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.localkeep.passkeytest"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
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
    // The relying-party half of the passkey APIs. `credentials-play-services-auth` is deliberately
    // absent: it is the pre-API-34 fallback that routes ceremonies through Google Play services,
    // and this app exists to exercise LocalKeep's own CredentialProviderService, which the platform
    // only offers from API 34 up.
    implementation(libs.androidx.credentials)
    implementation(libs.kotlinx.coroutines.android)
}
