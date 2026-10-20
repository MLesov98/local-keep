package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for 4-2 (`verdicts/4-2.json`, CONFIRMED).
 *
 * The Done step's autofill/passkey buttons (`openAutofillSettings`, `openCredentialProviderSettings`)
 * send the user to Android's Settings with a plain `startActivity`. `ProcessLifecycleOwner` reports
 * LocalKeep as backgrounded for that whole trip, and until this fix nothing told
 * `VaultLockManager` this was an in-app excursion rather than the user leaving — so a trip that
 * outlasted the Aggressive/Balanced background grace relocked the vault, which pops the whole nav
 * graph including ONBOARDING and silently discards the Hilt-scoped `OnboardingViewModel`'s
 * `migrateRequested` choice with no notice.
 *
 * `openSettings` now begins the excursion (`VaultLockManager.beginExternalPicker`) before
 * `startActivity`, and a `ProcessLifecycleOwner` observer registered in `init` ends it
 * (`endExternalPicker`) on the next `onStart` — there is no activity-result callback for a plain
 * Settings deep link, so `onStart` stands in for it.
 *
 * Structural rather than behavioural, matching this file's own precedent
 * (`RecoveryKitGateTest`'s ordering assertions): `OnboardingViewModel` is not instantiated by any
 * test in this module, and the property worth pinning here — that the begin/end calls bracket the
 * excursion rather than being placed so they cancel out on the same line — is exactly what a source
 * read states.
 */
class SettingsExcursionPairedTest {

    private val source = File(
        "src/main/kotlin/com/localkeep/feature/onboarding/OnboardingViewModel.kt",
    ).readText()

    /**
     * Production lines under test: `lockManager.beginExternalPicker()` and
     * `pendingSettingsReturns++` at the top of `openSettings`, before `startActivity`. Delete
     * either and this test goes red.
     */
    @Test
    fun `openSettings begins the excursion before starting the activity`() {
        val body = source.substringAfter("private fun openSettings(intent: Intent) {")
            .substringBefore("\n    }")

        val beginIndex = body.indexOf("lockManager.beginExternalPicker()")
        val flagSetIndex = body.indexOf("pendingSettingsReturns++")
        val startActivityIndex = body.indexOf("context.startActivity(")

        assertTrue(beginIndex >= 0, "openSettings must call lockManager.beginExternalPicker()")
        assertTrue(flagSetIndex >= 0, "openSettings must record that an excursion is pending")
        assertTrue(startActivityIndex >= 0, "openSettings must still start the settings activity")
        assertTrue(
            beginIndex < startActivityIndex && flagSetIndex < startActivityIndex,
            "the excursion must be declared to the lock manager BEFORE startActivity, or the " +
                "background grace timer can already be running by the time it is recorded",
        )
    }

    /**
     * Production line under test: the `catch` block's `lockManager.endExternalPicker()` (with
     * `pendingSettingsReturns--`) in `openSettings`. Delete it and this test goes red — a
     * device with no Settings activity to handle the intent would leave the excursion counter
     * incremented for the rest of the process, silently weakening every future background lock to
     * the idle timer.
     */
    @Test
    fun `openSettings balances the excursion when startActivity throws`() {
        val body = source.substringAfter("private fun openSettings(intent: Intent) {")
            .substringBefore("\n    }")
        val catchBlock = body.substringAfter("catch (_: Exception) {").substringBefore("}")

        assertTrue(
            "lockManager.endExternalPicker()" in catchBlock,
            "a startActivity failure must balance the excursion it began, or the counter leaks",
        )
        assertTrue(
            "pendingSettingsReturns--" in catchBlock,
            "the pending-return count must also be decremented on the same failure path",
        )
    }

    /**
     * Production lines under test: `settingsExcursionObserver`'s `onStart`, which is the only place
     * `endExternalPicker` is called for the ordinary (successful) trip. Delete the call and this
     * test goes red.
     */
    @Test
    fun `the process-foreground observer ends a pending excursion`() {
        val observerBody = source.substringAfter("settingsExcursionObserver = object : DefaultLifecycleObserver {")
            .substringBefore("\n    }\n")

        assertTrue(
            "override fun onStart" in observerBody,
            "the excursion must be closed on the process's own onStart — there is no " +
                "activity-result callback for a plain Settings startActivity",
        )
        assertTrue(
            "endPendingSettingsExcursions()" in observerBody,
            "onStart must actually end the excursions beginExternalPicker opened",
        )
    }

    /**
     * Regression test for 22-1 (`verdicts/22-1.json`, CONFIRMED medium).
     *
     * The 4-2 fix above tracked the excursion with a single `Boolean`, but the counter it drives —
     * `VaultLockManager.externalPickerDepth` — nests, and neither Done-step button is debounced
     * (`OnboardingScreen.kt` passes no `enabled` to either, and `LkButtons.kt` has no throttle).
     * Two taps before the Settings activity covers the screen therefore called
     * `beginExternalPicker()` twice while recording one pending return, and the single `onStart`
     * that followed ended one of them. `externalPickerDepth` is process-wide, `@Singleton`, and
     * reset by nothing — not even `lock()` — so the leftover pinned
     * `useGrace = inBackground && externalPickerDepth == 0` false for the life of the process,
     * silently giving every later backgrounding the 30s/120s/300s idle timeout in place of the
     * 5s/30s/60s background grace the user's auto-lock preset promises.
     *
     * Production lines under test: `pendingSettingsReturns` being an `Int` incremented once per
     * launch, and `endPendingSettingsExcursions`'s `while` loop draining it one
     * `endExternalPicker()` per `beginExternalPicker()`. Turn either the field back into a
     * `Boolean` or the `while` into an `if` and this test goes red.
     *
     * Control assertion: `endPendingSettingsExcursions` must not assign `externalPickerDepth`
     * directly. That held before this fix and must keep holding — the counter is shared with the
     * file pickers, the print dialog and the billing flow, so forcing it to zero would relock a
     * user mid-picker. Adversarial check that the loop is not vacuous: the drain is guarded on
     * `> 0`, so it is a no-op for an ordinary resume with nothing pending, exactly as the boolean
     * guard it replaces was.
     */
    @Test
    fun `outstanding settings excursions are counted, not collapsed into one flag`() {
        assertTrue(
            "private var pendingSettingsReturns = 0" in source,
            "outstanding excursions must be counted — a Boolean records at most one, so a second " +
                "openSettings() before the app foregrounds leaks externalPickerDepth permanently",
        )
        assertTrue(
            "awaitingSettingsReturn" !in source,
            "the single-excursion Boolean must be gone, not merely shadowed by the counter",
        )

        val openSettingsBody = source.substringAfter("private fun openSettings(intent: Intent) {")
            .substringBefore("\n    }")
        assertTrue(
            openSettingsBody.split("lockManager.beginExternalPicker()").size - 1 ==
                openSettingsBody.split("pendingSettingsReturns++").size - 1,
            "every beginExternalPicker() in openSettings must be counted exactly once",
        )

        val drainBody = source.substringAfter("private fun endPendingSettingsExcursions() {")
            .substringBefore("\n    }")
        assertTrue(
            "while (pendingSettingsReturns > 0)" in drainBody,
            "the drain must end EVERY outstanding excursion — the process foregrounds once no " +
                "matter how many Settings launches stacked up behind it, so an `if` leaks the rest",
        )
        assertTrue(
            "lockManager.endExternalPicker()" in drainBody,
            "the drain must balance each counted excursion with its own endExternalPicker()",
        )
        assertTrue(
            "externalPickerDepth" !in drainBody,
            "the drain must never assign the shared depth counter — resetting it would cancel " +
                "another component's genuinely open excursion and relock a user mid-file-picker",
        )
    }

    /**
     * `onCleared` is the safety net for the case the fix exists to make rare but cannot make
     * impossible: a lock that pops this ViewModel while an excursion is still open. Without this,
     * that path leaks the counter exactly like an unhandled startActivity failure would.
     */
    @Test
    fun `onCleared balances a still-pending excursion`() {
        val clearedBody = source.substringAfter("override fun onCleared() {").substringBefore("\n    }")

        assertTrue(
            "removeObserver(settingsExcursionObserver)" in clearedBody,
            "onCleared must unregister the process observer, or a destroyed ViewModel instance " +
                "keeps reacting to app resumes",
        )
        assertTrue(
            "endPendingSettingsExcursions()" in clearedBody,
            "onCleared must balance every still-open excursion the same way the failure path does",
        )
    }
}
