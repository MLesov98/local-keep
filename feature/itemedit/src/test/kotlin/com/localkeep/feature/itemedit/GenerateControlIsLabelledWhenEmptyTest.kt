package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * On an empty password field, the way to make a password says so in words.
 *
 * `shots/053-login-editor.png`, 2026-09-15: a bare 48dp die at `[886,859][1012,985]`, alone in the
 * row with the strength meter absent, ~100px of empty space above and below it and no label — on
 * the screen where a new user is most likely to want a generated password (F-16). A glyph is legible
 * as a *repeat* of something you have watched happen; it is not legible as an offer.
 *
 * The quiet bare die stays for the case it was argued for (2026-09-09 UI gate): once the field holds
 * a password, generating destroys it, and a destructive trigger drawn loud is an invitation.
 * Generating into an empty field destroys nothing — `generatePassword` arms no undo when there is
 * nothing to lose — so the two states get the two treatments.
 *
 * FAILS WITHOUT THE FIX: the row emitted one `LkGlyphButton` whatever the field held.
 */
class GenerateControlIsLabelledWhenEmptyTest {

    private val row = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        .readText()
        .substringAfter("private fun PasswordStrengthRow(", "")
        .substringBefore("private fun LoginTotpSection(")

    @Test
    fun `an empty field offers a labelled control, not a bare glyph`() {
        val empty = row.substringAfter("if (password.isEmpty()) {\n                // Empty field", "")
            .substringBefore("} else {")
        assertTrue("LkInlineAddButton(" in empty, "the empty state is back to an unlabelled glyph")
        assertTrue(
            "text = stringResource(R.string.itemedit_generate_password_a11y)" in empty,
            "the control no longer says what it does",
        )
        assertTrue("icon = LkIcons.Dice" in empty, "the die is the glyph for rolling a value")
    }

    @Test
    fun `a filled field keeps the quiet glyph, because generating over a password destroys it`() {
        val filled = row.substringAfter("} else {\n                LkGlyphButton(", "")
        assertTrue("icon = LkIcons.Dice" in filled)
        assertTrue("tint = c.textSecondary" in filled, "the destructive trigger was drawn loud")
        assertTrue("container = Color.Transparent" in filled)
    }
}
