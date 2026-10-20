plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// A JUnit test cannot prove the ABSENCE of a networking dependency — this build-graph check can.
// Fails the build if any air-gapped module transitively resolves a known networking artifact
// (roadmap §I no-network guardrail, mechanism 2 — Phase 16 Task 3 Step 1).
//
// SCOPE POLICY, written down 2026-07-28 rather than left implicit. This list covered SIX modules
// for months while `:autofill`, `:core:ui`, `:core:totp` and every `:feature:*` went unscanned —
// several of which handle plaintext secrets. The rule now is:
//
//   **Every module is in scope unless it transitively depends on `:core:billing`.**
//
// `:core:billing` legitimately pulls Play Billing in the `play` flavour, which is the app's only
// sanctioned network use, and it is excluded along with everything that really depends on it:
// `:app`, `:feature:vaultlist`, `:feature:itemedit`, `:feature:settings`. That exclusion is a real
// gap and should be read as one — those modules DO handle vault plaintext, and their air-gap rests
// entirely on `checkFlavorPermissions` proving the foss manifest has no INTERNET.
//
// The exclusion list used to be four modules wider, on a justification that was simply false.
// Established 2026-08-10: only `:feature:vaultlist`,
// `:feature:itemedit` and `:feature:settings` actually declare
// `implementation(project(":core:billing"))`. `:feature:files`, `:feature:health` and
// `:feature:import` depend on `:core:billing-api` — the Entitlement interface — and never on
// `:core:billing`; `:core:billing-api` is itself unflavored and declares coroutines-core only.
// All four were enrolled below the same day and the scan passes with them in it, so the gap they
// represented is closed rather than merely re-documented. The lesson worth keeping: a written
// justification for an exclusion is not evidence, and this one went unchallenged for months.
//
// Adding a module here is free; the way to keep the list honest is to add any new module on
// creation and only remove it with a written reason.
val airGappedModules = listOf(
    ":core:vault", ":core:crypto", ":core:model", ":core:import", ":core:health",
    ":credential",
    ":autofill", ":core:ui", ":core:totp", ":core:generator", ":core:common",
    ":feature:onboarding", ":feature:unlock", ":feature:generator", ":feature:backup",
    // Enrolled 2026-08-10, closing four of the exclusions the note above records as mis-justified.
    // These three depend on `:core:billing-api` — the Entitlement interface — and never on
    // `:core:billing`, so nothing drags Play Billing or GMS into them; and `:core:billing-api`
    // itself is unflavored and declares coroutines-core only. All four handle vault plaintext, so
    // they are exactly the modules the scan is worth running on. Verified by running
    // `checkNoNetworkingDeps` with them enrolled, not by reading build files alone.
    ":feature:files", ":feature:health", ":feature:import", ":core:billing-api",
    // Enrolled on creation, per the note above. It is the one module in the repo that talks to a
    // location API, so "it never reaches com.google.android.gms:play-services-location" is a claim
    // that has to be enforced rather than intended — the Play flavour already carries that artifact
    // transitively through billing, so intent alone would not distinguish the two.
    ":core:location",
)
val networkingGroups = listOf(
    "com.squareup.okhttp3", "com.squareup.retrofit2", "com.squareup.okio",
    "io.ktor", "com.android.volley", "org.apache.httpcomponents", // catches httpcore AND httpclient (startsWith on group)
    "com.google.android.gms", "com.google.firebase", "com.google.api-client",
)

// Known, justified exceptions — a networking-group artifact that is provably inert (protocol
// primitives only, never invoked, opens no transport) and cannot be excluded without breaking a
// module. Populate ONLY with a written justification backed by a runtime test; empty is the goal —
// and it currently IS empty: the one prior entry (org.apache.httpcomponents:httpcore, pulled by
// :core:import via KeePassJava2-simple) has since been fully excluded in core/import/build.gradle.kts,
// proven safe by KdbxRoundTripTest (creates + re-imports a real .kdbx in-JVM with httpcore absent).
// One entry, added 2026-07-28 when the scan's scope was widened from 6 modules to 15.
//
// `com.squareup.okio` — pulled by `androidx.datastore:datastore-core-okio`, which is DataStore's
// own file-storage layer (traced with `:autofill:dependencyInsight --dependency okio`). It arrives
// in every module that stores preferences and cannot be excluded without removing DataStore.
//
// It is on `networkingGroups` because okio is OkHttp's IO layer and okio-jvm genuinely contains
// socket code (`Okio.sink(Socket)`, `SocketAsyncTimeout`). So the honest statement is NOT "okio
// cannot open a socket" — it is:
//
//   **The air gap is enforced by the ABSENCE OF `android.permission.INTERNET` from the merged foss
//   manifest, which `checkFlavorPermissions` pins to an exact allowlist.** No library on the
//   classpath can open a TCP socket without it, whatever code it ships.
//
// `checkNoNetworkingDeps` is defence in depth against a dependency that would *want* that
// permission and drag it into the merged manifest — which is exactly the failure that produced
// Guardrail 0. Treat this exception as "capability present, permission absent", not as "harmless",
// and if okio ever arrives via OkHttp rather than DataStore that is a different fact and this entry
// does not cover it: check the source with `dependencyInsight` before assuming this line applies.
val knownExceptions = listOf("com.squareup.okio")

// ---------------------------------------------------------------------------------------------
// Guardrail 0: the permissions the installed artifact actually asks for.
//
// `checkNoNetworkingDeps` below proves no *dependency* can open a socket, and the foss manifest
// said "deliberately zero-permission" — but neither statement was about the APK. The merged foss
// manifest carried seven permissions (POST_NOTIFICATIONS, WAKE_LOCK, ACCESS_NETWORK_STATE,
// RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, USE_BIOMETRIC, USE_FINGERPRINT), because a library
// manifest merges its own `uses-permission` entries in whether or not the app ever calls it. One
// daily backup reminder built on androidx.work was enough to make the headline claim false, and
// nothing in the build could notice: no source file mentions those permissions.
//
// So this reads the *merged* manifest — the one that ships — and pins it to an exact set per
// flavour. Adding a dependency that quietly wants a permission now fails the build with the name
// of the permission, instead of shipping.
val allowedPermissions = mapOf(
    // Biometric unlock only. USE_FINGERPRINT is the API-28 half of it (minSdk is 28), and both
    // come from androidx.biometric rather than from any manifest in this repo.
    "foss" to setOf(
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_FINGERPRINT",
        // QR scanning for 2FA setup (and later the Recovery Kit). Offline capture only: the
        // air gap is enforced by the continued absence of INTERNET below.
        "android.permission.CAMERA",
        // Save current location. Foreground only, requested at the capture tap, and never at
        // launch. ACCESS_BACKGROUND_LOCATION and FOREGROUND_SERVICE are deliberately not here and
        // must never be: this feature takes one snapshot when asked and has no tracking, history or
        // scheduled work behind it. The air-gap claim is untouched — a coordinate is stored in the
        // encrypted vault and INTERNET is still absent below.
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
    ),
    // The store build adds only what its two extra features need: billing (INTERNET, BILLING and
    // the ACCESS_NETWORK_STATE the Play Billing library uses to decide whether a purchase call is
    // worth attempting) and the QR scanner (CAMERA). None of them are reachable from vault code.
    "play" to setOf(
        "android.permission.USE_BIOMETRIC",
        "android.permission.USE_FINGERPRINT",
        "android.permission.CAMERA",
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "com.android.vending.BILLING",
        // Same two as foss, for the same feature and on the same foreground-only terms.
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
    ),
)

