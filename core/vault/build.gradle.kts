import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.localkeep.core.vault"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        // Six of this module's suites are instrumented — vault lock management, KDF rewrap, the
        // stale-container sweep, atomic writes, the device keystore, the reminder clock. Measuring
        // only JVM unit tests reports 31.6% for a module whose real verification runs on a device.
        debug {
            enableAndroidTestCoverage = true
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            // Required by Jazzer's @FuzzTest (a JUnit 5 extension). The nine existing JUnit 4
            // classes keep running through junit-vintage-engine — see the dependency block.
            it.useJUnitPlatform()
            // A phone-sized heap, so an allocation from an attacker-controlled length field is a
            // failure here rather than something a 4 GB CI heap absorbs. Same reasoning as
            // `:test:fuzz`; the existing unit tests are well under this.
            it.maxHeapSize = "512m"
            // Gradle does not forward `-D`/`-P` to the test JVM, so the seed writer's
            // @EnabledIfSystemProperty guard would never see it.
            val writeSeeds = providers.gradleProperty("writeSeeds").getOrElse("false")
            it.systemProperty("writeSeeds", writeSeeds)
            it.inputs.property("writeSeeds", writeSeeds)
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:crypto"))
    api(project(":core:model"))

    implementation(libs.kotlin.bip39)

    // AppSignatureCache moved here from :autofill so the Credential Manager provider and the autofill
    // service share one signing-certificate pin store (see the class kdoc). It carries an @Inject
    // constructor + @ApplicationContext, auto-provided by Hilt in :app — this module needs only the
    // annotations and the DataStore runtime on its classpath, not the Hilt plugin.
    implementation(libs.hilt.android)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Jazzer, for the one fuzz target that cannot live in `:test:fuzz`. VaultHeaderCodec.decode is
    // the purest parser in the app — fixed-size, key-free, dispatching on a version field — and the
    // fuzzing plan put it first for exactly that reason, assuming `:test:fuzz` could depend on
    // `:core:vault`. It cannot: this is an Android library, and a JVM module cannot consume its
    // variants. So the target lives here instead.
    //
    // The cost is that this module's unit tests must move to the JUnit platform (Jazzer's @FuzzTest
    // is a JUnit 5 extension). junit-vintage-engine keeps the nine existing JUnit 4 classes running
    // — verified by count, not by assumption: 58 tests in 9 classes before the switch and after.
    // Checking that mattered, because a vintage engine that failed to load would not fail the
    // build; it would silently run zero of them, which is the exact defect this whole workstream
    // exists to end.
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test"))
}
