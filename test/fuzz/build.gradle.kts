import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The fuzz harness.
//
// This module ships nothing. Nothing depends on it, and Jazzer must never reach a shipping
// classpath — which is why the targets live here rather than in each module's own test source set.
//
// It is pure JVM on purpose, and that is not a preference: `:core:import` and `:core:health` are
// pure Kotlin/JVM modules whose parse entry points are pure functions, so a fuzzer drives them with
// no emulator, no APK and no instrumentation. That is what makes this the cheapest high-value item
// on the roadmap.
//
// `:core:vault`'s VaultHeaderCodec is the one target that does NOT fit here: `:core:vault` is an
// Android library, and a JVM module cannot consume its variants. The fuzzing plan assumed otherwise.
// Its target lives in `core/vault/src/test/` instead — see VaultHeaderCodecFuzzTest there.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(project(":core:import"))
    testImplementation(project(":core:health"))
    testImplementation(project(":core:common"))
    testImplementation(project(":core:model"))
    // Only the seed generator needs this: it builds a real .kdbx in memory, the way
    // KdbxRoundTripTest does, so the KDBX corpus is a genuine container rather than something
    // hand-assembled. `:core:import` keeps it `implementation`, so it is not on this classpath
    // transitively — and it should not be.
    testImplementation(libs.keepassjava2.dom) {
        exclude(group = "ch.qos.logback")
        exclude(group = "org.apache.httpcomponents")
    }
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jazzer.junit)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // 256 MB, not the 4 GB the rest of this build runs with. Property 2 of the oracle is "no
    // unbounded allocation from an attacker-controlled length field", and that property is only
    // observable on a heap the size of a phone's. A desktop-sized heap shrugs off exactly the
    // allocation this is looking for.
    maxHeapSize = "256m"

    // Without JAZZER_FUZZ the @FuzzTest methods run as ordinary parameterised tests over the
    // committed seed corpus — fast, deterministic, and safe for the daily `gate`. With
    // JAZZER_FUZZ=1 the same methods become real libFuzzer campaigns. One set of targets, two
    // modes, so a campaign and a regression run can never drift apart.
    environment("JAZZER_FUZZ", providers.environmentVariable("JAZZER_FUZZ").getOrElse(""))

    // Gradle does not forward `-D` to the test JVM, so SeedCorpusWriter's @EnabledIfSystemProperty
    // guard would never see it. Passed explicitly, and declared as an input so that flipping it
    // does not hit an UP-TO-DATE test task.
    val writeSeeds = providers.gradleProperty("writeSeeds")
        .orElse(providers.systemProperty("writeSeeds"))
        .getOrElse("false")
    systemProperty("writeSeeds", writeSeeds)
    inputs.property("writeSeeds", writeSeeds)

    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
