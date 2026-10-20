package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `TrashFolderRow`'s title must scale with font size the same way `TrashItemRow`'s does.
 *
 * Found 2026-09-13: `TrashFolderRow` (`TrashScreen.kt:732`,
 * pre-fix) rendered a trashed folder's user-typed name with a fixed `maxLines = 1`, while the sibling
 * `TrashItemRow` — same file, same `LkText.rowTitle` role, ~360 lines above — calls
 * `lkScaledMaxLines()` for the documented reason that a fixed cap loses most of a long name's
 * characters at the largest font size. `LkDynamicType.kt`'s own KDoc names "folder names" as one of
 * the three kinds of string that helper exists for.
 *
 * Source-scan rather than a rendered Compose test: this module's Robolectric harness (see
 * [FilterSheetContentTest]) exists, but pinning the actual pixel/line-count difference needs a real
 * font-scale render (`GraphicsMode.Mode.NATIVE`, as [VaultItemRowFontScaleBoundsTest] uses) that is
 * disproportionate for a one-line, `TrashItemRow`-mirrored fix — the code-level anchor is what the
 * fix changes. Bounded to `TrashFolderRow`'s own function body (brace-matched from its opening `{`),
 * not the whole file, because `TrashScreen.kt` has a fixed `maxLines = 1` on an unrelated row (the
 * attachment filename inside the file-preview dialog) that this finding does not touch.
 */
class TrashFolderRowScaledTitleTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/TrashScreen.kt")

    /** `TrashFolderRow`'s whole function body, braces balanced from its opening `{` to its closing one. */
    private fun trashFolderRowBody(): String {
        val text = source.readText()
        val signature = text.indexOf("internal fun TrashFolderRowBody(")
        assertTrue(signature >= 0, "TrashScreen.kt no longer declares TrashFolderRowBody (the title moved there from TrashFolderRow in wave 4) — update this test")
        val open = text.indexOf('{', signature)
        assertTrue(open >= 0, "could not find TrashFolderRow's opening brace")
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        fail("TrashFolderRow's closing brace was never found — update this test")
    }

    @Test
    fun `TrashFolderRow's title scales the same way TrashItemRow's does`() {
        val body = trashFolderRowBody()
        assertTrue(
            "lkScaledMaxLines()" in body,
            "TrashFolderRow no longer calls lkScaledMaxLines() — finding 21-4 mirrored " +
                "TrashItemRow's dynamic-type handling onto the folder row's title.",
        )
        assertFalse(
            "maxLines = 1," in body,
            "TrashFolderRow still has a fixed \"maxLines = 1,\" (the multi-line title call) — the " +
                "sibling mismatch finding 21-4 fixed is back.",
        )
    }

    /** The summary line wraps now (S-W4-F2): a fixed one-line cap clipped it to "1 folder," at 308dp / 2.0x. */
    @Test
    fun `the folder row's summary line is no longer capped to one line`() {
        val body = trashFolderRowBody()
        assertTrue(
            "Text(summary, style = LkText.subtitle, color = c.textSecondary)" in body,
            "TrashFolderRowBody's summary line changed shape — update this test's anchor",
        )
        assertFalse(
            "Text(summary, style = LkText.subtitle, color = c.textSecondary, maxLines = 1)" in body,
            "the summary line is capped to one line again — S-W4-F2's clipped \"1 folder,\" is back",
        )
    }
}
