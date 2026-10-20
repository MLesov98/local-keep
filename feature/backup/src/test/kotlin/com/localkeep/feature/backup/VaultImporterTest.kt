package com.localkeep.feature.backup

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.model.File as FileContent
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.feature.files.FileBlobStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlinx.coroutines.runBlocking

class VaultImporterTest {

    private companion object {
        // Real refs are `UUID.toString()`, and VaultBundle refuses any other shape since 2026-10-02.
        const val PRESENT_REF = "00000000-0000-4000-8000-000000000001"
        const val MISSING_REF_1 = "00000000-0000-4000-8000-000000000002"
        const val MISSING_REF_2 = "00000000-0000-4000-8000-000000000003"
        const val REF_1 = "00000000-0000-4000-8000-000000000004"
        const val REF_2 = "00000000-0000-4000-8000-000000000005"
        const val SHARED_REF = "00000000-0000-4000-8000-000000000006"
        const val FRESH_REF = "00000000-0000-4000-8000-000000000007"
        const val FIRST_REF = "00000000-0000-4000-8000-000000000008"
        const val SECOND_REF = "00000000-0000-4000-8000-000000000009"
        const val ROTATED_REF = "00000000-0000-4000-8000-000000000010"
        const val INTACT_REF = "00000000-0000-4000-8000-000000000011"
    }

    @Test
    fun `a bundle construction failure closes the resolver stream`() = runBlocking {
        val input = TrackingInputStream(VaultBundle.MAGIC)
        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(input)
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)
        val importer = VaultImporter(context, mock(), mock())

        val outcome = importer.readVault(uri, "password".toCharArray())

