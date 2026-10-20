import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    api(project(":core:model"))
    api(project(":core:common"))
    implementation(libs.zxcvbn)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}

/**
 * Maintainer-run corpus builder (release gate D1). NEVER wired into CI or any assemble task —
 * `BuildBreachFilterAsset`'s own KDoc is explicit that it is run by hand, before a release that
 * refreshes the breach-corpus vintage.
 *
 * It exists as a task because the documented alternative was a hand-assembled `kotlin -cp <runtime
 * classpath>` incantation, which nobody gets right twice and which silently picks up whatever
 * classpath the shell happens to have. A task makes the one input that matters — which corpus was
 * used — reproducible and reviewable.
 *
 *   ./gradlew :core:health:buildBreachFilter \
 *       -Pcorpus=/path/to/corpus.txt \
 *       -Pvintage="SecLists Pwdb_top-10000000, 2026-07"
 *
 * The corpus path is deliberately NOT defaulted and never committed: the input is tens of MB of
 * plaintext passwords, and the licensing of what may be redistributed in derived form is a decision
 * the maintainer makes per release, not a thing the build should assume.
 */
tasks.register<JavaExec>("buildBreachFilter") {
    group = "localkeep"
    description = "Builds app/src/main/assets/breached-passwords.filter from a plaintext corpus."
    mainClass.set("com.localkeep.core.health.BuildBreachFilterAssetKt")
    classpath = sourceSets["main"].runtimeClasspath
    // The builder holds the whole corpus and its derived bucket list in memory at once. Ten million
    // passwords do not fit in a default heap.
    maxHeapSize = "6g"
    val corpus = providers.gradleProperty("corpus")
    val vintage = providers.gradleProperty("vintage")
    val output = rootProject.layout.projectDirectory
        .file("app/src/main/assets/breached-passwords.filter").asFile.path
    doFirst {
        require(corpus.isPresent) { "pass -Pcorpus=<path to plaintext corpus>" }
        require(vintage.isPresent) { "pass -Pvintage=<what this corpus is, shown in About>" }
    }
    argumentProviders.add {
        listOf(corpus.getOrElse(""), output, vintage.getOrElse(""))
    }
}
