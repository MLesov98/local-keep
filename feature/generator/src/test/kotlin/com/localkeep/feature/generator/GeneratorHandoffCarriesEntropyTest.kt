package com.localkeep.feature.generator

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Save as a new login" hands over the bit count, not just the string.
 *
 * Repro, 2026-09-15: Generator → Passphrase → Regenerate → `Reasonable` / `77 bits`, two of four
 * segments (`shots/090-passphrase-top.png`) → "Save as a new login" → the editor showed `Excellent`,
 * four of four, for the identical string (`shots/094-gen-to-login.png`). Same for a 20-character
 * random: `Strong` 3/4 here against `Excellent` 4/4 there (F-29).
 *
 * Neither scorer is wrong for its own input. This screen names a value from the entropy of the draw;
 * the item form scores a *typed* password with zxcvbn, which reads the characters. The handover used
 * to carry only the characters, so the editor had no choice but to re-derive — and a string does not
 * say which pool it was drawn from, so what it re-derived was a different question's answer.
 *
 * FAILS WITHOUT THE FIX: `onSaveToVault` was `(String) -> Unit` and the click passed
 * `state.generatedValue` alone.
 */
class GeneratorHandoffCarriesEntropyTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/generator/GeneratorScreen.kt").readText()

    @Test
    fun `the save callback takes the entropy as well as the value`() {
        assertTrue(
            "onSaveToVault: (String, Double) -> Unit" in screen,
            "the handover is back to carrying the string alone",
        )
    }

    @Test
    fun `the tap hands over both, from the one state that holds them`() {
        assertTrue(
            "onSaveToVault(state.generatedValue.orEmpty(), state.entropyBits)" in screen,
            "the save tap no longer passes the draw's entropy",
        )
    }

    /**
     * The premise that makes passing it safe: both fields are written in the same `update`, so the
     * figure handed over always describes the string handed over. A generator that wrote them
     * separately could hand the editor last draw's bit count.
     */
    @Test
    fun `the value and its entropy are written together`() {
        val viewModel = File("src/main/kotlin/com/localkeep/feature/generator/GeneratorViewModel.kt").readText()
        val generate = viewModel.substringAfter("generatedValue = generated.value", "")
            .substringBefore("}")
        assertTrue(
            "entropyBits = generated.entropyBits" in generate,
            "the value and its bit count are no longer written in one update",
        )
    }

    /** And the same figure is what this screen prints beside its own verdict. */
    @Test
    fun `the figure handed over is the one the user just read`() {
        assertTrue("entropyBits = state.entropyBits.toInt()" in screen)
        assertTrue("LkStrength.ofBits(entropyBits)" in screen)
    }
}
