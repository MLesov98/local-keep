package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A wrapped dialog title is centred like the rest of the masthead.
 *
 * Reported from a phone, 2026-10-14, on "Delete the original from your Gallery?": the masthead
 * [Column] centres its children as boxes, but a [androidx.compose.material3.Text] that wraps lays
 * its lines out start-aligned inside its own box, so a two-line title sat flush left under a
 * centred icon. The body already set `textAlign = TextAlign.Center`; the title did not. Because
 * every `LkDialog` opens through `LkModalMasthead`, the one line fixes all of them.
 *
 * Source-scan, bounded to the title's own `Text(` call, because the defect is the absence of an
 * argument and a rendered one-line title looks identical either way.
 */
class LkModalMastheadCenteringTest {

    private val source = File("src/main/kotlin/com/localkeep/core/ui/theme/LkLogo.kt")

    /** The text of the `Text(` call whose arguments contain [anchor], brace/paren-matched. */
    private fun callContaining(anchor: String): String {
        val text = source.readText()
        val masthead = text.indexOf("fun LkModalMasthead(")
        assertTrue(masthead >= 0, "LkLogo.kt no longer declares LkModalMasthead — update this test")
        val at = text.indexOf(anchor, masthead)
        assertTrue(at >= 0, "LkModalMasthead no longer contains \"$anchor\" — update this test")
        // The bare composable, not the tail of `lkBrandedText(`.
        val open = Regex("""(?<![A-Za-z])Text\(""").findAll(text.substring(0, at)).last().range.first
        var depth = 0
        var i = text.indexOf('(', open)
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        fail("closing paren for the Text( containing \"$anchor\" was never found — update this test")
    }

    @Test
    fun `the masthead title is centred`() {
        val title = callContaining("style = LkText.sheetTitle")
        assertTrue(
            "TextAlign.Center" in title,
            "LkModalMasthead's title Text has no textAlign = TextAlign.Center: a title that wraps " +
                "to two lines renders start-aligned under a centred icon.",
        )
    }

    /** Control: the body was already centred; a regression that dropped it must also fail. */
    @Test
    fun `the masthead body is still centred`() {
        val body = callContaining("style = LkText.bodyQuiet")
        assertTrue("TextAlign.Center" in body, "LkModalMasthead's body Text is no longer centred")
    }

    @Test
    fun `the masthead gives each paragraph of the body its own Text with LkSpace sm between`() {
        val text = source.readText()
        val masthead = text.indexOf("fun LkModalMasthead(")
        val end = text.indexOf("\n}\n", masthead)
        val fn = text.substring(masthead, end)
        assertTrue("split(\"\\n\\n\")" in fn, "LkModalMasthead no longer splits the body on a blank line")
        assertTrue("spacedBy(LkSpace.sm)" in fn, "LkModalMasthead's paragraphs are no longer spaced by LkSpace.sm")
    }
}
