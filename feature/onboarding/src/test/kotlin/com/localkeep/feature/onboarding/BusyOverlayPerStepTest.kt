package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `isWorking` is one flag shared by three very different waits: creating the vault (an Argon2id
 * derivation, then an unlock), enrolling biometric unlock (a Keystore key and one AES-GCM wrap),
 * and writing the Recovery Kit PDF. The overlay it raises used to be the vault-creation one for all
 * three, so tapping "Enable biometric unlock" — on a screen that had just said the vault exists —
 * put up "Creating your vault" with the claim that the wait is "the same cost an attacker pays for
 * every guess". No KDF runs there, and the Tier G rationale is exactly the claim the unlock screen
 * already withholds when none does (`UnlockDerivingRationaleWiringTest`).
 *
 * The first three tests drive [onboardingBusyOverlay], the decision itself. The last pins that the
 * screen asks it rather than hard-coding the creation overlay again, in the same source-scan idiom
 * as the unlock wiring test — this module has no Compose runtime on its unit-test classpath.
 */
class BusyOverlayPerStepTest {

    @Test
    fun theBiometricStepNeverClaimsToBeCreatingTheVaultOrCitesTheKdfCost() {
        val overlay = onboardingBusyOverlay(OnboardingUiState.Step.ENABLE_BIOMETRIC)
        assertNotEquals(
            R.string.onboarding_screen_deriving_title,
            overlay.title,
            "no vault is being created on the biometric step",
        )
        assertFalse(overlay.showRationale, "no KDF runs when biometric unlock is enrolled")
    }

    @Test
    fun theRecoveryKitExportNeverClaimsToBeCreatingTheVaultOrCitesTheKdfCost() {
        val overlay = onboardingBusyOverlay(OnboardingUiState.Step.RECOVERY_KIT)
        assertNotEquals(
            R.string.onboarding_screen_deriving_title,
            overlay.title,
            "the vault already exists when the kit is written",
        )
        assertFalse(overlay.showRationale, "writing a PDF is not a key derivation")
    }

    @Test
    fun controlCreatePasswordStillShowsTheCreationOverlayWithItsRationale() {
        val overlay = onboardingBusyOverlay(OnboardingUiState.Step.CREATE_PASSWORD)
        assertEquals(R.string.onboarding_screen_deriving_title, overlay.title)
        assertTrue(overlay.showRationale, "vault creation really does run Argon2id")
    }

    @Test
    fun theScreenTakesBothFieldsFromTheDecision() {
        val source = File("src/main/kotlin/com/localkeep/feature/onboarding/OnboardingScreen.kt").readText()
        val start = source.indexOf("LkDerivingOverlay(")
        assertTrue(start >= 0, "LkDerivingOverlay(...) call not found")
        val call = source.substring(start, source.indexOf("\n        )", start))
        assertTrue("stringResource(busy.title)" in call, "title must come from the per-step decision: $call")
        assertTrue("showRationale = busy.showRationale" in call, "rationale must come from it too: $call")
    }
}
