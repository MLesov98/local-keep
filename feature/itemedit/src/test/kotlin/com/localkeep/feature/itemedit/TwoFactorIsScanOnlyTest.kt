package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A second factor arrives by scanning a QR code, on the 2FA item and on a Login, and a Login's
 * two-factor and notes live behind "Folder, tags and more" rather than open in the form.
 *
 * Asked for on 2026-10-05. The 2FA item opened on a two-step chooser — "Scan the site's QR code" or
 * "Enter the details myself" — and a Login showed a Two-factor heading with the same two buttons
 * between its websites and its notes, which read as a step of saving a password.
 *
 * Source-pinned: what is asserted is which controls each screen draws, and where.
 */
class TwoFactorIsScanOnlyTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()

    @Test
    fun `a new 2FA item is a scan screen until a code is in`() {
        val totpFields = screen.substringAfter("private fun TotpFields(", "").substringBefore("\n}\n")
        assertTrue("if (state.isNew && state.totpSecret.isBlank()) {" in totpFields)
        assertTrue("TotpScanStep(scanTrigger)" in totpFields)
    }

    /**
     * Before the scan the pinned slot is EMPTY (2026-10-15). The Scan button is in the form, centred
     * under its hint; the pinned-Scan variant of 2026-10-14 is gone, and so is the dead Save.
     */
    @Test
    fun `before the scan the pinned slot is empty`() {
        val action = screen.substringAfter("val itemEditActions: @Composable ColumnScope.() -> Unit = {", "").substringBefore("offersScan")
        assertTrue(action.isNotEmpty(), "the action slot moved; this test no longer reads it")
        assertTrue(
            "val awaitingScan = state.type == ItemType.TOTP && state.isNew && state.totpSecret.isBlank()" in screen,
            "the pre-scan condition changed",
        )
        val pre = action.substringAfter("if (awaitingScan) {", "").substringBefore("} else if (locationCapture")
        assertTrue(pre.isNotEmpty(), "the awaitingScan branch of the action slot moved")
        val code = pre.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n").trim()
        assertEquals("", code, "the pre-scan action slot draws something again: $code")
        assertFalse(
            "state.totpSecret.isBlank())," in action,
            "Save is disabled for a blank secret again - the dead-Save workaround is back",
        )
    }

    @Test
    fun `the scan group is a hint then the Scan button, centred, with no viewfinder`() {
        val step = screen.substringAfter("private fun TotpScanStep(", "").substringBefore("\n@Composable")
        assertTrue(step.isNotEmpty(), "TotpScanStep moved; this test no longer reads it")
        assertTrue("LkPrimaryButton(" in step, "the Scan button left the pre-scan group")
        assertTrue("lkTag(\"totp-scan\")" in step, "the Scan button lost its test tag")
        assertTrue("onClick = scanTrigger.openScanner" in step, "the Scan button does not open the scanner")
        assertTrue("R.string.itemedit_totp_scan_button" in step)
        assertTrue("Alignment.CenterVertically" in step, "the group is no longer vertically centred")
        assertTrue("LkSpace.md" in step, "the hint-to-button gap is not an LkSpace token")
        val hint = step.indexOf("R.string.itemedit_totp_scan_hint")
        val button = step.indexOf("LkPrimaryButton(")
        val denial = step.indexOf("scanTrigger.cameraDenial")
        assertTrue(hint in 0 until button && button < denial, "the order is not hint, button, denial notice")
        assertTrue("<string name=\"itemedit_totp_scan_hint\">" in strings)
    }

    /** The brackets looked like a camera already scanning (2026-10-15). */
    @Test
    fun `no viewfinder or Canvas remains in the pre-scan step`() {
        val step = screen.substringAfter("private fun TotpScanStep(", "").substringBefore("\n@Composable")
        for (gone in listOf("Canvas(", "drawLine", "VIEWFINDER", "totp-viewfinder", "StrokeCap", ".clickable(")) {
            assertFalse(gone in step, "`$gone` is back in the pre-scan step")
        }
        for (gone in listOf("VIEWFINDER_", "totp-viewfinder", "import androidx.compose.foundation.Canvas")) {
            assertFalse(gone in screen, "`$gone` is back in ItemEditScreen")
        }
    }

    @Test
    fun `filing waits for the scan`() {
        assertTrue("if (!awaitingScan) MoreSection(" in screen, "Folder, tags and more is shown before the scan")
    }

    /** An action-column copy of Scan would be a second control for the same tap. */
    @Test
    fun `the action column offers a second scan only once a code is in`() {
        assertTrue("ItemType.TOTP -> !awaitingScan" in screen)
    }

    @Test
    fun `there is no manual way in`() {
        assertFalse("itemedit_totp_manual_entry_button" in screen, "the manual 2FA entry is back")
        assertFalse("itemedit_totp_manual_entry_button" in strings)
        assertFalse("onPastedOtpAuthUriChanged" in screen, "the paste-a-link well is back")
        assertFalse("TotpEntryStep" in screen, "the two-step chooser is back")
    }

    @Test
    fun `the scan button says Scan the QR code`() {
        assertTrue("<string name=\"itemedit_totp_scan_button\">Scan the QR code</string>" in strings)
    }

    @Test
    fun `a Login's form holds no two-factor or notes`() {
        val loginFields = screen.substringAfter("private fun LoginFields(", "").substringBefore("\n}\n")
        assertFalse("itemedit_login_totp_heading" in loginFields, "Two-factor is open in the Login form again")
        assertFalse("onLoginNotesChanged" in loginFields, "Notes are open in the Login form again")
    }

    @Test
    fun `they are inside the disclosure, and its summary names them`() {
        val call = screen.substringAfter("MoreSection(\n", "").substringBefore("\n                )\n")
        assertTrue("LoginExtras(state, viewModel, totpScanTrigger)" in call)
        val extras = screen.substringAfter("private fun LoginExtras(", "").substringBefore("\n}\n")
        assertTrue("TotpScanControls(scanTrigger)" in extras)
        assertTrue("onLoginNotesChanged" in extras)
        val more = screen.substringAfter("private fun MoreSection(", "").substringBefore("val label =")
        assertTrue("R.string.itemedit_edit_more_summary_totp" in more)
        assertTrue("R.string.itemedit_edit_more_summary_notes" in more)
    }
}
