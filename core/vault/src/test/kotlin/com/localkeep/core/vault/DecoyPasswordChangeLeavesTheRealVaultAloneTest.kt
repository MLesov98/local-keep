package com.localkeep.core.vault

import com.localkeep.core.crypto.AeadAuthException
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VersionedHeader
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Changing the master password **inside a decoy session** must not reach the real vault.
 *
 * Settings withheld four SECURITY rows in a decoy session, which is how a coercer could tell the
 * two sessions apart by scrolling (release sweep 2026-09-20, 3A-F23). Change-master-password is
 * back, and in a decoy session it acts on the decoy — [VaultLockManager.changeDuressPassword], whose
 * byte-level step is [DuressSlotRewrap]. This is the adversarial half of that change: it does not
 * check that the decoy password changed, it checks that **nothing else did**.
 *
 * Written against the bytes rather than the manager because the manager needs a `Context`, a live
 * lock state and an Argon2id run, and this module's unit tests run on a plain JVM. What is at stake
 * is a property of the container, so the container is what is asserted on — and the assertions are
 * negative by construction: an equality on a whole byte range, and a search of the whole file for a
 * string only the real vault contains.
 */
class DecoyPasswordChangeLeavesTheRealVaultAloneTest {

    // Byte ranges of the header's two unlock slots. `VaultHeaderCodec` keeps its layout private,
    // so these are restated here and then checked against the one size it does publish — see
    // `the header layout is what this test assumes`. A slot is salt(16) + KDF params(12) +
    // nonce(12) + wrapped DEK(48) + commitment(32); a region descriptor is nonce(12) + two ints.
    private val slotSize = 120
    private val regionSize = 20
    private val slotsOffset = VersionedHeader.ENCODED_SIZE + 2
    private val slotZero = slotsOffset until slotsOffset + slotSize
    private val slotOne = slotsOffset + slotSize until slotsOffset + 2 * slotSize

    @Test
    fun `the header layout is what this test assumes`() {
        assertEquals(
            VaultHeaderCodec.ENCODED_SIZE,
            slotsOffset + VaultHeader.NUM_SLOTS * slotSize + VaultHeader.NUM_REGIONS * regionSize,
            "the header layout moved; every byte range in this test is now pointing at the wrong " +
                "part of the container, which would make its negative assertions meaningless",
        )
    }

    /**
     * A string that exists only in the real vault. The decoy is built from a disjoint vocabulary,
     * so finding these bytes anywhere in the container after the rewrap means the real payload has
     * been decrypted, moved or re-sealed — any of which is the failure this test exists for.
     */
    private val realOnlyMarker = "REAL-VAULT-ONLY-MARKER-ee6f1d2c"

    private val realDek = EnvelopeCrypto.generateDek()
    private val decoyDek = EnvelopeCrypto.generateDek()
    private val realEncKey = VaultSubkeys.encKey(realDek)
    private val decoyEncKey = VaultSubkeys.encKey(decoyDek)

