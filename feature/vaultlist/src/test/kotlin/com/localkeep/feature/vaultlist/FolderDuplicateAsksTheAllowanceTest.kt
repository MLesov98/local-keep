package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Folders > Duplicate asks the free allowances before it copies (2026-10-02, S5-04).
 *
 * Duplicating a folder mints fresh copies of every File, Card and Identity inside it, and it was the
 * one creation path that asked nothing — so a free vault's caps could be doubled with each tap. The
 * arithmetic is proven on the JVM by `:core:vault`'s `DuplicateAllowanceTest`; what this pins is the
 * wiring, which only an instrumented test can drive (`FolderViewModelTest`): that `duplicate`
 * consults the allowance, with the real entitlement, *before* the repository is asked to copy, and
 * names the refusal rather than falling through to the generic "That didn't work".
 *
 * A source assertion, for the reason `VaultListMainThreadWorkTest` gives: cheap, exact, and it fails
 * if the check is removed, which is the regression that matters.
 */
class FolderDuplicateAsksTheAllowanceTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/FolderViewModel.kt")

    private fun body(name: String): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val all = source.readLines()
        val start = all.indexOfFirst { it.contains(name) }
        assertTrue(start >= 0, "FolderViewModel no longer declares $name")
        val end = all.drop(start + 1).indexOfFirst { it == "    }" } + start + 1
        assertTrue(end > start, "could not find the end of $name")
        return all.subList(start, end + 1).joinToString("\n")
    }

    @Test
    fun `duplicate asks the allowance before it asks the repository to copy`() {
        val body = body("fun duplicate(")
        val check = body.indexOf("DuplicateAllowance.exceeded(")
        val copy = body.indexOf("repository.duplicateFolder(")
        assertTrue(check >= 0, "duplicate() never consults DuplicateAllowance. Found:\n$body")
        assertTrue(copy >= 0, "duplicate() no longer calls repository.duplicateFolder. Found:\n$body")
        assertTrue(check < copy, "the allowance must be asked before the copy is made. Found:\n$body")
        assertTrue(
            "entitlement.isPro.value" in body,
            "the allowance must be asked with the real entitlement, or Pro is refused too. Found:\n$body",
        )
        assertTrue(
            "R.string.vaultlist_folders_error_duplicate_allowance" in body,
            "a refused duplicate must say why, not fall through to the generic error. Found:\n$body",
        )
    }
}
