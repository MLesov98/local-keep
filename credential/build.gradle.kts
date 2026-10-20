import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.credential"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // No flavor dimension here, and no billing dependency at all since passkeys became free. Kept
    // as a note because the shape of the trap outlives this module's use of it: a flavor dimension
    // once lived here purely to disambiguate a dependency on the flavored :core:billing, since AGP
    // resolves each module's classpath using that module's OWN variant attributes and depending on
    // a flavor-split module from an unflavored one is ambiguous. The fix was the unflavored,
    // interface-only :core:billing-api — :core:billing's play flavor drags Play Billing's whole
    // GMS/Firebase/okio graph, which checkNoNetworkingDeps caught leaking onto this air-gapped
    // module's runtime classpath. Anything added here that wants a tier must go through
    // :core:billing-api, never :core:billing.

    // CredentialActivity's native-binding consent is an LkDialog, not a platform AlertDialog
    // (V3-06). It is the only Compose in this module, and it is here because a consent gate that
    // does not look like the rest of the app is asking to be trusted while looking like something
    // else.
    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric inflates the merged manifest and resolves resources; without this the unit-test
    // classpath has neither and every Compose test fails before it renders anything.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
}

dependencies {
    implementation(project(":core:crypto"))
    implementation(project(":core:model"))
    implementation(project(":core:totp"))
    implementation(project(":core:ui"))
    implementation(project(":core:vault"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.upokecenter.cbor)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    // Robolectric + Compose, the same harness :feature:vaultlist and :feature:unlock use for their
    // dialog tests. The question NativeBindingDialogTest asks — which controls does this consent
    // offer, and what does each one say — only a rendered semantics tree can answer.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    // The passkey paths need a real DEK and the reveal gate runs real Argon2 — neither exists on the
    // JVM, and this is the surface where a concealment leak is visible to a remote relying party.
    androidTestImplementation(project(":core:crypto"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
}