    private val oldDuressKek = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
    private val newDuressKek = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)

    /** A container with a real vault in region 0, a decoy in region 1, and a Recovery Kit. */
    private fun container(): ByteArray {
        val realSalt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val base = VaultHeaderCodec.create(
            salt = realSalt,
            kdfParams = KdfParams.DEFAULT,
            wrappedDek = WrappedDek(
                nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
                ciphertext = SecureRandomProvider.nextBytes(
                    CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
                ),
            ),
            commitment = EnvelopeCrypto.slotCommitment(realDek),
        )
        val withDuress = base.copy(
            slots = listOf(
                base.slots[0],
                UnlockSlot(
                    salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
                    kdfParams = KdfParams.DEFAULT,
                    wrappedDek = EnvelopeCrypto.wrapDek(oldDuressKek, decoyDek),
                    commitment = EnvelopeCrypto.slotCommitment(decoyDek),
                ),
            ),
        )
        return VaultFileCodec.encryptBothRegions(
            realVault = vaultOf(realOnlyMarker),
            encKeyReal = realEncKey,
            decoyVault = vaultOf("ordinary decoy note"),
            encKeyDecoy = decoyEncKey,
            prevHeader = withDuress,
            // Nothing to carry forward on a fresh container, and a noise block is what "no kit"
            // is — it is asserted on all the same, because a rewrap that dropped or rewrote the
            // trailing block would be exactly the kind of collateral damage this test is for.
            recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
        )
    }

    private fun rewrapped(before: ByteArray): ByteArray = DuressSlotRewrap.rewrap(
        fileBytes = before,
        newSalt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
        newKek = newDuressKek,
        decoyDek = decoyDek,
    )

    @Test
    fun `the fixture is what this test believes it is`() {
        // The control. Every assertion below is a negative, and negatives pass on a broken fixture:
        // a container whose region 0 never held the marker would satisfy "the marker is absent"
        // without the production code doing anything right.
        val before = container()
        assertTrue(
            realOnlyMarker in VaultFileCodec.decryptRegion(before, realEncKey, 0).second.render(),
            "the real region must contain the marker before the rewrap, or the leak assertions " +
                "below cannot fail",
        )
        assertFalse(
            realOnlyMarker in VaultFileCodec.decryptRegion(before, decoyEncKey, 1).second.render(),
            "the decoy must not already contain the marker",
        )
    }

    @Test
    fun `nothing outside the duress slot moves`() {
        val before = container()
        val after = rewrapped(before)

        assertEquals(before.size, after.size, "the container changed size, which is visible on disk")
        val movedOutsideSlotOne = before.indices.filter { it !in slotOne && before[it] != after[it] }
        assertTrue(
            movedOutsideSlotOne.isEmpty(),
            "a decoy password change altered ${movedOutsideSlotOne.size} byte(s) outside the " +
                "duress slot, at offsets ${movedOutsideSlotOne.take(16)} — the real slot, both " +
                "payload regions and the recovery block must all be carried across untouched",
        )
    }

    @Test
    fun `the real unlock slot is byte-identical`() {
        val before = container()
        val after = rewrapped(before)
        assertContentEquals(
            before.copyOfRange(slotZero.first, slotZero.last + 1),
            after.copyOfRange(slotZero.first, slotZero.last + 1),
            "slot 0 is the real vault's only door and a decoy session must not touch it",
        )
    }

    @Test
    fun `the real vault still opens, unchanged`() {
        val before = container()
        val after = rewrapped(before)
        assertEquals(
            VaultFileCodec.decryptRegion(before, realEncKey, 0).second,
            VaultFileCodec.decryptRegion(after, realEncKey, 0).second,
            "the real vault did not survive a decoy-side password change intact",
        )
    }

    @Test
    fun `no real-vault string reaches the decoy, or the file in the clear`() {
        val after = rewrapped(container())

        assertFalse(
            realOnlyMarker in VaultFileCodec.decryptRegion(after, decoyEncKey, 1).second.render(),
            "a string that exists only in the real vault appeared in the decoy after a decoy " +
                "session changed its password",
        )
        assertFalse(
            String(after, Charsets.ISO_8859_1).contains(realOnlyMarker),
            "a string that exists only in the real vault appears in the container in the clear",
        )
    }

    @Test
    fun `the old duress password stops opening the decoy and the new one starts`() {
        // Not the point of this test, but the control for every assertion above: a rewrap that did
        // nothing at all would satisfy "nothing outside slot 1 moved" perfectly.
        val after = rewrapped(container())
        val slot = VaultHeaderCodec.decode(after).slots[1]

        assertFailsWith<AeadAuthException>(
            "the retired duress password still unwraps the decoy key",
        ) {
            EnvelopeCrypto.unwrapDek(oldDuressKek, slot.wrappedDek)
        }
        assertContentEquals(
            decoyDek,
            EnvelopeCrypto.unwrapDek(newDuressKek, slot.wrappedDek),
            "the new duress password does not unwrap the decoy key",
        )
        assertTrue(
            EnvelopeCrypto.slotCommitment(decoyDek).contentEquals(slot.commitment),
            "the commitment must still bind the unrotated decoy key",
        )
    }

    private fun vaultOf(noteBody: String): Vault {
        val timestamp = Instant.parse("2026-09-21T00:00:00Z")
        return Vault(
            items = listOf(
                VaultItem(
                    id = UUID.nameUUIDFromBytes(noteBody.toByteArray()),
                    title = noteBody,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    content = Note(noteBody),
                ),
            ),
            createdAt = timestamp,
            modifiedAt = timestamp,
        )
    }

    /** Everything textual in a vault, flattened, so a leak cannot hide in a field this test forgot. */
    private fun Vault.render(): String = items.joinToString("|") { "${it.title}|${it.content}" }
}
