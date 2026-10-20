package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The camera-permission denial must sit one step below the buttons that raised it, not two.
 *
 * The denial is one item of the screen's list, spaced by the list's `Arrangement.spacedBy` and by
 * the block's own bottom padding (`blockGap`). A `Spacer` beside it is another item, spaced on both
 * sides, so the rendered distance used to be 16 + 8 + 16 = 40dp when the arrangement was 16dp. The
 * spacedBy trap, found again 2026-09-13.
 *
 * Asserted on the source because a spacing arrangement is not observable from a JVM unit test and
 * the property is structural — the same idiom `feature/vaultlist`'s `TrashPreviewRevealGateTest`
 * uses. Comment lines are stripped from the slice first: the fix's own comment explains the Spacer
 * it removed, and a scan that counted prose would be answering about the comment.
 */
class CameraDenialSpacingTest {

    private val picker: String =
        File("src/main/kotlin/com/localkeep/feature/files/AttachmentPicker.kt").readText()

    private val anchor = "if (cameraDenial != null) {"

    /** The denial block's own code, comments removed, bounded to the 20 lines it fits inside. */
    private val denialCode: String = run {
        val start = picker.indexOf(anchor)
        require(start >= 0) { "anchor '$anchor' not found in AttachmentPicker.kt" }
        picker.substring(start)
            .lineSequence()
            .take(20)
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    /** Control: the slice is bounded and reaches the error it is asserting about. */
    @Test
    fun `reads the denial block, whole and bounded`() {
        assertTrue(denialCode.lines().size <= 20, "the slice must stay bounded")
        assertTrue(
            "LkInlineError(" in denialCode,
            "the slice stops before the inline error, so an absence inside it proves nothing",
        )
    }

    /**
     * Control, and the thing the fix depends on: removing the Spacer is only correct while the
     * enclosing Column still supplies a gap of its own. If that arrangement ever goes, the error
     * would butt straight against the camera buttons and this test would be enforcing the wrong
     * half of the rule.
     */
    @Test
    fun `the enclosing column still arranges its children`() {
        assertTrue(
            "Arrangement.spacedBy(LkSpace.sm)" in picker && "val blockGap = Modifier.padding(bottom = LkSpace.sm)" in picker,
            "the attachments list no longer spaces its blocks, so the denial error has no gap",
        )
    }

    @Test
    fun `the denial error adds no spacer of its own`() {
        assertFalse(
            "Spacer(" in denialCode,
            "a Spacer beside the denial error is a sibling in the list's spacedBy arrangement, " +
                "so it is spaced on both sides and renders far more than the gap intended",
        )
    }
}
