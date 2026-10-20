import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.core.billing"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    // Same dimension + flavor NAMES as :app (Phase 1) — this is what makes AGP compile
    // src/play/kotlin only into :app's play* variants and src/foss/kotlin only into foss*.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
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
    api(project(":core:common"))
    // Entitlement interface lives in :core:billing-api (Phase 16 hardening fix — keeps it
    // reachable without the Play Billing/GMS transitive graph for air-gapped consumers).
    api(project(":core:billing-api"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // play flavor ONLY — Play Billing Library (the sole consumer of play's INTERNET permission).
    // DEVIATION from plan: the base `billing` artifact does NOT include the Kotlin suspend
    // extensions — originally verified by inspecting the resolved 7.1.1 jar (no
    // BillingClientKotlinKt class there), and still true on the 8.0.0 now pinned in
    // libs.versions.toml. They live in the separate `billing-ktx` artifact, which is what
    // actually supplies queryProductDetails/queryPurchasesAsync/acknowledgePurchase as suspend
    // funs. Both are `playImplementation`-only.
    "playImplementation"(libs.billing)
    "playImplementation"(libs.billing.ktx)
    // play flavor ONLY — local offline cache of the entitlement boolean (catalog alias already
    // exists from an earlier phase: androidx-datastore-preferences / datastore = "1.2.1").
    "playImplementation"(libs.androidx.datastore.preferences)

    // foss flavor: deliberately zero extra dependencies here — FossEntitlement is two fields.

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    // Virtual time for ConnectBudgetTest: the budgets it pins are seconds long.
    testImplementation(libs.kotlinx.coroutines.test)
}
