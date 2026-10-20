package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Neither `RenameFolderDialog` nor `BulkTagDialog` may open its `LkDialog` content with a bare
 * `Spacer(Modifier.height(4.dp))`.
 *
 * Found 2026-09-13: `LkDialog`'s `content` lambda is typed
 * `@Composable ColumnScope.() -> Unit` and runs directly inside `LkDialogSurface`'s own
 * `Arrangement.spacedBy(18.dp)` Column (`LkModals.kt:132-140,258`) — it is not a scope of its own. A
 * `Spacer(Modifier.height(4.dp))` opening that lambda is therefore a *sibling* of `LkModalMasthead`
 * in that Column, not nested inside anything narrower, so the arrangement puts its own 18dp gap on
 * both sides of it: 18 + 4 + 18 = 40dp between the dialog's masthead and its text field instead of
 * the 4dp the Spacer's author evidently intended. Both call sites in this file had it.
 * This is a known class of bug ("a Spacer inside a spacedBy Column is spaced too").
 *
 * Source-scan, bounded to each function's own body (brace-matched from its opening `{`) rather than
 * a rendered pixel measurement: a plain whole-file substring search for
 * `Spacer(Modifier.height(4.dp))` would also be satisfied by either function being deleted outright,
 * which is not the fix — the found-anchor and control assertions below guard against exactly that.
 */
class DialogContentSpacerTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")

    private fun functionBody(signatureAnchor: String): String {
        val text = source.readText()
        val signature = text.indexOf(signatureAnchor)
        assertTrue(signature >= 0, "VaultListScreen.kt no longer contains \"$signatureAnchor\" — update this test")
        val open = text.indexOf('{', signature)
        assertTrue(open >= 0, "could not find the opening brace after \"$signatureAnchor\"")
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
        fail("closing brace for \"$signatureAnchor\" was never found — update this test")
    }

    @Test
    fun `RenameFolderDialog does not open its content with a bare 4dp Spacer`() {
        val body = functionBody("internal fun RenameFolderDialog(")
        assertFalse(
            "Spacer(Modifier.height(4.dp))" in body,
            "RenameFolderDialog opens its LkDialog content with Spacer(Modifier.height(4.dp)) again " +
                "— that Spacer is a direct sibling of LkModalMasthead inside LkDialogSurface's own " +
                "spacedBy(18.dp) Column, so it renders as an 18+4+18=40dp gap, not 4dp (finding 22-1).",
        )
        // Control: the fix must not have deleted the field itself along with the Spacer.
        assertTrue("LkTextField(" in body, "RenameFolderDialog no longer renders its text field")
    }

    @Test
    fun `BulkTagDialog does not open its content with a bare 4dp Spacer`() {
        val body = functionBody("private fun BulkTagDialog(")
        assertFalse(
            "Spacer(Modifier.height(4.dp))" in body,
            "BulkTagDialog opens its LkDialog content with Spacer(Modifier.height(4.dp)) again — " +
                "same double-gap bug as RenameFolderDialog (finding 22-1).",
        )
        assertTrue("LkTextField(" in body, "BulkTagDialog no longer renders its text field")
    }
}
