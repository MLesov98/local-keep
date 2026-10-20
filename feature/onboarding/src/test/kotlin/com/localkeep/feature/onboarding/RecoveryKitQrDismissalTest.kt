package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The QR card can be closed without paying for it.
 *
 * On the standalone Recovery Kit screen the card had no exit that was not one of two bad ones. "Show
 * QR code instead" is disabled while `qrVisible` is true, the screen registered no `BackHandler`,
 * and the only controls left were "I captured the QR code" — which runs `markSaved` and revokes the
 * kit already on paper — and leaving the screen, which disposes the composable, runs `forget()`, and
 * takes the freshly generated 24 words with it. So a user who opened the QR merely to look at it
 * either committed a kit they had not saved (the outcome `prepareRecoveryKit` was split from
 * `commitPreparedRecoveryKit` to prevent) or lost a transcription already half written, because
 * `RecoveryKit.generate` draws fresh entropy on the next `createKit`.
 *
 * The onboarding twin has always had the escape: `OnboardingViewModel.back` intercepts the gesture
 * with "The QR is drawn inline on the Recovery Kit step and has no other dismissal, so back closes
 * it before it moves anywhere." The standalone screen never got it. Found 2026-09-06.
 *
 * Asserted on the source for the wiring facts a JVM test cannot otherwise reach — `BackHandler` is a
 * composable side effect and `RecoveryKitViewModel` needs a real `VaultLockManager` and a `Context`
 * to construct. Same technique, and the same reason, as `RecoveryKitAuthGateTest`.
 */
class RecoveryKitQrDismissalTest {

    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt").readText()

    private val viewModel: String =
        File("src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitViewModel.kt").readText()

    @Test
    fun `back closes the QR card`() {
        assertTrue(
            "BackHandler(enabled = state.qrVisible) { viewModel.hideQr() }" in screen,
            "The QR card must have a dismissal that neither commits the kit nor leaves the screen. " +
                "Enabled on qrVisible alone, so back still leaves the screen when no card is up.",
        )
    }

    @Test
    fun `dismissing the QR commits nothing`() {
        val body = viewModel.substringAfter("fun hideQr() {").substringBefore("}")
        assertTrue(
            "qrVisible = false" in body,
            "hideQr must clear qrVisible. Found: ${body.trim()}",
        )
        listOf("markSaved", "commitPreparedRecoveryKit", "setRecoveryKitSaved", "exported").forEach {
            assertFalse(
                it in body,
                "Hiding the card is a statement about what is on screen, not about whether a kit " +
                    "was saved — closing it must never revoke the kit on paper. Found `$it` in: " +
                    body.trim(),
            )
        }
    }
}
