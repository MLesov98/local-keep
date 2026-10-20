package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Removing a duress vault must not be a Pro feature, because losing Pro is not an act of the user.
 *
 * The duress row's Setup form and its Remove button both sat inside `ProGate(content = ...)`. Setup
 * belongs there — it is a Pro feature and may be sold. Remove does not: entitlement can be lost
 * without the user doing anything, through a Google refund, a changed Play account, or a re-install
 * that reconciles against a different one. `syncFromPlay` then writes `isPro = false`, the row
 * collapses to an upsell, and the decoy stays armed — `DuressUnlocker` has no entitlement check and
 * keeps honouring the duress password. The user was left with a coercion vault they could not
 * disarm, recoverable only by buying Pro a second time.
 *
 * `removeDuress()` itself never checked `isPro`, so the gate was the entire restriction, and moving
 * the control out from under it is the entire fix.
 *
 * Showing Remove to a user who never bought Pro discloses nothing. The row refuses to say whether a
 * decoy is configured in *either* state — the vault header carries two slots either way, so "the app
 * genuinely cannot know" — and that is the plausible-deniability property that makes an
 * unconditional control safe here. Removal against a vault with no decoy is a no-op.
 *
 * Asserted on the source, the same idiom as [SettingsInFlightControlsTest] and
 * [HiddenItemsSurfaceTest]: `:feature:settings` declares Robolectric but no Compose UI test
 * dependency, so there is no way to compose this row at `isPro = false` and look at it.
 */
class DuressRemovalSurvivesEntitlementLossTest {

    /**
     * The screen with its comments removed.
     *
     * This codebase explains each decision beside the code that implements it, so the prose above
     * the gate names the gate. A scan that could not tell prose from code would pass with the fix
     * reverted — the comment explaining why Remove sits outside `ProGate` would satisfy a search
     * for Remove sitting outside `ProGate`.
     */
    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** The duress row alone: from its title constant to the Panic row that follows it. */
    private val duressRow: String =
        screen.substringAfter("stringResource(SettingsFocus.DURESS)")
            .substringBefore("R.string.settings_panic_title")

    /**
     * The gated region: `ProGate(`'s opening through the upsell that ends `lockedContent`.
     *
     * Both slices are bounded below in [the slices are bounded] before anything is concluded from
     * them. A `substringBefore` whose delimiter stops matching silently returns the whole remaining
     * file, and a `substringAfter` whose delimiter stops matching returns the original string — in
     * both cases every `contains` check downstream keeps passing while measuring the wrong text.
     * That failure mode has shipped in this repo before.
     */
    private val gatedRegion: String =
        duressRow.substringAfter("ProGate(")
            .substringBefore("R.string.settings_duress_upsell_purpose")

    private val afterGate: String =
        duressRow.substringAfter("R.string.settings_duress_upsell_purpose")

    @Test
    fun `the slices are bounded`() {
        assertTrue(
            duressRow.isNotEmpty() && duressRow.length < screen.length,
            "the duress row slice is unbounded, so every other assertion here is measuring the " +
                "whole screen and cannot fail; length=${duressRow.length} of ${screen.length}",
        )
        assertTrue(
            gatedRegion.isNotEmpty() && gatedRegion.length < duressRow.length,
            "the gated-region slice is unbounded — one of its delimiters stopped matching, so " +
                "'not inside the gate' below is a tautology; length=${gatedRegion.length}",
        )
        assertTrue(
            afterGate.isNotEmpty() && afterGate.length < duressRow.length,
            "the after-gate slice is unbounded; length=${afterGate.length}",
        )
        assertTrue(
            gatedRegion.contains("isPro = state.isPro"),
            "the gated-region slice does not contain the gate it is named for, so it is not the " +
                "region this test believes it is reading",
        )
    }

    @Test
    fun `duress setup stays behind the Pro gate`() {
        assertTrue(
            gatedRegion.contains("R.string.settings_duress_setup_button"),
            "setup is a Pro feature and belongs inside the gate; moving it out would give the " +
                "duress vault away, which is the opposite mistake to the one this test guards",
        )
    }

    @Test
    fun `duress removal is not inside the Pro gate`() {
        assertTrue(
            !gatedRegion.contains("R.string.settings_duress_remove_button"),
            "Remove is inside ProGate's content branch: a user whose entitlement lapsed sees an " +
                "upsell where the disarm control was, while DuressUnlocker keeps honouring the " +
                "decoy password. Found in the gated region: $gatedRegion",
        )
    }

    @Test
    fun `duress removal is still reachable, outside the gate`() {
        assertTrue(
            afterGate.contains("R.string.settings_duress_remove_button"),
            "Remove is not in the duress row after the gate either — moving it out of the gate " +
                "must not have deleted it. Found after the gate: $afterGate",
        )
        assertTrue(
            afterGate.contains("confirmRemoval = true"),
            "the Remove button outside the gate no longer raises its confirmation dialog",
        )
    }

    @Test
    fun `the removal confirmation moved out with its button`() {
        assertTrue(
            afterGate.contains("viewModel::submitDuressRemoval"),
            "the confirmation dialog that actually removes the duress vault stayed inside the " +
                "gate, so the button outside it opens a dialog that a non-Pro user can never be " +
                "shown. (`submitDuressRemoval` is the ViewModel entry point `removeDuress` moved " +
                "behind when the duress form left composition — see DuressFormState.)",
        )
    }
}
