package com.localkeep.feature.importwizard

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The import wizard recognises LocalKeep's own `.vault` backup and says what it is, instead of
 * calling it a format LocalKeep does not recognise.
 *
 * Device report C3 finding 1 (2026-10-04): a user moving to a new phone took the natural route —
 * onboarding's "Import my passwords", then Other / CSV, then the `.vault` exported on the old phone
 * — and was told *"That file isn't in a format LocalKeep recognizes. If it came from a password
 * manager, export it again and pick a plain, unencrypted format."* About the app's own backup, on
 * the one journey where it is the only copy the user has. The restore lives under Backup & export,
 * and nothing on the way pointed there.
 *
 * Real bytes: a genuine container from [VaultFileCodec], and the `LKBUNDLE` wrapper `VaultExporter`
 * writes around it (`VaultBundle`, internal to `:feature:backup`, laid out by hand here from its
 * documented format).
 */
class LocalKeepBackupIsNamedTest {

    /** A real, sealed vault container — the whole file of a pre-bundle export. */
    private val container: ByteArray = run {
        val dek = EnvelopeCrypto.generateDek()
        // Any key wraps it: nothing here derives a password, only the container's shape matters.
        val kek = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
        val header = VaultHeaderCodec.create(
            SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
            KdfParams.FLOOR,
            EnvelopeCrypto.wrapDek(kek, dek),
            EnvelopeCrypto.slotCommitment(dek),
        )
        VaultFileCodec.encrypt(
            Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
    }

    /** What Backup & export writes today: `LKBUNDLE`, version 1, the container, no media. */
    private val bundle: ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply {
            write("LKBUNDLE".toByteArray(Charsets.US_ASCII))
            writeByte(1)
            writeInt(container.size)
            write(container)
            writeInt(0)
        }
    }.toByteArray()

    @Test
    fun `a vault backup picked under Other is named as a LocalKeep backup`() {
        assertEquals(ImportError.LocalKeepBackup, ImportWizardViewModel.refusalFor(ImportSource.CSV, bundle))
    }

    @Test
    fun `a pre-bundle export, the bare container, is named too`() {
        assertEquals(ImportError.LocalKeepBackup, ImportWizardViewModel.refusalFor(ImportSource.CSV, container))
    }

    @Test
    fun `whichever tile it was picked under`() {
        // The KeePass tile has its own sentence for "not a KDBX file", and it would be true — but
        // the useful answer is what the file *is*, and where it goes.
        ImportSource.entries.forEach { source ->
            assertEquals(source.name, ImportError.LocalKeepBackup, ImportWizardViewModel.refusalFor(source, bundle))
        }
    }

    @Test
    fun `no importer claims it, so the wizard never previews a backup as somebody else's export`() {
        ImportSource.entries.forEach { source ->
            assertNull(source.name, ImportWizardViewModel.importerFor(source, bundle))
            assertNull(source.name, ImportWizardViewModel.importerFor(source, container))
        }
    }

    @Test
    fun `a binary file that is not a backup keeps the generic answer`() {
        // The control: the recognition is by header, and anything else under Other is still a
        // file this app does not know.
        val binary = ByteArray(64).also { "VLT0".toByteArray().copyInto(it) }
        assertEquals(
            ImportError.Simple(R.string.import_error_unrecognised_format),
            ImportWizardViewModel.refusalFor(ImportSource.CSV, binary),
        )
    }
}
