package com.localkeep.feature.itemedit

import com.localkeep.core.generator.PasswordGenerator
import com.localkeep.core.ui.theme.LkStrength
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One password, one verdict.
 *
 * Repro, 2026-09-15: Generator → Passphrase → "Save as a new login". The Generator said `Reasonable`
 * with `77 bits` and two of four segments; the editor said `Excellent` with four of four, about the
 * identical string, one tap later (F-29, `shots/090` vs `shots/094`). Same again for a 20-character
 * random: `Strong` 3/4 against `Excellent` 4/4.
 *
 * Neither scorer is wrong for its own input — entropy is right for a value that was *drawn* and
 * zxcvbn is right for one that was *typed*, and `LkStrength` argues both. What was wrong is that the
 * editor scored a drawn value as a typed one, because the draw's own measure was thrown away at the
 * handover. The principal's ruling (WP5, 2026-09-15): carry the Generator's verdict.
 *
 * FAILS WITHOUT THE FIX: `ItemEditUiState` had no `generatedEntropyBits`, `generatePassword` wrote
 * only `password`, and `PasswordStrengthRow` ran `LkStrength.ofScore(StrengthAnalyzer.score(...))`
 * on every value it was given.
 */
class GeneratedPasswordKeepsItsVerdictTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
    private val viewModel = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt").readText()

    /**
     * The premise. Without a real disagreement between the two scales there is nothing to fix, and
     * the generator's own defaults are the exact case the device pass caught: a six-word EFF
     * passphrase is 77.5 bits, which `ofBits` calls Reasonable — while any zxcvbn score of 4, which
     * a 77-bit passphrase comfortably earns, would be Excellent.
     */
    @Test
    fun `the two scales really do disagree about a generated passphrase`() {
        val passphraseBits = PasswordGenerator.entropy(PasswordGenerator.PassphraseConfig()).toInt()
        assertEquals(77, passphraseBits, "the generator's passphrase default moved; re-derive this case")
        assertEquals(LkStrength.Reasonable, LkStrength.ofBits(passphraseBits))
        assertNotEquals(
            LkStrength.ofScore(4),
            LkStrength.ofBits(passphraseBits),
            "nothing to carry: the two scales now agree, and this finding is moot",
        )
    }

    /**
     * The random default was 20 characters when this test was written and scored Strong by its own
     * draw (127.5 bits, half a bit under the 128-bit band). Release-sweep finding 4A-F1 moved the
     * default to 21 on 2026-09-21, which clears the band, so the default random now reads Excellent
     * on both scales. That does not weaken the premise — the passphrase case above still disagrees —
     * and the draw's verdict is still the one the editor must carry; this case pins that the default
     * random is scored off its bits and lands in the top band, as the generator itself promises
     * (`DefaultRandomPasswordReachesTopBandTest`).
     */
    @Test
    fun `the default random is Excellent by its own draw`() {
        val bits = PasswordGenerator.entropy(PasswordGenerator.RandomConfig()).toInt()
        assertTrue(bits >= 128, "the generator's random default moved below the top band; re-derive this case")
        assertEquals(LkStrength.Excellent, LkStrength.ofBits(bits))
    }

    @Test
    fun `the editor carries the bits with the value rather than recomputing them`() {
        val generate = viewModel.substringAfter("fun generatePassword() {", "").substringBefore("\n    fun ")
        assertTrue(
            "generatedEntropyBits = generated.entropyBits" in generate,
            "generatePassword no longer records the draw's own entropy",
        )
        // The whole point: taken from the draw, never measured off the resulting string.
        assertTrue(
            "PasswordGenerator.entropy(" !in generate,
            "generatePassword is re-deriving entropy instead of carrying it",
        )
    }

    /**
     * The other half of F-29, and the one the device walk actually caught: a password arriving from
     * the Generator's "Save as a new login". `PendingSecret` carries the bits across (its own test
     * pins that); this is the editor collecting them instead of dropping them on arrival and
     * re-scoring the string with zxcvbn.
     */
    @Test
    fun `a password handed over by the Generator arrives with its verdict`() {
        val adopt = viewModel.substringAfter("fun adoptGeneratedPassword() {", "").substringBefore("\n    /**")
        assertTrue("password = generated.secret" in adopt)
        assertTrue(
            "generatedEntropyBits = generated.entropyBits" in adopt,
            "the Generator's verdict is dropped at the editor's door again",
        )
    }

    @Test
    fun `a typed value is scored, a drawn one is not`() {
        val row = screen.substringAfter("private fun PasswordStrengthRow(", "")
            .substringBefore("private fun LoginTotpSection(")
        assertTrue("if (bits != null) return@produceState" in row, "zxcvbn still runs for a drawn value")
        assertTrue(
            "val strength = if (bits != null) LkStrength.ofBits(bits) else scored" in row,
            "the row no longer prefers the draw's own verdict",
        )
        // And the figure that justifies the word travels with it, the way the Generator shows it.
        assertTrue("R.plurals.itemedit_entropy_bits" in row, "the bit count is not shown beside the word")
    }

    /**
     * One keystroke ends it. The bits describe the string the generator drew; the moment the user
     * edits that string the figure is a claim about something that is no longer in the field, and a
     * stale "128 bits" beside a hand-typed password would be the same lie in the other direction.
     */
    @Test
    fun `editing the password drops the carried verdict`() {
        val onChanged = viewModel.substringAfter("fun onPasswordChanged(value: String) {", "")
            .substringBefore("\n    /**")
        assertTrue(
            "generatedEntropyBits = null" in onChanged,
            "a typed edit keeps the generator's verdict for a string it no longer describes",
        )
    }

    /**
     * And the undo puts back both halves. Regenerate-then-undo restores a password the user typed,
     * and a bit count left over from the value it replaced would describe the wrong string —
     * the same pairing `passwordRevealedBeforeGenerate` already makes for the reveal state.
     */
    @Test
    fun `the undo restores the verdict that belonged to the restored value`() {
        val undo = viewModel.substringAfter("fun undoGeneratedPassword() {", "").substringBefore("\n    fun ")
        assertTrue("val previousBits = entropyBitsBeforeGenerate" in undo)
        assertTrue("generatedEntropyBits = previousBits" in undo)
    }

    /**
     * Not content. It is never written to the vault, so a form that differs only by it has no
     * unsaved edit — and `seedGeneratedPassword` deliberately forgives the password it seeds, which
     * an un-stripped bit count would have un-forgiven, re-opening the "discard changes?" prompt on
     * an untouched new item that the `editableOnly` strip list exists to keep shut.
     */
    @Test
    fun `the carried verdict is not an unsaved edit`() {
        val typed = ItemEditUiState(password = "hunter2")
        val drawn = typed.copy(generatedEntropyBits = 128.0)
        assertEquals(typed.editableOnly(), drawn.editableOnly())
        assertNull(drawn.editableOnly().generatedEntropyBits)
    }
}
