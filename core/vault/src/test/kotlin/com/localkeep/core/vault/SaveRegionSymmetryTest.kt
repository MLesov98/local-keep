package com.localkeep.core.vault

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
 * The container must not say whether a decoy is configured.
 *
 * The size oracle was closed by applying [VaultFileCodec.SAVE_MIN_PLAINTEXT_BUCKET] to every save.
 * This is the differential that survived it: with a decoy configured every save went through
 * `replaceRegion`, which freezes the header and rewrites one region, while a vault with no decoy
 * rebuilt the whole container and drew fresh nonces and fresh filler each time. Two generations of
 * `vault.enc` -- and two always exist, because `AtomicVaultWriter` keeps `vault.enc.bak` and
 * `RollingBackupManager` keeps five snapshots -- therefore disclosed that a decoy existed.
 *
 * The invariant these tests pin: **an ordinary save rewrites exactly one payload region and leaves
 * every other byte of the file identical, in every configuration.**
 */
class SaveRegionSymmetryTest {
    private val dek = EnvelopeCrypto.generateDek()
    private val encKey = VaultSubkeys.encKey(dek)
    private val decoyDek = EnvelopeCrypto.generateDek()
    private val decoyKey = VaultSubkeys.encKey(decoyDek)

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

    @AfterTest
    fun wipeKeys() {
        Zeroizer.wipe(dek)
        Zeroizer.wipe(encKey)
        Zeroizer.wipe(decoyDek)
        Zeroizer.wipe(decoyKey)
    }

    @Test
    fun `a save with no decoy freezes the header and the filler region`() {
        val first = noDecoyContainer()
        val second = VaultFileCodec.commitSave(first, vault("second"), encKey, isDuressSession = false)
        val third = VaultFileCodec.commitSave(second, vault("third"), encKey, isDuressSession = false)

        assertEquals(first.size, third.size, "an ordinary save must not resize the container")
        assertContentEquals(header(first), header(second), "the header must not change on a save")
        assertContentEquals(header(second), header(third), "the header must not change on a save")
        assertContentEquals(region(first, 1), region(second, 1), "the filler region must not change")
        assertContentEquals(region(second, 1), region(third, 1), "the filler region must not change")
        assertContentEquals(trailing(first), trailing(second), "the recovery block must be carried")
        assertFalse(
            region(first, 0).contentEquals(region(second, 0)),
            "the real region must actually change, or nothing was saved",
        )
    }

    @Test
    fun `a real-session save changes the same bytes with and without a decoy`() {
        val plain = noDecoyContainer()
        val duress = decoyContainer()

        val plainSaved = VaultFileCodec.commitSave(plain, vault("edited"), encKey, isDuressSession = false)
        val duressSaved = VaultFileCodec.commitSave(duress, vault("edited"), encKey, isDuressSession = false)

        assertEquals(
            changedRegions(plain, plainSaved),
            changedRegions(duress, duressSaved),
            "a real-session save must touch the same parts of the file either way",
        )
        assertEquals(setOf(0), changedRegions(plain, plainSaved))
    }

    @Test
    fun `a decoy-session save leaves the real region untouched`() {
        val duress = decoyContainer()

        val saved = VaultFileCodec.commitSave(duress, vault("decoy edit"), decoyKey, isDuressSession = true)

        assertEquals(setOf(1), changedRegions(duress, saved))
        val (_, real) = VaultFileCodec.decrypt(saved, encKey)
        assertEquals("real", real.items.single().title)
    }

    @Test
    fun `both regions still open after a real-session save on a duress container`() {
        val duress = decoyContainer()

        val saved = VaultFileCodec.commitSave(duress, vault("edited"), encKey, isDuressSession = false)

        assertEquals("edited", VaultFileCodec.decrypt(saved, encKey).second.items.single().title)
        assertEquals(
            "decoy",
            VaultFileCodec.decryptRegion(saved, decoyKey, regionIndex = 1).second.items.single().title,
        )
    }

