import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Phase 16 hardening fix: split out of :core:billing so air-gapped consumers (:credential) can
// depend on the [Entitlement] TYPE without pulling Play Billing Library's transitive Google Play
// Services / Firebase / okio dependency graph — those artifacts tripped the checkNoNetworkingDeps
// guardrail (root build.gradle.kts) for :credential's play-flavor runtime classpath, since
// `implementation(project(":core:billing"))` resolves the WHOLE play-flavor module (interface +
// billing-ktx + its GMS graph) onto the consumer's own runtime classpath — Gradle's api/implementation
// split only governs compile-time visibility to downstream consumers, not what's present at runtime.
// This module is unflavored and has no dependency beyond coroutines-core, so it carries no
// networking artifact of any kind.
android {
    // NOTE: this is the AGP namespace (governs generated R/BuildConfig classes only) and is
    // deliberately DIFFERENT from :core:billing's, to avoid a duplicate-namespace warning/clash —
    // it has nothing to do with the Kotlin source package, which stays `com.localkeep.core.billing`
    // (see Entitlement.kt) so every existing `import com.localkeep.core.billing.Entitlement`
    // call site keeps compiling unchanged.
    namespace = "com.localkeep.core.billing.api"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
    api(libs.kotlinx.coroutines.core)
}
