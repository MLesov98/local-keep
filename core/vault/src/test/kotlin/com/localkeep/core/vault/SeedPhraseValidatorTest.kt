package com.localkeep.core.vault

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Recovery-phrase item type checks a seed phrase two ways before it is ever needed: every word
 * against the bundled BIP-39 list, and the whole phrase against its own checksum. Both run offline
 * against the same 2048-word asset `RecoveryKit` already resolves against.
 */
class SeedPhraseValidatorTest {
    private val wordlist = File("src/main/assets/bip39-wordlist.txt").readLines().toSet()
    private val validator = SeedPhraseValidator(wordlist)

    /** Canonical valid phrase for a given entropy size, derived rather than hand-typed. */
    private fun canonical(entropyBytes: Int): List<String> {
        val code = MnemonicCode(ByteArray(entropyBytes))
        return try {
            code.chars.concatToString().split(" ")
        } finally {
            code.clear()
        }
    }

    @Test
    fun `an empty grid is incomplete, not bad`() {
        val words = List(12) { "" }
        assertEquals(SeedPhraseStatus.INCOMPLETE, validator.status(words))
        assertTrue(validator.badIndices(words).isEmpty())
    }

    @Test
    fun `a partially filled grid stays incomplete`() {
        val words = canonical(16).toMutableList().also { for (i in 6 until 12) it[i] = "" }
        assertEquals(SeedPhraseStatus.INCOMPLETE, validator.status(words))
    }

    @Test
    fun `a word that is not on the list is flagged at its own slot, before the phrase is complete`() {
        // "marbel" is the comp's example — a plausible misspelling of "marble".
        val words = canonical(16).toMutableList()
        words[9] = "marbel"
        words[11] = "" // still incomplete overall, yet the bad word must already show
        assertFalse("marbel" in wordlist)
        assertEquals(listOf(9), validator.badIndices(words))
        assertEquals(SeedPhraseStatus.BADWORD, validator.status(words))
    }

    @Test
    fun `a canonical 12-word phrase is valid`() {
        assertEquals(SeedPhraseStatus.VALID, validator.status(canonical(16)))
    }

    @Test
    fun `canonical phrases of every standard length are valid`() {
        for (bytes in listOf(16, 20, 24, 28, 32)) {
            assertEquals(SeedPhraseStatus.VALID, validator.status(canonical(bytes)), "entropy=$bytes")
        }
    }

    @Test
    fun `real words in the wrong order fail the checksum, not the wordlist`() {
        // Every word is on the list; only the checksum can catch that the phrase is wrong.
        val words = List(12) { "abandon" } // 11×abandon + "about" is the valid vector; all-abandon is not
        assertTrue(words.all { it in wordlist })
        assertEquals(SeedPhraseStatus.CHECKSUM, validator.status(words))
    }

    @Test
    fun `suggestions offer wordlist matches for a prefix, capped`() {
        val hits = validator.suggestions("spo", limit = 6)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.startsWith("spo") && it in wordlist })
        assertTrue(hits.size <= 6)
    }

    @Test
    fun `four letters always decide a single word`() {
        // The BIP-39 English list is built so no two words share their first four letters. This is
        // what lets the entry field commit on the fourth keystroke; pin it against the shipped asset.
        val fourPlus = wordlist.filter { it.length >= 4 }.map { it.take(4) }
        assertEquals(fourPlus.size, fourPlus.distinct().size)
        // And a concrete four-letter prefix yields exactly one candidate.
        assertEquals(1, validator.suggestions("aban", limit = 6).size)
    }
}
