package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The "Open this backup" button says which of two things is stopping it, and says the right one.
 *
 * This is V21, recurring. The button is `enabled = !isWorking && password.isNotEmpty()` — two
 * causes folded into one boolean — and a label derived from that flag can only ever name one of
 * them. The rule the ledger states is: key on the blocking condition, never on `enabled`.
 *
 * What makes it sharp rather than theoretical here: `checkSelectedBackup` sets
 * `password = "", isWorking = true` in the same update, so **both conditions hold at once for the
 * whole of every derivation**. A label that checked emptiness first would not be wrong in some edge
 * case — it would be wrong on every single check, for the tens of seconds Argon2id runs, telling a
 * screen-reader user to type the password they just typed.
 */
class RestoreCheckLabelTest {

    @Test
    fun aRunningDerivationSaysItIsWorking() {
        assertEquals(
            R.string.unlock_restore_check_a11y_working,
            restoreCheckLabel(isWorking = true, passwordEmpty = true),
            "the view-model clears the password when work starts, so this is the state a real " +
                "derivation is in — naming the empty field here is the V21 defect",
        )
    }

    @Test
    fun anEmptyFieldWithNoWorkRunningAsksForThePassword() {
        assertEquals(
            R.string.unlock_restore_check_a11y_needs_password,
            restoreCheckLabel(isWorking = false, passwordEmpty = true),
        )
    }

    @Test
    fun aReadyButtonIsNamedForWhatItDoes() {
        assertEquals(
            R.string.unlock_restore_check_button,
            restoreCheckLabel(isWorking = false, passwordEmpty = false),
        )
    }

    /** Working with a filled field is still working. The two causes are not interchangeable. */
    @Test
    fun workingWinsOverAFilledField() {
        assertEquals(
            R.string.unlock_restore_check_a11y_working,
            restoreCheckLabel(isWorking = true, passwordEmpty = false),
        )
    }

    /**
     * The control that catches a lazy fix: three conditions must produce three *different*
     * sentences. Collapsing any two of them would pass every assertion above that names only one.
     */
    @Test
    fun theThreeStatesAreThreeDistinctSentences() {
        val labels = listOf(
            restoreCheckLabel(isWorking = true, passwordEmpty = true),
            restoreCheckLabel(isWorking = false, passwordEmpty = true),
            restoreCheckLabel(isWorking = false, passwordEmpty = false),
        )
        assertEquals(labels.size, labels.toSet().size, "each state needs its own sentence: $labels")
    }

    /**
     * And the one thing the four assertions above cannot see: that the button is labelled at all.
     *
     * A perfectly correct `restoreCheckLabel` wired to nothing is the state this screen shipped in.
     * `.labelled(...)` is what makes an icon-or-disabled control announce something other than its
     * raw text, so the wiring is read out of the source — weaker than the tests above, and labelled
     * as such, but it is the half that was actually missing.
     */
    @Test
    fun theButtonIsActuallyLabelledWithIt() {
        val source = File("src/main/kotlin/com/localkeep/feature/unlock/PreUnlockRestoreScreen.kt").readText()
        // Scoped to the button's own modifier chain, and that is not fussiness. The first version of
        // this assertion searched everything after the test tag, so it also saw `restoreCheckLabel`'s
        // own declaration further down the file — deleting the `.labelled(...)` call left it green.
        // Measured 2026-09-13: a check that cannot fail is indistinguishable from one that passed.
        val chain = source
            .substringAfter(".lkTag(\"restore-check\")")
            .substringBefore("LkSecondaryButton")
        assertTrue(
            "restoreCheckLabel(" in chain && ".labelled(" in chain,
            "the restore-check button must carry the label this file computes. Chain was: $chain",
        )
    }

    /**
     * The progress line is announced.
     *
     * `unlock_restore_working` covers a derivation this codebase calls indistinguishable from a
     * hang, against a candidate whose cost can exceed anything this device has had to satisfy. A
     * bare `Text` leaves a screen-reader user in silence for that whole wait, right after they
     * pressed a button. Source-scanned because a live region is not something a `uiautomator` dump
     * reports — stated as the weaker check it is.
     */
    @Test
    fun theWorkingLineIsAnnounced() {
        val source = File("src/main/kotlin/com/localkeep/feature/unlock/PreUnlockRestoreScreen.kt").readText()
        val around = source.substringAfter("R.string.unlock_restore_working").substringBefore("LkPrimaryButton")
        assertTrue(
            "Modifier.announce(" in around,
            "the working line must be a live region; siblings in this module all are. Saw: $around",
        )
    }

    /** The label must not be derived from the disabled flag — the whole point of V21. */
    @Test
    fun theLabelIsNotDerivedFromTheEnabledFlag() {
        val source = File("src/main/kotlin/com/localkeep/feature/unlock/PreUnlockRestoreScreen.kt").readText()
        assertNotEquals(
            true,
            "labelled(if (enabled" in source || "enabled ->" in source,
            "a label computed from `enabled` cannot say which of its two causes applies",
        )
    }
}
