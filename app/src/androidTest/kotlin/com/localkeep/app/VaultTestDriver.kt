package com.localkeep.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.VaultInitializer
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.currentDekOrThrow
import com.localkeep.feature.files.FileBlobStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Real onboarding->add->view driver for [NoPlaintextToDiskTest] (Phase 16, Task 3 Step 3).
 * Instantiates the real production classes directly (all plain constructors, no Hilt needed for
 * a test) — creates a vault, adds a Login item whose password IS the marker, adds a File item
 * whose encrypted blob content IS the marker, then decrypts both back into memory (the real
 * view path) so the guardrail can scan app-private storage afterward.
 */
object VaultTestDriver {
    private var lockManager: VaultLockManager? = null
    private var fileBlobStore: FileBlobStore? = null

    fun createVaultAndAddSecret(
        context: Context,
        password: CharArray,
        secret: String,
    ) = runBlocking {
        // Idempotent across repeated local runs (connectedAndroidTest normally reinstalls fresh,
        // but `adb shell am instrument` re-runs against the same app-private storage otherwise).
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()

        val keystoreManager = KeystoreManager(context)
        val manager = VaultLockManager(context, keystoreManager)
        VaultInitializer.create(context, password.copyOf())
        val unlockResult = manager.unlock(password.copyOf())
        check(unlockResult == UnlockResult.REAL) { "test vault did not unlock: $unlockResult" }

        val repository = VaultRepository(manager)
        val blobStore = FileBlobStore(context)
        val dek = manager.currentDekOrThrow()
        val now = Instant.now()

        repository.addItem(
            VaultItem(
                id = UUID.randomUUID(),
                title = "Plaintext guardrail — login",
                createdAt = now,
                updatedAt = now,
                content = Login(username = "guardrail", password = secret),
            ),
        )

        val blobRef = blobStore.encryptToBlob(
            ByteArrayInputStream(secret.toByteArray(Charsets.UTF_8)),
            dek,
        )
        repository.addItem(
            VaultItem(
                id = UUID.randomUUID(),
                title = "Plaintext guardrail — blob",
                createdAt = now,
                updatedAt = now,
                content = com.localkeep.core.model.File(
                    filename = "marker.bin",
                    mime = "application/octet-stream",
                    sizeBytes = secret.toByteArray(Charsets.UTF_8).size.toLong(),
                    blobRef = blobRef,
                ),
            ),
        )

        lockManager = manager
        fileBlobStore = blobStore
    }

    /** Exercises the real in-memory-only view/decrypt path for every item added above. */
    fun viewFirstItemAndAnyBlob(context: Context) = runBlocking {
        val manager = checkNotNull(lockManager) { "call createVaultAndAddSecret first" }
        val blobStore = checkNotNull(fileBlobStore) { "call createVaultAndAddSecret first" }
        val repository = VaultRepository(manager)
        val dek = manager.currentDekOrThrow()

        val items = repository.getAllItems()
        check(items.isNotEmpty()) { "expected at least one item after createVaultAndAddSecret" }
        items.forEach { item ->
            val content = item.content
            if (content is com.localkeep.core.model.File && content.blobRef.isNotBlank()) {
                blobStore.decryptToBytes(content.blobRef, dek) // RAM-only decrypt (FileBlobStore contract)
            }
        }
    }

    /**
     * Seeds the named standalone-file fixture used by the viewer device flow. It intentionally
     * contains one page per renderer family plus a nested page, so a pager that accidentally
     * includes attachments or non-visible descendants fails against a real projection.
     */
    fun createMediaViewerFixture(context: Context, password: CharArray) = runBlocking {
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        val manager = VaultLockManager(context, KeystoreManager(context))
        VaultInitializer.create(context, password.copyOf())
        check(manager.unlock(password.copyOf()) == UnlockResult.REAL)
        val repository = VaultRepository(manager)
        val blobs = FileBlobStore(context)
        val dek = manager.currentDekOrThrow()
        val now = Instant.now()
        val nested = repository.createFolder("Viewer folder")

        val image = pngFixture()
        addViewerFile(repository, blobs, dek, VIEWER_IMAGE_ID, "Viewer image", "viewer.png", "image/png", image, now)
        addViewerFile(repository, blobs, dek, VIEWER_PDF_ID, "Viewer PDF", "viewer.pdf", "application/pdf", "%PDF-1.4\n%%EOF".toByteArray(), now)
        addViewerFile(repository, blobs, dek, VIEWER_TEXT_ID, "Viewer text", "viewer.txt", "text/plain", "viewer fixture text".toByteArray(), now)
        addViewerFile(repository, blobs, dek, VIEWER_UNSUPPORTED_ID, "Viewer binary", "viewer.bin", "application/octet-stream", byteArrayOf(0x01, 0x02, 0x03), now)
        addViewerFile(repository, blobs, dek, VIEWER_NESTED_ID, "Nested viewer file", "nested.txt", "text/plain", "nested".toByteArray(), now, nested.id)

        // The first page is favorite, proving the launch snapshot follows the rendered list order.
        repository.updateWith(VIEWER_IMAGE_ID) { it.copy(favorite = true) }
        check(repository.getAllItems().size == 5) { "media viewer fixture should contain five root/nested files" }
        manager.lock()

        val reopened = VaultLockManager(context, KeystoreManager(context))
        check(reopened.unlock(password.copyOf()) == UnlockResult.REAL) {
            "media viewer fixture should reopen with its seed password"
        }
        check(VaultRepository(reopened).getAllItems().size == 5) {
            "media viewer fixture should persist five root/nested files"
        }
        reopened.lock()
    }

    private suspend fun addViewerFile(
        repository: VaultRepository,
        blobs: FileBlobStore,
        dek: ByteArray,
        id: UUID,
        title: String,
        filename: String,
        mime: String,
        bytes: ByteArray,
        now: Instant,
        folderId: UUID? = null,
    ) {
        val ref = blobs.encryptToBlob(ByteArrayInputStream(bytes), dek)
        repository.addItem(
            VaultItem(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                folderId = folderId,
                content = com.localkeep.core.model.File(
                    filename = filename,
                    mime = mime,
                    sizeBytes = bytes.size.toLong(),
                    blobRef = ref,
                ),
            ),
        )
    }

    private fun pngFixture(): ByteArray {
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(42, 160, 130))
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    private const val VIEWER_IMAGE_ID_STRING = "00000000-0000-4000-8000-0000000000e1"
    private val VIEWER_IMAGE_ID = UUID.fromString(VIEWER_IMAGE_ID_STRING)
    private val VIEWER_PDF_ID = UUID.fromString("00000000-0000-4000-8000-0000000000e2")
    private val VIEWER_TEXT_ID = UUID.fromString("00000000-0000-4000-8000-0000000000e3")
    private val VIEWER_UNSUPPORTED_ID = UUID.fromString("00000000-0000-4000-8000-0000000000e4")
    private val VIEWER_NESTED_ID = UUID.fromString("00000000-0000-4000-8000-0000000000e5")
}
