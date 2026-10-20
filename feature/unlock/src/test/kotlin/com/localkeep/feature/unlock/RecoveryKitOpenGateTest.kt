package com.localkeep.feature.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Open with Recovery Kit" must not be live on a phrase that cannot open anything.
 *
 * It was. The gate was `recoveryWords.isNotBlank()`, so three words submitted, and what came back
 * was "Those words didn't open this vault. Check the order and the spelling — all 24 have to be
 * right" — advice about a transcription error the user had not made, on the screen reached by
 * someone who has already lost their master password. Driven on emulator-5554 at three of
 * twenty-four words, 2026-09-15 (`shots/011-rk-3words-submit.png`: the CTA read `en=true`).
 *
 * The count is not the whole gate. A phrase can be twenty-four words long and still be nothing —
 * BIP-39 spends the last word's bits on a checksum, which is what catches a swapped pair or a word
 * typed twice, and `RecoveryKit.deriveRecoveryKek` already does that check. Every refused attempt
 * costs real unlock backoff, so refusing one the app can prove is wrong is not pedantry.
 *
 * The valid phrase is BIP-39's own published all-zero-entropy vector for 256 bits, not a real kit:
 * nothing in this repo may carry credentials that open anything.
 */
class RecoveryKitOpenGateTest {

    private val validKit = (List(23) { "abandon" } + "art").joinToString(" ")

    @Test
    fun `three words cannot be submitted`() {
        assertEquals(3, recoveryWordCount("eight pool ocean"))
        assertFalse(recoveryKitIsComplete("eight pool ocean"))
    }

    @Test
    fun `an empty or blank field cannot be submitted`() {
        assertEquals(0, recoveryWordCount(""))
        assertEquals(0, recoveryWordCount("   \n  "))
        assertFalse(recoveryKitIsComplete(""))
        assertFalse(recoveryKitIsComplete("   "))
    }

    @Test
    fun `twenty-three words cannot be submitted`() {
        val short = validKit.split(" ").dropLast(1).joinToString(" ")
        assertEquals(23, recoveryWordCount(short))
        assertFalse(recoveryKitIsComplete(short))
    }

    /**
     * Twenty-four words that fail their checksum: `abandon` twenty-four times is the valid vector
     * with its checksum word replaced. Word count alone would pass this, which is why the gate
     * cannot be word count alone.
     */
    @Test
    fun `twenty-four words that fail the BIP-39 checksum cannot be submitted`() {
        val wrong = List(24) { "abandon" }.joinToString(" ")
        assertEquals(24, recoveryWordCount(wrong))
        assertFalse(recoveryKitIsComplete(wrong))
    }

    /** A word that is not in the wordlist at all, in an otherwise complete phrase. */
    @Test
    fun `twenty-four words containing a non-wordlist word cannot be submitted`() {
        val typo = validKit.replaceFirst("art", "artt")
        assertEquals(24, recoveryWordCount(typo))
        assertFalse(recoveryKitIsComplete(typo))
    }

    /**
     * The control, and it must stay green: a gate that refused everything would pass every test
     * above while making the Recovery Kit unusable — which is a worse bug than the one being fixed,
     * because this screen is the last way into a vault whose password is gone.
     */
    @Test
    fun `a whole valid kit can be submitted`() {
        assertEquals(24, recoveryWordCount(validKit))
        assertTrue(recoveryKitIsComplete(validKit))
    }

    /** The same kit as it arrives from a paste: ragged whitespace, mixed case, stray newlines. */
    @Test
    fun `a whole valid kit survives ragged whitespace and capitals`() {
        val pasted = "  " + validKit.uppercase().replace(" ", "\n  ") + "  \n"
        assertEquals(24, recoveryWordCount(pasted))
        assertTrue(recoveryKitIsComplete(pasted))
    }

    /**
     * N-01: a full 24 words that fail the checksum is the one incomplete-CTA case the screen can
     * actually name, and [recoveryKitChecksumInvalid] is what the UI now checks to say so — see
     * `unlock_recovery_checksum_invalid_message` and the button's a11y label in UnlockScreen.kt.
     * Same vector as `twenty-four words that fail the BIP-39 checksum cannot be submitted` above.
     */
    @Test
    fun `twenty-four words that fail the checksum are reported as checksum-invalid`() {
        val wrong = List(24) { "abandon" }.joinToString(" ")
        assertFalse(recoveryKitIsComplete(wrong))
        assertTrue(recoveryKitChecksumInvalid(wrong))
    }

    /** The control for N-01: an unfinished phrase is not "checksum-invalid", it is just short. */
    @Test
    fun `fewer than twenty-four words are not reported as checksum-invalid`() {
        assertFalse(recoveryKitChecksumInvalid("eight pool ocean"))
        assertFalse(recoveryKitChecksumInvalid(""))
    }

    /** The other control for N-01: a genuinely valid kit must never be flagged as checksum-invalid. */
    @Test
    fun `a whole valid kit is not reported as checksum-invalid`() {
        assertFalse(recoveryKitChecksumInvalid(validKit))
    }

    /**
     * V3-01: more than twenty-four words is its own answer, and neither of the two branches above
     * covers it. [recoveryKitChecksumInvalid] is false at any count but exactly twenty-four, so
     * pasting thirty-two words left the screen with nothing to say and the CTA announced as
     * "available once all 24 words are entered" — to a reader who had entered thirty-two.
     */
    @Test
    fun `more than twenty-four words is reported as too many`() {
        val long = (List(24) { "abandon" } + List(8) { "ability" }).joinToString(" ")
        assertEquals(32, recoveryWordCount(long))
        assertTrue(recoveryKitTooManyWords(long))
        assertFalse(recoveryKitIsComplete(long))
        assertFalse(recoveryKitChecksumInvalid(long))
    }

    /** One word past the kit is already too many — a kit is exactly twenty-four, not at least. */
    @Test
    fun `twenty-five words is already too many`() {
        val long = "$validKit abandon"
        assertEquals(25, recoveryWordCount(long))
        assertTrue(recoveryKitTooManyWords(long))
        assertFalse(recoveryKitIsComplete(long))
    }

    /**
     * The controls for V3-01, and they matter more than the case above: a predicate that answered
     * true at twenty-four would put an inline error under every kit that works, on the screen
     * reached by someone who has already lost their password.
     */
    @Test
    fun `a whole kit and a part-typed one are not too many`() {
        assertFalse(recoveryKitTooManyWords(validKit))
        assertFalse(recoveryKitTooManyWords(List(24) { "abandon" }.joinToString(" ")))
        assertFalse(recoveryKitTooManyWords("eight pool ocean"))
        assertFalse(recoveryKitTooManyWords(""))
    }
}
