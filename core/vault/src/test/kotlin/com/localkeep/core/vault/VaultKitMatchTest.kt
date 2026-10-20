package com.localkeep.core.vault

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import com.localkeep.core.crypto.EnvelopeCrypto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `vaultkit` refusal on the Recovery-phrase screen: entering this vault's own Recovery Kit words
 * is caught because they unwrap the vault's recovery block, not because they are stored anywhere to
 * compare against. See [VaultKitMatch].
 */
class VaultKitMatchTest {

    private fun kit(): Pair<List<String>, com.localkeep.core.crypto.WrappedDek> {
        val dek = EnvelopeCrypto.generateDek()
        val data = RecoveryKit.generate(dek)
        return data.mnemonic.split(" ") to data.wrappedDek
    }

    @Test
    fun `this vault's own kit words are recognised`() {
        val (words, wrappedDek) = kit()
        assertTrue(VaultKitMatch.isVaultRecoveryKit(words, wrappedDek))
    }

    @Test
    fun `capitalisation does not hide the kit`() {
        // The screen lowercases input, but the check must not depend on that: deriveRecoveryKek
        // canonicalises, so upper-cased kit words still unwrap.
        val (words, wrappedDek) = kit()
        assertTrue(VaultKitMatch.isVaultRecoveryKit(words.map { it.uppercase() }, wrappedDek))
    }

    @Test
    fun `an unrelated but valid 24-word phrase is not the kit`() {
        val (_, wrappedDek) = kit()
        // A different, checksum-valid 24-word phrase (fixed entropy, so it cannot equal the random
        // kit above). It is a genuine seed — just not the one that opens this vault.
        val other = MnemonicCode(ByteArray(32) { 7 }).chars.concatToString().split(" ")
        assertFalse(VaultKitMatch.isVaultRecoveryKit(other, wrappedDek))
    }

    @Test
    fun `a 12-word phrase is never the kit`() {
        val (_, wrappedDek) = kit()
        val twelve = MnemonicCode(ByteArray(16)).chars.concatToString().split(" ")
        assertFalse(VaultKitMatch.isVaultRecoveryKit(twelve, wrappedDek))
    }

    @Test
    fun `a vault with no recovery block never refuses`() {
        val (words, _) = kit()
        assertFalse(VaultKitMatch.isVaultRecoveryKit(words, null))
    }
}
