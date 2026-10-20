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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two facts `VaultLockManager.sessionAuthenticatesContainer` is built on.
 *
 * The first is the gap it exists to close: **an unlock slot can verify over a payload that will
 * never open again.** Slots live in the header and the payload regions are sealed independently, so
 * a password check says nothing whatever about the ciphertext. Every caller that goes on to decrypt
 * discovers that immediately; the master-password export, which *copies* the container instead of
 * reading it, did not — it re-authenticated the header and streamed a dead file out as "Exported
 * 4.5 MB" (fixed 2026-09-13).
 *
 * The second is why that check is addressed by session region rather than hard-coded to region 0:
 * **a decoy session cannot authenticate region 0, by construction.** It holds the decoy DEK, which
 * is independent of the real one. A region-0 check would therefore refuse every export made from a
 * decoy session while succeeding from a real one — a repeatable, observable difference between the
 * two, which is precisely the tell the duress feature exists not to produce.
 */
class SessionRegionAuthenticationTest {
    private val dek = EnvelopeCrypto.generateDek()
    private val encKey = VaultSubkeys.encKey(dek)
    private val decoyDek = EnvelopeCrypto.generateDek()
    private val decoyKey = VaultSubkeys.encKey(decoyDek)

    @AfterTest
    fun wipeKeys() {
        Zeroizer.wipe(dek)
        Zeroizer.wipe(encKey)
        Zeroizer.wipe(decoyDek)
        Zeroizer.wipe(decoyKey)
    }

    @Test
    fun `a damaged payload leaves the unlock slot verifying and the region not authenticating`() {
        val container = noDecoyContainer()
        val damaged = container.copyOf().also {
            val region = VaultHeaderCodec.decode(it).regions[0]
            it[region.offset] = (it[region.offset].toInt() xor 0x01).toByte()
        }

        // The credential check the export path relies on still passes over the damaged file.
        val header = VaultHeaderCodec.decode(damaged)
        var kek: ByteArray? = null
        var unwrapped: ByteArray? = null
        try {
            kek = EnvelopeCrypto.deriveKek(PASSWORD.copyOf(), header.slots[0].salt, header.slots[0].kdfParams)
            unwrapped = EnvelopeCrypto.unwrapDek(kek, header.slots[0].wrappedDek)
            assertTrue(
                unwrapped.contentEquals(dek),
                "the unlock slot must still verify — that is the gap this whole check exists for",
            )
        } finally {
            Zeroizer.wipe(kek)
            Zeroizer.wipe(unwrapped)
        }

        assertFalse(
            VaultFileCodec.authenticatesRegion(damaged, encKey, regionIndex = 0),
            "a payload with a flipped bit must not authenticate, or the export gate detects nothing",
        )
        assertTrue(
            VaultFileCodec.authenticatesRegion(container, encKey, regionIndex = 0),
            "the control: the undamaged container must authenticate, or the check is vacuous",
        )
    }

    @Test
    fun `a decoy session authenticates its own region and never the real one`() {
        val container = decoyContainer()

        assertFalse(
            VaultFileCodec.authenticatesRegion(container, decoyKey, regionIndex = 0),
            "a region-0 check run from a decoy session would refuse every time — the tell this " +
                "gate must not create",
        )
        assertTrue(
            VaultFileCodec.authenticatesRegion(container, decoyKey, regionIndex = 1),
            "the decoy session's own region must authenticate, or the gate refuses a healthy " +
                "duress export",
        )
        assertTrue(
            VaultFileCodec.authenticatesRegion(container, encKey, regionIndex = 0),
            "the control: the real session still authenticates region 0 of the same container",
        )
    }

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

    /** Slot 0 carries a real KEK-wrapped DEK, because the first test verifies against it. */
    private fun initialHeader(): VaultHeader {
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        var kek: ByteArray? = null
        val wrapped: WrappedDek
        try {
            kek = EnvelopeCrypto.deriveKek(PASSWORD.copyOf(), salt, KdfParams.FLOOR)
            wrapped = EnvelopeCrypto.wrapDek(kek, dek)
        } finally {
            Zeroizer.wipe(kek)
        }
        return VaultHeaderCodec.create(
            salt = salt,
            kdfParams = KdfParams.FLOOR,
            wrappedDek = wrapped,
            commitment = EnvelopeCrypto.slotCommitment(dek),
        )
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
        val PASSWORD = "correct horse battery staple".toCharArray()
    }
}
