package com.localkeep.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import com.localkeep.core.vault.VaultLockManager
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [VaultImporter.readVault] reports when the file's vault had a Recovery Kit that cannot open the
 * vault it is being imported into.
 *
 * A `.vault` import writes the file's items into *this* vault, under this vault's key; the file's
 * own key and its kit block stay behind. So a kit printed for the file's vault opens nothing here —
 * which is exactly the move-to-a-new-phone case in device report C3 finding 2, where the restore
 * said "Import complete" and the old kit's words were later refused.
 *
 * Driven through the real container and the real crypto, the way the sibling
 * `VaultImporterTest` cases are, because the two facts this rests on — "the file carries a kit
 * block" and "the file's key is not this session's key" — are both properties of real bytes.
 */
class ImportVoidsRecoveryKitTest {

    private val password = "file password"

    private fun container(dek: ByteArray, withKit: Boolean): ByteArray {
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek(password.toCharArray(), salt, kdfParams)
        val header = VaultHeaderCodec.create(
            salt,
            kdfParams,
            EnvelopeCrypto.wrapDek(kek, dek),
            EnvelopeCrypto.slotCommitment(dek),
        )
        val vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH)
        val bytes = VaultFileCodec.encrypt(
            vault,
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
        if (!withKit) return bytes
        // Any key will do for the wrap: what is under test is whether a kit block is present, and
        // no code path here ever tries to unwrap it.
        val recoveryKek = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
        return VaultFileCodec.withRecoveryBlock(bytes, EnvelopeCrypto.wrapDek(recoveryKek, dek))
    }

    private fun readWithSession(fileBytes: ByteArray, sessionDek: ByteArray): Boolean = runBlocking {
        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(fileBytes))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            VaultLockManager.SessionSnapshot(
                vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
                dek = sessionDek.copyOf(),
            )
        }
        val outcome = VaultImporter(context, mock(), lockManager).readVault(uri, password.toCharArray())
        assertIs<Outcome.Success<VaultImporter.ImportResult>>(outcome).value.voidsRecoveryKit
    }

    @Test
    fun `another vault's backup that carries a kit voids that kit here`() {
        val fileDek = EnvelopeCrypto.generateDek()
        val thisVaultsDek = EnvelopeCrypto.generateDek()

        assertEquals(true, readWithSession(container(fileDek, withKit = true), thisVaultsDek))
    }

    @Test
    fun `this vault's own backup leaves its kit working`() {
        // The control the whole flag turns on: a same-key import keeps the container, and so the
        // kit, exactly as they were.
        val dek = EnvelopeCrypto.generateDek()

        assertEquals(false, readWithSession(container(dek, withKit = true), dek))
    }

    @Test
    fun `a backup that never carried a kit does not claim one was lost`() {
        // An independent-passphrase export writes no kit block at all. Saying "your old Recovery
        // Kit no longer works" to someone whose file never had one is the false claim once made
        // on the password-change path.
        val fileDek = EnvelopeCrypto.generateDek()
        val thisVaultsDek = EnvelopeCrypto.generateDek()

        assertEquals(false, readWithSession(container(fileDek, withKit = false), thisVaultsDek))
    }
}
