package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.WrappedDek
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicVaultWriterInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @AfterTest
    fun cleanup() {
        file(AtomicVaultWriter.VAULT_FILE).delete()
        file(AtomicVaultWriter.BACKUP_FILE).delete()
        file(AtomicVaultWriter.TEMP_FILE).delete()
    }

    @Test
    fun writeThenLoad_roundTripsAndRemovesTemporaryFile() {
        val expected = validVaultBytes()

        AtomicVaultWriter.write(context, expected)

        val loaded = assertIs<Outcome.Success<ByteArray>>(VaultLoader.loadFileBytes(context))
        assertContentEquals(expected, loaded.value)
        assertFalse(file(AtomicVaultWriter.TEMP_FILE).exists())
    }

    @Test
    fun secondWrite_retainsPriorCommittedVaultAsBackup() {
        val first = validVaultBytes()
        val second = validVaultBytes()

        AtomicVaultWriter.write(context, first)
        AtomicVaultWriter.write(context, second)

        assertContentEquals(second, file(AtomicVaultWriter.VAULT_FILE).readBytes())
        assertContentEquals(first, file(AtomicVaultWriter.BACKUP_FILE).readBytes())
    }

    @Test
    fun truncatedPrimary_recoversPriorCommittedBackup() {
        val first = validVaultBytes()
        val second = validVaultBytes()
        AtomicVaultWriter.write(context, first)
        AtomicVaultWriter.write(context, second)
        file(AtomicVaultWriter.VAULT_FILE).writeBytes(ByteArray(10))

        val loaded = assertIs<Outcome.Success<ByteArray>>(VaultLoader.loadFileBytes(context))

        assertContentEquals(first, loaded.value)
    }

    @Test
    fun missingPrimary_recoversBackupAfterInterruptedRotation() {
        val first = validVaultBytes()
        val second = validVaultBytes()
        AtomicVaultWriter.write(context, first)
        AtomicVaultWriter.write(context, second)
        assertTrue(file(AtomicVaultWriter.VAULT_FILE).delete())

        val loaded = assertIs<Outcome.Success<ByteArray>>(VaultLoader.loadFileBytes(context))

        assertContentEquals(first, loaded.value)
    }

    /**
     * The crash [AtomicVaultWriter] leaves behind between its two renames: `vault.enc` has already
     * become `.bak` and `.tmp` has not yet become `vault.enc`, so the only copy of the save that
     * just completed is the `.tmp`. Recovering the `.bak` here would be silent data loss — it holds
     * the state from before that save, and the next write overwrites the `.tmp` that still had it.
     */
    @Test
    fun missingPrimary_prefersTheStagedSaveOverThePriorBackup() {
        val previous = validVaultBytes()
        val justSaved = validVaultBytes()
        file(AtomicVaultWriter.BACKUP_FILE).writeBytes(previous)
        file(AtomicVaultWriter.TEMP_FILE).writeBytes(justSaved)
        assertFalse(file(AtomicVaultWriter.VAULT_FILE).exists())

        val loaded = assertIs<Outcome.Success<ByteArray>>(VaultLoader.loadFileBytes(context))

        assertContentEquals(justSaved, loaded.value)
    }

    /**
     * The other half of the ordering: a `.tmp` orphaned by a crash *during* the write must never
     * displace the committed `vault.enc`, whatever it happens to contain.
     */
    @Test
    fun committedPrimary_winsOverAnOrphanedTemporaryFile() {
        val committed = validVaultBytes()
        AtomicVaultWriter.write(context, committed)
        file(AtomicVaultWriter.TEMP_FILE).writeBytes(validVaultBytes())

        val loaded = assertIs<Outcome.Success<ByteArray>>(VaultLoader.loadFileBytes(context))

        assertContentEquals(committed, loaded.value)
    }

    @Test
    fun invalidTemporaryFileAlone_doesNotCountAsVaultStorage() {
        file(AtomicVaultWriter.TEMP_FILE).writeBytes(ByteArray(10))

        assertFalse(VaultLoader.hasVaultFiles(context))
    }

    @Test
    fun validTemporaryFileAlone_countsAsVaultStorage() {
        file(AtomicVaultWriter.TEMP_FILE).writeBytes(validVaultBytes())

        assertTrue(VaultLoader.hasVaultFiles(context))
    }

    @Test
    fun missingAndCorruptFiles_returnStorageFailure() {
        file(AtomicVaultWriter.VAULT_FILE).writeBytes(ByteArray(10))
        file(AtomicVaultWriter.BACKUP_FILE).writeBytes(ByteArray(10))

        assertIs<Outcome.Failure>(VaultLoader.loadFileBytes(context))
    }

    private fun validVaultBytes(): ByteArray {
        val dek = EnvelopeCrypto.generateDek()
        val kek = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
        return VaultFileCodec.create(
            dek = dek,
            salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
            kdfParams = KdfParams.FLOOR,
            wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
            commitment = EnvelopeCrypto.slotCommitment(dek),
        )
    }

    private fun file(name: String): File = File(context.filesDir, name)
}