    @Test
    fun `a save that outgrows the bucket rebuilds when there is no decoy`() {
        val container = noDecoyContainer()
        val big = oversizedVault()

        val saved = VaultFileCodec.commitSave(container, big, encKey, isDuressSession = false)

        assertTrue(saved.size > container.size, "the container must grow to fit a larger vault")
        assertEquals(big, VaultFileCodec.decrypt(saved, encKey).second)
    }

    @Test
    fun `a save that outgrows the bucket fails rather than orphaning a decoy`() {
        val container = decoyContainer()

        assertFailsWith<VaultBucketExhaustedException> {
            VaultFileCodec.commitSave(container, oversizedVault(), encKey, isDuressSession = false)
        }
    }

    @Test
    fun `a container with no recovery block gains a noise one on save`() {
        // Otherwise a vault written before recovery blocks existed keeps announcing its age by its
        // length for ever -- the rebuild-every-save path used to give it one on the next save.
        val legacy = withoutTrailingBlock(noDecoyContainer())

        val saved = VaultFileCodec.commitSave(legacy, vault("edited"), encKey, isDuressSession = false)

        assertEquals(legacy.size + VaultRecoveryBlock.ENCODED_SIZE, saved.size)
        assertEquals("edited", VaultFileCodec.decrypt(saved, encKey).second.items.single().title)
    }

    @Test
    fun `an ordinary save carries the recovery kit`() {
        val kit = RecoveryKit.generate(dek)
        val withKit = VaultFileCodec.withRecoveryBlock(noDecoyContainer(), kit.wrappedDek)

        val saved = VaultFileCodec.commitSave(withKit, vault("edited"), encKey, isDuressSession = false)

        assertContentEquals(trailing(withKit), trailing(saved))
    }

    /**
     * Configuring or removing a decoy rebuilds the **whole** container — both regions and the
     * header — and that is deliberate, not an oversight of the symmetry rule above.
     *
     * Pinned because nothing pinned it, and a reader who has just come through the seven tests
     * above would reasonably assume the one-region rule holds everywhere (F38's second half, sweep
     * `final-20260913-fable`). It does not, and it cannot for `setupDuress`: that write installs a
     * decoy under a second, independent key, re-buckets both regions to the larger of the two
     * payloads, and therefore needs both keys in hand — which is exactly the situation
     * `replaceRegion` exists because the app is normally *not* in.
     *
     * **The residual this states rather than hides.** A whole-container rebuild at an unchanged
     * file length happens on precisely two occasions: configuring a decoy and removing one. An
     * observer holding two generations of `vault.enc` — and two always exist, `vault.enc.bak` plus
     * the snapshot ring — therefore learns that the duress *configuration changed*, though not in
     * which direction, and learns nothing at all if they hold only one. That is strictly weaker
     * than the size oracle `SAVE_MIN_PLAINTEXT_BUCKET` closed and than the save differential
     * `commitSave` closed, and unlike those two it costs a deliberate act by the user at a moment
     * they have chosen, rather than every ordinary edit. `docs/THREAT-MODEL.md`
     * carries the same class of statement for the decoy-session save.
     *
     * Removal is the half that could in principle imitate an ordinary decoy-session save — splicing
     * fresh filler into region 1 alone would freeze the header, as
     * [VaultFileCodec.replaceRegionWithFiller] now makes possible for the stale-container sweep.
     * Recorded as a follow-up rather than changed here: it rewrites the live vault, which is not a
     * thing to alter as a side effect of a test that went missing.
     */
    @Test
    fun `configuring and removing a decoy both rebuild the whole container`() {
        val plain = noDecoyContainer()
        // What `VaultLockManager.setupDuress` writes.
        val configured = VaultFileCodec.encryptBothRegions(
            realVault = vault("real"),
            encKeyReal = encKey,
            decoyVault = vault("decoy"),
            encKeyDecoy = decoyKey,
            prevHeader = VaultHeaderCodec.decode(plain),
            recoveryBlock = VaultFileCodec.recoveryBlockOf(plain),
        )
        // What `VaultLockManager.removeDuress` writes.
        val removed = VaultFileCodec.encrypt(
            vault = vault("real"),
            encKey = encKey,
            prevHeader = VaultHeaderCodec.decode(configured),
            recoveryBlock = VaultFileCodec.recoveryBlockOf(configured),
        )

        assertFalse(
            header(plain).contentEquals(header(configured)),
            "configuring a decoy rebuilds the header — fresh nonces and a new slot 1",
        )
        assertFalse(
            header(configured).contentEquals(header(removed)),
            "removing a decoy rebuilds the header too",
        )
        assertEquals(
            setOf(0, 1),
            (0 until VaultHeader.NUM_REGIONS)
                .filterNot { region(plain, it).contentEquals(region(configured, it)) }
                .toSet(),
            "configuring a decoy rewrites both regions",
        )
        assertEquals(
            setOf(0, 1),
            (0 until VaultHeader.NUM_REGIONS)
                .filterNot { region(configured, it).contentEquals(region(removed, it)) }
                .toSet(),
            "removing a decoy rewrites both regions",
        )
        // And the state it leaves: the decoy is gone and region 1 is filler the real key opens,
        // which is what `isDuressConfigured` reads.
        assertTrue(VaultFileCodec.authenticatesRegion(removed, encKey, regionIndex = 1))
        assertFalse(VaultFileCodec.authenticatesRegion(removed, decoyKey, regionIndex = 1))
    }

