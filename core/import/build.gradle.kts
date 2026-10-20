import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.kotlinx.serialization.json)
    // KeePassJava2 drags in two artifacts an offline vault app (roadmap §I) has no business
    // shipping — both excluded here:
    //   - ch.qos.logback (a logging backend): SLF4J with no backend safely no-ops; slf4j-api stays
    //     so the library still links.
    //   - org.apache.httpcomponents:httpcore (HTTP protocol primitives, §I-forbidden networking
    //     group): proven unused by the KDBX load path via KdbxRoundTripTest, which creates and
    //     re-imports a real .kdbx entirely in-JVM with httpcore absent from the classpath.
    //
    // The flavour choice is load-bearing and is the fix for the B4a defect. KeePassJava2 ships one
    // artifact per XML backend, and only one of them runs on Android:
    //   - `simple` (what this used to be) is backed by simple-xml, which needs the StAX API
    //     (javax.xml.stream). Android has never shipped StAX, so every parse failed on-device —
    //     surfaced to the user as "wrong password or corrupted file" — while passing in JVM tests,
    //     because the JVM *does* have StAX.
    //   - `jackson` is no better: it pulls jackson-dataformat-xml and woodstox-core, and Woodstox
    //     is a StAX *implementation* — it needs the very javax.xml.stream interfaces Android lacks.
    //   - `dom` uses org.w3c.dom / javax.xml.parsers, which Android has always shipped. Its only
    //     transitive dependency is KeePassJava2-kdbx (-> database, commons-codec).
    // `checkNoJvmOnlyXmlApis` in the root build file fails the build if a StAX-referencing artifact
    // ever reappears here; a unit test cannot catch this, because the JVM satisfies it.
    implementation(libs.keepassjava2.dom) {
        exclude(group = "ch.qos.logback")
        exclude(group = "org.apache.httpcomponents")
        // JUnit and Hamcrest, in the SHIPPING APK. `org.linguafranca.pwdb:util` declares junit at
        // compile scope in its POM, so it and hamcrest-core rode the whole way onto
        // fossReleaseRuntimeClasspath — dead weight in a release artifact, and a licence problem
        // besides: junit is EPL-1.0 and hamcrest is BSD, neither of which is the Apache-2.0 that
        // covers the rest of this graph. Found by the About screen's licence-coverage test once it
        // was pointed at what actually ships rather than at the version catalog.
        //
        // Safe to drop for the same reason httpcore was: KdbxRoundTripTest creates and re-imports a
        // real .kdbx entirely in-JVM, so a load path that actually needed these would fail loudly.
        exclude(group = "junit")
        exclude(group = "org.hamcrest")
    }

    // Argon2id, which KeePassJava2 2.2.4 cannot derive (see KdbxArgon2id.kt). The library already
    // pulls BouncyCastle in for its own Argon2d and AES-KDF, so this adds nothing to the shipping
    // graph — `app/sbom/dependencies.txt` already resolves bcprov to the version `:core:crypto`
    // pins — but the usage here is direct and so is declared rather than borrowed transitively.
    implementation(libs.bouncycastle)

    // Dependency-safety regression test only (proves the httpcore exclusion above doesn't break
    // KDBX import). This is a deliberate, narrow exception to Phase 12's "zero automated tests"
    // mandate — it guards a §I no-network invariant, not importer parsing logic.
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}

// NOTE: the `--add-opens java.base/java.util=ALL-UNNAMED` that used to live here is gone with the
// `simple` flavour. It existed because simple-xml reflected on java.util.UUID's private fields,
// which JDK 17+ strong encapsulation blocks. The `dom` backend reads UUIDs out of DOM text nodes
// and reflects on nothing, so the flag is no longer needed — and leaving it would quietly hide a
// reflection regression if the backend were ever swapped again.
