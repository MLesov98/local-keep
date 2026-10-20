import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.importwizard"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    // Declared 2026-09-17, and not because anything here differs between the two builds — no
    // `src/foss` or `src/play` source set exists in this module and none is intended. It is the
    // price of depending on `:feature:files`, which is flavoured: an unflavoured consumer cannot
    // choose between its `fossDebugApiElements` and `playDebugApiElements`, and a
    // `missingDimensionStrategy` would be worse than this — it pins ONE variant, so a play app
    // would resolve `:feature:files` twice, foss through here and play through `:feature:vaultlist`.
    // Both variants of this module are identical, exactly as `:feature:files`' own two are.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    buildFeatures {
        compose = true
    }

    // Robolectric needs the merged manifest and resources on the unit-test classpath: the preview
    // step's regression test composes real `stringResource`/`pluralStringResource` copy at a chosen
    // window size.
    testOptions.unitTests.isIncludeAndroidResources = true

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
    // The Entitlement TYPE only — :core:billing-api is unflavored and carries no Play Billing/GMS
    // graph, so this module pulls no networking artifacts and stays on the air-gap guardrail's
    // list. (It gained product flavours in 2026-09-17 for the `:feature:files` dependency below;
    // that is a variant-resolution matter and drags in no billing or GMS graph.) Cards and
    // identities are Pro (§11), and the wizard is the one place they enter the vault.
    implementation(project(":core:billing-api"))
    implementation(project(":core:common"))
    implementation(project(":core:import"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:vault"))
    // The encrypted blob store, for the files a `.1pux` archive carries beside its records. Read
    // only: this module writes imported files through `FileBlobStore.encryptToBlob`, the same call
    // the item editor uses, and owns nothing in there.
    implementation(project(":feature:files"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    // The preview step renders in a window whose height it does not choose. Its landscape defect —
    // zero item rows beside an enabled "Import N items" — was invisible to every non-rendering test
    // in this module, so the module grows the same Robolectric + Compose harness `:autofill` and
    // `:credential` already use rather than leaving the fix unpinned.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The ViewModel itself, driven with its vault-side collaborators mocked — the same pair
    // `:feature:backup` drives its ViewModel with. Added 2026-10-02 for the apply-retry regression,
    // which no source-text assertion could pin.
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
}
