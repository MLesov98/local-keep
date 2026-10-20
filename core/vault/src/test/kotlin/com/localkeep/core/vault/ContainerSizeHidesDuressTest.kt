package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.model.Login
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The size of `vault.enc` must say nothing about whether a decoy exists.
 *
 * It said everything. The 1 MiB padding floor was applied by `setupDuress` alone, so a vault with no
 * decoy bucketed to its own contents — about 16 KB for a small one — while configuring duress jumped
 * the file to roughly 2 MB and kept it there, because every later save goes through `replaceRegion`,
 * which preserves the bucket. For any real vault under 1 MiB, which at this project's measured
 * ~840 bytes/item is essentially all of them, **"the file is ~2 MB" meant "a decoy is configured"**:
 * one file, no differential, no timing, and visible without root in Android's own
 * Settings → Apps → Storage.
 *
 * The floor's purpose was never concealment — it is headroom, because regions are forced to equal
 * length and a decoy can only be re-bucketed by someone holding both keys. Applying it to one case
 * and not the other is what turned a headroom allowance into the tell it was meant to prevent.
 *
 * `MIN_PLAINTEXT_BUCKET` is deliberately *not* raised alongside it: that is the decode-side floor,
 * and every vault written before this change has a smaller bucket. Raising it would refuse to open
 * all of them. `VaultFormatVectorTest` pins it at 256 and must stay green.
 *
 * Found 2026-09-03.
 */
class ContainerSizeHidesDuressTest {

    private val now: Instant = Instant.parse("2026-09-04T09:00:00Z")

    private fun vault(items: Int, titlePrefix: String) = Vault(
        items = (1..items).map {
            VaultItem(
                id = UUID.randomUUID(),
                title = "$titlePrefix $it",
                createdAt = now,
                updatedAt = now,
                content = Login(username = "user$it", password = "correct horse battery staple $it"),
            )
        },
        folders = emptyList(),
        createdAt = now,
        modifiedAt = now,
    )

    private val dek = ByteArray(CryptoConstants.KEY_LEN) { 7 }

    private fun key(seed: Byte) = ByteArray(CryptoConstants.KEY_LEN) { seed }

    /** The same starting container `VaultFileCodecTest` builds. */
    private fun header() = VaultHeaderCodec.create(
        salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
        kdfParams = KdfParams.DEFAULT,
        wrappedDek = WrappedDek(
            nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
            ciphertext = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN),
        ),
        commitment = EnvelopeCrypto.slotCommitment(dek),
    )

    /** Plaintext bucket, read the way `VaultFileCodecTest` reads it: region length minus the tag. */
    private fun bucketOf(fileBytes: ByteArray): Int =
        VaultHeaderCodec.decode(fileBytes).regions[0].length - CryptoConstants.AEAD_TAG_LEN

    /**
     * Weaker than it looks, and labelled so nobody mistakes it for the guard.
     *
     * The original oracle did not live in this codec — `encryptBothRegions` has always taken the
     * floor as a parameter and defaulted it. It lived at the *call site*: `setupDuress` passed a
     * 1 MiB floor and every other save took the default. So this equality held before the fix too,
     * and reverting the fix leaves it green. What actually catches the defect is
     * [`both cases clear the save floor`], which pins the default itself.
     *
     * Kept because it states the property in the form the threat cares about, and it would catch a
     * future change that made the decoy branch size differently for some other reason.
     */
    @Test
    fun `a vault with a decoy is the same size as one without`() {
        val prev = header()
        val real = vault(20, "Real")

        val withoutDecoy = VaultFileCodec.encryptBothRegions(
            realVault = real,
            encKeyReal = key(1),
            decoyVault = null,
            encKeyDecoy = null,
            prevHeader = prev,
            recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
        )

        val withDecoy = VaultFileCodec.encryptBothRegions(
            realVault = real,
            encKeyReal = key(1),
            decoyVault = vault(8, "Decoy"),
            encKeyDecoy = key(2),
            prevHeader = prev,
            recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
        )

        assertEquals(
            withoutDecoy.size,
            withDecoy.size,
            "container size must not disclose that a decoy exists",
        )
    }

    /**
     * The real guard. Verified red by restoring the pre-fix default — saves padding only to
     * `MIN_PLAINTEXT_BUCKET` — which is exactly the state where a duress-configured vault stood out
     * at ~2 MB among ~16 KB ones.
     */
    @Test
    fun `both cases clear the save floor`() {
        // It would also hold if the floor were dropped to nothing on both sides, which would close
        // the oracle by removing the decoy's headroom — the thing the floor is actually for. The
        // assertion is >= the floor, not merely "equal to each other", for that reason.
        val encoded = VaultFileCodec.encryptBothRegions(
            realVault = vault(3, "Small"),
            encKeyReal = key(1),
            decoyVault = null,
            encKeyDecoy = null,
            prevHeader = header(),
            recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
        )

        assertTrue(
            bucketOf(encoded) >= VaultFileCodec.SAVE_MIN_PLAINTEXT_BUCKET,
            "a save with no decoy must still pad to the shared floor",
        )
    }

    @Test
    fun `the decode floor stays where it was, so old vaults still open`() {
        // Not a restatement of VaultFormatVectorTest's constant check: this one says *why* the two
        // floors differ. Collapsing them into one raised value is the obvious-looking tidy-up, and
        // it would refuse every vault written before 2026-09-04.
        assertEquals(256, VaultFileCodec.MIN_PLAINTEXT_BUCKET)
        assertTrue(VaultFileCodec.SAVE_MIN_PLAINTEXT_BUCKET > VaultFileCodec.MIN_PLAINTEXT_BUCKET)
    }

    @Test
    fun `a real vault larger than the floor still sizes to itself`() {
        // The floor is a minimum, not a fixed size: a big vault must not be truncated or padded to
        // a constant, or the file would stop growing and the decoy's headroom argument would break.
        // Deliberately past 1 MiB of serialized payload rather than merely "a lot of rows": at a
        // few dozen bytes each, four thousand small logins still fit inside the floor, and the
        // assertion below would then be testing nothing.
        val big = Vault(
            items = (1..2000).map {
                VaultItem(
                    id = UUID.randomUUID(),
                    title = "Bulk $it",
                    createdAt = now,
                    updatedAt = now,
                    content = Login(username = "user$it", password = "x".repeat(600)),
                )
            },
            folders = emptyList(),
            createdAt = now,
            modifiedAt = now,
        )
        val encoded = VaultFileCodec.encryptBothRegions(
            realVault = big,
            encKeyReal = key(1),
            decoyVault = null,
            encKeyDecoy = null,
            prevHeader = header(),
            recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
        )

        assertTrue(
            bucketOf(encoded) > VaultFileCodec.SAVE_MIN_PLAINTEXT_BUCKET,
            "a vault past the floor must bucket to its own size",
        )
    }
}
