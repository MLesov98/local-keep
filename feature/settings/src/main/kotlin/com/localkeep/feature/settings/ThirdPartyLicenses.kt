package com.localkeep.feature.settings

/**
 * The third-party notices shown under About (UI/UX spec §10.1).
 *
 * Every license here was read out of the dependency's own POM rather than recalled — several are
 * not what you would guess (the CBOR library is Unlicense, Play Billing is not open source at all).
 *
 * [aliasFor] keys each library to its `gradle/libs.versions.toml` alias so `ThirdPartyLicensesTest`
 * can fail the build when a dependency is added without a notice. A licence list that silently goes
 * stale is worse than none: it makes a false statement about what the app redistributes.
 */
object ThirdPartyLicenses {

    class Library(
        /** Alias in `gradle/libs.versions.toml`. */
        val alias: String,
        /** Human-facing coordinates, e.g. "androidx.biometric:biometric". */
        val coordinates: String,
    )

    class Group(
        val license: String,
        val url: String,
        val libraries: List<Library>,
        /**
         * Present only in the `play` build.
         *
         * A `foss` install ships no billing and no Play Core code at all, so listing their licences
         * there told the reader this build contains dependencies it does not have — on the one
         * screen whose entire job is stating truthfully what is in the binary. The licence names
         * already say "(play build only)"; that is a note to whoever maintains this list, not an
         * instruction the renderer was following.
         */
        val playOnly: Boolean = false,
    )

    private fun lib(
        alias: String,
        coordinates: String,
    ) = Library(alias, coordinates)

    val groups: List<Group> = listOf(
        Group(
            license = "Apache License 2.0",
            url = "https://www.apache.org/licenses/LICENSE-2.0",
            libraries = listOf(
                // --- Transitive, added 2026-07-29 ---------------------------------------------
                // These arrive through other dependencies and have no version-catalog alias, which
                // is exactly why nothing flagged them: the coverage test used to diff the catalog,
                // i.e. the dependencies somebody CHOSE, rather than what actually ships. A
                // transitive artifact is redistributed just as thoroughly as a direct one.
                // Every licence below was read from the artifact's own POM (or its parent, noted
                // where that was needed) in the Gradle cache — none is guessed.
                lib("okio", "com.squareup.okio:okio"), // via androidx.datastore
                lib("commons-codec", "commons-codec:commons-codec"), // via KeePassJava2; licence from org.apache.commons:commons-parent
                lib("jakarta-inject-api", "jakarta.inject:jakarta.inject-api"), // via Hilt/Dagger
                lib("javax-inject", "javax.inject:javax.inject"), // via Hilt/Dagger
                lib("jspecify", "org.jspecify:jspecify"),

                lib("androidx-annotation", "androidx.annotation:annotation"),
                lib("androidx-core-ktx", "androidx.core:core-ktx"),
                lib("androidx-activity", "androidx.activity:activity"),
                lib("androidx-activity-compose", "androidx.activity:activity-compose"),
                lib("androidx-lifecycle-process", "androidx.lifecycle:lifecycle-process"),
                lib("androidx-lifecycle-runtime-ktx", "androidx.lifecycle:lifecycle-runtime-ktx"),
                lib("androidx-lifecycle-runtime-compose", "androidx.lifecycle:lifecycle-runtime-compose"),
                lib("androidx-lifecycle-viewmodel-compose", "androidx.lifecycle:lifecycle-viewmodel-compose"),
                lib("androidx-compose-bom", "androidx.compose:compose-bom"),
                lib("androidx-compose-ui", "androidx.compose.ui:ui"),
                lib("androidx-compose-ui-tooling", "androidx.compose.ui:ui-tooling"),
                lib("androidx-compose-ui-tooling-preview", "androidx.compose.ui:ui-tooling-preview"),
                lib("androidx-compose-material3", "androidx.compose.material3:material3"),
                lib("androidx-compose-material-icons-core", "androidx.compose.material:material-icons-core"),
                lib("androidx-navigation-compose", "androidx.navigation:navigation-compose"),
                lib("androidx-hilt-navigation-compose", "androidx.hilt:hilt-navigation-compose"),
                lib("androidx-biometric", "androidx.biometric:biometric"),
                lib("androidx-fragment", "androidx.fragment:fragment-ktx"),
                lib("androidx-camera-core", "androidx.camera:camera-core"),
                lib("androidx-camera-camera2", "androidx.camera:camera-camera2"),
                lib("androidx-camera-lifecycle", "androidx.camera:camera-lifecycle"),
                lib("androidx-camera-view", "androidx.camera:camera-view"),
                lib("androidx-autofill", "androidx.autofill:autofill"),
                lib("androidx-datastore-preferences", "androidx.datastore:datastore-preferences"),
                lib("androidx-credentials", "androidx.credentials:credentials"),
                lib("androidx-exifinterface", "androidx.exifinterface:exifinterface"),
                lib("androidx-work-runtime-ktx", "androidx.work:work-runtime-ktx"),
                lib("kotlinx-coroutines-core", "org.jetbrains.kotlinx:kotlinx-coroutines-core"),
                lib("kotlinx-coroutines-android", "org.jetbrains.kotlinx:kotlinx-coroutines-android"),
                lib("kotlinx-serialization-protobuf", "org.jetbrains.kotlinx:kotlinx-serialization-protobuf"),
                lib("kotlinx-serialization-json", "org.jetbrains.kotlinx:kotlinx-serialization-json"),
                lib("hilt-android", "com.google.dagger:hilt-android"),
                lib("hilt-compiler", "com.google.dagger:hilt-compiler"),
                lib("zxing-core", "com.google.zxing:core"),
                lib("keepassjava2-dom", "org.linguafranca.pwdb:KeePassJava2-dom"),
            ),
        ),
        Group(
            license = "MIT License",
            url = "https://opensource.org/licenses/MIT",
            libraries = listOf(
                lib("zxcvbn", "com.nulab-inc:zxcvbn"),
                lib("kotlin-bip39", "cash.z.ecc.android:kotlin-bip39"),
                // Transitive, added 2026-07-29. Neither is Apache-2.0, which is why they could not
                // simply be appended to the group above — licences were read from each POM
                // (slf4j-api's from its parent, org.slf4j:slf4j-parent).
                lib("checker-qual", "org.checkerframework:checker-qual"),
                lib("slf4j-api", "org.slf4j:slf4j-api"),
            ),
        ),
        Group(
            license = "Bouncy Castle Licence",
            url = "https://www.bouncycastle.org/licence.html",
            libraries = listOf(lib("bouncycastle", "org.bouncycastle:bcprov-jdk18on")),
        ),
        Group(
            license = "The Unlicense",
            url = "https://unlicense.org/",
            libraries = listOf(lib("upokecenter-cbor", "com.upokecenter:cbor")),
        ),
        Group(
            license = "CC0 1.0 Universal (public domain dedication)",
            url = "https://creativecommons.org/publicdomain/zero/1.0/",
            libraries = listOf(
                // Transitive via com.upokecenter:cbor, which the passkey/WebAuthn path uses. Read
                // from their POMs: "CC0-1.0" and "CC0 Universal" respectively — a third distinct
                // licence in this graph, and one a blanket "Apache" notice would misstate.
                lib("peteroupc-numbers", "com.github.peteroupc:numbers"),
                lib("peteroupc-datautilities", "com.github.peteroupc:datautilities"),
            ),
        ),
        Group(
            // Not an open-source licence, and only present in the `play` build — the `foss` flavor
            // ships no billing code at all. Saying "Apache" here would be plainly untrue.
            license = "Android Software Development Kit License (play build only)",
            playOnly = true,
            url = "https://developer.android.com/studio/terms.html",
            libraries = listOf(
                lib("billing", "com.android.billingclient:billing"),
                lib("billing-ktx", "com.android.billingclient:billing-ktx"),
            ),
        ),
        Group(
            // Also not open source, and also `play`-only — but a *different* non-open licence from
            // the billing group above, so it gets its own entry rather than being folded in for
            // tidiness. The Play Core SDK terms are their own document with their own URL, and a
            // licence notice that points a reader at the wrong terms is worse than a longer list.
            license = "Play Core Software Development Kit Terms of Service (play build only)",
            playOnly = true,
            url = "https://developer.android.com/guide/playcore/license",
            libraries = listOf(lib("play-review-ktx", "com.google.android.play:review-ktx")),
        ),
    )

