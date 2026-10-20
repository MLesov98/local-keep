package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Recovery Kit word field must use the password keyboard variation, not merely
 * `autoCorrectEnabled = false`. Measured 2026-09-14 on an API 28 emulator with Gboard: with only
 * the autocorrect flag cleared, "excite" (BIP-39 word 9 of that kit) was retyped as "excited" on
 * the following space, and the vault refused the kit with no way for the user to tell which word
 * had been changed. Compose maps `autoCorrectEnabled = false` to the absence of
 * `TYPE_TEXT_FLAG_AUTO_CORRECT`, a hint Gboard ignores; `TYPE_TEXT_VARIATION_PASSWORD` is the flag
 * every IME honours, and it also stops the keyboard learning the phrase. The words stay visible —
 * the transformation is pinned to None alongside it.
 *
 * Source-pinned rather than driven, because the keyboard's behaviour cannot be observed from a JVM
 * test; the observation that motivated this lives in the device pass of 2026-09-14.
 */
class RecoveryWordsKeyboardTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/unlock/UnlockScreen.kt").readText()

    @Test
    fun theRecoveryWordsFieldUsesThePasswordKeyboardVariationAndStaysVisible() {
        val field = screen.substringAfter("R.string.unlock_recovery_words_label")
            .substringBefore("val words = state.recoveryWords")
        assertTrue("keyboardType = KeyboardType.Password" in field, "recovery words field lost the password keyboard variation")
        assertTrue("autoCorrectEnabled = false" in field)
        assertTrue("visualTransformation = VisualTransformation.None" in field, "the words must stay visible")
    }

    @Test
    fun theSettingsRecoveryWordsFieldMatches() {
        val settings = File("../settings/src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt").readText()
        val field = settings.substringAfter("R.string.settings_password_kit_placeholder").substringBefore("PasswordField(currentPassword")
        assertTrue("keyboardType = KeyboardType.Password" in field, "Settings' recovery words field lost the password keyboard variation")
        assertTrue("visualTransformation = VisualTransformation.None" in field)
    }
}
