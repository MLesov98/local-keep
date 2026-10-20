package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the one deliberate hole in the concealment boundary.
 *
 * `VaultRepository.getCompleteStorageInventory()` returns every record the vault holds, concealed
 * ones included. A handful of callers need that and would destroy or corrupt data without it; every other
 * read in the app must go through the presentation projection. The failure this prevents is not
 * hypothetical and not loud: a future contributor reaches for "the one that returns everything"
 * because it is the obvious name, a concealed bank login appears in an autofill sheet, and nothing
 * about the build goes red.
 *
 * So the caller set is a committed list rather than a convention. This follows the precedent of
 * `config/i18n-allowlist.txt`: adding a caller is a reviewed edit to a list with a written
 * justification, never an accident, and — like that allowlist — this one should only ever shrink.
 *
 * Production sources only. Tests are expected to read the raw inventory: proving an item is
 * concealed from the projection *and* still physically present is exactly how the feature is tested.
 */
class HiddenChokepointTest {

    /**
     * file path → the function that may call it, and why filtering would be a bug.
     *
     * Each entry names a data-loss or authenticated-management justification. "It was convenient"
     * is not one.
     */
    private val allowlist = mapOf(
        // Reachability is not a screen. A sweep driven by a presentation read computes "nothing
        // points at this blob" for every concealed item and permanently deletes the attachments of
        // exactly the entries the user asked to protect.
        "feature/files/src/main/kotlin/com/localkeep/feature/files/BlobGarbageCollector.kt"
            to "referencedRefs",
        // A concealed item's files occupy the same disk as any other. Counting only what is visible
        // would make hiding a folder a way to buy quota back.
        "core/vault/src/main/kotlin/com/localkeep/core/vault/AttachmentQuota.kt"
            to "exceedsFreeLimit",
        // Same reason as AttachmentQuota's, for the Card and Identity allowances added 2026-09-03:
        // a concealed card is still a stored card. Counting only what is visible would make hiding
        // one a way to buy the allowance back, and the cap could then be walked past indefinitely
        // by concealing everything already stored.
        "core/vault/src/main/kotlin/com/localkeep/core/vault/ProTypeQuota.kt"
            to "usedFromRepository",
        // The accessor itself, plus the authenticated Hidden-items management feed beside it.
        "core/vault/src/main/kotlin/com/localkeep/core/vault/VaultRepository.kt"
            to "getCompleteStorageInventory",
    )

    private val repoRoot: File
        get() {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
            return requireNotNull(dir) { "could not locate the repository root from ${System.getProperty("user.dir")}" }
        }

    /**
     * Code with its comments removed.
     *
     * The scan has to read code, not prose. Several files explain *why* they avoid the raw inventory
     * and name it while doing so — `VaultVisibility` is one — and counting those as callers would
     * either fail the build for a correct file or, far worse, teach the next person to delete the
     * explanation to make the guard pass.
     */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun productionSources(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") && !it.name.endsWith("-out") }
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                path.contains("/src/main/") && !path.startsWith("test/")
            }
            .toList()

    @Test
    fun onlyTheAllowlistedCallersReadTheCompleteStorageInventory() {
        val root = repoRoot
        val sources = productionSources(root)
        assertTrue(sources.size > 100, "source scan found only ${sources.size} files — the walk is wrong, not the code")

        val callers = sources
            .filter { codeOnly(it.readText()).contains("getCompleteStorageInventory") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()

        assertEquals(
            allowlist.keys.sorted(),
            callers,
            "the set of files reading the complete storage inventory changed. Adding one is a " +
                "deliberate edit to the allowlist in this test, with a data-loss or " +
                "authenticated-management justification written beside it. Every other read goes " +
                "through VaultRepository.visibilitySnapshot().",
        )
    }

    @Test
    fun eachAllowlistedCallSiteSitsInsideItsNamedFunction() {
        val root = repoRoot
        for ((path, function) in allowlist) {
            val file = File(root, path)
            assertTrue(file.isFile, "allowlisted file no longer exists: $path")
            val text = codeOnly(file.readText())
            val functionAt = text.indexOf("fun $function")
            assertTrue(functionAt >= 0, "$path no longer declares fun $function")
            // The call has to appear after the declaration of the function that is allowed to make
            // it. Crude, and deliberately so: it catches the case this guard exists for — a second,
            // unreviewed call added elsewhere in an already-allowlisted file.
            val calls = Regex("getCompleteStorageInventory").findAll(text).map { it.range.first }.toList()
            assertTrue(
                calls.all { it > functionAt },
                "$path calls getCompleteStorageInventory outside fun $function",
            )
        }
    }
}
