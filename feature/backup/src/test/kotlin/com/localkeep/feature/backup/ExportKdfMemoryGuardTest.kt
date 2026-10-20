package com.localkeep.feature.backup

import android.content.Context
import android.net.Uri
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.KdfNeedsMoreMemoryException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock

/**
 * The export passphrase's derivation running out of heap is the named memory failure, not a dead
 * process (S2-04's export half, 2026-10-02).
 *
 * `BackupSettingsViewModel.export` catches `KdfNeedsMoreMemoryException` for exactly this, and nothing
 * threw it: the `OutOfMemoryError` passed `exportWithPassphrase`'s `catch (e: Exception)` and escaped
 * a scope with no handler. The allocation failure is stood in for through the derivation seam.
 */
class ExportKdfMemoryGuardTest {

    @Test
    fun `an out-of-memory derivation becomes the named memory failure`() {
        val exporter = VaultExporter(mock<Context>(), mock(), mock(), mock())
        exporter.deriveKek = { _, _, _ -> throw OutOfMemoryError("Failed to allocate a 33554448 byte allocation") }
        val passphrase = "a separate passphrase".toCharArray()
        val now = Instant.now()

        assertFailsWith<KdfNeedsMoreMemoryException> {
            runBlocking {
                exporter.exportWithPassphrase(
                    mock<Uri>(),
                    Vault(items = emptyList(), createdAt = now, modifiedAt = now),
                    EnvelopeCrypto.generateDek(),
                    passphrase,
                )
            }
        }
        assertTrue(passphrase.all { it.code == 0 }, "the passphrase is still wiped on the way out")
    }
}
