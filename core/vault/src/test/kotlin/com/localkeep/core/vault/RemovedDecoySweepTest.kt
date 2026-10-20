package com.localkeep.core.vault

import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Turning the duress vault off must take the decoy out of every container on disk, not just one.
 *
 * `removeDuress` rewrites `vault.enc` — fresh filler in region 1, a random slot in slot 1 — and
 * that write moves the *previous* generation straight to `vault.enc.bak`, decoy and live duress
 * slot intact. `backups/` holds the newest five saves, each carrying the same pair. Nothing swept
 * them, so a decoy the user had just been told was removed went on opening under the retired duress
 * password, out of a file sitting beside the live vault, until five ordinary saves had pushed the
 * last copy out of the ring (fixed 2026-09-13).
 *
 * The threat this closes is narrow and worth stating exactly, because the finding's first reading
 * was wrong: the retired duress password opens nothing *through the app* — the slot scan stops at
 * the primary — and it never reaches the real vault, whose key is independent. What leaks is decoy
 * plaintext to someone who already holds the retired password and the files. The user's side of it
 * is the part that matters: they turned duress off, and a coercer who had been given that password
 * can still be shown a vault they were told no longer exists.
 */
class RemovedDecoySweepTest {
    private val dek = EnvelopeCrypto.generateDek()
    private val encKey = VaultSubkeys.encKey(dek)
    private val decoyDek = EnvelopeCrypto.generateDek()
    private val decoyKey = VaultSubkeys.encKey(decoyDek)
    private val dir: File = Files.createTempDirectory("removed-decoy-sweep").toFile()

    @AfterTest
    fun cleanUp() {
        Zeroizer.wipe(dek)
        Zeroizer.wipe(encKey)
        Zeroizer.wipe(decoyDek)
        Zeroizer.wipe(decoyKey)
        dir.deleteRecursively()
    }

    @Test
    fun `the decoy is unrecoverable from every stale container after the sweep`() {
        val bak = container("vault.enc.bak", decoyContainer())
        val snapshot = container("vault-20260913-000000.enc", decoyContainer())

        // Before: this is the state the bug left behind, asserted so a fix that changed nothing
        // could not read as a pass.
        for (file in listOf(bak, snapshot)) {
            assertEquals(
                "decoy",
                VaultFileCodec.decryptRegion(file.readBytes(), decoyKey, regionIndex = 1)
                    .second.items.single().title,
                "${file.name}: the fixture must actually hold a recoverable decoy",
            )
        }

        StaleContainerSweeper.sweepRemovedDecoy(listOf(bak, snapshot), encKey)

        for (file in listOf(bak, snapshot)) {
            val bytes = file.readBytes()
            assertFailsWith<AeadAuthException>("${file.name}: the decoy is still recoverable") {
                VaultFileCodec.decryptRegion(bytes, decoyKey, regionIndex = 1)
            }
            assertFalse(
                VaultFileCodec.authenticatesRegion(bytes, decoyKey, regionIndex = 1),
                "${file.name}: the decoy key must no longer authenticate region 1",
            )
            assertTrue(
                VaultFileCodec.authenticatesRegion(bytes, encKey, regionIndex = 1),
                "${file.name}: region 1 must now be filler under the real key — the same state " +
                    "`removeDuress` leaves the live vault in, which is what isDuressConfigured reads",
            )
        }
    }

    /**
     * The control that stops "delete the `.bak`" passing as a fix. A `.bak` is the rollback from a
     * corrupt live vault; the sweep must leave the real vault in it intact and readable.
     */
    @Test
    fun `the real vault in a stale container survives the sweep unchanged`() {
        val bak = container("vault.enc.bak", decoyContainer())
        val before = bak.readBytes()
        val region0 = VaultHeaderCodec.decode(before).regions[0]

        StaleContainerSweeper.sweepRemovedDecoy(listOf(bak), encKey)

        assertTrue(bak.isFile, "the sweep must never delete a container")
        val after = bak.readBytes()
        assertEquals(before.size, after.size, "the container must not be resized")
        assertContentEquals(
            before.copyOfRange(region0.offset, region0.offset + region0.length),
            after.copyOfRange(region0.offset, region0.offset + region0.length),
            "region 0 must come across byte for byte — only one of the two keys is in hand",
        )
        assertEquals(
            "real",
            VaultFileCodec.decrypt(after, encKey).second.items.single().title,
            "the real vault must still open out of the swept container",
        )
    }

