package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The vault list's empty panel is decided by emptiness, never by the tier.
 *
 * Replaces `LockedCategoryPitchTest`, which pinned the rule this one forbids. Cards and Identity
 * documents were a binary Pro gate until 2026-09-03, when each became a one-item free allowance
 * (`ProTypeQuota`). The guard in front of `EmptyVault` was not migrated with them, so it kept
 * asking `it.requiresPro && !state.isPro` and composed the empty panel for the Cards and Identities
 * chips on every free vault — including a `foss` vault, where `isPro` is permanently false. A user
 * who had created their one allowed card, or imported twenty from KDBX (import is uncapped), was
 * shown "No cards yet" over an "Add a card" button that then refused, with the header one line
 * above reading "1 item". The rows were in `state.items` the whole time; only the LazyColumn branch
 * was skipped.
 *
 * The invariant is written verbatim at `ProTypeQuota`'s § The invariant:
 *
 * > **The cap limits creation. It never limits import, and it never limits display.**
 *
 * followed by "Anything enforcing a cap must therefore be a check at the add path, never a filter
 * on a list". This is the vault list's half of that; `SpentBadgeCopyTest.theFilterSheetMarksNoType`
 * `AsPaid` is the filter sheet's, and its absence here is why this call site survived the migration.
 *
 * Asserted on the source, for the same reason `SpentBadgeCopyTest` and `UnlockDispatcherTest` are:
 * the defect is a composable guard around a call, and discriminating "no panel" from "a panel the
 * test could not find" would need the whole screen plus a Pro-entitlement ambient.
 */
class EmptyPanelGuardTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")

    /**
     * Comments explain why the tier question is gone and quote the rule it used to apply; they must
     * not trip the checks, or the explanation becomes unwritable. Same idiom as `SpentBadgeCopyTest`.
     */
    private fun code(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    /** The condition standing between the screen and the empty panel. */
    private fun guard(): String =
        code().substringAfter("if (!state.loadFailed", missingDelimiterValue = "")
            .substringBefore("EmptyVault(")
            .ifEmpty { fail("the empty panel is no longer guarded on !state.loadFailed") }

    @Test
    fun `the empty panel is decided by emptiness, not by the tier`() {
        val guard = guard()
        listOf("lockedCategory", "requiresPro", "isPro").forEach { tierQuestion ->
            assertTrue(
                tierQuestion !in guard,
                "the empty-panel guard consults `$tierQuestion` again. That is the pre-2026-09-03 " +
                    "gate rule: it replaces the list with an empty panel for every free vault's " +
                    "Cards and Identities chips, hiding cards and identity documents the vault " +
                    "HOLDS. The cap limits creation, never display. Found: $guard",
            )
        }
    }

    @Test
    fun `emptiness still means no items and no folders`() {
        val guard = guard()
        assertTrue(
            "state.items.isEmpty()" in guard && "state.childFolders.isEmpty()" in guard,
            "the empty panel must be composed exactly when there is nothing to draw — no item rows " +
                "and no folder rows. Found: $guard",
        )
    }

    /**
     * The load-failure case, carried over from `LockedCategoryPitchTest`. An unreadable vault also
     * produces an empty list, and an empty-vault panel over a "couldn't read the vault" banner is
     * the app asserting something it does not know — on the one screen where an apparently empty
     * vault reads as lost data.
     */
    @Test
    fun `a failed read still suppresses the empty panel`() {
        assertTrue(
            "if (!state.loadFailed" in code(),
            "a failed read must never be painted as an empty vault",
        )
    }

    /**
     * `EmptyVault` took an `isPro` its body never read — the parameter outlived the branch that
     * used it. A dead entitlement parameter on this composable is what a re-gating would reach for
     * first, so its absence is worth pinning.
     */
    @Test
    fun `the empty panel takes no entitlement parameter`() {
        val declaration = code().substringAfter("internal fun EmptyVault(", missingDelimiterValue = "")
            .substringBefore(") {")
            .ifEmpty { fail("VaultListScreen no longer declares EmptyVault(") }
        assertTrue(
            "isPro" !in declaration,
            "EmptyVault takes an entitlement again. It renders what the vault holds; the tier is " +
                "not one of its inputs. Found: $declaration",
        )
    }

    /** The control: prove the file being read is really the vault list screen. */
    @Test
    fun `the file being read really is the vault list screen`() {
        val text = source.readText()
        assertTrue("fun VaultListScreen(" in text, "read a file that is not the vault list screen")
        assertTrue("EmptyVault(" in text, "read a vault list screen with no empty panel")
    }
}
