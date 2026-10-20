package com.localkeep.core.vault

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.Zeroizer
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryKitTest {
    @Test
    fun `bundled English wordlist is the complete BIP39 list`() {
        val words = File("src/main/assets/bip39-wordlist.txt").readLines()

        assertEquals(2_048, words.size)
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
        assertEquals(words.size, words.distinct().size)
    }

    @Test
    fun `every word the recovery path can emit comes from that bundled list`() {
        // The test above pins an asset that no production code opens: `RecoveryKit` resolves words
        // through `MnemonicCode`, whose own list is compiled into the library artifact. So the two
        // lists could diverge — on a version bump, or on a library that quietly shipped a different
        // locale — and every assertion here would still have passed while a kit written by one
        // version failed to unwrap under the next. This is what ties them together.
        val allowed = File("src/main/assets/bip39-wordlist.txt").readLines().toSet()
        // Seeded, so a failure is reproducible rather than a flake somebody re-runs away.
        val random = java.util.Random(20260728)
        val seen = mutableSetOf<String>()
        repeat(300) {
            val entropy = ByteArray(32).also(random::nextBytes)
            val code = MnemonicCode(entropy)
            try {
                code.chars.concatToString().split(" ").forEach { word ->
                    assertTrue(word in allowed, "MnemonicCode emitted \"$word\", absent from the bundled list")
                    seen += word
                }
            } finally {
                code.clear()
            }
        }
        // A sanity floor on the sample itself: 300 × 24 draws that only ever hit a handful of words
        // would mean this test was checking almost nothing.
        assertTrue(seen.size > 1_500, "only ${seen.size} distinct words drawn — sample too narrow to mean anything")
    }

    @Test
    fun `official zero-entropy vector produces expected 24 words`() {
        val code = MnemonicCode(ByteArray(32))
        try {
            assertEquals(
                List(23) { "abandon" }.plus("art").joinToString(" "),
                code.chars.concatToString(),
            )
        } finally {
            code.clear()
        }
    }

    @Test
    fun `generated recovery kit has 24 words and unwraps exact DEK`() {
        val dek = EnvelopeCrypto.generateDek()
        var kek: ByteArray? = null
        var unwrapped: ByteArray? = null
        try {
            val kit = RecoveryKit.generate(dek)
            assertEquals(24, kit.mnemonic.split(" ").size)
            kek = RecoveryKit.deriveRecoveryKek(kit.mnemonic)
            unwrapped = EnvelopeCrypto.unwrapDek(kek, kit.wrappedDek)
            assertTrue(dek.contentEquals(unwrapped))
        } finally {
            Zeroizer.wipe(dek)
            Zeroizer.wipe(kek)
            Zeroizer.wipe(unwrapped)
        }
    }

    @Test
    fun `different valid mnemonic cannot unwrap recovery copy`() {
        val dek = EnvelopeCrypto.generateDek()
        val otherDek = EnvelopeCrypto.generateDek()
        var wrongKek: ByteArray? = null
        try {
            val kit = RecoveryKit.generate(dek)
            val wrongKit = RecoveryKit.generate(otherDek)
            wrongKek = RecoveryKit.deriveRecoveryKek(wrongKit.mnemonic)

            assertFailsWith<AeadAuthException> {
                EnvelopeCrypto.unwrapDek(wrongKek, kit.wrappedDek)
            }
        } finally {
            Zeroizer.wipe(dek)
            Zeroizer.wipe(otherDek)
            Zeroizer.wipe(wrongKek)
        }
    }

    @Test
    fun `QR format validates and round-trips the words`() {
        val dek = EnvelopeCrypto.generateDek()
        try {
            val kit = RecoveryKit.generate(dek)

            assertEquals(
                kit.mnemonic,
                assertNotNull(RecoveryKit.parseQrCode(RecoveryKit.formatForQrCode(kit.mnemonic))),
            )
        } finally {
            Zeroizer.wipe(dek)
        }
    }

    @Test
    fun `the QR payload carries no key material`() {
        // It used to append the wrapped DEK, because unlocking needed both halves and the vault
        // file held neither. The file holds the wrapped key now, so putting a second copy on a
        // screen the user is about to photograph with another device buys nothing.
        val dek = EnvelopeCrypto.generateDek()
        try {
            val kit = RecoveryKit.generate(dek)
            val payload = RecoveryKit.formatForQrCode(kit.mnemonic)

            assertEquals(2, payload.lines().size, "expected a header line and the words: $payload")
            assertFalse(
                payload.contains(Base64.getEncoder().encodeToString(kit.wrappedDek.ciphertext)),
                "the QR payload still contains the wrapped data key",
            )
        } finally {
            Zeroizer.wipe(dek)
        }
    }

    @Test
    fun `invalid QR structure and checksum are rejected`() {
        val invalidChecksum = List(24) { "abandon" }.joinToString(" ")

        assertNull(RecoveryKit.parseQrCode("garbage"))
        assertNull(RecoveryKit.parseQrCode("LOCALKEEP-RECOVERY\n$invalidChecksum"))
        // A trailing third line is the old format. Rejected rather than tolerated: it would carry
        // a wrapped key against a vault whose key has since been rotated.
        assertNull(
            RecoveryKit.parseQrCode(
                "LOCALKEEP-RECOVERY\n" +
                    "${List(23) { "abandon" }.plus("art").joinToString(" ")}\nAA==",
            ),
        )
    }
}
