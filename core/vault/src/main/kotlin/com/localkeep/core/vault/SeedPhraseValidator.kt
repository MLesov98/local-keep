package com.localkeep.core.vault

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import com.localkeep.core.crypto.Zeroizer
import java.util.Locale

/**
 * The states a Recovery-phrase grid can be in.
 *
 * [SeedPhraseValidator] — pure, JVM-only — returns only the first four. [VAULTKIT] is composed on
 * top by a caller that can reach key material: it means the entered words are *this vault's own*
 * Recovery Kit, proven by [VaultKitMatch] deriving the recovery KEK and unwrapping the vault's
 * recovery block. It ranks above [VALID] (a vault-kit phrase is checksum-valid too) and blocks the
 * save.
 */
enum class SeedPhraseStatus { INCOMPLETE, BADWORD, CHECKSUM, VALID, VAULTKIT }

/**
 * BIP-39 seed-phrase checks for the Recovery-phrase item type, done offline against the bundled
 * 2048-word list (`core/vault/src/main/assets/bip39-wordlist.txt`) — the same asset `RecoveryKit`
 * resolves against.
 *
 * Two distinct checks, in this order, because they answer different questions and the comp's banner
 * says different things about each:
 *  - **membership** — is every entered word actually a BIP-39 word? A misspelling ("marbel") reads
 *    as correct in a plain note and is fatal on restore; here it is coral at its own slot the moment
 *    it is typed, before the phrase is even finished.
 *  - **checksum** — do real words in this order form a phrase whose last word encodes a valid
 *    checksum over the rest? Only this catches a swapped pair. Delegated to [MnemonicCode.toEntropy],
 *    which throws on a bad checksum for every standard length (12/15/18/21/24 words).
 *
 * Pure: it holds only a word set, so it unit-tests on the JVM. The Android adapter that reads the
 * asset into that set is a thin wrapper.
 */
class SeedPhraseValidator(wordlist: Set<String>) {
    private val words: Set<String> = wordlist
    private val sorted: List<String> = wordlist.sorted()

    fun isWord(word: String): Boolean =
        word.isNotBlank() && normalize(word) in words

    /** Indices of entered (non-blank) words that are not on the list. Blank slots are not "bad". */
    fun badIndices(words: List<String>): List<Int> =
        words.indices.filter { words[it].isNotBlank() && !isWord(words[it]) }

    /** Wordlist matches for a typed prefix, alphabetical, capped. Empty for a blank draft. */
    fun suggestions(draft: String, limit: Int = 6): List<String> {
        val prefix = normalize(draft)
        if (prefix.isEmpty()) return emptyList()
        return sorted.asSequence().filter { it.startsWith(prefix) }.take(limit).toList()
    }

    fun status(entries: List<String>): SeedPhraseStatus {
        if (badIndices(entries).isNotEmpty()) return SeedPhraseStatus.BADWORD
        if (entries.any { it.isBlank() }) return SeedPhraseStatus.INCOMPLETE
        return if (checksumValid(entries)) SeedPhraseStatus.VALID else SeedPhraseStatus.CHECKSUM
    }

    private fun checksumValid(entries: List<String>): Boolean {
        val joined = entries.joinToString(" ") { normalize(it) }
        return try {
            val code = MnemonicCode(joined)
            try {
                val entropy = code.toEntropy()
                Zeroizer.wipe(entropy)
                true
            } finally {
                code.clear()
            }
        } catch (_: Exception) {
            // Any BIP-39 objection — bad checksum, wrong word count, unknown word — is "not valid".
            // Unknown words are already caught as BADWORD above, so reaching here means checksum.
            false
        }
    }

    private fun normalize(word: String): String = word.trim().lowercase(Locale.ROOT)
}
