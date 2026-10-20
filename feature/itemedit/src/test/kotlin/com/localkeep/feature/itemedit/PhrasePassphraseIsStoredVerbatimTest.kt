package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A recovery phrase's passphrase is stored exactly as typed.
 *
 * S3-04, 2026-10-02: the save trimmed the BIP-39 "25th word". A passphrase is arbitrary text and its
 * whitespace is part of it — a wallet derives a different seed for "my pass " than for "my pass" —
 * so a passphrase with a leading or trailing space was silently stored as a different secret, one
 * that restores an empty wallet. The words themselves are still normalised: BIP-39 defines them as
 * lowercase dictionary entries, and that is the control below.
 */
class PhrasePassphraseIsStoredVerbatimTest {

    /** The canonical BIP-39 vector: 11 x "abandon" + "about" is checksum-valid. */
    private fun phrase(passphrase: String, words: List<String> = List(11) { "abandon" } + "about") =
        ItemEditUiState(
            type = ItemEditUiState.ItemType.PHRASE,
            phraseWords = words,
            phrasePassphrase = passphrase,
        )

    @Test
    fun `edge whitespace in the passphrase is kept`() {
        assertEquals(" my pass ", phrase(" my pass ").toSeedPhrase().passphrase)
    }

    @Test
    fun `a pasted trailing newline is kept too`() {
        assertEquals("correct horse\n", phrase("correct horse\n").toSeedPhrase().passphrase)
    }

    /** The control, true before and after: the words and an untouched passphrase come through. */
    @Test
    fun `the words and a plain passphrase are stored as entered`() {
        val stored = phrase("plain").toSeedPhrase()
        assertEquals(List(11) { "abandon" } + "about", stored.words)
        assertEquals("plain", stored.passphrase)
    }
}
