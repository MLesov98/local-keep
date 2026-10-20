package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * N-03: the recovery-phrase editor's Reveal/Hide pill rendered its revealed state in jade, where
 * every other reveal control in the app — the per-field toggle 1400 lines below in this same file,
 * the unlock screen's password well, the detail screen's password well — renders it in amber.
 *
 * `LkColors.kt`'s own rule is that jade is the app's one colour for a primary action, and revealing
 * a field is a state toggle, not one; amber is "worth looking at, nothing wrong", which is the whole
 * of what a secret sitting on screen is. This was the one reveal control that got it wrong.
 *
 * A source scan rather than a render, for the reason `PhrasePillIsSafeToMissTest` gives: this module
 * has no Robolectric wiring. Paired with a control that was true before the fix too, so a region
 * that stopped matching fails loudly instead of passing empty.
 */
class PhraseRevealColourMatchesOtherScreensTest {

    private fun screenSource(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    /** The Words-header Reveal/Hide pill, the same region `PhrasePillIsSafeToMissTest` does not cover. */
    private fun revealPillBody(): String {
        val text = screenSource()
        val start = text.indexOf("R.string.itemedit_phrase_hide else R.string.itemedit_phrase_reveal")
        assertTrue(start > 0, "the phrase Reveal/Hide pill's text argument not found — did it move?")
        // The pill moved into `PhraseWordsHeader` (SCL-014), which takes the toggle as a lambda.
        val end = text.indexOf("onClick = onToggleReveal", start)
        assertTrue(end > start, "onToggleReveal not found after the Reveal/Hide text — region unbounded")
        return text.substring(start, end)
    }

    /** The per-field reveal toggle every other secret on this screen already uses amber for. */
    private fun perFieldRevealToggleBody(): String {
        val text = screenSource()
        val start = text.indexOf("Amber, not jade, once the secret is on screen.")
        assertTrue(start > 0, "the per-field reveal toggle's amber-rationale comment not found — did it move?")
        val end = text.indexOf("container = Color.Transparent,", start)
        assertTrue(end > start, "the per-field reveal toggle's tint assignment not found after its rationale")
        return text.substring(start, end)
    }

    @Test
    fun `the phrase editor's Reveal-Hide pill is amber when revealed, not jade`() {
        val body = revealPillBody()
        // The control: still the icon/text pair that flips with `reveal`. True before the fix and
        // after, so a region that stopped matching the composable fails instead of passing empty.
        assertTrue(
            "LkIcons.EyeOff else LkIcons.Eye" in body,
            "the phrase Reveal/Hide pill no longer swaps its glyph on `reveal` — region wrong?",
        )
        assertTrue(
            "tint = if (reveal) c.amber else c.textMuted" in body,
            "the phrase editor's Reveal/Hide pill must render amber, not jade, once revealed — " +
                "jade is reserved for primary action, and every other reveal control on this " +
                "screen (and on the unlock and detail screens) already uses amber for this state.",
        )
        assertFalse(
            "tint = if (reveal) c.jade else c.textMuted" in body,
            "the phrase editor's Reveal/Hide pill is back to jade when revealed",
        )
    }

    /** Control: the per-field toggle this fix now matches must still be amber — unmoved by the fix. */
    @Test
    fun `the per-field reveal toggle stays amber when revealed`() {
        val body = perFieldRevealToggleBody()
        assertTrue(
            "tint = if (revealed) c.amber else c.textMuted" in body,
            "the per-field reveal toggle no longer renders amber when revealed — region wrong, or " +
                "this fix's own comparison point moved with it",
        )
    }
}