    private fun noDecoyContainer(): ByteArray =
        VaultFileCodec.encrypt(vault("real"), encKey, initialHeader, VaultFileCodec.revokedRecoveryBlock())

    private fun decoyContainer(): ByteArray = VaultFileCodec.encryptBothRegions(
        realVault = vault("real"),
        encKeyReal = encKey,
        decoyVault = vault("decoy"),
        encKeyDecoy = decoyKey,
        prevHeader = initialHeader,
        recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
    )

    private fun header(fileBytes: ByteArray): ByteArray =
        fileBytes.copyOfRange(0, VaultHeaderCodec.ENCODED_SIZE)

    private fun region(fileBytes: ByteArray, index: Int): ByteArray {
        val r = VaultHeaderCodec.decode(fileBytes).regions[index]
        return fileBytes.copyOfRange(r.offset, r.offset + r.length)
    }

    private fun trailing(fileBytes: ByteArray): ByteArray {
        val r = VaultHeaderCodec.decode(fileBytes).regions[1]
        return fileBytes.copyOfRange(r.offset + r.length, fileBytes.size)
    }

    /** Which payload regions differ between two generations of the same container. */
    private fun changedRegions(before: ByteArray, after: ByteArray): Set<Int> {
        assertContentEquals(header(before), header(after), "the header must not change on a save")
        return (0 until VaultHeader.NUM_REGIONS)
            .filterNot { region(before, it).contentEquals(region(after, it)) }
            .toSet()
    }

    private fun withoutTrailingBlock(fileBytes: ByteArray): ByteArray {
        val r = VaultHeaderCodec.decode(fileBytes).regions[1]
        return fileBytes.copyOf(r.offset + r.length)
    }

    private fun vault(title: String): Vault {
        val timestamp = Instant.parse("2026-07-23T00:00:00Z")
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

    /** Comfortably past [VaultFileCodec.SAVE_MIN_PLAINTEXT_BUCKET], so the bucket has to grow. */
    private fun oversizedVault(): Vault {
        val timestamp = Instant.parse("2026-07-23T00:00:00Z")
        val body = "x".repeat(2048)
        return Vault(
            items = (0 until 800).map { index ->
                VaultItem(
                    id = UUID.nameUUIDFromBytes("item-$index".toByteArray()),
                    title = "item $index",
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    content = Note(body),
                )
            },
            createdAt = timestamp,
            modifiedAt = timestamp,
        )
    }
}
