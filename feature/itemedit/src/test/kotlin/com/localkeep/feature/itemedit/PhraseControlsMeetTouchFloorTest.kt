package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two remaining under-floor controls on the recovery-phrase form.
 *
 * `PhrasePillIsSafeToMissTest` raised the Words-header pills and scoped itself to that composable;
 * it asserts nothing about the rest of the screen, and two controls were still short of the 48dp
 * floor the UI/UX spec sets (§ "minimum touch targets (>=48dp Android / >=44pt iOS)"):
 *
 * - the word-count selector (12/15/18/21/24), a row of `.selectable()` boxes fixed at 44dp;
 * - the autocomplete chip that commits a word straight into the grid, fixed at 36dp.
 *
 * Both are one-axis deficits — width is comfortable in each case — so what the shortfall costs is
 * a lost tap rather than a wrong word, and both sit on the one screen where the file's own comments
 * call a miss expensive. The chip needed the two-node split rather than an appended
 * `minTouchTarget()`: that helper is `defaultMinSize`, which raises a dimension only when the
 * incoming minimum is zero, so appending it under an exact `.height()` is a silent no-op — the
 * regression `LkIconButton` records having shipped for three months.
 *
 * Source scans, for the reason `PhrasePillIsSafeToMissTest` gives, each with a control that held
 * before the fix.
 */
class PhraseControlsMeetTouchFloorTest {

    private fun screenSource(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    /**
     * The region's code with its comment lines dropped, the way `AllowanceDialogVoiceTest` reads
     * the allowance dialog. Both fixes below are explained in comments that quote the modifier
     * calls they replaced, so a scan over the raw text would find `.height(36.dp)` in prose and
     * conclude the drawn chip sits outside its own touch target.
     */
    private fun region(from: String, to: String): String {
        val text = screenSource()
        val start = text.indexOf(from)
        assertTrue(start > 0, "$from not found — did the region move?")
        val end = text.indexOf(to, start)
        assertTrue(end > start, "$to not found after $from — region unbounded")
        return text.substring(start, end)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    private fun countRow(): String =
        region("R.string.itemedit_phrase_count_label", "// ── words: header")

    private fun suggestionChips(): String =
        region("if (suggestions.isNotEmpty()) {", "private fun PhrasePillButton(")

    @Test
    fun `the word-count selector stands at the touch floor`() {
        val body = countRow()
        // The control: it is still the exclusive radio group, not some other row. True before the
        // fix and after, so a region that stopped matching fails instead of passing empty.
        assertTrue(
            "Role.RadioButton" in body,
            "the word-count row is no longer a selectable radio group — region wrong?",
        )
        assertTrue(
            "MinTouchTarget" in body,
            "the word-count options no longer state their height as the floor constant. They were " +
                "an exact 44.dp, 4dp under it, on the control that decides how many slots a " +
                "recovery phrase gets.",
        )
        assertTrue(
            ".height(44.dp)" !in body,
            "the word-count options are back to an exact 44.dp. `.height()` is an exact " +
                "constraint, so no minTouchTarget() appended below it can raise them.",
        )
    }

    @Test
    fun `the suggestion chip is tappable across the whole floor`() {
        val body = suggestionChips()
        // The control: the chip is still the thing that commits a word into the grid. True before
        // the fix and after.
        assertTrue(
            "viewModel.commitPhraseWord(word)" in body,
            "the suggestion chip no longer commits a word — region wrong?",
        )
        assertTrue(
            ".minTouchTarget()" in body,
            "the suggestion chip has no touch floor. It is a 36dp clickable that writes a word " +
                "straight into a recovery phrase.",
        )
        val click = body.indexOf(".clickable {")
        val floor = body.indexOf(".minTouchTarget()")
        assertTrue(click > 0, "the suggestion chip is no longer clickable — region wrong?")
        assertTrue(
            floor > click,
            "minTouchTarget() must sit after .clickable() in the same chain, or the reserved " +
                "space is not part of the pointer-input bounds (see A11y.kt and MinTouchTargetTest).",
        )
        val drawn = body.indexOf(".height(36.dp)")
        assertTrue(
            drawn > floor,
            "the drawn 36dp chip is not inside the tappable node. With the fixed height above " +
                "minTouchTarget() in one chain the floor is a silent no-op — the trap LkIconButton " +
                "records as having cost every icon button in the app 8dp for three months.",
        )
    }
}
