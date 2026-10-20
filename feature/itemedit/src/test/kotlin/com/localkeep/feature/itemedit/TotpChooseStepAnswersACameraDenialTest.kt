package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Denying the camera on the 2FA wizard's first step says so, there (2026-09-20, sweep 4B-1).
 *
 * The bug this closes: `TotpChooseStep` rendered two buttons and nothing else, so after a denial
 * the step-1 tree was identical to before it — no notice, no snackbar — and the primary button
 * just stopped working. The denial state was set and correct the whole time; it surfaced one step
 * later, on the manual form, which is the one screen the user had chosen not to go to.
 * `rememberTotpScanTrigger`'s own KDoc says the denial branch exists because "tapping the button,
 * reading the disclosure and then denying produced a screen that did nothing and said nothing".
 *
 * The chooser became a single scan step on 2026-10-05 (`TotpScanStep`, 2FA is scan-only); the
 * denial slot moved with it. On 2026-10-14 the step became a viewfinder hero with the scan button in the
 * pinned action slot; on 2026-10-15 the viewfinder went, and the button is back in the step, so the
 * denial notice sits directly under the button it answers.
 *
 * Source-pinned: the wiring is Compose and the trigger needs an Activity-scoped permission
 * launcher, so what is asserted is that step 1 receives the whole trigger and renders its denial.
 */
class TotpChooseStepAnswersACameraDenialTest {

    private val screen =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()

    private val chooseStep = screen
        .substringAfter("private fun TotpScanStep(", "")
        .substringBefore("\n@Composable")

    @Test
    fun `step one renders the denial notice`() {
        assertTrue(chooseStep.isNotEmpty(), "TotpScanStep moved; this test no longer reads it")
        assertTrue(
            "scanTrigger.cameraDenial?.let" in chooseStep,
            "step 1 still has no slot for the camera denial",
        )
        assertTrue("LkInlineError(" in chooseStep, "the denial is held but never drawn")
    }

    /**
     * With the way out. A notice that names the problem and not the fix leaves the user on a dead
     * screen — Android will not ask again once it has been denied twice.
     */
    @Test
    fun `the notice carries the settings action`() {
        assertTrue("actionLabel = denial.settingsActionLabel" in chooseStep)
        assertTrue("context.openAppSettings()" in chooseStep)
    }

    /**
     * And it is announced, not only drawn. Colour and position say nothing to a screen reader, and
     * the button the user just pressed has silently stopped responding.
     */
    @Test
    fun `the notice announces itself`() {
        assertTrue("Modifier.announce(denial.message)" in chooseStep)
    }

    /**
     * The plumbing that makes it possible: the step is handed the whole trigger rather than its
     * `openScanner` lambda.
     */
    @Test
    fun `the step receives the trigger, not just a way to open the scanner`() {
        val fields = screen.substringAfter("private fun TotpFields(", "")
            .substringBefore("\n@Composable")
        assertTrue("scanTrigger: TotpScanTrigger" in fields)
        assertTrue("TotpScanStep(scanTrigger)" in fields)
    }
}