        assertIs<Outcome.Failure>(outcome)
        assertTrue(input.closed, "failed bundle probing must release the resolver stream")
    }

    private class TrackingInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = bytes.inputStream()
        var closed = false

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    // Regression for a recorded finding: a backup taken while the duress vault is open is
    // incomplete with respect to the *real* vault's media, and cannot say so at export time
    // without confessing a second vault exists. The honest remedy is restore-side and carries no
    // duress knowledge at all — [VaultImporter.readVault] already knows both the bundle's blob
    // count and the restored vault's blob refs, so it reports how many distinct refs were never
    // in the bundle. This drives the real `readVault` through a real encrypted container and a
    // real bundle rather than stubbing `Container`, which is `private` and cannot be reached from
    // this test file directly.
    @Test
    fun `readVault reports the distinct referenced files this backup did not contain`() = runBlocking {
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        // Argon2id's floor rather than DEFAULT — this test pays the KDF cost for real, and the
        // floor is still slow enough to prove the container decrypts correctly.
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val wrappedDek = EnvelopeCrypto.wrapDek(kek, dek)
        val commitment = EnvelopeCrypto.slotCommitment(dek)
        val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
        val encKey = VaultSubkeys.encKey(dek)

        val now = Instant.now()
        fun fileItem(ref: String) = VaultItem(
            id = UUID.randomUUID(),
            createdAt = now,
            updatedAt = now,
            content = FileContent(filename = "$ref.jpg", blobRef = ref),
        )
        // Two refs the restored vault's items point at were never in the bundle's media section;
        // a third was. missingBlobRefCount must count the two distinct refs, not the two items —
        // a vault where both missing items point at the SAME ref would still be 1.
        val vault = Vault(
            items = listOf(
                fileItem(PRESENT_REF),
                fileItem(MISSING_REF_1),
                fileItem(MISSING_REF_2),
            ),
            createdAt = now,
            modifiedAt = now,
        )
        val vaultFileBytes =
            VaultFileCodec.encrypt(vault, encKey, header, VaultFileCodec.revokedRecoveryBlock())

        val bundleOut = ByteArrayOutputStream()
        VaultBundle.write(
            bundleOut,
            vaultFileBytes,
            listOf(VaultBundle.BlobSource(PRESENT_REF, 3L) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
        )

        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bundleOut.toByteArray()))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)

        // Same vault as the export (the ordinary restore case): the lock manager reports the same
        // DEK content, so restoreBlobs takes the "sameVault" branch. The blob store itself is not
        // asserted against — this test is about the missing-ref count, not local disk writes — but
        // the stubbed writeEncrypted must still drain its InputStream: VaultBundle.Reader.forEachBlob
        // requires every blob fully consumed before moving to the next one (it can't seek), and a
        // Mockito no-op default leaves the bytes unread.
        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.writeEncrypted(any(), any())).thenAnswer { invocation ->
            (invocation.arguments[1] as InputStream).readBytes()
            Unit
        }
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            // A fresh copy per call: restoreBlobs owns the array it is handed and wipes it.
            VaultLockManager.SessionSnapshot(vault = vault, dek = dek.copyOf())
        }

        val importer = VaultImporter(context, fileBlobStore, lockManager)

        val outcome = importer.readVault(uri, "password".toCharArray())

        val success = assertIs<Outcome.Success<VaultImporter.ImportResult>>(outcome)
        assertEquals(vault.items.map { it.id }.toSet(), success.value.vault.items.map { it.id }.toSet())
        assertEquals(2, success.value.missingBlobRefCount)
    }

    // Finding 6-2's second-order issue: restoreBlobs writes blobs one at a time
    // (VaultImporter.Container.restoreBlobs), so a later blob throwing — here, a truncated bundle
    // raising EOFException("bundle truncated") from VaultBundle.Reader.forEachBlob's
    // BoundedInputStream, exactly the crash this finding was filed against — must not leave the
    // earlier, already-written blob orphaned on disk. Drives readVault through a real encrypted
    // container and a real (deliberately truncated) bundle, same as the sibling missing-refs test.
    @Test
    fun `a later blob failing during restore deletes the blobs already written this pass`() = runBlocking {
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val wrappedDek = EnvelopeCrypto.wrapDek(kek, dek)
        val commitment = EnvelopeCrypto.slotCommitment(dek)
        val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
        val encKey = VaultSubkeys.encKey(dek)

        val now = Instant.now()
        val vault = Vault(items = emptyList(), createdAt = now, modifiedAt = now)
        val vaultFileBytes =
            VaultFileCodec.encrypt(vault, encKey, header, VaultFileCodec.revokedRecoveryBlock())

        val bundleOut = ByteArrayOutputStream()
        VaultBundle.write(
            bundleOut,
            vaultFileBytes,
            listOf(
                // Writes cleanly: the declared length matches what open() actually produces.
                VaultBundle.BlobSource(REF_1, 3L) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                // Lies about its length — 100 declared, 3 actually written — so the reader's
                // BoundedInputStream hits end-of-stream before `remaining` reaches zero and throws
                // EOFException("bundle truncated"), the real defect's exact failure.
                VaultBundle.BlobSource(REF_2, 100L) { ByteArrayInputStream(byteArrayOf(4, 5, 6)) },
            ),
        )

        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bundleOut.toByteArray()))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)

        // Same vault as the export, so restoreBlobs takes the "sameVault" branch. `authenticates`
        // is left unstubbed (defaults to false), which routes both blobs to writeEncrypted — the
        // branch that actually lands bytes on disk and can therefore orphan them.
        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.writeEncrypted(any(), any())).thenAnswer { invocation ->
            (invocation.arguments[1] as InputStream).readBytes()
            Unit
        }
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            // A fresh copy per call: restoreBlobs owns the array it is handed and wipes it.
            VaultLockManager.SessionSnapshot(vault = vault, dek = dek.copyOf())
        }

        val importer = VaultImporter(context, fileBlobStore, lockManager)

        val outcome = importer.readVault(uri, "password".toCharArray())

        val failure = assertIs<Outcome.Failure>(outcome)
        assertIs<AppError.Storage>(failure.error)
        verify(fileBlobStore).writeEncrypted(eq(REF_1), any())
        verify(fileBlobStore).deleteBlob(REF_1)
        verify(fileBlobStore, never()).deleteBlob(REF_2)
    }

    // Finding 20-1: `blobs/` is a single flat namespace shared by both payload regions
    // (FileBlobStore.blobFile carries no region qualifier), and a session holds the key for
    // exactly one region. Restoring a real-vault export while the decoy session is unlocked
    // therefore took the cross-vault branch and renamed decoy-keyed ciphertext over
    // blobs/<ref>.enc — the only copy of the media the *other* vault's items still point at.
    // Unrecoverable: blobs/ is out of Auto Backup and transcodeEncrypted keeps no journal.
    //
    // The refusal asks only whether the live key opens what is already on disk, so it says
    // nothing about a second vault existing; the assertion below is that the transcode never
    // happens, not merely that the import failed.
    @Test
    fun `an import does not re-key over a blob the live key cannot open`() = runBlocking<Unit> {
        val exportDek = EnvelopeCrypto.generateDek()
        // A different region's key — what liveDek() returns while a decoy session is unlocked.
        val liveDek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val wrappedDek = EnvelopeCrypto.wrapDek(kek, exportDek)
        val commitment = EnvelopeCrypto.slotCommitment(exportDek)
        val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
        val encKey = VaultSubkeys.encKey(exportDek)

        val now = Instant.now()
        val vault = Vault(
            items = listOf(
                VaultItem(
                    id = UUID.randomUUID(),
                    createdAt = now,
                    updatedAt = now,
                    content = FileContent(filename = "shared.jpg", blobRef = SHARED_REF),
                ),
            ),
            createdAt = now,
            modifiedAt = now,
        )
        val vaultFileBytes =
            VaultFileCodec.encrypt(vault, encKey, header, VaultFileCodec.revokedRecoveryBlock())

        val bundleOut = ByteArrayOutputStream()
        VaultBundle.write(
            bundleOut,
            vaultFileBytes,
            listOf(VaultBundle.BlobSource(SHARED_REF, 3L) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
        )

        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bundleOut.toByteArray()))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)

        // A blob already sits under this ref, and it does not open under the live key
        // (`authenticates` is unstubbed, so false) — it belongs to the other region.
        // `transcodeEncrypted` drains so that an unguarded run fails on the assertions below
        // rather than on VaultBundle's "was not fully consumed" check.
        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.hasBlob(any())).thenReturn(true)
        whenever(fileBlobStore.transcodeEncrypted(any(), any(), any(), any())).thenAnswer { invocation ->
            (invocation.arguments[1] as InputStream).readBytes()
            Unit
        }
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            // A fresh copy per call: restoreBlobs owns the array it is handed and wipes it.
            VaultLockManager.SessionSnapshot(vault = vault, dek = liveDek.copyOf())
        }

        val importer = VaultImporter(context, fileBlobStore, lockManager)

        val outcome = importer.readVault(uri, "password".toCharArray())

        verify(fileBlobStore, never()).transcodeEncrypted(any(), any(), any(), any())
        verify(fileBlobStore, never()).writeEncrypted(any(), any())
        val failure = assertIs<Outcome.Failure>(outcome)
        assertIs<AppError.Storage>(failure.error)
    }

    // The control for the test above: the guard must refuse only the collision, never the
    // ordinary cross-vault import. With no blob under the ref, the export's media is still
    // re-keyed in and the import succeeds.
    @Test
    fun `an import from another vault still re-keys media into an unused ref`() = runBlocking<Unit> {
        val exportDek = EnvelopeCrypto.generateDek()
        val liveDek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val wrappedDek = EnvelopeCrypto.wrapDek(kek, exportDek)
        val commitment = EnvelopeCrypto.slotCommitment(exportDek)
        val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
        val encKey = VaultSubkeys.encKey(exportDek)

        val now = Instant.now()
        val vault = Vault(
            items = listOf(
                VaultItem(
                    id = UUID.randomUUID(),
                    createdAt = now,
                    updatedAt = now,
                    content = FileContent(filename = "fresh.jpg", blobRef = FRESH_REF),
                ),
            ),
            createdAt = now,
            modifiedAt = now,
        )
        val vaultFileBytes =
            VaultFileCodec.encrypt(vault, encKey, header, VaultFileCodec.revokedRecoveryBlock())

        val bundleOut = ByteArrayOutputStream()
        VaultBundle.write(
            bundleOut,
            vaultFileBytes,
            listOf(VaultBundle.BlobSource(FRESH_REF, 3L) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
        )

        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bundleOut.toByteArray()))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)

        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.hasBlob(any())).thenReturn(false)
        whenever(fileBlobStore.transcodeEncrypted(any(), any(), any(), any())).thenAnswer { invocation ->
            (invocation.arguments[1] as InputStream).readBytes()
            Unit
        }
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            // A fresh copy per call: restoreBlobs owns the array it is handed and wipes it.
            VaultLockManager.SessionSnapshot(vault = vault, dek = liveDek.copyOf())
        }

        val importer = VaultImporter(context, fileBlobStore, lockManager)

        val outcome = importer.readVault(uri, "password".toCharArray())

        assertIs<Outcome.Success<VaultImporter.ImportResult>>(outcome)
        verify(fileBlobStore).transcodeEncrypted(eq(FRESH_REF), any(), any(), any())
    }

    // A lock landing mid-restore must not re-key the rest of the media under an all-zero key.
    //
    // `currentDekOrThrow()` hands back the array VaultLockManager zeroes in place on lock(), and
    // restoreBlobs took no borrow — so an auto-lock or screen-off during a long multi-blob restore
    // zeroed the key under the loop and every blob after that point was sealed with zeroes. Silent:
    // the staging step still succeeded, and the media only failed to open after the user re-unlocked
    // and confirmed the import. `managerDek` below stands for the manager's own array (the stub
    // copies out of it, exactly as the real `sessionSnapshotOrNull` copies under its state lock) and
    // is wiped between the two blobs to stand for the lock.
    @Test
    fun `a lock during a cross-vault restore does not re-key later blobs under a zeroed key`() = runBlocking<Unit> {
        val exportDek = EnvelopeCrypto.generateDek()
        val liveDek = EnvelopeCrypto.generateDek()
        val managerDek = liveDek.copyOf()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val wrappedDek = EnvelopeCrypto.wrapDek(kek, exportDek)
        val commitment = EnvelopeCrypto.slotCommitment(exportDek)
        val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
        val encKey = VaultSubkeys.encKey(exportDek)

        val now = Instant.now()
        val vault = Vault(
            items = listOf(FIRST_REF, SECOND_REF).map { ref ->
                VaultItem(
                    id = UUID.randomUUID(),
                    createdAt = now,
                    updatedAt = now,
                    content = FileContent(filename = "$ref.jpg", blobRef = ref),
                )
            },
            createdAt = now,
            modifiedAt = now,
        )
        val vaultFileBytes =
            VaultFileCodec.encrypt(vault, encKey, header, VaultFileCodec.revokedRecoveryBlock())

        val bundleOut = ByteArrayOutputStream()
        VaultBundle.write(
            bundleOut,
            vaultFileBytes,
            listOf(
                VaultBundle.BlobSource(FIRST_REF, 3L) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
                VaultBundle.BlobSource(SECOND_REF, 3L) { ByteArrayInputStream(byteArrayOf(4, 5, 6)) },
            ),
        )

        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bundleOut.toByteArray()))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)

        val destinationKeys = mutableMapOf<String, ByteArray>()
        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.hasBlob(any())).thenReturn(false)
        whenever(fileBlobStore.transcodeEncrypted(any(), any(), any(), any())).thenAnswer { invocation ->
            val ref = invocation.arguments[0] as String
            (invocation.arguments[1] as InputStream).readBytes()
            // Copied, not aliased: restoreBlobs wipes its key when the loop ends, which would
            // otherwise zero what this assertion is holding and make the test pass for the
            // wrong reason.
            destinationKeys[ref] = (invocation.arguments[3] as ByteArray).copyOf()
            // The lock, landing between the two blobs.
            if (ref == FIRST_REF) managerDek.fill(0)
            Unit
        }
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            VaultLockManager.SessionSnapshot(vault = vault, dek = managerDek.copyOf())
        }

        val importer = VaultImporter(context, fileBlobStore, lockManager)

        val outcome = importer.readVault(uri, "password".toCharArray())

        assertIs<Outcome.Success<VaultImporter.ImportResult>>(outcome)
        // Control — the blob before the lock was always re-keyed under the real session key.
        assertTrue(
            destinationKeys[FIRST_REF]?.contentEquals(liveDek) == true,
            "the first blob must be re-keyed under the session key",
        )
        assertTrue(
            destinationKeys[SECOND_REF]?.contentEquals(liveDek) == true,
            "a blob after the lock must not be sealed under a zeroed key",
        )
    }

    // The other control for the refusal, and the one the pair was missing: the guard is
    // `hasBlob(ref) && !authenticates(ref, local)`, and until this test nothing anywhere made
    // `authenticates` return true, so the whole `!authenticates` term could be deleted with every
    // test still green — the guard would then refuse *any* ref already on disk.
    //
    // The case is reachable and must not be refused: a pre-rotation export restored after a DEK
    // rotation carries the same refs, and the blob already on disk does open under the live key
    // (recorded as deliberately unguarded). Re-keying is the right answer there —
    // it is the same region's own media either way.
    @Test
    fun `an import re-keys over a colliding blob the live key does own`() = runBlocking<Unit> {
        val exportDek = EnvelopeCrypto.generateDek()
        val liveDek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val wrappedDek = EnvelopeCrypto.wrapDek(kek, exportDek)
        val commitment = EnvelopeCrypto.slotCommitment(exportDek)
        val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
        val encKey = VaultSubkeys.encKey(exportDek)

        val now = Instant.now()
        val vault = Vault(
            items = listOf(
                VaultItem(
                    id = UUID.randomUUID(),
                    createdAt = now,
                    updatedAt = now,
                    content = FileContent(filename = "rotated.jpg", blobRef = ROTATED_REF),
                ),
            ),
            createdAt = now,
            modifiedAt = now,
        )
        val vaultFileBytes =
            VaultFileCodec.encrypt(vault, encKey, header, VaultFileCodec.revokedRecoveryBlock())

        val bundleOut = ByteArrayOutputStream()
        VaultBundle.write(
            bundleOut,
            vaultFileBytes,
            listOf(VaultBundle.BlobSource(ROTATED_REF, 3L) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
        )

        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bundleOut.toByteArray()))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)

        // A blob does sit under this ref, and it opens under the live key. Stubbed on `any()`
        // rather than `eq(liveDek)`: the importer passes whatever the lock manager hands it, not
        // the array this test holds.
        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.hasBlob(any())).thenReturn(true)
        whenever(fileBlobStore.authenticates(any(), any())).thenReturn(true)
        whenever(fileBlobStore.transcodeEncrypted(any(), any(), any(), any())).thenAnswer { invocation ->
            (invocation.arguments[1] as InputStream).readBytes()
            Unit
        }
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            // A fresh copy per call: restoreBlobs owns the array it is handed and wipes it.
            VaultLockManager.SessionSnapshot(vault = vault, dek = liveDek.copyOf())
        }

        val importer = VaultImporter(context, fileBlobStore, lockManager)

        val outcome = importer.readVault(uri, "password".toCharArray())

        assertIs<Outcome.Success<VaultImporter.ImportResult>>(outcome)
        verify(fileBlobStore).transcodeEncrypted(eq(ROTATED_REF), any(), any(), any())
    }

    // The same-vault half of the same blind spot. `authenticates` false in every other test here
    // means the drain branch of the same-vault arm was never taken, so replacing that `if` with an
    // unconditional `writeEncrypted` survived the whole file. That mutation is not merely
    // wasteful: an intact pre-existing blob would join `written`, and a later blob failing would
    // then delete the device's own good copy through the rollback.
    @Test
    fun `a same-vault import drains a blob this device already holds intact`() = runBlocking<Unit> {
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val wrappedDek = EnvelopeCrypto.wrapDek(kek, dek)
        val commitment = EnvelopeCrypto.slotCommitment(dek)
        val header = VaultHeaderCodec.create(salt, kdfParams, wrappedDek, commitment)
        val encKey = VaultSubkeys.encKey(dek)

        val now = Instant.now()
        val vault = Vault(
            items = listOf(
                VaultItem(
                    id = UUID.randomUUID(),
                    createdAt = now,
                    updatedAt = now,
                    content = FileContent(filename = "intact.jpg", blobRef = INTACT_REF),
                ),
            ),
            createdAt = now,
            modifiedAt = now,
        )
        val vaultFileBytes =
            VaultFileCodec.encrypt(vault, encKey, header, VaultFileCodec.revokedRecoveryBlock())

        val bundleOut = ByteArrayOutputStream()
        VaultBundle.write(
            bundleOut,
            vaultFileBytes,
            listOf(VaultBundle.BlobSource(INTACT_REF, 3L) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }),
        )

        val uri = mock<Uri>()
        val resolver = mock<ContentResolver>()
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bundleOut.toByteArray()))
        val context = mock<Context>()
        whenever(context.contentResolver).thenReturn(resolver)

        // The copy on disk opens under the live key, so restore has nothing to fix and must leave
        // it alone. `writeEncrypted` is still stubbed to drain so that a build which writes anyway
        // fails on the assertion below rather than on VaultBundle's "not fully consumed" check.
        val fileBlobStore = mock<FileBlobStore>()
        whenever(fileBlobStore.authenticates(any(), any())).thenReturn(true)
        whenever(fileBlobStore.writeEncrypted(any(), any())).thenAnswer { invocation ->
            (invocation.arguments[1] as InputStream).readBytes()
            Unit
        }
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.sessionSnapshotOrNull()).thenAnswer {
            // A fresh copy per call: restoreBlobs owns the array it is handed and wipes it.
            VaultLockManager.SessionSnapshot(vault = vault, dek = dek.copyOf())
        }

        val importer = VaultImporter(context, fileBlobStore, lockManager)

        val outcome = importer.readVault(uri, "password".toCharArray())

        val success = assertIs<Outcome.Success<VaultImporter.ImportResult>>(outcome)
        verify(fileBlobStore, never()).writeEncrypted(any(), any())
        verify(fileBlobStore, never()).transcodeEncrypted(any(), any(), any(), any())
        // The bundle did carry the ref, so nothing is reported missing — draining is not a gap.
        assertEquals(0, success.value.missingBlobRefCount)
    }

    @Test
    fun `a decryption failure is reported as a crypto error`() {
        val boom = IllegalStateException("tag mismatch")
        var restored = false

        val outcome = decryptThenRestore<String>(
            decrypt = { throw boom },
            restoreBlobs = { restored = true },
        )

        val failure = assertIs<Outcome.Failure>(outcome)
        val error = assertIs<AppError.Crypto>(failure.error)
        assertSame(boom, error.cause)
        assertFalse(restored, "blobs must not be restored when decryption failed")
    }

    // The password was already proven correct by the time blobs are written, so an I/O failure
    // there must not send the user back to re-type it.
    @Test
    fun `a blob restore failure is reported as a storage error, not a crypto error`() {
        val boom = IOException("No space left on device")

        val outcome = decryptThenRestore(
            decrypt = { "vault" },
            restoreBlobs = { throw boom },
        )

        val failure = assertIs<Outcome.Failure>(outcome)
        val error = assertIs<AppError.Storage>(failure.error)
        assertSame(boom, error.cause)
    }

    @Test
    fun `both phases succeeding yields the decrypted value`() {
        var restored = false

        val outcome = decryptThenRestore(
            decrypt = { "vault" },
            restoreBlobs = { restored = true },
        )

        assertEquals("vault", assertIs<Outcome.Success<*>>(outcome).value)
        assertTrue(restored)
    }
}
