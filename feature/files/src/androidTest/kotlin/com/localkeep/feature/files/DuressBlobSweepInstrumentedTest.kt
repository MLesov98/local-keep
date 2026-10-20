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
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.DuressUnlocker
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The on-unlock blob sweep must never run against a denominator it cannot see all of.
 *
 * A session holds the key for exactly one payload region, so the referenced set it can compute is
 * that region's alone. A duress session computes it from the decoy — which references no blobs at
 * all — so "nothing points at this blob" was true of every blob the *real* vault owns, and the
 * sweep deleted the user's entire media library the first time they opened the decoy. Nothing
 * warned them, and nothing could bring it back: `blobs/` is excluded from Auto Backup and device
 * transfer, and the rolling snapshots never held blobs.
 *
 * The symmetric direction is the same bug: a file added inside the decoy is unreferenced as far as
 * the next real unlock can tell.
 */
@RunWith(AndroidJUnit4::class)
class DuressBlobSweepInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var store: FileBlobStore

    @BeforeTest
    fun setUp() = runBlocking {
        wipe()
        createFreshVault()
        store = FileBlobStore(context)
        lockManager = newLockManager()
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
    fun aDuressSessionNeverSweepsTheRealVaultsBlobs() = runBlocking {
        val photo = ByteArray(50_000) { (it * 17 % 251).toByte() }
        val dek = lockManager.currentDekOrThrow().copyOf()
        val ref = store.encryptToBlob(ByteArrayInputStream(photo), dek)
        seedFileItem(ref)
        Zeroizer.wipe(dek)
        // Past the sweep's ten-minute write grace. Without this the blob is spared for a reason
        // that has nothing to do with the defect, and the test would pass against the broken code.
        ageBlob(ref)
        assertTrue(blobFile(ref).isFile, "control: the blob is on disk and old enough to be swept")

        DuressUnlocker.setupDuress(lockManager, duressPassword(), decoyVault())
        lockManager.lock()
        lockManager.unlock(duressPassword())

        val swept = BlobGarbageCollector(repository, lockManager, store).sweep()

        assertEquals(0, swept, "a duress session must not delete anything it cannot see")
        assertTrue(
            blobFile(ref).isFile,
            "the real vault's blob was destroyed by opening the decoy",
        )
    }

    @Test
    fun aRealSessionStillSweepsWhenNoDecoyExists() = runBlocking {
        // The control the fix needs: declining under duress must not have turned reclamation off
        // for everyone. No decoy here, and the blob is referenced by nothing.
        val dek = lockManager.currentDekOrThrow().copyOf()
        val orphan = store.encryptToBlob(ByteArrayInputStream(ByteArray(1_000)), dek)
        Zeroizer.wipe(dek)
        ageBlob(orphan)
        assertTrue(blobFile(orphan).isFile, "control: the orphan is on disk")

        val swept = BlobGarbageCollector(repository, lockManager, store).sweep()

        assertEquals(1, swept, "an unreferenced blob must still be reclaimed")
        assertTrue(!blobFile(orphan).isFile)
    }

    private fun blobFile(ref: String) = File(File(context.filesDir, "blobs"), "$ref.enc")

    private fun ageBlob(ref: String) {
        val twentyMinutesAgo = System.currentTimeMillis() - 20L * 60L * 1_000L
        assertTrue(blobFile(ref).setLastModified(twentyMinutesAgo), "could not age the blob")
    }

    private suspend fun seedFileItem(blobRef: String) {
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
                    sizeBytes = 50_000L,
                    blobRef = blobRef,
                    thumbnailRef = null,
                ),
            ),
        )
    }

    private fun decoyVault(): Vault {
        val now = Instant.now()
        return Vault(items = emptyList(), folders = emptyList(), createdAt = now, modifiedAt = now)
    }

    private fun newLockManager(): VaultLockManager =
        VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }

    private fun password() = "correct horse battery staple".toCharArray()

    private fun duressPassword() = "a completely different phrase".toCharArray()

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
