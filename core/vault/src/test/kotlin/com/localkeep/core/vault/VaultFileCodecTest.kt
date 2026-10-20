package com.localkeep.core.vault

import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultFileCodecTest {
    private val dek = EnvelopeCrypto.generateDek()
    private val encKey = VaultSubkeys.encKey(dek)
    private val initialHeader = VaultHeaderCodec.create(
        salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
        kdfParams = KdfParams.DEFAULT,
        wrappedDek = WrappedDek(
            nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
            ciphertext = SecureRandomProvider.nextBytes(
                CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
            ),
        ),
        commitment = EnvelopeCrypto.slotCommitment(dek),
    )

    /** A container with no Recovery Kit, which is the starting state for most of these tests. */
    private fun noKit(): ByteArray = VaultFileCodec.revokedRecoveryBlock()

    @AfterTest
    fun wipeKeys() {
        Zeroizer.wipe(dek)
        Zeroizer.wipe(encKey)
    }

    @Test
    fun `a recovery kit survives an ordinary save`() {
        // The regression this guards is the worst one available: the save path rebuilds the whole
        // container, so a rewrite that forgot to carry the block would revoke the user's Recovery
        // Kit silently, on a save as ordinary as editing a note. Nothing would look wrong until
        // the day the kit was needed.
        val kit = RecoveryKit.generate(dek)
        val withKit = VaultFileCodec.withRecoveryBlock(
            VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit()),
            kit.wrappedDek,
        )

        val resaved = VaultFileCodec.encrypt(
            sampleVault(),
            encKey,
            VaultHeaderCodec.decode(withKit),
            VaultFileCodec.recoveryBlockOf(withKit),
        )

        assertEquals(kit.wrappedDek, VaultFileCodec.recoveryWrappedDek(resaved))
    }

    @Test
    fun `installing a kit leaves every byte of both payloads alone`() {
        // Why the block is appended rather than added to the header: it must be installable while
        // a duress vault is configured, and resealing region 1 would need the duress password.
        val original = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val regionsEnd = VaultHeaderCodec.decode(original).let {
            it.regions[1].offset + it.regions[1].length
        }

        val withKit = VaultFileCodec.withRecoveryBlock(original, RecoveryKit.generate(dek).wrappedDek)

        assertContentEquals(
            original.copyOfRange(0, regionsEnd),
            withKit.copyOfRange(0, regionsEnd),
            "installing a Recovery Kit rewrote the header or a payload region",
        )
    }

    @Test
    fun `a container written before recovery blocks still decrypts`() {
        // The migration case. Truncating the block is exactly what a pre-existing vault file looks
        // like: regions that end at EOF. It must open, and report no kit rather than throwing.
        val withKit = VaultFileCodec.withRecoveryBlock(
            VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit()),
            RecoveryKit.generate(dek).wrappedDek,
        )
        val regionsEnd = VaultHeaderCodec.decode(withKit).let {
            it.regions[1].offset + it.regions[1].length
        }
        val legacy = withKit.copyOfRange(0, regionsEnd)

        assertEquals(sampleVault(), VaultFileCodec.decrypt(legacy, encKey).second)
        assertNull(VaultFileCodec.recoveryWrappedDek(legacy))
    }

    @Test
    fun `a vault with no kit is the same size as one with a kit`() {
        // Noise, not absence. If the file's length or shape said whether a Recovery Kit exists,
        // it would be answering a question about the user to anyone holding the file.
        val without = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val with = VaultFileCodec.withRecoveryBlock(without, RecoveryKit.generate(dek).wrappedDek)

        assertEquals(without.size, with.size)
        assertNull(VaultFileCodec.recoveryWrappedDek(without))
    }

    @Test
    fun `a rotated data key leaves no recovery block the old kit can open`() {
        // What a master-password change relies on. Re-wrapping the same key under a new password
        // leaves the kit's copy of that key untouched, so the old 24 words go on opening the vault.
        val kit = RecoveryKit.generate(dek)
        val withKit = VaultFileCodec.withRecoveryBlock(
            VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit()),
            kit.wrappedDek,
        )
        val newDek = EnvelopeCrypto.generateDek()
        val newEncKey = VaultSubkeys.encKey(newDek)
        val oldRecoveryKek = RecoveryKit.deriveRecoveryKek(kit.mnemonic)
        try {
            // Both shapes a rotation writes: a full rebuild when no decoy is configured, and a
            // single-region rewrite when one is.
            val rebuilt = VaultFileCodec.encrypt(
                sampleVault(),
                newEncKey,
                VaultHeaderCodec.decode(withKit),
                VaultFileCodec.revokedRecoveryBlock(),
            )
            val spliced = VaultFileCodec.withoutRecoveryKit(
                VaultFileCodec.replaceRegion(withKit, sampleVault(), newEncKey, 0),
            )

            assertEquals(kit.wrappedDek, VaultFileCodec.recoveryWrappedDek(withKit))
            for (rotated in listOf(rebuilt, spliced)) {
                assertNull(VaultFileCodec.recoveryWrappedDek(rotated))
                assertEquals(
                    sampleVault(),
                    VaultFileCodec.decryptRegion(rotated, newEncKey, 0).second,
                )
            }

            // Splicing the old block back in buys nothing either: the words still unwrap their own
            // copy, and the key it yields no longer opens the payload.
            val retiredDek = EnvelopeCrypto.unwrapDek(oldRecoveryKek, kit.wrappedDek)
            val retiredEncKey = VaultSubkeys.encKey(retiredDek)
            try {
                assertFailsWith<AeadAuthException> {
                    VaultFileCodec.decryptRegion(rebuilt, retiredEncKey, 0)
                }
            } finally {
                Zeroizer.wipe(retiredDek)
                Zeroizer.wipe(retiredEncKey)
            }
        } finally {
            Zeroizer.wipe(newDek)
            Zeroizer.wipe(newEncKey)
            Zeroizer.wipe(oldRecoveryKek)
        }
    }

    @Test
    fun `create writes decryptable empty vault`() {
        val fileBytes = VaultFileCodec.create(
            dek = dek,
            salt = initialHeader.slots[0].salt,
            kdfParams = KdfParams.DEFAULT,
            wrappedDek = initialHeader.slots[0].wrappedDek,
            commitment = initialHeader.slots[0].commitment,
        )

        val (header, vault) = VaultFileCodec.decrypt(fileBytes, encKey)

        assertEquals(KdfParams.DEFAULT, header.slots[0].kdfParams)
        assertTrue(vault.items.isEmpty())
        assertTrue(vault.folders.isEmpty())
        assertEquals(vault.createdAt, vault.modifiedAt)
    }

    @Test
    fun `vault round-trip preserves model`() {
        val expected = sampleVault()
        val fileBytes = VaultFileCodec.encrypt(expected, encKey, initialHeader, noKit())

        val (_, actual) = VaultFileCodec.decrypt(fileBytes, encKey)

        assertEquals(expected, actual)
        assertIs<Note>(actual.items.single().content)
    }

    @Test
    fun `save rerandomizes unused slot and both region nonces`() {
        val firstBytes = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val first = VaultHeaderCodec.decode(firstBytes)
        val secondBytes = VaultFileCodec.encrypt(sampleVault(), encKey, first, noKit())
        val second = VaultHeaderCodec.decode(secondBytes)

        assertEquals(first.slots[0], second.slots[0])
        assertFalse(first.slots[1].salt.contentEquals(second.slots[1].salt))
        assertFalse(first.slots[1].wrappedDek.ciphertext.contentEquals(second.slots[1].wrappedDek.ciphertext))
        assertFalse(first.slots[1].commitment.contentEquals(second.slots[1].commitment))
        assertFalse(first.regions[0].nonce.contentEquals(second.regions[0].nonce))
        assertFalse(first.regions[1].nonce.contentEquals(second.regions[1].nonce))
    }

    @Test
    fun `structural header and payload tampering fail authentication`() {
        val fileBytes = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val headerTampered = fileBytes.copyOf().also {
            val regionZeroNonceOffset = VaultHeaderCodec.ENCODED_SIZE - 40
            it[regionZeroNonceOffset] = (it[regionZeroNonceOffset].toInt() xor 1).toByte()
        }
        val payloadTampered = fileBytes.copyOf().also {
            it[VaultHeaderCodec.ENCODED_SIZE + 5] =
                (it[VaultHeaderCodec.ENCODED_SIZE + 5].toInt() xor 1).toByte()
        }

        assertFailsWith<AeadAuthException> {
            VaultFileCodec.decrypt(headerTampered, encKey)
        }
        assertFailsWith<AeadAuthException> {
            VaultFileCodec.decrypt(payloadTampered, encKey)
        }
    }

    @Test
    fun `independently keyed real and decoy regions round-trip`() {
        val decoyDek = EnvelopeCrypto.generateDek()
        val decoyKey = VaultSubkeys.encKey(decoyDek)
        val decoyVault = sampleVault().copy(
            items = sampleVault().items.map { it.copy(title = "decoy") },
        )
        try {
            val fileBytes = VaultFileCodec.encryptBothRegions(
                recoveryBlock = noKit(),
                realVault = sampleVault(),
                encKeyReal = encKey,
                decoyVault = decoyVault,
                encKeyDecoy = decoyKey,
                prevHeader = initialHeader,
            )

            assertEquals(sampleVault(), VaultFileCodec.decryptRegion(fileBytes, encKey, 0).second)
            assertEquals(decoyVault, VaultFileCodec.decryptRegion(fileBytes, decoyKey, 1).second)
            assertFailsWith<AeadAuthException> {
                VaultFileCodec.decryptRegion(fileBytes, encKey, 1)
            }
        } finally {
            Zeroizer.wipe(decoyDek)
            Zeroizer.wipe(decoyKey)
        }
    }

    @Test
    fun `region authentication distinguishes random filler from independent decoy`() {
        val fillerFile = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val decoyDek = EnvelopeCrypto.generateDek()
        val decoyKey = VaultSubkeys.encKey(decoyDek)
        try {
            val decoyFile = VaultFileCodec.encryptBothRegions(
                recoveryBlock = noKit(),
                realVault = sampleVault(),
                encKeyReal = encKey,
                decoyVault = sampleVault().copy(items = emptyList()),
                encKeyDecoy = decoyKey,
                prevHeader = initialHeader,
            )

            assertTrue(VaultFileCodec.authenticatesRegion(fillerFile, encKey, 1))
            assertFalse(VaultFileCodec.authenticatesRegion(decoyFile, encKey, 1))
            assertTrue(VaultFileCodec.authenticatesRegion(decoyFile, decoyKey, 1))
        } finally {
            Zeroizer.wipe(decoyDek)
            Zeroizer.wipe(decoyKey)
        }
    }

    @Test
    fun `duress encryption honors requested shared bucket headroom`() {
        val decoyDek = EnvelopeCrypto.generateDek()
        val decoyKey = VaultSubkeys.encKey(decoyDek)
        try {
            val fileBytes = VaultFileCodec.encryptBothRegions(
                recoveryBlock = noKit(),
                realVault = sampleVault(),
                encKeyReal = encKey,
                decoyVault = sampleVault().copy(items = emptyList()),
                encKeyDecoy = decoyKey,
                prevHeader = initialHeader,
                minimumPlaintextBucketSize = 1 shl 20,
            )
            val header = VaultHeaderCodec.decode(fileBytes)

            assertEquals(
                (1 shl 20) + CryptoConstants.AEAD_TAG_LEN,
                header.regions[0].length,
            )
            assertEquals(header.regions[0].length, header.regions[1].length)
        } finally {
            Zeroizer.wipe(decoyDek)
            Zeroizer.wipe(decoyKey)
        }
    }

    @Test
    fun `single-region rewrite preserves independently keyed opposite ciphertext`() {
        val decoyDek = EnvelopeCrypto.generateDek()
        val decoyKey = VaultSubkeys.encKey(decoyDek)
        try {
            val original = VaultFileCodec.encryptBothRegions(
                recoveryBlock = noKit(),
                realVault = sampleVault(),
                encKeyReal = encKey,
                decoyVault = sampleVault().copy(items = emptyList()),
                encKeyDecoy = decoyKey,
                prevHeader = initialHeader,
            )
            val header = VaultHeaderCodec.decode(original)
            val originalRealCiphertext = original.copyOfRange(
                header.regions[0].offset,
                header.regions[0].offset + header.regions[0].length,
            )
            val updatedDecoy = sampleVault().copy(
                items = sampleVault().items.map { it.copy(title = "updated decoy") },
            )

            val rewritten = VaultFileCodec.replaceRegion(original, updatedDecoy, decoyKey, 1)
            val rewrittenHeader = VaultHeaderCodec.decode(rewritten)

            assertEquals(header, rewrittenHeader)
            assertEquals(
                originalRealCiphertext.toList(),
                rewritten.copyOfRange(
                    header.regions[0].offset,
                    header.regions[0].offset + header.regions[0].length,
                ).toList(),
            )
            assertEquals(
                updatedDecoy,
                VaultFileCodec.decryptRegion(rewritten, decoyKey, 1).second,
            )
            assertEquals(sampleVault(), VaultFileCodec.decryptRegion(rewritten, encKey, 0).second)
        } finally {
            Zeroizer.wipe(decoyDek)
            Zeroizer.wipe(decoyKey)
        }
    }

    @Test
    fun `slot-only rewrap leaves payload decryptable with unchanged DEK`() {
        val original = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val header = VaultHeaderCodec.decode(original)
        val replacementSlot = VaultHeaderCodec.create(
            salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
            kdfParams = header.slots[0].kdfParams,
            wrappedDek = WrappedDek(
                nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
                ciphertext = SecureRandomProvider.nextBytes(
                    CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
                ),
            ),
            commitment = SecureRandomProvider.nextBytes(UnlockSlot.COMMITMENT_SIZE),
        ).slots[0]
        val rewrappedHeader = header.copy(slots = listOf(replacementSlot, header.slots[1]))
        val rewrapped = original.copyOf().also {
            VaultHeaderCodec.encode(rewrappedHeader).copyInto(it)
        }

        assertEquals(sampleVault(), VaultFileCodec.decrypt(rewrapped, encKey).second)
    }

    @Test
    fun `wrong encryption key fails authentication`() {
        val fileBytes = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val wrongKey = VaultSubkeys.encKey(EnvelopeCrypto.generateDek())

        try {
            assertFailsWith<AeadAuthException> {
                VaultFileCodec.decrypt(fileBytes, wrongKey)
            }
        } finally {
            Zeroizer.wipe(wrongKey)
        }
    }

    @Test
    fun `shared bucket regions are equal contiguous and complete`() {
        val fileBytes = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val header = VaultHeaderCodec.decode(fileBytes)
        val real = header.regions[0]
        val filler = header.regions[1]
        val plaintextBucket = real.length - CryptoConstants.AEAD_TAG_LEN

        assertEquals(real.length, filler.length)
        assertEquals(VaultHeaderCodec.ENCODED_SIZE, real.offset)
        assertEquals(real.offset + real.length, filler.offset)
        // The regions still end contiguously; what follows them is the fixed-size recovery block,
        // which is not a region and is not covered by the payload AAD.
        assertEquals(fileBytes.size - VaultRecoveryBlock.ENCODED_SIZE, filler.offset + filler.length)
        assertTrue(plaintextBucket >= VaultFileCodec.MIN_PLAINTEXT_BUCKET)
        assertEquals(0, plaintextBucket and (plaintextBucket - 1))
    }

    @Test
    fun `malformed region topology and trailing data are rejected before decrypt`() {
        val fileBytes = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val header = VaultHeaderCodec.decode(fileBytes)
        val badHeader = header.copy(
            regions = listOf(
                header.regions[0].copy(offset = VaultHeaderCodec.ENCODED_SIZE + 1),
                header.regions[1],
            ),
        )
        val badFile = fileBytes.copyOf().also {
            VaultHeaderCodec.encode(badHeader).copyInto(it)
        }

        assertFailsWith<IllegalArgumentException> {
            VaultFileCodec.decrypt(badFile, encKey)
        }
        assertFailsWith<IllegalArgumentException> {
            VaultFileCodec.decrypt(fileBytes + 0, encKey)
        }
    }

    @Test
    fun `a recovery block is never read from an offset the region descriptors invented`() {
        // The descriptors below still sum to fileSize - ENCODED_SIZE and still declare equal region
        // lengths, so the offset arithmetic alone accepts them. What they actually point at is the
        // tail of region 1, and carrying those bytes forward as the recovery block would destroy
        // the user's kit on the next save.
        val withKit = VaultFileCodec.withRecoveryBlock(
            VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit()),
            RecoveryKit.generate(dek).wrappedDek,
        )
        val header = VaultHeaderCodec.decode(withKit)
        val shift = 64
        val slid = header.copy(
            regions = listOf(
                header.regions[0],
                header.regions[1].copy(offset = header.regions[1].offset - shift),
            ),
        )
        val badFile = withKit.copyOfRange(0, withKit.size - shift).also {
            VaultHeaderCodec.encode(slid).copyInto(it)
        }

        assertEquals(
            VaultRecoveryBlock.ENCODED_SIZE,
            badFile.size - (slid.regions[1].offset + slid.regions[1].length),
        )
        assertFailsWith<IllegalArgumentException> { VaultFileCodec.recoveryBlockOf(badFile) }
        assertFailsWith<IllegalArgumentException> { VaultFileCodec.recoveryWrappedDek(badFile) }
    }

    @Test
    fun `region descriptors out of range yield no recovery block offset`() {
        val fileBytes = VaultFileCodec.encrypt(sampleVault(), encKey, initialHeader, noKit())
        val header = VaultHeaderCodec.decode(fileBytes)
        val regionsEnd = fileBytes.size - VaultRecoveryBlock.ENCODED_SIZE

        assertEquals(regionsEnd, VaultFileCodec.recoveryBlockOffset(header, fileBytes.size))
        listOf(
            header.regions[1].copy(offset = Int.MAX_VALUE, length = Int.MAX_VALUE),
            header.regions[1].copy(offset = Int.MAX_VALUE, length = regionsEnd),
            header.regions[1].copy(offset = regionsEnd, length = Int.MAX_VALUE),
        ).forEach { descriptor ->
            assertEquals(
                -1,
                VaultFileCodec.recoveryBlockOffset(
                    header.copy(regions = listOf(header.regions[0], descriptor)),
                    fileBytes.size,
                ),
                "a region descriptor past the end of the file produced a recovery block offset",
            )
        }
    }

    @Test
    fun `large vault crosses fixed buckets and round-trips`() {
        val timestamp = Instant.EPOCH
        val vault = Vault(
            items = (1..2_000).map { index ->
                VaultItem(
                    id = UUID.nameUUIDFromBytes("item-$index".toByteArray()),
                    title = "item-$index",
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    content = Login(username = "user-$index", password = "password-$index"),
                )
            },
            createdAt = timestamp,
            modifiedAt = timestamp,
        )

        val fileBytes = VaultFileCodec.encrypt(vault, encKey, initialHeader, noKit())
        val (_, decoded) = VaultFileCodec.decrypt(fileBytes, encKey)

        assertEquals(2_000, decoded.items.size)
        assertEquals(vault, decoded)
    }

    private fun sampleVault(): Vault {
        val timestamp = Instant.parse("2026-07-23T00:00:00Z")
        return Vault(
            items = listOf(
                VaultItem(
                    id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
                    title = "sample",
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    content = Note("secret note"),
                ),
            ),
            createdAt = timestamp,
            modifiedAt = timestamp,
        )
    }
}
