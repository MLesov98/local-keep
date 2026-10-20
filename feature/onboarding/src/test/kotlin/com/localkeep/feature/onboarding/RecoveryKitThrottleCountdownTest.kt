package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Recovery Kit gate's throttle must tell the user how long to wait, not just that the attempt
 * failed.
 *
 * `VaultLockManager.checkThrottle` throws `VaultLockedException(retryAfterMs)` on a throttled
 * attempt — the same exception `UnlockViewModel` and `SettingsViewModel` catch and report a
 * countdown for (`R.plurals.unlock_retry_after_seconds`, `R.plurals.settings_error_retry_after`).
 * `RecoveryKitViewModel.authorize` used to catch it and discard `retryAfterMs` entirely, mapping it
 * to the same flat `onboarding_recoverykit_gate_error_throttled` string every other failure got —
 * so a user locked out for five minutes and a user locked out for one second read the identical
 * "wait a moment and try again."
 *
 * Asserted against the source, the same technique `RecoveryKitAuthGateTest` and `RecoveryKitGateTest`
 * already use for this class: `RecoveryKitViewModel` needs a real `VaultLockManager` to construct,
 * so what is worth pinning here is structural — that the count survives the catch, reaches state
 * distinctly from the plain-string error path, and is rendered as a plural.
 */
class RecoveryKitThrottleCountdownTest {

    private fun viewModelSource(): String =
        File("src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitViewModel.kt").readText()

    private fun screenSource(): String =
        File("src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt").readText()

    private fun authorizeBody(): String =
        viewModelSource()
            .substringAfter("fun authorize(password: CharArray) {")
            .substringBefore("\n    /**\n     * A lock takes the gate")

    @Test
    fun `a fresh state carries no throttle countdown`() {
        assertFalse(
            RecoveryKitUiState().throttleSeconds != null,
            "the default state must not claim a throttle that was never hit",
        )
    }

    @Test
    fun `the throttle catch reports how long, not just that it happened`() {
        val body = authorizeBody()
        val throttleCatch = body
            .substringAfter("catch (throttled: VaultLockedException) {")
            .substringBefore("\n            }")
        assertTrue(
            "throttled.retryAfterMs" in throttleCatch,
            "authorize's VaultLockedException handler must read retryAfterMs — dropping it is the " +
                "exact defect this test guards. Catch body: $throttleCatch",
        )
        assertTrue(
            "throttleSeconds = " in throttleCatch,
            "the handler must assign the computed wait into throttleSeconds so it reaches state. " +
                "Catch body: $throttleCatch",
        )
        // Ceiling conversion, matching UnlockViewModel's `((locked.retryAfterMs + 999) / 1_000)` and
        // SettingsViewModel's `((cause.retryAfterMs + 999) / 1_000)` for the identical exception —
        // truncating instead would report "4 seconds" for the whole of a throttle that in fact
        // blocks the next attempt for a little over 4, which is the wrong direction to be wrong in.
        assertTrue(
            "(throttled.retryAfterMs + 999) / 1_000" in throttleCatch,
            "the ms-to-seconds conversion must round up, the same way the unlock and settings " +
                "throttle messages already do. Catch body: $throttleCatch",
        )
    }

    @Test
    fun `a throttled attempt does not also report the flat string error`() {
        val throttleCatch = authorizeBody()
            .substringAfter("catch (throttled: VaultLockedException) {")
            .substringBefore("\n            }")
        assertFalse(
            "onboarding_recoverykit_gate_error_throttled" in throttleCatch,
            "the flat string resource must not be assigned from the throttle branch any more — " +
                "the plural, count-bearing message replaces it. Catch body: $throttleCatch",
        )
        assertFalse(
            "error = " in throttleCatch,
            "the throttle branch must set throttleSeconds, not error — RecoveryKitUiState treats " +
                "the two as mutually exclusive outcomes of one attempt. Catch body: $throttleCatch",
        )
    }

    @Test
    fun `the final state update actually carries throttleSeconds forward`() {
        val body = authorizeBody()
        val finalUpdate = body.substringAfterLast("_state.update {")
        assertTrue(
            "throttleSeconds = throttleSeconds" in finalUpdate,
            "computing the count is not enough if the closing state update never assigns it. " +
                "Final update: $finalUpdate",
        )
    }

    @Test
    fun `the screen resolves the countdown as a plural, with the count as an argument`() {
        val screen = screenSource()
        assertTrue(
            "state.throttleSeconds?.let" in screen,
            "RecoveryKitScreen must branch on throttleSeconds to show the countdown at all.",
        )
        val block = screen.substringAfter("state.throttleSeconds?.let { seconds ->")
            .substringBefore("LkInlineError(message")
        assertTrue(
            "pluralStringResource(" in block,
            "a count-bearing message must resolve with pluralStringResource, never stringResource " +
                "— a plural resolved as singular either throws or silently picks the wrong form. " +
                "Block: $block",
        )
        assertTrue(
            Regex("""pluralStringResource\(\s*R\.plurals\.onboarding_recoverykit_gate_error_throttled,\s*seconds,\s*seconds,?\s*\)""")
                .containsMatchIn(block),
            "pluralStringResource needs the count twice — once to pick the plural form, once as " +
                "the %1\$d format argument. Block: $block",
        )
    }
}
