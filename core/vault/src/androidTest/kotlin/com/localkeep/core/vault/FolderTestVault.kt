package com.localkeep.core.vault

import android.content.Context
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import java.io.File

/**
 * The fresh-vault-on-disk boilerplate the folder suites share.
 *
 * Four test classes in this wave each need an unlockable empty vault and nothing else, and the
 * setup is ~40 lines of crypto ceremony that says nothing about folders. Extracted so the suites
 * read as what they assert.
 */
internal object FolderTestVault {

    /** The floor `KdfParams` enforces — these suites are about folders, and pay the KDF once. */
    val kdfParams = KdfParams(memoryKib = 24_576, iterations = 3, parallelism = 2)

    fun password(): CharArray = "test-master-password-123".toCharArray()

    fun create(context: Context) {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, kdfParams)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = kdfParams,
                    wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
                    commitment = EnvelopeCrypto.slotCommitment(dek),
                ),
            )
        } finally {
            Zeroizer.wipe(password)
            Zeroizer.wipe(dek)
            Zeroizer.wipe(kek)
        }
    }

    fun cleanup(context: Context) {
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.TEMP_FILE).delete()
        File(context.filesDir, DeviceKeyStore.FILE_NAME).delete()
        File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR).deleteRecursively()
    }
}
