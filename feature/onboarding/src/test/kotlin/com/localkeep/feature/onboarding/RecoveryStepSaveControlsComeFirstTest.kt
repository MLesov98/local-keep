package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * On the onboarding Recovery-Kit step, the ways to keep the kit render **above** the 24-word
 * grid. (Three of them until 2026-10-13, when Print was dropped from this step; the history below
 * is written for that layout.)
 *
 * The grid used to come first, and it is twenty-four rows tall. On a 1080x2400 phone the fold
 * therefore landed inside `Save as PDF`, which was drawn as a half-height sliver against the pinned
 * Continue strip with `lkScrollEdges`' fade across it, while `Print` and `Show QR code instead`
 * were below the fold entirely (`shots/195-onboarding-3.png`, 2026-09-15; after the QR round trip
 * the same happened to `Show QR code instead`, `shots/201`). Nothing was unreachable — a scroll
 * brought all three up, `shots/196` — but the frame the step opens on is the one the user reads,
 * and it showed a butchered control where the step's entire purpose is. Principal's call,
 * 2026-09-16: the controls move up, the grid scrolls below them.
 *
 * The footnote is pinned to the buttons by the same assertion, because it is the caveat on pressing
 * them — "some print or share targets upload to the cloud" — and a caveat that outruns its control
 * is not one.
 *
 * **Why this reads the source.** `RecoveryStep` is `private`, takes an `OnboardingViewModel` (Hilt,
 * over a `VaultLockManager` and a `KeystoreManager`), and launches a SAF document-creation
 * contract; none of that exists off a device. What is being pinned is not a decision the step makes
 * but the order in which it emits, and order is exactly what source states. Same trade — and the
 * same plain statement of its limits — as `:core:vault`'s `UnlockDispatcherTest`: this proves the
 * emission order, not the rendered geometry, which only a device pass can.
 */
class RecoveryStepSaveControlsComeFirstTest {

    private val source = java.io.File("src/main/kotlin/com/localkeep/feature/onboarding/OnboardingScreen.kt")

    /** The body of `private fun RecoveryStep`, up to the next top-level declaration. */
    private fun recoveryStepBody(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val text = source.readText()
        val start = text.indexOf("private fun RecoveryStep(")
        assertTrue(start >= 0, "OnboardingScreen.kt no longer declares RecoveryStep(")
        val end = text.indexOf("\n@Composable", start + 1).takeIf { it > start } ?: text.length
        return text.substring(start, end)
    }

    /**
     * Matched on the *string resource* each control resolves, not on a composable name: the
     * save buttons are the same `LkSecondaryButton`, so nothing else in the file distinguishes them.
     */
    private fun String.orderOf(vararg needles: String): List<Int> = needles.map { needle ->
        indexOf(needle).also { assertTrue(it >= 0, "RecoveryStep no longer contains $needle") }
    }

    @Test
    fun `the save controls and their footnote render before the word grid`() {
        val body = recoveryStepBody()
        val (pdf, qr, footnote, grid) = body.orderOf(
            "R.string.onboarding_recovery_save_pdf",
            "R.string.onboarding_recovery_show_qr",
            "R.string.onboarding_recovery_footnote",
            "MnemonicGrid(",
        )

        assertTrue(pdf < qr, "Save as PDF must come before Show QR code instead")
        assertTrue(
            qr < footnote,
            "the print/share caveat must follow the buttons it is a caveat on, not lead them",
        )
        assertTrue(
            footnote < grid,
            "both ways to keep the kit, and their caveat, must render above the 24-word grid " +
                "— below it the fold falls inside them and the step opens on a half-drawn control " +
                "(F-49). Offsets: pdf=$pdf qr=$qr footnote=$footnote grid=$grid",
        )
    }

    /**
     * The control. Every notice and error this step raises is the *result* of pressing one of those
     * controls, so they belong beside them; behind the grid they would be a screen's worth of
     * scrolling from their own cause. Fails if the reorder above is achieved by leaving them behind.
     */
    @Test
    fun `the step's notice and error stay with the controls that cause them`() {
        val body = recoveryStepBody()
        val (notice, error, grid) = body.orderOf("NoticeText(", "ErrorText(", "MnemonicGrid(")

        assertTrue(notice < grid, "the notice must not sit below the 24-word grid")
        assertTrue(error < grid, "nor the error")
    }

    /** The second control: the grid is still rendered at all. */
    @Test
    fun `the word grid is still part of the step`() {
        assertTrue(
            recoveryStepBody().contains("MnemonicGrid(state.recoveryMnemonic)"),
            "the 24 words must still be shown — moving the buttons up is a reorder, not a removal",
        )
    }
}
