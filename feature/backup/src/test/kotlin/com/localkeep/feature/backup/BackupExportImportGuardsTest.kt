package com.localkeep.feature.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Three ViewModel-level guards on the Backup screen (2026-10-02).
 *
 *  - **S2-05** — an empty, non-null export passphrase is the screen saying "separate passphrase,
 *    but the well came back blank", which an activity recreated while the picker was open produces.
 *    It used to fall through to a master-password export reported as an ordinary success.
 *  - **S2-09** — reading a `.vault` to stage it never touches the local vault, so it must not hold
 *    the user on the screen while a slow provider takes its time.
 *  - **S2-04** — the file's own key-derivation cost not fitting the heap is named, not generic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupExportImportGuardsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        vaultExporter: VaultExporter = mock(),
        vaultImporter: VaultImporter = mock(),
    ) = BackupSettingsViewModel(
        application = ApplicationProvider.getApplicationContext<Application>(),
        vaultExporter = vaultExporter,
        vaultImporter = vaultImporter,
        rollingBackupManager = mock(),
        vaultLockManager = mock<VaultLockManager>(),
        vaultRepository = mock<VaultRepository>(),
    )

    private val destination: Uri = Uri.parse("content://test.documents/document/vault-export.vault")

    @Test
    fun `a blank separate passphrase is refused rather than exported under the master password`() =
        runTest(dispatcher) {
            val exporter = mock<VaultExporter>()
            val vm = viewModel(vaultExporter = exporter)

            vm.export(destination, CharArray(0))
            dispatcher.scheduler.advanceUntilIdle()

            verify(exporter, never()).exportWithMasterPassword(any())
            assertEquals(BackupMessage.Res(R.string.backup_error_export_build_failed), vm.state.value.error)
            assertFalse(vm.state.value.isWorking)
        }

    /** The control: null is how the screen asks for a master-password export, and it still gets one. */
    @Test
    fun `no passphrase at all is still a master-password export`() = runTest(dispatcher) {
        val exporter = mock<VaultExporter>()
        whenever(exporter.exportWithMasterPassword(any())).thenReturn(
            Outcome.Success(VaultExporter.ExportManifest(sizeBytes = 1L, exportedAt = Instant.now())),
        )
        val vm = viewModel(vaultExporter = exporter)

        vm.export(destination, null)
        dispatcher.scheduler.advanceUntilIdle()

        verify(exporter).exportWithMasterPassword(destination)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `reading a file to stage it does not hold the user on the screen`() = runTest(dispatcher) {
        val importer = mock<VaultImporter>()
        whenever(importer.readVault(any(), any())).thenReturn(
            Outcome.Failure(AppError.Storage("failed to read import file")),
        )
        val vm = viewModel(vaultImporter = importer)

        vm.stageImport(mock<Uri>(), "pw".toCharArray())
        // Not advanced: the read has been launched and has not come back, which is the whole window
        // a stalled provider stretches out.
        assertTrue(vm.state.value.isWorking, "the stage is still in flight")
        assertFalse(vm.state.value.blocksExit, "a stalled read must not trap the user on the screen")

        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.state.value.isStagingImport, "the flag must clear with the stage")
    }

    /** The control: the operations that rewrite the local vault still refuse to be walked out of. */
    @Test
    fun `a vault-rewriting operation still blocks exit`() {
        assertTrue(BackupSettingsUiState(isWorking = true).blocksExit)
        assertFalse(BackupSettingsUiState(isWorking = true, isStagingImport = true).blocksExit)
    }

    @Test
    fun `a backup whose key derivation does not fit the heap names the memory failure`() =
        runTest(dispatcher) {
            val importer = mock<VaultImporter>()
            whenever(importer.readVault(any(), any())).thenReturn(
                Outcome.Failure(AppError.Storage(IMPORT_KDF_NEEDS_MORE_MEMORY)),
            )
            val vm = viewModel(vaultImporter = importer)

            vm.stageImport(mock<Uri>(), "pw".toCharArray())
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(BackupMessage.Res(R.string.backup_error_kdf_needs_more_memory), vm.state.value.error)
        }
}
