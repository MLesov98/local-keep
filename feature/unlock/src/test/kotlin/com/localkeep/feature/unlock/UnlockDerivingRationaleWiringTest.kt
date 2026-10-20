package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `UnlockScreen`'s `LkDerivingOverlay` call used to show the Argon2id "this wait is the point"
 * rationale, and the vault's "N passes, M MiB" KDF summary, unconditionally — including on the
 * biometric and Recovery Kit unlock paths, which run no KDF at all (`UnlockViewModel`'s
 * `unlockWithBiometric`/`unlockWithRecoveryKit` only ever set `isWorking`, never `unlockPhase`;
 * only the password path — `unlockPassword` — sets `unlockPhase = UnlockPhase.READING` the same
 * instant it sets `isWorking = true`). `state.unlockPhase != null` is therefore the signal for "a
 * real derivation is actually running", used to gate both the rationale and the summary.
 *
 * `LkDerivingOverlay`'s own `showRationale` gating is covered directly (with a real Compose /
 * Robolectric render) by `:core:ui`'s `LkDerivingRationaleGateTest`; this is a source scan of the
 * call site's wiring, same idiom as `UnlockSubtitleTest` in this module — no Compose/Robolectric
 * runtime on this module's unit-test classpath.
 */
class UnlockDerivingRationaleWiringTest {

    private val source: String = File("src/main/kotlin/com/localkeep/feature/unlock/UnlockScreen.kt").readText()

    private val overlayCall: String
        get() {
            val start = source.indexOf("LkDerivingOverlay(")
            assertTrue(start >= 0, "LkDerivingOverlay(...) call not found")
            val end = source.indexOf(")\n        }", start).let { if (it < 0) source.length else it }
            return source.substring(start, end)
        }

    @Test
    fun rationaleIsGatedOnARealUnlockPhase() {
        assertTrue(
            "showRationale = realKdfRunning" in overlayCall,
            "the overlay call must pass showRationale, gated on a real KDF running: $overlayCall",
        )
    }

    @Test
    fun kdfSummaryIsGatedOnARealUnlockPhaseToo() {
        assertTrue(
            "kdfSummary = if (realKdfRunning) resolveKdfSummary(state.kdfSummary) else null" in overlayCall,
            "the KDF-parameter summary must not render on a path where no derivation ran: $overlayCall",
        )
    }

    @Test
    fun theGateIsDerivedFromUnlockPhaseNotFromProgress() {
        // The obvious-looking alternative — gating on `state.unlockProgress != null` — is also null
        // for onboarding's vault-creation call to the same overlay (it never passes `progress` at
        // all), so it would have hidden the rationale on the one path where it is entirely accurate.
        // unlockPhase is the field that is actually null for the whole biometric/Recovery Kit path
        // and non-null from the first frame of the password path.
        assertTrue(
            "val realKdfRunning = state.unlockPhase != null" in source,
            "the KDF-running signal must be derived from unlockPhase, not from unlockProgress",
        )
    }
}
