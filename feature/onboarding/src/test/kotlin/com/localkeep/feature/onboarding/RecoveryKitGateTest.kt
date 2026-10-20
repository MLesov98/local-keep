package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Recovery Kit step may and may not do.
 *
 * The history matters, because this file has now been on both sides of it. The step once accepted a
 * ticked "I'll do this later" consent row that was OR-ed into the same readiness flag the three save
 * buttons set, so onboarding could finish with no kit while the app believed one existed. That was
 * removed and the step became a wall — which was also wrong, and is the
 * defect these tests were rewritten for on 2026-08-10: writing down 24 words is the heaviest thing
 * the app asks of anyone, it is asked in the first two minutes before the vault holds anything worth
 * recovering, and blocking there produces abandoned installs rather than saved kits.
 *
 * So the step yields, and the invariant moves. It is no longer "there is no way past this screen".
 * It is: **nothing but a real export may ever be recorded as a saved kit.** A user may leave without
 * one; the app may not forget that they did.
 */
class RecoveryKitGateTest {

    @Test
    fun `an un-exported kit does not open Continue`() {
        assertFalse(OnboardingUiState(recoveryExported = false).recoveryStepReady)
    }

    @Test
    fun `an actual export opens Continue`() {
        // The save paths (PDF, QR capture) all set recoveryExported.
        assertTrue(OnboardingUiState(recoveryExported = true).recoveryStepReady)
    }

    /**
     * The load-bearing one, and the reason the old skip was a defect rather than a preference.
     *
     * `recoveryStepReady` means "a kit exists". Deferring is a different answer to a different
     * question, and it must not be reachable from this property by any route — an added field, a
     * second flag OR-ed in, a rename. If deferring ever satisfies this, every downstream claim built
     * on it becomes a lie at once: the Done step stops warning, Settings stops warning, and the user
     * is told they have a kit they never made.
     */
    @Test
    fun `readiness is derived from the export alone`() {
        val source = File("src/main/kotlin/com/localkeep/feature/onboarding/OnboardingViewModel.kt")
            .readText()
        val gate = source
            .substringAfter("val recoveryStepReady: Boolean")
            .substringBefore("\n\n")
        assertTrue(
            gate.contains("get() = recoveryExported") && "||" !in gate && "&&" !in gate,
            "recoveryStepReady must be exactly `recoveryExported`. Anything OR-ed into it makes an " +
                "acknowledgement indistinguishable from a saved kit — the exact defect that was " +
                "removed once and must not come back under a new name. Found: $gate",
        )
    }

    /**
     * A failed commit must never be recorded as a saved kit.
     *
     * `commitPreparedRecoveryKit()` returns false for reasons that are not benign — the vault
     * relocking mid-flow (the grace timer runs while the user picks a folder, which this codebase
     * has already been bitten by), a changed session epoch, an I/O failure in `AtomicVaultWriter`.
     * In each of those the user holds 24 words that open nothing. Writing the settings flag before
     * that call reads as harmless — the export really did happen — but it clears the Settings
     * warning over a vault with no working kit, which is precisely what spec §3.3's invariant
     * forbids. The flag is only truthful after a commit that returned true, or on the early-return
     * path where an earlier save already committed one.
     *
     * Structural rather than behavioural: `RecoveryKitViewModel` needs a real `VaultLockManager` and
     * a `Context`, and the property worth pinning is the *ordering*, which a source read states
     * exactly.
     */
    @Test
    fun `a kit is only recorded as saved after it actually commits`() {
        val source = File(
            "src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitViewModel.kt",
        ).readText()
        val body = source.substringAfter("private suspend fun markSaved(").substringBefore("\n    }")
        val commitCall = "lockManager.commitPreparedRecoveryKit()"
        assertTrue(commitCall in body, "markSaved must still be the funnel that commits the kit.")

        // Everything before the commit attempt, minus the early-return branch for a kit some other
        // save path already committed. Nothing left in there may record a saved kit.
        val beforeCommit = body.substringBefore(commitCall)
        val alreadyExportedBranch = beforeCommit
            .substringAfter("if (_state.value.exported) {", "")
            .substringBefore("return", "")
        val beforeCommitOutsideBranch = beforeCommit.replace(alreadyExportedBranch, "")
        assertFalse(
            "setRecoveryKitSaved" in beforeCommitOutsideBranch,
            "RECOVERY_KIT_SAVED is written before commitPreparedRecoveryKit() has succeeded. A " +
                "failed commit would then leave the app claiming a kit exists while the vault has " +
                "none, and the Settings warning would clear over it.",
        )
        assertTrue(
            "setRecoveryKitSaved" in body.substringAfter(commitCall),
            "A successful commit must record the kit, or the Settings warning never clears.",
        )
    }

    /**
     * Deferring exists, is its own action, and does not touch the export flag.
     *
     * Asserted against the source because the alternative — a ViewModel test — needs a Context and
     * a real vault, and the property worth pinning is structural: `deferRecoveryKit` must never
     * learn to write `recoveryExported`.
     */
    @Test
    fun `deferring advances without claiming a kit exists`() {
        val source = File("src/main/kotlin/com/localkeep/feature/onboarding/OnboardingViewModel.kt")
            .readText()
        assertTrue(
            "fun deferRecoveryKit()" in source,
            "The Recovery Kit step must offer a way past it that does not require an export.",
        )
        val defer = source.substringAfter("fun deferRecoveryKit()").substringBefore("\n    }")
        assertFalse(
            "recoveryExported" in defer,
            "deferRecoveryKit must not set recoveryExported — that flag means a kit exists, and " +
                "after a deferral none does.",
        )
        assertFalse(
            "setRecoveryKitSaved" in defer,
            "deferRecoveryKit must not record a saved kit in settings; that is what keeps the " +
                "Settings warning and the Done-step copy truthful.",
        )
    }
}
