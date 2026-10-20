package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The standalone Recovery Kit screen must offer a way forward once the kit is actually saved.
 *
 * Found on a device, 2026-08-31: after saving — either export path — the screen said "Recovery Kit
 * captured. Verify the photo is readable before continuing." and then composed nothing to continue
 * *to*. The only exit was the same Back chevron that had been in the header since before anything
 * was saved, so nothing on screen distinguished "saved, you are finished" from "you have not saved
 * yet" — on the one screen in the app whose whole purpose is producing a durable artefact the user
 * must be told they now have.
 *
 * Onboarding's own Recovery step has had this since 2026-08-10: a Continue gated on
 * `recoveryStepReady`, which is `recoveryExported` and nothing else (`RecoveryKitGateTest`). This
 * screen is the same decision reached from Settings, so it gets the same shape, gated on the same
 * fact — `RecoveryKitUiState.exported`, which only `markSaved` sets and only after
 * `commitPreparedRecoveryKit` returned true.
 */
class RecoveryKitDoneActionTest {

    /**
     * Structural, deliberately. `RecoveryKitScreen` takes a concrete `RecoveryKitViewModel`, which
     * needs a real `VaultLockManager` and a `Context`, so the screen itself cannot be composed in a
     * unit test — but the property that was wrong is *where the control is wired*, and a source read
     * states that exactly. The rendered behaviour of the control is covered by
     * `RecoveryKitDoneBarTest`.
     */
    @Test
    fun `the screen composes a way forward, gated on the kit having been saved`() {
        val source = File(
            "src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt",
        ).readText()
        val call = source
            .substringAfter("RecoveryKitDoneBar(", missingDelimiterValue = "")
            .substringBefore(")")
        assertTrue(
            call.isNotBlank() && "state.exported" in call,
            "RecoveryKitScreen must compose RecoveryKitDoneBar gated on state.exported. Without it " +
                "the screen tells the user to continue and offers nothing to continue with, and the " +
                "header's Back chevron reads identically before and after the kit is saved. Found: " +
                if (call.isBlank()) "no RecoveryKitDoneBar call at all" else "RecoveryKitDoneBar($call)",
        )
    }

    /**
     * The gate may not drift onto something softer. `exported` is the only field that means "a kit
     * exists and this vault committed it"; `qrVisible`, `notice` or a bare `mnemonic.isNotBlank()`
     * would each open the way forward over words that were shown and never saved — which is the
     * state the footnote promises still leaves the user's previous kit working.
     */
    @Test
    fun `the way forward is not opened by anything weaker than an export`() {
        val source = File(
            "src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt",
        ).readText()
        val call = source
            .substringAfter("RecoveryKitDoneBar(", missingDelimiterValue = "")
            .substringBefore(")")
        assertTrue(
            call.isNotBlank() && "||" !in call && "mnemonic" !in call && "qrVisible" !in call,
            "The Done action must be gated on state.exported alone. Found: " +
                if (call.isBlank()) "no RecoveryKitDoneBar call at all" else "RecoveryKitDoneBar($call)",
        )
    }
}
