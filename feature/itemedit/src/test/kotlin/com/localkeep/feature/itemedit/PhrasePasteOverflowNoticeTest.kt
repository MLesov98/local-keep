package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A phrase paste longer than the selected word count must say so.
 *
 * Found in the 2026-09-09 functional sweep: `pastePhrase`'s own doc comment already claimed "extra
 * words are dropped" as a settled fact, but nothing told the user it happened — paste an 18-word
 * BIP-39 phrase into a 12-word grid and the last six vanish with no sign anything was cut. The
 * existing Undo notice ([LkNotices]) only fires when the paste *overwrote* words already on the
 * grid; the common overflow case is a first paste into an empty grid, where nothing is overwritten
 * and the old code returned in total silence.
 *
 * The fix names the drop with its own notice — `itemedit_notice_phrase_paste_overflow`, a
 * `<plurals>` naming the count, posted through the same [LkNotices] channel the overwrite Undo
 * notice already uses (prior art: `LkNotices`, `feature/itemedit/.../
 * LkNotices.kt`). No Undo action on it: the overflow words never landed on the grid, so there is no
 * prior state to restore — restoring one would mean growing the grid past the word count the user
 * selected, which is a different feature.
 *
 * Source scan, not a unit test on `pastePhrase` directly: this module has no Robolectric wiring and
 * `ItemEditViewModel` cannot be constructed on a JVM at all (its `VaultRepository` needs a
 * `VaultLockManager` whose `KeystoreManager` opens AndroidKeyStore in its constructor) — the same
 * reason `IdentityPhotoRouteEntitlementTest` and `IdentityStartScrollableTest` are scans. Each claim
 * is paired with a control that held before the fix.
 */
class PhrasePasteOverflowNoticeTest {

    private fun pastePhraseBody(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
        assertTrue(src.exists(), "ItemEditViewModel.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("fun pastePhrase(")
        assertTrue(start > 0, "pastePhrase( not found — did it get renamed?")
        val end = text.indexOf("private fun recheckVaultKit()", start)
        assertTrue(end > start, "recheckVaultKit( not found after pastePhrase — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `an overflowing paste posts a notice naming the dropped count`() {
        val body = pastePhraseBody()
        assertTrue(
            "itemedit_notice_phrase_paste_overflow" in body,
            "pastePhrase no longer posts the overflow notice — a paste longer than the selected " +
                "word count is dropped again with nothing said about it: $body",
        )
        assertTrue(
            "LkNotices.Message.Plural(" in body,
            "the overflow notice is not posted as a counted LkNotices.Message.Plural — the count " +
                "of dropped words would not be a true, localized claim",
        )
    }

    @Test
    fun `the overflow count is the extra words beyond the selected word count, not the total pasted`() {
        val body = pastePhraseBody()
        assertTrue(
            "pasted.size - previous.phraseWordCount" in body,
            "the overflow count is no longer computed as pasted words minus the grid's own word " +
                "count — a paste exactly filling the grid must not be reported as an overflow: $body",
        )
    }

    @Test
    fun `overflow is checked only once the overwrite branch has returned`() {
        val body = pastePhraseBody()
        val overwriteAt = body.indexOf("if (overwrote)")
        val overflowAt = body.indexOf("val overflow =")
        assertTrue(overwriteAt > 0, "the overwrote branch is gone — did pastePhrase get restructured?")
        assertTrue(overflowAt > 0, "no overflow count is computed in pastePhrase")
        assertTrue(
            overflowAt > overwriteAt,
            "the overflow count is computed before the overwrite check — LkNotices is single-slot, " +
                "so posting the overflow notice first would bury the overwrite Undo notice, the one " +
                "notice in this function that can actually recover lost data",
        )
    }

    /**
     * The control: the doc comment's own claim — that extra words are dropped — is the behavior
     * this fix must not change, only announce. True before this fix and must stay true after it.
     */
    @Test
    fun `the grid still holds only up to the selected word count`() {
        val body = pastePhraseBody()
        assertTrue(
            "List(phraseWordCount) { pasted.getOrElse(it) { \"\" } }" in body,
            "pastePhrase no longer bounds the filled grid to phraseWordCount — a longer paste would " +
                "silently grow the grid instead of being reported as an overflow: $body",
        )
    }
}