    /**
     * Catalog aliases that are test-only and therefore ship in no APK. Listed explicitly so the
     * coverage test can tell "deliberately not redistributed" from "someone forgot".
     */
    val testOnlyAliases: Set<String> = setOf(
        "junit",
        "kotlinx-coroutines-test",
        "androidx-test-core",
        "androidx-test-runner",
        "androidx-test-ext-junit",
        // Compose UI testing, used by :core:ui's focus-ring pixel tests. `ui-test-manifest` is a
        // debugImplementation, so it is in the debug APK but never in a release one — the same
        // "never ships to a user" line the rest of this list draws.
        "androidx-compose-ui-test-junit4",
        "androidx-compose-ui-test-manifest",
        // The fuzz harness (`:test:fuzz`). That module has no `main` source set at all and nothing
        // in the build depends on it, so none of these can reach an APK by any path — which is
        // stricter than the rest of this list, where the artifacts at least exist on a debug
        // classpath. Jazzer in particular must never ship: it installs a bytecode instrumentation
        // agent.
        "junit-jupiter",
        "junit-platform-launcher",
        "jazzer-junit",
        // :core:vault's unit tests moved to the JUnit platform so VaultHeaderCodecFuzzTest can run
        // there; vintage is what keeps its nine JUnit 4 classes executing.
        "junit-vintage-engine",
        // Robolectric + mockito-kotlin: testImplementation-only in feature/settings, feature/backup
        // and feature/files, added to close the "no way to unit-test a ViewModel" gap those three
        // modules had (real Context needs Robolectric; KeystoreManager/VaultLockManager/etc. are
        // final classes with platform-crypto constructors that mockito-kotlin's inline mock maker
        // mocks without invoking). Never on a `main` classpath, so never in an APK.
        "robolectric",
        "mockito-kotlin",
    )

    /** Every alias with a notice. */
    val coveredAliases: Set<String>
        get() = groups.flatMap { group -> group.libraries.map { it.alias } }.toSet()
}
