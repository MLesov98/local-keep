package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two pills on the Recovery-phrase Words header must be tappable, and "Paste all" must be
 * undoable.
 *
 * `PhrasePillButton` drew a 30dp row with a `clickable` on it and nothing else — 18dp under the
 * 48dp floor that the word slots twenty pixels below it already hold, and the file says so in its
 * own `PHRASE_SLOT_HEIGHT` comment ("a tappable cell is held to the 48dp touch floor"). The floor
 * was applied to the grid in that same function and simply not extended to the header.
 *
 * That is a touch-target defect anywhere. Here it is a data-loss one, because of what sits 8dp to
 * the left of Reveal: "Paste all" replaced every word in the grid with no confirmation and no way
 * back, so a near-miss on a control that is only 30dp tall cost a recovery phrase that typically
 * exists on one piece of paper. Both halves are asserted here — the floor alone does not remove the
 * adjacency risk, and the Undo alone does not make the target hittable.
 *
 * A source scan rather than a render, for the reason `IdentityStartScrollableTest` gives: this
 * module has no Robolectric wiring and its Compose UI tests never reach idle (see the note in its
 * `build.gradle.kts`). Each claim is paired with a control that was true before the fix too, so a
 * scan whose region silently stopped matching cannot pass by matching nothing.
 */
class PhrasePillIsSafeToMissTest {

    private fun screenSource(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    private fun pillBody(): String {
        val text = screenSource()
        val start = text.indexOf("private fun PhrasePillButton(")
        assertTrue(start > 0, "PhrasePillButton( not found — did the composable get renamed?")
        val end = text.indexOf("private fun PhraseSlot(", start)
        assertTrue(end > start, "PhraseSlot( not found after PhrasePillButton — region unbounded")
        return text.substring(start, end)
    }

    private fun pastePhraseBody(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
        assertTrue(src.exists(), "ItemEditViewModel.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("fun pastePhrase(text: String) {")
        assertTrue(start > 0, "pastePhrase( not found — did it get renamed?")
        val end = text.indexOf("private fun recheckVaultKit()", start)
        assertTrue(end > start, "recheckVaultKit( not found after pastePhrase — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `a words-header pill is held to the touch floor`() {
        val body = pillBody()
        // The control: the pill still draws itself as a pill. True before the fix and after, so a
        // region that stopped matching the composable fails loudly instead of passing empty.
        assertTrue("LkShape.xs" in body, "PhrasePillButton no longer draws the pill shape — region wrong?")
        assertTrue(
            "minTouchTarget()" in body,
            "PhrasePillButton has no minTouchTarget(). It is a 30dp clickable holding 'Paste all', " +
                "8dp from Reveal/Hide, and 'Paste all' overwrites the whole recovery-phrase grid.",
        )
    }

    @Test
    fun `the tappable box is the one that grows, not only the drawn pill`() {
        val body = pillBody()
        // minTouchTarget forces the floor onto the constraints it hands down, so the click has to
        // be outside it in the chain for the grown area to be tappable. A version that put the
        // floor on an inner node would satisfy the test above while leaving the extra space dead.
        val click = body.indexOf(".clickable(")
        val floor = body.indexOf(".minTouchTarget()")
        assertTrue(click > 0, "PhrasePillButton is no longer clickable — region wrong?")
        assertTrue(
            floor > click,
            "minTouchTarget() must sit after .clickable() in the same chain, or the reserved space " +
                "is not part of the pointer-input bounds (see A11y.kt's own note and MinTouchTargetTest).",
        )
    }

    @Test
    fun `pasting over an entered phrase is undoable`() {
        val body = pastePhraseBody()
        // The control: pasting still re-checks whether the grid is this vault's own Recovery Kit.
        // True before the fix and after.
        assertTrue("recheckVaultKit()" in body, "pastePhrase no longer re-checks the vault kit — region wrong?")
        assertTrue(
            "LkNotices.post(" in body && "itemedit_notice_undo" in body,
            "pastePhrase offers no Undo. One mistaken tap of 'Paste all' — a 30dp pill 8dp from " +
                "Reveal — replaces every word entered so far, and the words are typically on paper.",
        )
    }

    @Test
    fun `the undo notice names no word of the phrase`() {
        val body = pastePhraseBody()
        // A notice outlives the screen it was raised on, so it must carry no part of the secret.
        // The only format-argument overload of LkNotices.post takes a String message; the resource
        // overload this must use takes ids only.
        assertTrue(
            "phraseWords" !in body.substringAfter("LkNotices.post(").substringBefore("actionLabelRes"),
            "the paste notice's message argument mentions phraseWords — a recovery phrase must not " +
                "be described in a banner.",
        )
    }
}
