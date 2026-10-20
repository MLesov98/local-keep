package com.localkeep.feature.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.localkeep.core.common.AppError
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A `.vault` that was cut short is reported as a malformed file, not as one that could not be read.
 *
 * S9-F-06 (functional pass, 2026-10-15): the first 100 bytes of a real backup, with the right
 * password, answered "Couldn't read the file you chose" — a storage failure, which invites the user
 * to pick it again. The file was read fine; it is the content that cannot be a vault. Repo law: an
 * error names the real cause. A stream that genuinely fails to read stays a storage failure.
 */
class ImportTruncatedFileNamesTheRealCauseTest {

    private val now: Instant = Instant.now()
    private val vault = Vault(items = emptyList(), createdAt = now, modifiedAt = now)
    private val dek = EnvelopeCrypto.generateDek()

    private fun bundleBytes(): ByteArray {
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val header = VaultHeaderCodec.create(
            salt,
            kdfParams,
            EnvelopeCrypto.wrapDek(kek, dek),
            EnvelopeCrypto.slotCommitment(dek),
        )
        val vaultFile = VaultFileCodec.encrypt(
            vault,
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
        return ByteArrayOutputStream().also { VaultBundle.write(it, vaultFile, emptyList()) }.toByteArray()
    }

    private fun importerReading(stream: InputStream): Pair<VaultImporter, Uri> {
        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(stream)
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)
        return VaultImporter(context, mock(), mock<VaultLockManager>()) to uri
    }

    @Test
    fun `a bundle cut to 100 bytes is not a valid vault file`() = runBlocking {
        val (importer, uri) = importerReading(ByteArrayInputStream(bundleBytes().copyOf(100)))

        val outcome = importer.readVault(uri, "password".toCharArray())

        val failure = assertIs<Outcome.Failure>(outcome)
        val error = assertIs<AppError.Validation>(
            failure.error,
            "a truncated file was reported as ${failure.error}, which reads as a storage fault",
        )
        assertEquals("not a valid .vault file", error.message)
    }

    @Test
    fun `control - a stream that fails mid-read is still a storage failure`() = runBlocking {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("device went away")
        }
        val (importer, uri) = importerReading(broken)

        val outcome = importer.readVault(uri, "password".toCharArray())

        val failure = assertIs<Outcome.Failure>(outcome)
        assertIs<AppError.Storage>(failure.error)
        Unit
    }
}