tasks.register("checkFlavorPermissions") {
    group = "verification"
    description = "Fails if a shipped flavour's merged manifest declares a permission outside its allowlist."
    // Produce what we inspect. Until 2026-07-28 this task declared no dependency at all and simply
    // read whatever manifest happened to be on disk, so it would happily re-validate a stale artifact
    // from an earlier build — or fail outright on a clean tree. It only ever appeared to work because
    // the documented gate command listed the assembles first, which is an ordering Gradle never
    // promised and parallel execution does not respect. Naming the producers makes the guardrail
    // independent of how it is invoked, and lets the gate drop `assemblePlayRelease` (blocked on D1)
    // without silently losing play-flavour permission coverage.
    dependsOn(
        ":app:processFossReleaseMainManifest",
        ":app:processPlayReleaseMainManifest",
    )
    // Everything the action needs is resolved at configuration time and captured by value —
    // referencing the script's `allowedPermissions` or `layout` from inside `doLast` makes the
    // task hold a Project reference, which the configuration cache (on repo-wide) rejects.
    val allowlists = allowedPermissions
    val manifests = allowedPermissions.keys.associateWith { flavor ->
        val capitalised = flavor.replaceFirstChar { it.uppercase() }
        layout.projectDirectory
            .file(
                "app/build/intermediates/merged_manifest/${flavor}Release/" +
                    "process${capitalised}ReleaseMainManifest/AndroidManifest.xml",
            )
            .asFile
    }
    doLast {
        val violations = mutableListOf<String>()
        allowlists.forEach { (flavor, allowed) ->
            val manifest = checkNotNull(manifests[flavor])
            if (!manifest.isFile) {
                throw GradleException(
                    "Permission guardrail could not run: ${manifest.path} is missing. Run " +
                        "`./gradlew :app:assemble${flavor.replaceFirstChar { it.uppercase() }}Release` first.",
                )
            }
            val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
                .findAll(manifest.readText())
                .map { it.groupValues[1] }
                // AGP synthesises this one per-package for its own broadcast plumbing; it grants
                // nothing to the app and is not something a reviewer would ever see.
                .filterNot { it.endsWith("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
                .toSet()
            (declared - allowed).sorted().forEach { violations += "$flavor -> $it" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Permission guardrail FAILED — the merged manifest asks for permissions that are " +
                    "not on the flavour's allowlist:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nEither drop the dependency that contributes it, remove it with " +
                    "tools:node=\"remove\", or — if it genuinely backs a feature — add it to " +
                    "`allowedPermissions` in build.gradle.kts AND fix every claim that says " +
                    "otherwise. Do not add it to the allowlist to make the build green.",
            )
        }
        println("Permission guardrail OK: ${allowlists.keys.joinToString()} declare only their allowlisted permissions.")
    }
}

// ---------------------------------------------------------------------------------------------
// Guardrail: every shipped window still blocks screenshots.
//
// SecureActivity sets FLAG_SECURE only when R.bool.lk_obscure_screen is true. The library default
// is true; app/src/debug/res/values/bools.xml overrides it to false so UI work can be photographed
// during development. Release variants never compile that directory — but "never" is a claim, and
// this repo's rule is that a claim about a shipped artifact is verified against the shipped
// artifact.
//
// An instrumented test cannot make this statement: instrumentation runs one variant at a time, so
// an androidTest can confirm the debug build dropped the flag and can never confirm the release
// build kept it. Same shape as checkFlavorPermissions, one layer down: name the producers, read
// what they produced, fail on anything but the expected value.
// Which file to read is the whole difficulty, and this check has had it wrong twice.
//
// It used to read `incremental/<flavor>Release/merge<Flavor>ReleaseResources/merged.dir/values/
// values.xml`. On 2026-08-10 that went intermittently red — bisected across four clean builds:
// identical task set, build cache on -> FAIL, cache off -> PASS — and the
// fix applied then was `doNotCacheIf` on both merge tasks, on the theory that the sidecar was not a
// declared output and so was never materialised by cache restoration.
//
// Re-measured 2026-08-31 by dumping `outputs.files` of `:app:mergeFossReleaseResources` (AGP 8.x,
// this repo's pinned version). That theory is wrong today, whatever it was in August: the
// incremental directory IS a declared output, and so is
// `merged-not-compiled-resources/<flavor>/release`. Both are restored from the cache. The
// `doNotCacheIf` was therefore paying for two resource-merge re-executions on every release build
// and buying nothing, so it is gone.
//
// The file now read is `merged-not-compiled-resources/<flavor>/release/values/values.xml`, chosen
// over the sidecar for one reason: it is an artifact AGP publishes, not AGP's scratch space. Its
// contents are the same fully merged values — `lk_obscure_screen` is declared in `:core:ui`, and
// this file has it while `packaged_res/<flavor>Release/.../values.xml` (the other cache-stable
// candidate, and the one a 2026-08-31 bug report proposed) does not: packaged_res holds only the
// app module's own resources, so pointing here would report the bool missing on every run.
//
// The path is still spelled out rather than pulled off the merge task's output property, because
// the root project is evaluated before `:app` and its tasks do not exist yet at that point. What
// closes the hole the old error message complained about is the pairing below: `dependsOn` orders
// the merge before the read, and `inputs.file` makes the merged values a tracked input of this
// task rather than a file it happens to look for.
tasks.register("checkScreenSecurity") {
    group = "verification"
    description = "Fails if a release variant's merged resources drop FLAG_SECURE (lk_obscure_screen)."
    dependsOn(
        ":app:mergeFossReleaseResources",
        ":app:mergePlayReleaseResources",
    )
    // Resolved at configuration time and captured by value — touching `layout` from inside doLast
    // would make the task hold a Project reference, which the configuration cache rejects.
    val mergedValues = listOf("foss", "play").associateWith { flavor ->
        project(":app").layout.buildDirectory
            .file("intermediates/merged-not-compiled-resources/$flavor/release/values/values.xml")
            .get()
            .asFile
    }
    mergedValues.forEach { (flavor, values) ->
        inputs.file(values)
            .withPropertyName("${flavor}ReleaseMergedValues")
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .optional()
    }
    doLast {
        val violations = mutableListOf<String>()
        mergedValues.forEach { (flavor, values) ->
            if (!values.isFile) {
                throw GradleException(
                    "Screen-security guardrail could not run: ${values.path} is missing. Run " +
                        "`./gradlew :app:merge${flavor.replaceFirstChar { it.uppercase() }}ReleaseResources` " +
                        "first. If AGP has moved this intermediate, fix the path — do not delete the check.",
                )
            }
            val declared = Regex("""<bool name="lk_obscure_screen">([^<]*)</bool>""")
                .findAll(values.readText())
                .map { it.groupValues[1].trim() }
                .toList()
            when {
                declared.isEmpty() ->
                    violations += "$flavor -> lk_obscure_screen is absent from the merged release resources"
                declared.any { it != "true" } ->
                    violations += "$flavor -> lk_obscure_screen = ${declared.joinToString()} (expected true)"
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Screen-security guardrail FAILED — a release build would not set FLAG_SECURE:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nlk_obscure_screen must be `true` in every release variant. The only " +
                    "`false` in the tree belongs in app/src/debug/res/values/bools.xml, which " +
                    "release does not compile. Screenshot and app-switcher blocking are claims " +
                    "this app makes to users (core_ui_always_on_screenblock_*, Tier G) — do not " +
                    "relax this to make a build green.",
            )
        }
        println("Screen-security guardrail OK: foss, play release builds set FLAG_SECURE.")
    }
}

tasks.register("checkNoApi30Signatures") {
    group = "verification"
    description = "Fails if an API-30 inline-suggestion type is named outside InlineSuggestions.kt."
    // minSdk is 28 and the inline-suggestion API is 30. A `Build.VERSION.SDK_INT` guard around the
    // call site is not sufficient protection on its own, which is the whole reason this task exists.
    //
    // What shipped: FillResponseFactory.buildUnlockedResponse declared the spec as a typed
    // parameter, `InlinePresentationSpec?`. It is a `suspend` fun, so Kotlin spills its parameters
    // into the continuation as `Object` and check-casts them back on resume — generated code, with
    // no guard in front of it and nowhere for the author to put one. On API 29 a form the vault
    // *had* a credential for threw NoClassDefFoundError and the user was offered nothing at all,
    // while the same build served the no-match path correctly because it returned before the second
    // suspension point. Measured on an API 29 emulator, 2026-08-26.
    //
    // Reading source rather than bytecode is a deliberate approximation: the invariant is really
    // about method descriptors, but a type cannot reach a descriptor without being named here, and
    // a source check runs without a build and points at the line to change.
    val sourceRoots = layout.projectDirectory.asFile.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name !in setOf("build", ".git", ".gradle", "gradle") }
    val banned = listOf(
        "InlinePresentationSpec",
        "InlinePresentation",
        "InlineSuggestionUi",
        "inlineSuggestionsRequest",
        // Added 2026-09-13: the line above is the lowercase
        // property-accessor spelling (`request.inlineSuggestionsRequest`) and is not a substring of
        // the actual API-30 class name, capital I — so a future suspend fun naming the real class
        // `android.service.autofill.InlineSuggestionsRequest` as a parameter/return type reached the
        // same checkcast-on-resume hazard InlinePresentationSpec caused (see the comment above,
        // 302-308) without tripping this guard. Adding the explicit class name also closes the legal
        // `request.getInlineSuggestionsRequest()` Java-getter spelling for free, since the class name
        // is a substring of it. Not case-insensitive: that would also match the legitimate accessor
        // `inlinePresentationSpecs` (line 38 below). Red-proof (no JUnit harness for a Gradle task):
        // plant a file, e.g. autofill/src/main/kotlin/com/localkeep/autofill/Api30Probe.kt, containing
        // `suspend fun probe(r: android.service.autofill.InlineSuggestionsRequest?) = r`, then run
        // `./gradlew checkNoApi30Signatures` — must FAIL naming that file/line; delete the probe, must
        // pass; and InlineSuggestions.kt:38's `request.inlineSuggestionsRequest` must still not trip it.
        "InlineSuggestionsRequest",
    )
    val holder = "InlineSuggestions.kt"
    doLast {
        val violations = mutableListOf<String>()
        sourceRoots.forEach { root ->
            root.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" && "/src/" in it.path }
                .filter { it.name != holder }
                .forEach { file ->
                    // Comments are skipped for the same reason checkNoPlatformDrag skips them: the
                    // explanation of why a type is avoided has to be allowed to name it, or the
                    // rule survives with its reason deleted.
                    file.readLines().forEachIndexed { index, rawLine ->
                        val line = rawLine.trim()
                        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                            return@forEachIndexed
                        }
                        val code = line.substringBefore("//")
                        banned.firstOrNull { it in code }?.let {
                            violations += "${file.path}:${index + 1} names $it"
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "API-30 signature guardrail FAILED — these would be resolved on API 28/29, where " +
                    "the type does not exist:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nPass the spec as `Any?` and do the work in autofill's InlineSuggestions, " +
                    "which is @RequiresApi(R) and only ever invoked behind an SDK_INT guard.",
            )
        }
        println("API-30 signature guardrail OK: inline types confined to $holder.")
    }
}

tasks.register("checkNoPlatformDrag") {
    group = "verification"
    description = "Fails if the app uses Android's system drag, which draws outside FLAG_SECURE."
    // A companion to checkScreenSecurity rather than part of it: that one reads merged resources,
    // this one reads source. Both defend the same claim — that nothing this app renders escapes its
    // own window — but they can only fail for different reasons, so they report separately.
    //
    // `View.startDragAndDrop` and `Modifier.dragAndDropSource` hand a drag shadow to the system
    // window manager. That shadow is drawn in a window the app does not own and therefore outside
    // the app's FLAG_SECURE surface: dragging a row carrying a login title would put that title
    // somewhere a screenshot can reach and somewhere the recents thumbnail can show. LkDragAndDrop
    // draws its ghost inside the app's own window instead, and this task is what stops that
    // decision from being quietly undone by an autocomplete.
    val sourceRoots = layout.projectDirectory.asFile.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name !in setOf("build", ".git", ".gradle", "gradle") }
    val banned = listOf("startDragAndDrop", "dragAndDropSource", "dragAndDropTarget")
    doLast {
        val violations = mutableListOf<String>()
        sourceRoots.forEach { root ->
            root.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" && "/src/" in it.path }
                .forEach { file ->
                    // Line by line, skipping comments. LkDragAndDrop's own KDoc names both APIs to
                    // explain why it does not use them, and a check that cannot tell an explanation
                    // from a call would force that explanation to be deleted — leaving a rule with
                    // its reason removed, which is how rules get relaxed by the next person.
                    file.readLines().forEachIndexed { index, rawLine ->
                        val line = rawLine.trim()
                        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                            return@forEachIndexed
                        }
                        val code = line.substringBefore("//")
                        banned.forEach { api ->
                            if (api in code) violations += "${file.path}:${index + 1} uses $api"
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Platform-drag guardrail FAILED — a drag shadow would be drawn outside this app's " +
                    "FLAG_SECURE window:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nUse core/ui's LkDragAndDrop, which keeps the ghost and the payload " +
                    "inside the process. Do not relax this to make a build green.",
            )
        }
        println("Platform-drag guardrail OK: no system drag-and-drop in any source set.")
    }
}

tasks.register("checkNoDismissThenNavigate") {
    group = "verification"
    description = "Fails if a handler closes a modal and starts a navigation in the same breath."
    // Two window teardowns for one user intent, which the platform plays in sequence.
    //
    // Reported five times across three phone recordings as "flickering", and the same shape every
    // time. Measured on the emulator for the discard dialog (mean luminance of the form region,
    // 14 fps): 29 while the dialog is up, then SEVEN frames at 33 — the form the user has just
    // discarded, redrawn undimmed and complete, generated password and all — then 24 for the vault
    // list. Half a second of a screen nobody asked to see. Deleting the `confirmDiscard = false`
    // line and letting the pop take the dialog with it moved the same measurement to a single
    // step, 29 -> 24, with nothing outside the band the two ends span.
    //
    // The rule this enforces: DO NOT DISMISS A MODAL THE NAVIGATION IS ABOUT TO DESTROY ANYWAY.
    // A pop takes the screen's dialogs and sheets down as part of taking the screen down. Closing
    // one first is the sequence. Where the departure is conditional, hand the caller a Boolean
    // saying whether it actually left — see `leave` in ItemEditScreen — and close the modal only
    // on false.
    //
    // Limits, stated rather than hidden: this reads source line by line, so it sees a state write
    // and a navigation call near each other, not a proven ordering. It will not catch a navigation
    // reached through a helper it cannot name (`addOfType` in VaultListScreen is such a case, and
    // is correct for a different reason — the sheet there stays composed through its exit). A
    // green result means this shape is absent, not that the class is.
    val sourceRoots = layout.projectDirectory.asFile.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name !in setOf("build", ".git", ".gradle", "gradle") }
    // A state write that takes a modal off screen.
    val closes = Regex(
        """(\w*(?:[Ss]heet|[Dd]ialog|[Pp]icker|[Pp]rompt|[Oo]verlay|[Mm]enu|[Ss]canner|[Ww]izard)\w*|show\w+|confirm\w+|pending\w+)\s*=\s*(?:false|null)\b""",
    )
    // A call that changes which screen you are on.
    val navigates = Regex(
        """(navController\.\w+|navigate\(|popBackStack\(|on(?:Back|Saved|Open\w*|Navigate\w*|Leave|Done|Finish\w*)\s*\()""",
    )
    doLast {
        val violations = mutableListOf<String>()
        sourceRoots.forEach { root ->
            root.walkTopDown()
                .onEnter { it.name != "build" }
                .filter {
                    it.isFile && it.extension == "kt" && "/src/main/" in it.path
                }
                .forEach { file ->
                    val lines = file.readLines()
                    lines.forEachIndexed { index, rawLine ->
                        val line = rawLine.trim()
                        // Skip comments. This file's own rule is explained in prose next to the
                        // code it governs, and a check that cannot tell an explanation from a call
                        // forces the explanation to be deleted — which is how a rule survives with
                        // its reason removed. Same reasoning as checkNoPlatformDrag.
                        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                            return@forEachIndexed
                        }
                        val code = rawLine.substringBefore("//")
                        val close = closes.find(code) ?: return@forEachIndexed
                        // Same line: `onDismiss = { showX = false; onBack() }`.
                        val sameLine = navigates.find(code.substring(close.range.last))
                        if (sameLine != null) {
                            violations += "${file.path}:${index + 1} closes ${close.groupValues[1]} " +
                                "and calls ${sameLine.groupValues[1].trimEnd('(')} on one line"
                            return@forEachIndexed
                        }
                        // Or the next few lines, up to the end of the handler.
                        for (ahead in index + 1 until minOf(index + 6, lines.size)) {
                            val next = lines[ahead].trim()
                            if (next.startsWith("//")) continue
                            if (next.startsWith("}") || next.startsWith(")")) break
                            val hit = navigates.find(lines[ahead].substringBefore("//")) ?: continue
                            violations += "${file.path}:${index + 1} closes ${close.groupValues[1]}, " +
                                "then line ${ahead + 1} calls ${hit.groupValues[1].trimEnd('(')}"
                            break
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Dismiss-then-navigate guardrail FAILED — a screen would be redrawn in a state " +
                    "the user did not ask for, between the modal closing and the crossing:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nDo not close a modal the navigation is about to destroy anyway: the pop " +
                    "takes it down with the screen. Where the departure is conditional, return a " +
                    "Boolean from the leave handler and close the modal only when it did not go. " +
                    "Do not relax this to make a build green.",
            )
        }
        println("Dismiss-then-navigate guardrail OK: no modal is closed alongside a navigation.")
    }
}

tasks.register("checkNoLocationAiding") {
    group = "verification"
    description = "Fails if anything asks the platform to fetch GNSS assistance data (§I no-network)."
    // The one accuracy technique that is a network request wearing a local API.
    //
    // `LocationManager.sendExtraCommand` takes a vendor command string, and the ones that would
    // help a cold GNSS start — "force_xtra_injection" and the PSDS equivalents — tell the *platform*
    // to download predicted ephemeris. The app never opens a socket, so nothing in
    // checkNoNetworkingDeps or checkFlavorPermissions would notice: `foss` still holds no INTERNET
    // permission and the merged manifest is unchanged. What changes is that a capture would cause
    // traffic on the user's behalf, which is the thing the no-network claim is actually about. An
    // audit finding, and a true one.
    //
    // The other commands are worse for a different reason: "delete_aiding_data" forces a cold start
    // by discarding a valid almanac, making time-to-first-fix dramatically worse — the opposite of
    // what anyone reaching for it would intend.
    //
    // This exists as a build-time gate rather than a runtime probe deliberately. `sendExtraCommand`
    // returns a boolean, so probing whether a command works means *calling* it, and a probe would be
    // the first step toward a call site. A gate cannot be satisfied by an unexercised path.
    //
    // If this ever fails, the fix is deleting the call. Not adding an exception here.
    val sourceRoots = layout.projectDirectory.asFile.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name !in setOf("build", ".git", ".gradle", "gradle") }
    val banned = listOf(
        "sendExtraCommand",
        "force_xtra_injection",
        "force_psds_injection",
        "delete_aiding_data",
        "force_time_injection",
    )
    doLast {
        val violations = mutableListOf<String>()
        sourceRoots.forEach { root ->
            root.walkTopDown()
                .onEnter { it.name != "build" }
                .filter { it.isFile && it.extension == "kt" && "/src/" in it.path }
                .forEach { file ->
                    // Comments are skipped for the same reason checkNoPlatformDrag skips them: the
                    // capture's own source names these commands to record that they were considered
                    // and refused, and a check that cannot tell an explanation from a call would
                    // force that explanation to be deleted — leaving the rule without its reason.
                    file.readLines().forEachIndexed { index, rawLine ->
                        val line = rawLine.trim()
                        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                            return@forEachIndexed
                        }
                        val code = line.substringBefore("//")
                        banned.forEach { api ->
                            if (api in code) violations += "${file.path}:${index + 1} uses $api"
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Location-aiding guardrail FAILED — a capture would cause a network fetch on the " +
                    "user's behalf:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nGNSS assistance data is downloaded by the platform, not by this app, " +
                    "which is exactly why no permission check catches it. The capture waits for " +
                    "satellites instead; see core/location. Do not relax this to make a build green.",
            )
        }
        println("Location-aiding guardrail OK: nothing requests GNSS assistance data.")
    }
}

tasks.register("checkNoNetworkingDeps") {
    group = "verification"
    description = "Fails if an air-gapped module pulls a networking artifact (§I no-network guardrail)."
    // DEVIATION from the plan's literal task body (not the regex/blocklist/exception logic, which
    // is unchanged): this task resolves other projects' dependency configurations at execution
    // time via `project(path)`, which the Gradle configuration cache (enabled repo-wide via
    // org.gradle.configuration-cache=true in gradle.properties) forbids outright — every run
    // failed with "invocation of 'Task.project' ... unsupported" before this opt-out was added.
    notCompatibleWithConfigurationCache(
        "resolves other subprojects' dependency configurations at execution time",
    )
    doLast {
        val violations = mutableListOf<String>()
        airGappedModules.forEach { path ->
            val proj = project(path)
            // .toList() snapshots the live filtered view before iterating: resolving a
            // configuration's artifacts below can trigger AGP to lazily register further
            // configurations on the same project, mutating `configurations` mid-iteration and
            // throwing ConcurrentModificationException if iterated directly (observed empirically).
            proj.configurations
                .matching { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
                .toList()
                .forEach { cfg ->
                    runCatching {
                        cfg.incoming.artifactView { lenient(true) }.artifacts.forEach { art ->
                            val id = art.id.componentIdentifier.displayName // e.g. "com.squareup.okhttp3:okhttp:4.12.0"
                            if (networkingGroups.any { id.startsWith(it) } &&
                                knownExceptions.none { id.startsWith(it) }) {
                                violations += "$path -> $id (via ${cfg.name})"
                            }
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("No-network guardrail FAILED — air-gapped modules pulled networking artifacts:\n" +
                violations.joinToString("\n") { "  - $it" })
        }
        println("No-network guardrail OK: ${airGappedModules.joinToString()} pull no networking artifacts.")
    }
}

// ---------------------------------------------------------------------------------------------
// Guardrail 1b: the vault-at-rest cryptography has no public-key/key-agreement dependency.
//
// LocalKeep's post-quantum claim is deliberately narrow: a stolen vault file is protected by
// Argon2id, AES-256, HKDF, HMAC and full-entropy recovery words, not RSA/ECC/DH. Passkeys still
// use WebAuthn ES256 in :credential, but that is not part of vault encryption or Recovery Kit
// unlock. If cloud sync, sharing, or remote recovery ever lands, this task should fail first and
// force an explicit hybrid ML-KEM design instead of quietly adding classical key exchange here.
val vaultCryptoSourceRoots = listOf(
    "core/crypto/src/main/kotlin",
    "core/vault/src/main/kotlin",
)
val forbiddenVaultAsymmetricPatterns = listOf(
    Regex("""\bRSA\b"""),
    Regex("""\bECDH\b"""),
    Regex("""\bECDSA\b"""),
    Regex("""\bDiffieHellman\b"""),
    Regex("""\bKeyAgreement\b"""),
    Regex("""\bKeyPair\b"""),
    Regex("""\bKeyPairGenerator\b"""),
    Regex("""\bPublicKey\b"""),
    Regex("""\bPrivateKey\b"""),
    Regex("""\bX25519\b"""),
    Regex("""\bEd25519\b"""),
    Regex("""\bECGenParameterSpec\b"""),
    Regex("""Signature\.getInstance\s*\("""),
)

tasks.register("checkVaultAtRestNoAsymmetricCrypto") {
    group = "verification"
    description = "Fails if vault-at-rest code adds RSA/ECC/DH/key-agreement primitives."
    val roots = vaultCryptoSourceRoots.map { layout.projectDirectory.dir(it).asFile }
    val patterns = forbiddenVaultAsymmetricPatterns
    // Captured at configuration time. `rootDir` inside `doLast` resolves through the build script's
    // implicit Project receiver, which the configuration cache refuses to serialize — and this
    // build has `org.gradle.configuration-cache=true` set repo-wide, not as an opt-in. The task
    // therefore exited non-zero on every run regardless of its verdict, so a guardrail that reads
    // as "vault-at-rest is still symmetric" was in fact reporting nothing at all. Only used to
    // shorten paths in the failure message; the check itself never needed the Project.
    val projectRoot = rootDir
    // Same reason: the success `println` below interpolated the script-level `vaultCryptoSourceRoots`
    // directly, which is a script object reference from inside the task action and fails the cache
    // on its own — capturing `rootDir` alone left the task still exiting non-zero while printing OK,
    // which is the exact shape of the bug: a guardrail that says "OK" and fails the build anyway.
    val rootsDescription = vaultCryptoSourceRoots.joinToString()
    doLast {
        val violations = mutableListOf<String>()
        roots.forEach { root ->
            if (!root.isDirectory) throw GradleException("Vault crypto guardrail root missing: ${root.path}")
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (patterns.any { it.containsMatchIn(line) }) {
                            violations += "${file.relativeTo(projectRoot).path}:${index + 1}: ${line.trim()}"
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Vault-at-rest PQ guardrail FAILED — asymmetric or key-agreement crypto appeared " +
                    "inside the vault encryption/recovery modules:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\n\nKeep vault-at-rest local and symmetric. For cloud sync, sharing, or " +
                    "remote recovery, design a separate hybrid ML-KEM path and update the product " +
                    "claim instead of slipping RSA/ECC/DH into these modules.",
            )
        }
        println("Vault-at-rest PQ guardrail OK: no RSA/ECC/DH/key-agreement primitives in $rootsDescription.")
    }
}

// ---------------------------------------------------------------------------------------------
// Guardrail 2: JVM-only APIs that Android does not implement.
//
// This exists because of B4a, and the failure it prevents is worth stating in full. `:core:import`
// depended on KeePassJava2's `simple` flavour, which is backed by simple-xml, which needs the StAX
// API (javax.xml.stream). Android has never shipped StAX. So *every* KeePass import failed on a
// real device — and, because the importer collapsed all failures into one message, it told users
// their password was wrong. Meanwhile the unit test was green, and stayed green for months, because
// it runs on a JVM and the JVM has StAX.
//
// No JUnit test can catch this: it is precisely a difference between the test runtime and the
// shipping runtime. Only the build graph knows. This scans the constant pools of every class on the
// scanned modules' runtime classpaths for references to packages Android lacks.
//
// The package list is deliberately minimal — only APIs actually observed to break this app. Adding
// speculative entries (java.awt, java.beans, ...) invites false positives from libraries that
// reference them on codepaths never taken, and a guardrail that cries wolf gets disabled.
val jvmOnlyPackages = listOf(
    // javax.xml.stream — StAX. Absent from Android since API 1. Note this catches *implementations*
    // too (Woodstox, Aalto): they implement these interfaces, so they reference them.
    "javax/xml/stream",
)
val jvmOnlyScannedModules = listOf(":core:import")

tasks.register("checkNoJvmOnlyXmlApis") {
    group = "verification"
    description = "Fails if a module ships a dependency referencing an API Android does not implement (B4a guardrail)."
    // Same configuration-cache opt-out, and for the same reason, as checkNoNetworkingDeps above.
    notCompatibleWithConfigurationCache(
        "resolves other subprojects' dependency configurations at execution time",
    )
    doLast {
        val needles = jvmOnlyPackages.map { it.toByteArray(Charsets.UTF_8) }
        val violations = linkedSetOf<String>()

        jvmOnlyScannedModules.forEach { path ->
            val proj = project(path)
            proj.configurations
                .matching { it.isCanBeResolved && it.name.endsWith("RuntimeClasspath", ignoreCase = true) }
                .toList()
                .forEach { cfg ->
                    runCatching {
                        cfg.incoming.artifactView { lenient(true) }.artifacts.forEach artifact@{ art ->
                            val file = art.file
                            if (!file.isFile || !file.name.endsWith(".jar")) return@artifact
                            val offenders = jarReferences(file, needles, jvmOnlyPackages)
                            offenders.forEach { pkg ->
                                violations += "$path -> ${art.id.componentIdentifier.displayName} references ${pkg.replace('/', '.')}"
                            }
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Android-compatibility guardrail FAILED — these dependencies use APIs Android does " +
                    "not implement, so they will throw at runtime on a device while every JVM unit " +
                    "test passes:\n" + violations.joinToString("\n") { "  - $it" } +
                    "\n\nPick a dependency (or a flavour of it) that does not need the API. See " +
                    "core/import/build.gradle.kts for the KeePassJava2 case.",
            )
        }
        println("Android-compatibility guardrail OK: ${jvmOnlyScannedModules.joinToString()} reference no JVM-only APIs.")
    }
}

/** Which of [packages] the classes inside [jar] reference, by scanning their constant pools. */
fun jarReferences(jar: java.io.File, needles: List<ByteArray>, packages: List<String>): Set<String> {
    val found = linkedSetOf<String>()
    java.util.zip.ZipFile(jar).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.name.endsWith(".class")) continue
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            needles.forEachIndexed { idx, needle ->
                val pkg = packages[idx]
                if (pkg !in found && indexOfBytes(bytes, needle) >= 0) found += pkg
            }
            if (found.size == packages.size) return found
        }
    }
    return found
}

/** Naive byte-sequence search; the haystacks here are single class files, so this is fast enough. */
fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    candidate@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@candidate
        return i
    }
    return -1
}

// D1 release gate. The release plan has said "do not ship `play` with the placeholder" since the
// breach pipeline was built, and nothing enforced it: the shipped asset is a 190-byte stub, the
// Security screen renders its verdicts from whatever is there, and a release built today would
// tell every user their passwords are absent from a breach corpus that contains nothing. That is
// the same class of untrue user-facing claim that was already fixed once on this screen.
//
// Deliberately size-based rather than hash-based: any real corpus is orders of magnitude larger
// than the stub, and pinning a hash would mean editing this file every time the corpus is rebuilt.
val breachFilterMinBytes = 64 * 1024L

tasks.register("checkBreachCorpusNotPlaceholder") {
    group = "verification"
    description = "Fails if a release would ship the placeholder breach corpus (release gate D1)."
    val filter = layout.projectDirectory.file("app/src/main/assets/breached-passwords.filter").asFile
    val minBytes = breachFilterMinBytes
    doLast {
        if (!filter.exists()) {
            throw GradleException("breach corpus missing: ${filter.path}")
        }
        val size = filter.length()
        if (size < minBytes) {
            throw GradleException(
                "breach corpus is still the placeholder (${size} bytes, expected at least " +
                    "${minBytes}). No flavour may ship it: the Security screen would " +
                    "report every password as unbreached without any check having run. Build " +
                    "one with :core:health:buildBreachFilter — choosing which corpus may be " +
                    "redistributed is a licensing decision, not a build problem.",
            )
        }
    }
}

// The gate above was registered and then wired to nothing, so a release built today still shipped
// the placeholder: a task that cannot run is not a gate. Scoped to `:app` because the library
// modules carry the same flavour names and have no assets to check. `matching {}.configureEach {}`
// is lazy, so it binds whenever the Android plugin registers the task, whatever the evaluation
// order, and by task path so the dependency resolves without forcing `:app` to evaluate early.
//
// Both task names, and that is the whole point: the first wiring covered only `assemblePlayRelease`
// and left `bundlePlayRelease` open, which is the one that matters — Google Play distributes an
// AAB, so the gated APK path was not the path a release actually takes. Found by the review that
// audited this commit hours after it landed. Adding a variant here without adding it to this set is
// how the hole comes back.
// ---------------------------------------------------------------------------------------------
// Guardrail 3: the instrumented job runs every module that has instrumented tests.
//
// `connectedAndroidTest` (the aggregate) is correct but wasteful: it walks all 25 modules, and the
// ~19 with no androidTest sources still compile a test variant, build a test APK, install it,
// launch instrumentation and uninstall — one to three minutes each, to run nothing. The eight
// flavoured modules do it twice. Measured 2026-07-28: ~130 tests took ~45 minutes on one emulator,
// and most of that was ceremony.
//
// So CI names the six modules that actually have tests. The obvious hazard of a hand-maintained
// list is that a module gaining its first instrumented test silently stops being covered — which
// is precisely the failure this whole pipeline exists to prevent, and precisely how 23 test files
// went unrun for months. This task closes that: it walks every subproject for an androidTest
// source containing @Test and fails if the set does not match the declared list, in either
// direction.
val instrumentedModules = setOf(
    ":app",
    ":autofill",
    ":core:crypto",
    // Holds one instrumented "test" that is not a test: the GNSS field harness, which self-skips
    // in milliseconds unless armed with `-e gpsField run`. It is declared here anyway because the
    // guardrail's rule is about *coverage*, not about cost — a module with an @Test under
    // src/androidTest that CI does not run is a module whose device tests silently never run, and
    // an exception carved out for "but this one skips itself" is how that starts.
    ":core:location",
    ":core:ui",
    ":core:vault",
    ":credential",
    ":feature:files",
    ":feature:health",
    ":feature:itemedit",
    ":feature:unlock",
    ":feature:vaultlist",
)

tasks.register("checkInstrumentedModulesCovered") {
    group = "verification"
    description = "Fails if a module has instrumented tests that the CI device job does not run."
    val declared = instrumentedModules
    // Resolved at configuration time and captured by value — the configuration cache rejects a
    // task action that reaches back into Project. Same constraint as the guardrails above.
    val candidates = subprojects.map { it.path to File(it.projectDir, "src/androidTest") }
    doLast {
        val found = candidates
            .filter { (_, dir) ->
                dir.isDirectory && dir.walkTopDown().any { file ->
                    file.isFile && file.extension == "kt" && file.readText().contains("@Test")
                }
            }
            .map { it.first }
            .toSet()

        val uncovered = (found - declared).sorted()
        val stale = (declared - found).sorted()
        val problems = buildList {
            uncovered.forEach { add("  - $it has instrumented tests but is NOT in instrumentedModules") }
            stale.forEach { add("  - $it is listed in instrumentedModules but has no @Test under src/androidTest") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Instrumented coverage guardrail FAILED:\n" + problems.joinToString("\n") +
                    "\n\nUpdate `instrumentedModules` in build.gradle.kts AND the task list in " +
                    ".gitlab-ci.yml's `instrumented` job together — they are two halves of one " +
                    "statement, and a module missing from either is a module whose device tests " +
                    "never run.",
            )
        }
        println("Instrumented coverage guardrail OK: ${declared.sorted().joinToString()} — all modules with @Test are covered.")
    }
}

// ---------------------------------------------------------------------------------------------
// Guardrail 3b: every @FuzzTest is actually fuzzed.
//
// Same hazard as Guardrail 3, from a different direction. Jazzer fuzzes at most ONE TARGET PER JVM:
// under JAZZER_FUZZ the first @FuzzTest method runs a campaign and every later one in that JVM is
// SKIPPED — skipped, not failed, so the build is green. Measured 2026-07-28: a single
// `:test:fuzz:test` invocation fuzzed `onepux` and silently skipped the other four importers.
//
// So campaigns run one Gradle invocation per target, from the list in tools/fuzz.sh, and that list
// is hand-maintained. This task pins it to the annotations: adding a @FuzzTest without adding it to
// the script produces a target that exists, compiles, passes its seed replay in `gate`, and is
// never once mutated.
tasks.register("checkFuzzTargetsCovered") {
    group = "verification"
    description = "Fails if a @FuzzTest method is missing from tools/fuzz.sh's campaign list."
    // Every source root that may hold a @FuzzTest. `:core:vault` is here because one target cannot
    // live in the pure-JVM `:test:fuzz` module — see VaultHeaderCodecFuzzTest.
    val sourceRoots = listOf(
        File(rootDir, "test/fuzz/src/test/kotlin"),
        File(rootDir, "core/vault/src/test/kotlin"),
    )
    val script = File(rootDir, "tools/fuzz.sh")
    // The importer registry is the authoritative list of parsers that meet an untrusted file.
    val importerRegistry = File(
        rootDir,
        "feature/import/src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardViewModel.kt",
    )
    doLast {
        if (!script.isFile) throw GradleException("Fuzz guardrail could not run: ${script.path} is missing.")

        // "@FuzzTest(...)" on one line, "fun name(" on the next non-blank one. Deliberately not a
        // Kotlin parser: the shape is fixed by convention in this one directory, and a regex that
        // stops working is a loud failure rather than a quiet one.
        val declared = mutableSetOf<String>()
        sourceRoots.asSequence().flatMap { it.walkTopDown() }.filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            val className = file.nameWithoutExtension
            lines.forEachIndexed { index, line ->
                if (!line.trimStart().startsWith("@FuzzTest")) return@forEachIndexed
                val method = lines.drop(index + 1)
                    .firstOrNull { it.isNotBlank() }
                    ?.let { Regex("""fun\s+(\w+)\s*\(""").find(it)?.groupValues?.get(1) }
                    ?: throw GradleException("Fuzz guardrail: @FuzzTest at ${file.name}:${index + 1} has no fun on the next line.")
                declared += "$className.$method"
            }
        }
        if (declared.isEmpty()) {
            throw GradleException("Fuzz guardrail found no @FuzzTest methods under ${sourceRoots.joinToString { it.path }} — the scan is broken, not the code.")
        }

        // Not anchored on the quote: entries in the script are "<gradle task>|<test filter>", so
        // the class name is mid-string.
        val listed = Regex("""(\w+FuzzTest\.\w+)""")
            .findAll(script.readText())
            .map { it.groupValues[1] }
            .toSet()

        val missing = (declared - listed).sorted()
        val stale = (listed - declared).sorted()
        val problems = buildList {
            missing.forEach { add("  - $it is a @FuzzTest but is NOT in tools/fuzz.sh — it would never be fuzzed") }
            stale.forEach { add("  - $it is listed in tools/fuzz.sh but no such @FuzzTest exists") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Fuzz coverage guardrail FAILED:\n" + problems.joinToString("\n"))
        }
        // Pinning the list to the annotations is only half of it. That half says every target we
        // wrote gets fuzzed; it says nothing about a parser nobody wrote a target for. Every
        // importer in ALL_IMPORTERS meets an attacker-supplied file chosen from anywhere via SAF,
        // so a sixth importer added later with no @FuzzTest used to pass this gate in silence
        // (V13, 2026-09-12).
        if (!importerRegistry.isFile) {
            throw GradleException("Fuzz guardrail could not run: ${importerRegistry.path} is missing.")
        }
        val registered = Regex("""val ALL_IMPORTERS: List<Importer> = listOf\(([^)]*)\)""")
            .find(importerRegistry.readText())
            ?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: throw GradleException("Fuzz guardrail: could not read ALL_IMPORTERS — the scan is broken, not the code.")

        val fuzzedParsers = declared
            .filter { it.startsWith("ImporterFuzzTest.") }
            .map { it.substringAfter(".").lowercase() }
            .toSet()
        val unfuzzed = registered.filter { importer ->
            importer.removeSuffix("Importer").replace("Json", "").lowercase() !in fuzzedParsers
        }
        if (unfuzzed.isNotEmpty()) {
            throw GradleException(
                "Fuzz coverage guardrail FAILED: these importers parse untrusted files and have no " +
                    "@FuzzTest in ImporterFuzzTest:\n" + unfuzzed.joinToString("\n") { "  - $it" },
            )
        }

        println(
            "Fuzz coverage guardrail OK: ${declared.size} @FuzzTest targets, all in tools/fuzz.sh; " +
                "all ${registered.size} importers fuzzed.",
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Guardrail 4: the coverage number this project has always claimed and never computed.
//
// The project's contributor rules have required "at least 90% coverage for core/crypto and core/vault"
// since the beginning. Nothing measured it — there was no Kover, no JaCoCo, nothing in the version
// catalog. The rule was neither met nor unmet; it was unevaluated.
//
// Measuring it turns out to need BOTH test sources, and that is the whole subtlety. Measured
// 2026-07-28 on this emulator:
//
//     module        JVM unit tests    instrumented tests
//     core/crypto        72.1%              12.0%
//     core/vault         31.6%              83.1%
//
// Either number alone is precise and badly misleading: crypto is verified mostly on the JVM, vault
// almost entirely on a device. A JVM-only tool (Kover was tried first) cannot see the .ec files
// AGP writes from `connectedDebugAndroidTest`, so it would have reported 31.6% for a module whose
// real coverage is far higher. This task unions the two JaCoCo execution files instead, which is
// the only number the ≥90% rule was ever about.
//
// Consequence worth stating: the true number REQUIRES A DEVICE. This runs in the instrumented CI
// job, not the daily gate. A coverage check that could run without a device would be measuring the
// wrong thing.
val coverageFloors = mapOf(
    ":core:crypto" to 80,
    ":core:vault" to 80,
)

subprojects {
    val floor = coverageFloors[path]
    if (floor != null) {
        apply(plugin = "jacoco")

        val moduleDir = projectDir
        val buildDir = layout.buildDirectory
        // Captured here, not inside a task block: `path` resolves to the TASK's path once inside
        // `tasks.register`, so the failure message named ":core:vault:checkCoverageFloor" instead
        // of the module it is about.
        val modulePath = path

        val unionReport = tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("coverageUnionReport") {
            group = "verification"
            description = "Line coverage over unit AND instrumented tests combined (needs a device)."
            dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")

            executionData.setFrom(
                buildDir.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
                fileTree(buildDir.dir("outputs/code_coverage/debugAndroidTest/connected")) {
                    include("**/*.ec")
                },
            )
            // Kotlin output only: there is no Java source in either module, and pointing at the
            // javac intermediates as well would add empty class dirs that JaCoCo warns about.
            classDirectories.setFrom(buildDir.dir("tmp/kotlin-classes/debug"))
            sourceDirectories.setFrom(files("$moduleDir/src/main/kotlin"))

            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        tasks.register("checkCoverageFloor") {
            group = "verification"
            description = "Fails if combined line coverage falls below this module's floor."
            dependsOn(unionReport)
            // Captured by value: referencing the task or project inside doLast would hold a Project
            // reference, which the repo-wide configuration cache rejects — same constraint the
            // permission guardrail above documents.
            val reportXml = buildDir.file("reports/jacoco/coverageUnionReport/coverageUnionReport.xml")
            val minPercent = floor
            doLast {
                val file = reportXml.get().asFile
                if (!file.isFile) {
                    throw GradleException("Coverage guardrail could not run: ${file.path} is missing.")
                }
                // The report's own top-level LINE counter, read as text rather than parsed as XML:
                // JaCoCo emits a DOCTYPE with an external DTD, and a parser that resolves it turns
                // an offline build into a network fetch.
                val counters = Regex("""<counter type="LINE" missed="(\d+)" covered="(\d+)"/>""")
                    .findAll(file.readText())
                    .toList()
                val last = counters.lastOrNull()
                    ?: throw GradleException("Coverage guardrail could not run: no LINE counter in ${file.path}.")
                val missed = last.groupValues[1].toInt()
                val covered = last.groupValues[2].toInt()
                val total = missed + covered
                val percent = if (total == 0) 0.0 else 100.0 * covered / total
                val rounded = String.format("%.1f", percent)
                if (percent < minPercent) {
                    throw GradleException(
                        "Coverage guardrail FAILED — $modulePath is at $rounded% line coverage " +
                            "($covered/$total), below its floor of $minPercent%.\n\n" +
                            "This number is the union of unit and instrumented tests. If it dropped " +
                            "because a test was deleted or stopped running, restore the test — do " +
                            "not lower the floor. Lower it only when code was deliberately removed.",
                    )
                }
                println("Coverage guardrail OK: $modulePath at $rounded% line coverage ($covered/$total), floor $minPercent%.")
            }
        }
    }
}

// `foss` joined this set when the corpus moved to `src/main`: both flavours now ship the asset, so
// both can ship the placeholder.
val gatedReleaseTasks = setOf(
    "assemblePlayRelease",
    "bundlePlayRelease",
    "assembleFossRelease",
    "bundleFossRelease",
)

subprojects {
    if (path == ":app") {
        tasks.matching { it.name in gatedReleaseTasks }.configureEach {
            dependsOn(":checkBreachCorpusNotPlaceholder")
        }
    }
}

// ── i18n guardrail ───────────────────────────────────────────────────────────
//
// Android Lint's HardcodedText inspects XML layouts, and this app has none — every
// user-facing string is a Kotlin argument to a Compose function, which Lint never
// sees. Without this task there is no automated answer to "is this module's
// extraction finished?", and 161 files is far past what eyeballing covers.
//
// Deliberately narrow: it matches the ~10 parameter names that actually reach a
// screen, plus a bare Text("…") first argument. A broad "any literal in a
// composable" rule would flag test tags, mime types, resource ids and animation
// labels, and a check that cries wolf gets an allowlist entry per file, which is
// the same as no check at all.
//
// config/i18n-allowlist.txt starts holding every unmigrated file; the extraction
// wave deletes lines from it module by module. An allowlist with only comments
// left means extraction is complete.
tasks.register("checkNoHardcodedUiStrings") {
    group = "verification"
    description = "Fails when a user-visible Compose parameter is given a string literal."
    // Same configuration-cache opt-out as the other guardrails: the doLast closure
    // captures script-scope objects the cache cannot serialize.
    notCompatibleWithConfigurationCache("captures build-script state in its doLast action")

    val sources = fileTree(rootDir) {
        // main AND the flavour source sets — feature/files, core/billing and
        // feature/health carry user-facing strings in src/foss and src/play too,
        // and a gate that skips them reports "done" one flavour early.
        include("**/src/main/kotlin/**/*.kt")
        include("**/src/foss/kotlin/**/*.kt")
        include("**/src/play/kotlin/**/*.kt")
        exclude("**/build/**")
        exclude("Modern security app design system/**")
    }
    val allowlistFile = rootProject.file("config/i18n-allowlist.txt")

    inputs.files(sources)
    inputs.file(allowlistFile)
    outputs.upToDateWhen { false }

    doLast {
        val allowed = allowlistFile.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val params = listOf(
            "text", "label", "title", "body", "contentDescription", "placeholder",
            "hint", "message", "subtitle", "actionLabel", "dismissLabel",
            // Added 2026-09-13: the \b-anchored regex below built
            // from this list matches a name as a whole word, so it never matched onClickLabel /
            // onLongClickLabel / stateDescription — "Label" and "Description" only ever appear here
            // as suffixes, not as the parameter name itself. A hardcoded a11y hint on any of those
            // three passed this gate silently; this is the same blind-spot class documented above for
            // dismissLabel's default-argument shape, just missing from the params list rather than
            // the regex's `=` anchoring. Verified by regex simulation: 'onClickLabel = "Skip"',
            // 'onLongClickLabel = "Skip"' and 'stateDescription = "Skip"' all NO MATCH before this
            // line, MATCH after; 'contentDescription = "Delete item"' still matches (control).
            "onClickLabel", "onLongClickLabel", "stateDescription",
        )
        // Matches BOTH `title = "..."` at a call site and `title: String = "..."` in a component's
        // own parameter list. The optional type is why: the regex used to require the `=` to follow
        // the parameter name directly, so `dismissLabel: String? = "Cancel"` in LkDialog slipped
        // past it for the whole extraction wave — and that one default reached nine dialogs, which
        // offered an English "Cancel" in all four translated languages. A default argument is
        // user-facing text like any other, and it is worse than a call-site literal because one
        // occurrence hides behind every caller that stays silent.
        val paramLiteral =
            Regex("""\b(${params.joinToString("|")})\s*(:\s*[\w.]+\??\s*)?=\s*"[^"]{2,}"""")
        val bareText = Regex("""\bText\(\s*"[^"]{2,}"""")

        // SECOND DETECTOR: prose-shaped literals the parameter scan cannot see.
        //
        // The first detector matches ~10 Compose parameter names. That misses every
        // user-facing string a ViewModel holds, every entry in an error catalogue, and
        // every literal passed positionally — and those are not edge cases: the first
        // extraction wave shipped two modules whose SCREENS were fully migrated while
        // their ViewModels still held "Cloud backup disabled" and "Good, with one thing
        // worth fixing", and this gate reported both modules complete.
        //
        // A prose literal is >= 15 characters and starts with a capital. Excluded:
        // internal-only contexts (throw / error( / require( / Log.), because those are
        // preconditions and diagnostics that never reach a screen and must NOT be
        // translated; and LkIcons.kt, which is SVG path data.
        val prose = Regex(""""[A-Z][^"]{14,}"""")
        val internalOnly = Regex(
            """\b(error|require|check|checkNotNull|requireNotNull)\(|throw |Log\.[dviwe]|Illegal|assert""",
        )

        // SVG path data, recognised by SHAPE rather than by which file it sits in.
        //
        // `LkIcons.kt` is exempted by filename below. That exemption was written when it was the
        // only file holding path data, and on 2026-07-31 `LkLogo.kt` arrived with the same content
        // and was duly reported as four hardcoded user-facing strings. A filename allowlist only
        // knows the files that already exist, so it fails exactly when someone adds the next one.
        //
        // Path data is command letters, numbers and separators — "M12 5.24 L17.8 7.71 V12.07 …".
        // No sentence in any of the five shipped languages survives that character set, so this
        // cannot mask a real string: the moment a literal contains a word, it stops matching.
        val svgPathData = Regex("""^[-MmLlCcSsQqTtAaZzVvHh0-9 .,+]+$""")
        val uiRoots = listOf("app/", "feature/", "core/ui/", "core/vault/", "autofill/", "credential/")

        // Compose animation APIs take a debug `label` too (animateFloatAsState,
        // animateColorAsState, ...), and those are never user-facing. They are
        // reliably distinguishable by shape: an animation label is a lowercase
        // kebab token ("regen-spin"), while every user-facing label in this app
        // is capitalised prose. Exempting the token shape keeps whole files out
        // of the allowlist for four debug strings.
        val animationLabel = Regex("""\blabel\s*=\s*"[a-z0-9-]+"""")

        // A redaction marker is the exact opposite of user-facing. `toString()` on a secret-bearing
        // type exists so the type prints *nothing* — `core/model` has shipped `"Login(redacted)"`
        // since the rule was written, and only escaped this gate because `core/model` is not one of
        // the `uiRoots`. When the presentation-layer states grew the same guard (V27, 2026-09-12)
        // the identical literal landed inside `feature/`, and the prose detector read
        // "ItemEditUiState(redacted)" as 25 characters of copy. The shape is unmistakable and
        // cannot mask a real string: it must be an `override fun toString()` whose literal is a
        // single identifier followed by "(redacted)".
        val redactedToString = Regex("""override fun toString\(\).*"\w+\(redacted\)"""")

        // THIRD DETECTOR: accessibility labels built as string templates.
        //
        // `Modifier.labelled(...)` takes its text positionally, so the parameter scan never
        // sees it, and a template that opens with `$` or a lowercase word never reaches the
        // prose detector's "capital letter, 15+ characters" shape. Two shipped that way and
        // were only caught by reading TalkBack labels on a French device on 2026-07-30:
        // `"$label, enabled"` on every generator tick box, and `"$x seconds of $y"` in the
        // video player. Both announced English in all four translated languages.
        //
        // A template is an offender when, after removing every `${...}`/`$name` hole, what
        // remains contains a run of two or more letters — that is a word, and a word inside
        // an accessibility label is user-facing text.
        // Every literal on the line is examined, not just one directly after the paren: the
        // tick-box offender was `.labelled(if (checked) "…" else "…")`, and a regex anchored
        // to the call's first argument would have missed the case that motivated the check.
        val labelledCall = Regex("""\.labelled\(""")
        val anyLiteral = Regex(""""([^"]*)"""")
        val templateHole = Regex("""\$\{[^}]*\}|\$\w+""")

        // Annotation arguments are never user-facing. They are compiler and tooling input — the
        // same category as `error(` and `Log.d`, which `internalOnly` already exempts — but those
        // are single-line shapes, and an annotation argument both wraps and sits on its own line.
        //
        // Two real false positives motivated this, one per shape: `@Deprecated`'s replacement
        // message for `PRO_PRICE` runs to two lines, and only the line *not* holding the
        // `@Deprecated(` was reported; and `@Suppress("UNUSED_PARAMETER")` in `PlatformReview.kt`
        // is sixteen capitalised characters, which is precisely the prose detector's shape.
        //
        // Paren-depth rather than "the line after the annotation", so an argument of any length is
        // covered and an ordinary literal on the line that closes the annotation is not. Scoped to
        // annotations that OPEN a paren at the start of a line, so `@Composable` on a function
        // whose body follows is untouched.
        val annotationOpen = Regex("""^@\w+\s*\(""")

        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            val rel = file.relativeTo(rootDir).path.replace('\\', '/')
            if (rel in allowed) return@forEach
            var annotationDepth = 0
            file.readLines().forEachIndexed { i, line ->
                // Skip comments — including KDoc continuation lines. The prose detector
                // below is deliberately loose, and this codebase's comments quote UI copy
                // constantly ("Delete forever", "Remove animations"), so without the `*`
                // and `/*` cases every explanatory block comment reads as an offender.
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    return@forEachIndexed
                }
                val openingAnnotation = annotationDepth == 0 && annotationOpen.containsMatchIn(trimmed)
                if (openingAnnotation || annotationDepth > 0) {
                    annotationDepth += line.count { it == '(' } - line.count { it == ')' }
                    return@forEachIndexed
                }
                val hit = paramLiteral.containsMatchIn(line) || bareText.containsMatchIn(line)
                val proseHit = uiRoots.any { rel.startsWith(it) } &&
                    prose.containsMatchIn(line) &&
                    !internalOnly.containsMatchIn(line) &&
                    // Not prose if every prose-shaped literal on the line is SVG path data. This
                    // replaces an `!rel.endsWith("LkIcons.kt")` exemption — a filename allowlist
                    // that failed the moment LkLogo.kt appeared holding the same content. Shape
                    // travels with the string; a filename does not.
                    //
                    // Template holes come out first, as in the `labelled` detector below: a circle
                    // in LkIcons.kt is built as "M${cx - r} ${cy}a$r $r 0 1 0 …", and the computed
                    // parts are Kotlin expressions rather than anything a translator would read.
                    !prose.findAll(line).all {
                        svgPathData.matches(templateHole.replace(it.value.trim('"'), ""))
                    }
                val animOnly = animationLabel.containsMatchIn(line) && !bareText.containsMatchIn(line)
                val labelledHit = labelledCall.containsMatchIn(line) &&
                    anyLiteral.findAll(line).any { m ->
                        templateHole.replace(m.groupValues[1], "").contains(Regex("""\p{L}{2,}"""))
                    }
                val redactionMarker = redactedToString.containsMatchIn(line)
                if (!redactionMarker && ((hit && !animOnly) || proseHit || labelledHit)) {
                    offenders += "$rel:${i + 1}: ${line.trim()}"
                }
            }
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Hardcoded user-facing strings in ${offenders.size} place(s).\n" +
                    "Extract them to strings.xml, or add the file to config/i18n-allowlist.txt\n" +
                    "if it is not yet migrated.\n\n" + offenders.joinToString("\n"),
            )
        }
        println("checkNoHardcodedUiStrings OK: no unmigrated user-facing literals outside the allowlist.")
    }
}

// Twice in the 2026-09-13 fix wave a resource comment was written with a literal `--` inside it,
// and both times it took the build down for every agent at once: AAPT rejects it
// ("The string \"--\" is not permitted within comments"), so `packageDebugResources` fails and
// every task downstream of the owning module — including the other guardrails — cannot run. The
// failure is also badly localised: the error names the module, and whoever is next to build
// inherits a red they did not cause.
//
// The contributor notes already warned about this for tier markers, where it matters for a different
// reason (an XML comment may not contain two consecutive hyphens, so a tier rationale cannot quote
// the marker syntax). A warning in prose stopped neither occurrence. This is the same statement as
// a check that runs.
//
// Parsing rather than pattern-matching, deliberately: the XML spec's rule is about comment bodies,
// and a regex over raw text has to re-derive where those start and end. The parser already knows —
// and it catches every other malformedness in the same pass for free.
tasks.register("checkResXmlWellFormed") {
    group = "verification"
    description = "Fails when a resource XML file is malformed, including `--` inside a comment."

    val resFiles = fileTree(rootDir) {
        include("**/src/*/res/**/*.xml")
        exclude("**/build/**")
    }.files.sorted()
    // Captured at configuration time, not read from `rootDir` inside the action: touching a
    // Project from doLast is what makes a task non-serializable, and
    // checkVaultAtRestNoAsymmetricCrypto was fixed for exactly this on 2026-09-07.
    val rootPath = rootDir

    inputs.files(resFiles)
    outputs.upToDateWhen { false }

    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        // No DTD fetching, no entity expansion: this reads only local resource files and must
        // never reach for the network to validate one.
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

        val broken = resFiles.mapNotNull { file ->
            try {
                factory.newDocumentBuilder().apply {
                    setErrorHandler(null)
                }.parse(file)
                null
            } catch (e: Exception) {
                "${file.relativeTo(rootPath)}: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
            }
        }

        if (broken.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Malformed resource XML in ${broken.size} file(s).")
                    appendLine("A comment may not contain two consecutive hyphens — use a comma or an em dash.")
                    appendLine()
                    broken.forEach { appendLine("  $it") }
                },
            )
        }
        println("checkResXmlWellFormed OK: ${resFiles.size} resource files parse.")
    }
}
