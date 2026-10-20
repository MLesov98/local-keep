import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localkeep.feature.vaultlist"
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

    // Robolectric inflates the merged manifest and resolves resources; without this the unit-test
    // classpath has neither and every Compose test fails before it renders anything.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":core:billing"))
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    // Inline "copy current code" on TOTP rows (B7) generates on tap — no per-row ticking.
    implementation(project(":core:totp"))
    implementation(project(":core:vault"))
    // Trash's read-only preview mounts SecureMediaViewer and reads FileBlobStore, both of which
    // live here. Trash used to be the one screen in the app where an entry could only be restored
    // or destroyed without ever being looked at, and for a scanned ID or a photograph the title
    // alone is not enough to decide with. No cycle: :feature:files depends on core modules only.
    implementation(project(":feature:files"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    // BackHandler: multi-select has to intercept Back so "never mind" leaves the selection rather
    // than the screen.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Every other screen in this app receives its ViewModel as a parameter from AppNavGraph
    // (:app). The vault list's chip strip needs a second ViewModel — FolderViewModel, for the
    // root-folder long-press menu — that the nav graph never wires in, and adding it there is
    // out of scope for a change confined to :feature:vaultlist. hiltViewModel() called inline
    // still resolves correctly: it binds to the nearest ViewModelStoreOwner, which for a
    // destination composable is the NavBackStackEntry AppNavGraph already provides.
    implementation(libs.androidx.hilt.navigation.compose)

    // Matches feature/files. This module had no unit-test dependencies at all, so the pure-Kotlin
    // helpers behind the list's copy had nowhere to be tested.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)

    // A vault row's geometry is an assertion about a measure pass, not about pixels: whether the
    // title and the trailing controls can be laid out side by side in a 308dp window at font scale
    // 2.0. Robolectric runs that measure pass on the JVM, so the check runs on every push rather
    // than only when a device happens to be attached — which is what an androidTest would have cost,
    // this being the widest window an accessibility font scale ever has to survive.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // The folder indicator is a fact about what a row draws, not about what a function returns —
    // filing an item into a folder used to change nothing anywhere the user was looking, and the
    // only way to assert that it now does is to compose the row and read it back.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Folder management writes to the vault, so its tests have to read back through a real
    // encrypt/decrypt cycle — a rename that only updated a StateFlow would pass a test of the
    // ViewModel and lose the name on the next unlock. KeystoreManager opens AndroidKeyStore in its
    // constructor, which is why this is instrumented rather than JVM; :feature:itemedit and
    // :core:vault both reached the same wall.
    androidTestImplementation(project(":core:crypto"))
}
