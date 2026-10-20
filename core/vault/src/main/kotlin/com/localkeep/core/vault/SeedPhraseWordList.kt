package com.localkeep.core.vault

import cash.z.ecc.android.bip39.Mnemonics

/**
 * The BIP-39 English wordlist for the Recovery-phrase item type, and a validator seeded from it.
 *
 * Taken from [Mnemonics.getCachedWords] — the list compiled into kotlin-bip39, the *same* list
 * [cash.z.ecc.android.bip39.Mnemonics.MnemonicCode.toEntropy] validates a phrase against. Reading it
 * from here rather than from the bundled `bip39-wordlist.txt` asset means membership (this list) and
 * checksum (the library) can never disagree, and it needs no Android `Context`. The asset stays as
 * the test-pinned copy `RecoveryKitTest` compares the two against, exactly so a future divergence
 * would be caught.
 *
 * [validator] holds only immutable data, so the single lazy instance is safe to share across the
 * editor's recompositions.
 */
object SeedPhraseWordList {
    val english: Set<String> by lazy { Mnemonics.getCachedWords(Mnemonics.DEFAULT_LANGUAGE_CODE).toSet() }

    val validator: SeedPhraseValidator by lazy { SeedPhraseValidator(english) }
}
