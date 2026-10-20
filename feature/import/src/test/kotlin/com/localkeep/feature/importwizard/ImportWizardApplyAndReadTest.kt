package com.localkeep.feature.importwizard

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.import.ImportFailureReason
import com.localkeep.core.model.File as FileContent
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultMutator
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultTooLargeForDeviceException
import com.localkeep.core.vault.VaultVisibilitySnapshot
import com.localkeep.feature.files.FileBlobStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The import wizard, driven end to end with its vault-side collaborators mocked (2026-10-02).
 *
 *  - **S2-02** — a failed apply wiped the `.1pux` file payloads while the preview stayed up with
 *    Import enabled, so the retry sealed nothing and saved every Document as a File item pointing at
 *    no blob, reported as a success.
 *  - **S2-08** — every apply failure said "Couldn't save the imported items.", including a vault too
 *    large for this device, which no retry fixes.
 *  - **S2-07** — a file over the 64 MB read cap said "Couldn't read that file."
 *  - **S2-10** — the keyboard's Done key re-entered `submitPassword` while a parse was running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportWizardApplyAndReadTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now: Instant = Instant.now()

    /** Every attempt at the vault write; the first [failingWrites] of them throw. */
    private var writes = 0
    private var failingWrites = 0
    private val committed = mutableListOf<VaultItem>()
    private val sealedBytes = mutableListOf<ByteArray>()

    // Built before the repository mock: a mock stubbed inside another's unfinished stubbing is refused.
    private val emptyVault: VaultVisibilitySnapshot = mock {
        on { liveItems } doReturn emptyList()
    }

    private val repository: VaultRepository = mock {
        on { visibilitySnapshot() } doReturn emptyVault
        onBlocking { mutate(any()) } doAnswer { invocation ->
            writes++
            if (writes <= failingWrites) throw VaultTooLargeForDeviceException(heapLimitMib = 48)
            @Suppress("UNCHECKED_CAST")
            val block = invocation.arguments[0] as (VaultMutator) -> Unit
            val mutator = VaultMutator(Vault(items = emptyList(), createdAt = now, modifiedAt = now))
            block(mutator)
            committed += mutator.items
            true
        }
    }

    private val lockManager: VaultLockManager = mock {
        on { sessionSnapshotOrNull() } doAnswer {
            VaultLockManager.SessionSnapshot(
                vault = Vault(items = emptyList(), createdAt = now, modifiedAt = now),
                dek = ByteArray(32) { 9 },
            )
        }
    }

    private val blobStore: FileBlobStore = mock {
        on { encryptToBlob(any(), any()) } doAnswer { invocation ->
            sealedBytes += (invocation.arguments[0] as InputStream).readBytes()
            "00000000-0000-4000-8000-%012d".format(sealedBytes.size)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ImportWizardViewModel(repository, lockManager, blobStore, context)

    /** Runs the Main queue until [done], letting the IO and Default halves finish on their threads. */
    private fun settle(done: () -> Boolean) {
        repeat(1_000) {
            dispatcher.scheduler.advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("the wizard never settled")
    }

    private fun pickedFile(bytes: ByteArray): Uri =
        Uri.fromFile(File.createTempFile("import", ".bin", context.cacheDir).apply { writeBytes(bytes) })

    /** A `.1pux` holding one Document item and the file it is. */
    private fun onePuxWithDocument(): ByteArray {
        val exportData = """{ "accounts": [ { "vaults": [ { "items": [ {
            "categoryUuid": "006",
            "overview": { "title": "Passport scan" },
            "details": { "documentAttributes": { "fileName": "passport.pdf", "documentId": "doc1" } }
        } ] } ] } ] }"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("export.data"))
            zip.write(exportData.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("files/doc1__passport.pdf"))
            zip.write(DOCUMENT)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun previewOf(bytes: ByteArray, source: ImportSource): ImportWizardViewModel {
        val vm = viewModel()
        vm.selectSource(source)
        vm.onFilePicked(pickedFile(bytes))
        settle { !vm.state.value.isWorking }
        return vm
    }

    @Test
    fun `a retried apply still seals the file a failed apply could not save`() {
        failingWrites = 1
        val vm = previewOf(onePuxWithDocument(), ImportSource.ONEPASSWORD)
        assertEquals(ImportUiState.Step.PREVIEW, vm.state.value.step)

        vm.applyImport()
        settle { !vm.state.value.isWorking }
        assertEquals(ImportUiState.Step.PREVIEW, vm.state.value.step, "a failed apply stays on the preview")

        vm.applyImport()
        settle { vm.state.value.step == ImportUiState.Step.DONE }

        val file = committed.single().content as FileContent
        assertTrue(file.blobRef.isNotBlank(), "the saved File item must point at a sealed blob")
        assertTrue(
            DOCUMENT.contentEquals(sealedBytes.last()),
            "the retry must seal the document's own bytes, not a wiped buffer",
        )
    }

    @Test
    fun `a vault too large for this device is named, not reported as a generic save failure`() {
        failingWrites = 1
        val vm = previewOf(onePuxWithDocument(), ImportSource.ONEPASSWORD)

        vm.applyImport()
        settle { !vm.state.value.isWorking }

        assertEquals(ImportError.Simple(R.string.import_error_save_vault_too_large), vm.state.value.error)
    }

    @Test
    fun `save failures are classified, and anything unknown keeps the generic message`() {
        assertEquals(R.string.import_error_save_vault_too_large, saveFailureMessage(VaultTooLargeForDeviceException(48)))
        assertEquals(
            R.string.import_error_save_out_of_space,
            saveFailureMessage(java.io.IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(R.string.import_error_save_failed, saveFailureMessage(IllegalStateException("boom")))
    }

    @Test
    fun `a file over the read cap says it is too large`() {
        val big = File.createTempFile("big", ".1pux", context.cacheDir)
        RandomAccessFile(big, "rw").use { it.setLength(ImportWizardViewModel.MAX_IMPORT_BYTES + 1L) }
        val vm = viewModel()
        vm.selectSource(ImportSource.ONEPASSWORD)

        vm.onFilePicked(Uri.fromFile(big))
        settle { !vm.state.value.isWorking }

        assertEquals(
            ImportError.FromImporter(ImportFailureReason.ExportTooLarge(64)),
            vm.state.value.error,
        )
        big.delete()
    }

    @Test
    fun `a second Done while a password is being tried does not start another parse`() {
        // A KDBX 4 signature and nothing else: enough to reach the password step, which a file with no
        // signature no longer does (KeePassTileChecksTheHeaderFirstTest), and to fail the derivation fast.
        val kdbxSignature = byteArrayOf(
            0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(),
            0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte(),
        )
        val vm = previewOf(kdbxSignature + ByteArray(64), ImportSource.KEEPASS)
        assertEquals(ImportUiState.Step.PASSWORD, vm.state.value.step)

        vm.onPasswordChanged("first")
        vm.submitPassword()
        assertTrue(vm.state.value.isWorking)
        vm.onPasswordChanged("second")
        vm.submitPassword()

        assertEquals("second", vm.state.value.password, "the second submit must be refused before it takes the password")
        settle { !vm.state.value.isWorking }
    }

    private companion object {
        val DOCUMENT = "%PDF-1.4 a passport scan".toByteArray()
    }
}
