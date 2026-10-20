package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every free-text well that carries a secret asks the IME for a password keyboard
 * (2026-09-20, release sweep 2B-2 and 4B-2).
 *
 * The bugs this closes, both measured with `dumpsys input_method` on a device:
 * - the secure-note body ran at `inputType=0x28001` — TEXT | AUTO_CORRECT, with neither
 *   `TYPE_TEXT_FLAG_NO_SUGGESTIONS` nor `IME_FLAG_NO_PERSONALIZED_LEARNING` — and the keyboard
 *   visibly rewrote typed words and learned the rest ("sed do" to "see do", "tempor" to
 *   "temporary"). `ReauthFields.kt` says a secure note IS the secret.
 * - the 2FA "Paste setup link" well ran at `inputType=0x8001`, on a URI whose `secret=` parameter
 *   is the whole second factor, and corrupted typed links (`?q=1` to `?I=1`). That well was removed
 *   on 2026-10-05, when 2FA became scan-only, and its test with it.
 *
 * `KeyboardType.Password` is the app's existing answer — `LkFormField` derives
 * `autoCorrectEnabled` from it and its KDoc names it as "what suppresses autocorrect and the IME's
 * learning of the value", and the recovery-phrase word field already relies on exactly that. It
 * changes no masking: that is `visualTransformation`, which these wells do not set.
 *
 * Source-pinned because `KeyboardOptions` reach the platform through `EditorInfo`, not through
 * Compose semantics, so no Robolectric assertion can see them; the module pins other keyboard
 * decisions the same way.
 */
class SecretFreeTextWellsSuppressImeLearningTest {

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
    private val wizard =
        File("src/main/kotlin/com/localkeep/feature/itemedit/CardWizard.kt").readText()

    private fun wellAfter(source: String, marker: String): String =
        source.substringAfter(marker, "").substringBefore(")\n")

    @Test
    fun `the secure note body`() {
        val well = wellAfter(screen, "onValueChanged = viewModel::onNoteBodyChanged,")
        assertTrue(well.isNotEmpty(), "the secure note's well moved; this test no longer reads it")
        assertTrue(
            "keyboardType = KeyboardType.Password" in well,
            "the note body is typed with autocorrect and personalised learning on",
        )
    }

    @Test
    fun `a login's notes well`() {
        val well = wellAfter(screen, "onValueChanged = viewModel::onLoginNotesChanged,")
        assertTrue(well.isNotEmpty(), "the login notes well moved; this test no longer reads it")
        assertTrue("keyboardType = KeyboardType.Password" in well)
    }

    @Test
    fun `both card notes wells, whichever route reaches them`() {
        val form = wellAfter(screen, "onValueChanged = viewModel::onCardNotesChanged,")
        val step = wellAfter(wizard, "onValueChanged = viewModel::onCardNotesChanged,")
        assertTrue(form.isNotEmpty() && step.isNotEmpty(), "a card notes well moved")
        assertTrue("keyboardType = KeyboardType.Password" in form, "the all-at-once card form")
        assertTrue("keyboardType = KeyboardType.Password" in step, "the card step wizard")
    }
}
