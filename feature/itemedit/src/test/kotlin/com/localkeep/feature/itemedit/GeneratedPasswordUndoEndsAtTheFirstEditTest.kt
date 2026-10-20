package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Typing over a generated password withdraws the undo (2026-09-20, release sweep 4A-F2).
 *
 * The bug this closes: `generatePassword` armed `passwordBeforeGenerate`, `canUndoGeneratedPassword`
 * read only that field, and `onPasswordChanged` cleared `generatedEntropyBits` but never the undo.
 * So after generating, clearing the well and typing a 256-character password by hand, "Undo the
 * generated password" was still on offer — and one tap replaced the typed value with the generated
 * one and then vanished, with no second undo to take *that* back. The affordance's own KDoc says it
 * exists "to take back a mis-tap"; after a hand edit there is no mis-tap left to reverse.
 *
 * Source-pinned for the reason `ItemEditViewModelTest` gives: this module's `ItemEditViewModel`
 * cannot be constructed on a JVM (its `VaultRepository` needs a `VaultLockManager` whose
 * `KeystoreManager` opens AndroidKeyStore in its constructor), and the undo bookkeeping is in
 * private fields rather than in the state a pure function could be tested on. Same technique as
 * `GeneratedPasswordKeepsItsVerdictTest`, which pins the other half of this function.
 */
class GeneratedPasswordUndoEndsAtTheFirstEditTest {

    private val viewModel =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt").readText()

    private val onPasswordChanged = viewModel
        .substringAfter("fun onPasswordChanged(value: String) {", "")
        .substringBefore("\n    /**")

    @Test
    fun `a hand edit disarms the undo`() {
        assertTrue(
            "passwordBeforeGenerate = null" in onPasswordChanged,
            "the undo survives a typed password and will destroy it when fired",
        )
    }

    /**
     * Both companions travel with it. They describe the value `generatePassword` replaced, and an
     * undo that never fires must not leave a stale reveal state or a stale bit count behind for the
     * next generate to restore.
     */
    @Test
    fun `the undo's companions are cleared with it`() {
        assertTrue("entropyBitsBeforeGenerate = null" in onPasswordChanged)
        assertTrue("passwordRevealedBeforeGenerate = false" in onPasswordChanged)
    }

    /**
     * And only on a real edit. Compose can call `onValueChange` with the value the field already
     * holds; clearing on that would disarm the undo on the very keystroke-free recomposition that
     * follows the generate, which is the one moment the control has to work.
     */
    @Test
    fun `an unchanged value leaves the undo armed`() {
        assertTrue(
            "if (value != _state.value.password) {" in onPasswordChanged,
            "the undo is disarmed by any callback, not only by a real edit",
        )
    }
}