    /** The retired duress password must not even unwrap a key any more. */
    @Test
    fun `the duress unlock slot is replaced along with the region`() {
        val bak = container("vault.enc.bak", decoyContainer())
        val beforeHeader = VaultHeaderCodec.decode(bak.readBytes())
        val beforeSlot = beforeHeader.slots[1]

        StaleContainerSweeper.sweepRemovedDecoy(listOf(bak), encKey)

        val afterHeader = VaultHeaderCodec.decode(bak.readBytes())
        assertEquals(beforeHeader.slots[0], afterHeader.slots[0], "slot 0 must be untouched")
        assertFalse(
            beforeSlot.wrappedDek.ciphertext.contentEquals(afterHeader.slots[1].wrappedDek.ciphertext),
            "the duress slot must be overwritten, or the retired password still unwraps the decoy DEK",
        )
        val kek = EnvelopeCrypto.deriveKek(
            DURESS_PASSWORD.copyOf(),
            afterHeader.slots[1].salt,
            afterHeader.slots[1].kdfParams,
        )
        try {
            assertFailsWith<AeadAuthException>("the retired duress password still unwraps a key") {
                EnvelopeCrypto.unwrapDek(kek, afterHeader.slots[1].wrappedDek)
            }
        } finally {
            Zeroizer.wipe(kek)
        }
    }

    /**
     * A container with no decoy is left strictly alone. Rewriting it would reseal region 1 under the
     * key and nonce it is already sealed with, for no gain — see
     * [VaultFileCodec.replaceRegionWithFiller].
     */
    @Test
    fun `a container that never had a decoy is not rewritten`() {
        val bak = container("vault.enc.bak", noDecoyContainer())
        val before = bak.readBytes()

        StaleContainerSweeper.sweepRemovedDecoy(listOf(bak), encKey)

        assertContentEquals(before, bak.readBytes(), "a decoy-free container must not be touched")
    }

    /** A container on some other key is skipped, never destroyed. */
    @Test
    fun `a container this key cannot open is left exactly as it was`() {
        val foreign = container("vault-19700101-000000.enc", SecureRandomProvider.nextBytes(4096))
        val before = foreign.readBytes()

        StaleContainerSweeper.sweepRemovedDecoy(listOf(foreign), encKey)

        assertTrue(foreign.isFile, "a container that cannot be swept must not be deleted")
        assertContentEquals(before, foreign.readBytes())
    }

    private fun container(name: String, bytes: ByteArray): File =
        File(dir, name).also { it.writeBytes(bytes) }

    private fun noDecoyContainer(): ByteArray =
        VaultFileCodec.encrypt(vault("real"), encKey, initialHeader(), VaultFileCodec.revokedRecoveryBlock())

    private fun decoyContainer(): ByteArray = VaultFileCodec.encryptBothRegions(
        realVault = vault("real"),
        encKeyReal = encKey,
        decoyVault = vault("decoy"),
        encKeyDecoy = decoyKey,
        prevHeader = initialHeader(),
        recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
    )

    /**
     * Slot 1 carries a real duress-password-wrapped decoy DEK, because one of the tests above
     * verifies that the retired password stops unwrapping it.
     */
    private fun initialHeader(): VaultHeader {
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val duressSalt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        var duressKek: ByteArray? = null
        val duressSlot: UnlockSlot
        try {
            duressKek = EnvelopeCrypto.deriveKek(DURESS_PASSWORD.copyOf(), duressSalt, KdfParams.FLOOR)
            duressSlot = UnlockSlot(
                salt = duressSalt,
                kdfParams = KdfParams.FLOOR,
                wrappedDek = EnvelopeCrypto.wrapDek(duressKek, decoyDek),
                commitment = EnvelopeCrypto.slotCommitment(decoyDek),
            )
        } finally {
            Zeroizer.wipe(duressKek)
        }
        val base = VaultHeaderCodec.create(
            salt = salt,
            kdfParams = KdfParams.FLOOR,
            wrappedDek = WrappedDek(
                nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
                ciphertext = SecureRandomProvider.nextBytes(
                    CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
                ),
            ),
            commitment = EnvelopeCrypto.slotCommitment(dek),
        )
        return base.copy(slots = listOf(base.slots[0], duressSlot))
    }

    private fun vault(title: String): Vault {
        val timestamp = Instant.parse("2026-09-13T00:00:00Z")
        return Vault(
            items = listOf(
                VaultItem(
                    id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    content = Note("secret note"),
                ),
            ),
            createdAt = timestamp,
            modifiedAt = timestamp,
        )
    }

    private companion object {
        /** Copied at each use: `deriveKek` consumes and wipes what it is handed. */
        val DURESS_PASSWORD = "a duress password".toCharArray()
    }
}
