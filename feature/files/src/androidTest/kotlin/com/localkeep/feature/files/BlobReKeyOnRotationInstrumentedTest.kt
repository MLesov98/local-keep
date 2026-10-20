package com.localkeep.feature.files

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.File as FileContent
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.MasterPasswordChanger
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Changing the master password used to destroy every stored file, photo and attachment.
 *
 * The rotation mints a fresh DEK on purpose — re-wrapping alone would leave the old Recovery Kit
 * and the device-wrapped biometric copy still opening the vault — and it rewrote the container and
 * swept the stale ones. It did not touch the blob directory, and every blob's stream key is
 * HKDF-derived from the DEK. So the vault opened, the items were all there, and each of them
 * pointed at ciphertext that no key on the device could read any more. No error at the time, and
 * nothing to recover from afterwards.
 */
@RunWith(AndroidJUnit4::class)
class BlobReKeyOnRotationInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var store: FileBlobStore

    @BeforeTest
    fun setUp() = runBlocking {
        wipe()
        createFreshVault()
        store = FileBlobStore(context)
        lockManager = newLockManager(registerStore = true)
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
        Unit
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        wipe()
    }

    @Test
    fun aStoredFileStillOpensAfterTheMasterPasswordChanges() = runBlocking {
        // Larger than one 64 KiB StreamingAead frame, so this exercises the framed path a photo or
        // a video actually takes rather than a single-frame special case.
        val photo = ByteArray(200_000) { (it * 31 % 251).toByte() }
        val thumbnail = "thumbnail bytes".toByteArray()
        val dekBefore = lockManager.currentDekOrThrow().copyOf()
        val photoRef = store.encryptToBlob(ByteArrayInputStream(photo), dekBefore)
        val thumbRef = store.encryptToBlob(ByteArrayInputStream(thumbnail), dekBefore)
        seedFileItem(photoRef, thumbRef)
        assertContentEquals(photo, store.decryptToBytes(photoRef, dekBefore), "control: readable before")

        val result = MasterPasswordChanger.changePassword(lockManager, password(), newPassword())

        assertTrue(result.success, "the password change itself must succeed")
        val dekAfter = lockManager.currentDekOrThrow()
        assertFalse(
            dekBefore.contentEquals(dekAfter),
            "control: the DEK really rotated, so this is not passing because nothing happened",
        )
        assertContentEquals(
            photo,
            store.decryptToBytes(photoRef, dekAfter),
            "a password change must not make a stored file unreadable",
        )
        assertContentEquals(thumbnail, store.decryptToBytes(thumbRef, dekAfter))
        assertEquals(2, result.blobsReKeyed, "the photo and its thumbnail")
        assertEquals(0, result.blobsLeftBehind)
        assertFalse(
            File(context.filesDir, "dek-rotation.journal").exists(),
            "a finished pass retires the old key by deleting its journal",
        )
    }

    /**
     * The seam is a runtime registration, so it can be absent. Refusing is the only safe answer:
     * the failure it replaces was silent permanent data loss, and nothing has been written yet.
     */
    @Test
    fun aRotationWithNoBlobStoreRegisteredRefusesInsteadOfOrphaningFiles() = runBlocking {
        val bytes = "a receipt".toByteArray()
        val dek = lockManager.currentDekOrThrow().copyOf()
        val ref = store.encryptToBlob(ByteArrayInputStream(bytes), dek)
        seedFileItem(ref, thumbnailRef = null)
        lockManager.lock()

        val unwired = newLockManager(registerStore = false)
        unwired.unlock(password())
        assertFailsWith<IllegalStateException> {
            MasterPasswordChanger.changePassword(unwired, password(), newPassword())
        }
        unwired.lock()

        val reopened = newLockManager(registerStore = true)
        reopened.unlock(password())
        assertContentEquals(
            bytes,
            store.decryptToBytes(ref, reopened.currentDekOrThrow()),
            "and nothing was written, so the file is untouched",
        )
        reopened.lock()
    }

    /** A vault holding no blobs needs no store and no journal — the ordinary case must stay cheap. */
    @Test
    fun aVaultWithNoStoredFilesRotatesWithoutAJournal() = runBlocking {
        val unwired = run {
            lockManager.lock()
            newLockManager(registerStore = false).also { it.unlock(password()) }
        }
        val result = MasterPasswordChanger.changePassword(unwired, password(), newPassword())
        assertTrue(result.success)
        assertEquals(0, result.blobsReKeyed)
        assertFalse(File(context.filesDir, "dek-rotation.journal").exists())
        unwired.lock()
    }

    private fun newLockManager(registerStore: Boolean): VaultLockManager =
        VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
            if (registerStore) it.registerBlobReKeyStore(store)
        }

    private suspend fun seedFileItem(blobRef: String, thumbnailRef: String?) {
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = UUID.randomUUID(),
                title = "Passport scan",
                createdAt = now,
                updatedAt = now,
                content = FileContent(
                    filename = "passport.jpg",
                    mime = "image/jpeg",
                    sizeBytes = 200_000L,
                    blobRef = blobRef,
                    thumbnailRef = thumbnailRef,
                ),
            ),
        )
    }

    private fun password() = "correct horse battery staple".toCharArray()

    private fun newPassword() = "a different long passphrase".toCharArray()

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, KdfParams.FLOOR)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = KdfParams.FLOOR,
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

    private fun wipe() {
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }
}
