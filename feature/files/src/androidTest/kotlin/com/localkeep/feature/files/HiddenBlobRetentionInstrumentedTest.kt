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
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The one row of the chokepoint table where getting it wrong destroys data instead of disclosing it.
 *
 * The garbage collector deletes every on-disk blob nothing points at. "Nothing points at it" has to
 * be computed from the complete storage inventory, because a concealed item is by construction
 * absent from every presentation read — so a sweep driven by one would conclude that the attachments
 * of exactly the entries the user chose to protect are unreferenced, and delete them. Permanently,
 * silently, and with no way to notice until the lease expires and the file is gone.
 *
 * The second case here is older than the feature and was found while wiring it: `getTrashItems()`
 * deliberately omits items that went down with a folder, because the trash *screen* represents those
 * by the folder's row. Reachability is not a screen. Trashing a folder holding a photo and sweeping
 * dropped that photo's blob while the item was still restorable.
 */
@RunWith(AndroidJUnit4::class)
class HiddenBlobRetentionInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var gc: BlobGarbageCollector

    @BeforeTest
    fun setUp() = runBlocking {
        wipe()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
        gc = BlobGarbageCollector(repository, lockManager, FileBlobStore(context))
        Unit
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        wipe()
    }

    @Test
    fun aHiddenItemsAttachmentBlobStaysReferenced() = runBlocking {
        val id = seedItemWithAttachment("Offshore statement", "blob-hidden")
        assertTrue("blob-hidden" in gc.referencedRefs(), "control: referenced while visible")

        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        assertTrue(
            "blob-hidden" in gc.referencedRefs(),
            "a concealed item's blob must stay referenced — a sweep that drops it is permanent data loss",
        )
        assertTrue(
            repository.visibilitySnapshot().liveItems.none { it.id == id },
            "and it really is concealed, so this is not passing by accident",
        )
    }

    @Test
    fun anItemHiddenInsideAHiddenFolderKeepsItsBlobToo() = runBlocking {
        val folder = repository.createFolder("Offshore")
        val id = seedItemWithAttachment("Statement", "blob-descendant", folderId = folder.id)

        repository.hide(
            HiddenTarget.Folder(folder.id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        assertTrue(repository.visibilitySnapshot().liveItems.none { it.id == id }, "ancestry conceals it")
        assertTrue("blob-descendant" in gc.referencedRefs())
    }

    /** Predates the feature; the move to the complete inventory is what fixed it. */
    @Test
    fun anItemTrashedWithItsFolderKeepsItsBlob() = runBlocking {
        val folder = repository.createFolder("Receipts")
        seedItemWithAttachment("Receipt", "blob-folder-trashed", folderId = folder.id)

        repository.trashFolder(folder.id)

        assertTrue(
            "blob-folder-trashed" in gc.referencedRefs(),
            "a folder-trashed item is still restorable, so its blob is still referenced",
        )
    }

    private suspend fun seedItemWithAttachment(
        title: String,
        blobRef: String,
        folderId: UUID? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = title, createdAt = now, updatedAt = now, folderId = folderId,
                content = Note("nothing to see"),
                attachments = listOf(
                    Attachment(
                        id = UUID.randomUUID(),
                        filename = "statement.pdf",
                        mime = "application/pdf",
                        sizeBytes = 1_024,
                        blobRef = blobRef,
                    ),
                ),
            ),
        )
        return id
    }

    private fun password() = "correct horse battery staple".toCharArray()

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
