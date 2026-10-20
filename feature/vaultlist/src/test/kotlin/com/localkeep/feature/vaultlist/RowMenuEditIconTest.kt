package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The row overflow's "Edit" entry may not wear the drawer's Settings glyph.
 *
 * F-14 (visual pass 2026-09-15): a crop compare of the row menu against the command drawer found a
 * pixel-identical two-slider mark on "Edit this item" and on "App settings". The word was corrected
 * on 2026-09-11 — the entry used to read "Settings" — and the glyph was left behind, so the picture
 * went on making the claim the label had stopped making.
 *
 * Source-scan, bounded to the `DropdownMenuItem` that carries `settingsActionLabel`, so the
 * assertion cannot pass because some other row in the same menu happens to use a pencil.
 */
class RowMenuEditIconTest {

    private val rowSource = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultItemRow.kt")

    /** The `leadingIcon` block of the menu entry whose text is [settingsActionLabel]. */
    private fun editEntryIcon(): String {
        val text = rowSource.readText()
        val entry = text.indexOf("text = { Text(settingsActionLabel")
        assertTrue(entry >= 0, "VaultItemRow.kt no longer has a menu entry rendering settingsActionLabel")
        val icon = text.indexOf("leadingIcon", entry)
        assertTrue(icon >= 0, "the Edit menu entry no longer declares a leadingIcon — update this test")
        // The `Icon(` call that follows, up to its first argument's end of line.
        val call = text.indexOf("Icon(", icon)
        val lineEnd = text.indexOf('\n', text.indexOf('\n', call) + 1)
        return text.substring(call, lineEnd)
    }

    @Test
    fun `the Edit entry draws a pencil, not the app-settings sliders`() {
        val icon = editEntryIcon()
        assertTrue(
            "LkIcons.Edit" in icon,
            "the row menu's Edit entry draws \"$icon\" — it must be LkIcons.Edit (F-14)",
        )
        assertFalse(
            "LkIcons.Settings" in icon,
            "the row menu's Edit entry is back on LkIcons.Settings, which the drawer uses for app " +
                "settings — one glyph cannot mean both this record and the whole app (F-14)",
        )
    }

    /** Control: the glyph the Edit entry must stay away from is still the one the drawer uses. */
    @Test
    fun `the drawer still uses the sliders for app settings`() {
        val shell = File("../../app/src/main/kotlin/com/localkeep/app/navigation/AppShell.kt")
        assertTrue(shell.exists(), "AppShell.kt not found at ${shell.absolutePath} — update this test")
        assertTrue(
            "LkIcons.Settings" in shell.readText(),
            "the drawer no longer draws app settings with LkIcons.Settings — this test's premise is stale",
        )
    }
}
