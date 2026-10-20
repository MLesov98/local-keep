package com.localkeep.feature.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A failed [BackupSettingsViewModel.stageImport] must not leave the previously staged vault armed.
 *
 * `stageImport`'s failure branch reported the error and wrote nothing else, so a pick that failed
 * on top of an earlier *successful* one — a wrong password for the second file, or an unreadable
 * one — answered "Wrong password for this file" while Merge and Replace stayed live on the file
 * staged before it. Confirming then applied the earlier file. For Replace that is the app's one
 * vault-discarding act, performed on a selection the user believed they had just changed; it is
 * recoverable from the rolling snapshots listed further down the same screen (which hold `vault.enc`
 * but no media), which is what keeps this a correctness bug rather than a data-loss one.
 *
 * `pendingImportMissingBlobRefCount` goes stale by the same route and is asserted with it: left
 * behind it describes the discarded file and picks the *next* import's completion message.
 *
 * The ordinary "wrong password, try again" case is unaffected and is the control below — it starts
 * with nothing staged, and nothing staged is what it must still have afterwards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StaleStagedImportTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(vaultImporter: VaultImporter): BackupSettingsViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return BackupSettingsViewModel(
            application = application,
            vaultExporter = mock(),
            vaultImporter = vaultImporter,
            rollingBackupManager = mock(),
            vaultLockManager = mock<VaultLockManager>(),
            vaultRepository = mock<VaultRepository>(),
        )
    }

    private fun aVault(): Vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH)

    @Test
    fun `a failed second pick disarms the vault the first pick staged`() = runTest(dispatcher) {
        val vaultImporter = mock<VaultImporter>()
        whenever(vaultImporter.readVault(any(), any())).thenReturn(
            Outcome.Success(VaultImporter.ImportResult(aVault(), missingBlobRefCount = 3)),
        )
        val viewModel = buildViewModel(vaultImporter)

        viewModel.stageImport(mock<Uri>(), "right".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull(
            viewModel.state.value.pendingImportVault,
            "the test has to actually stage something first, or it proves nothing",
        )

        whenever(vaultImporter.readVault(any(), any())).thenReturn(
            Outcome.Failure(AppError.Validation("wrong password")),
        )
        viewModel.stageImport(mock<Uri>(), "wrong".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(
            viewModel.state.value.pendingImportVault,
            "the second pick failed, so Merge and Replace must not still be armed on the file the " +
                "first one staged — confirming would apply a vault the user did not choose",
        )
        assertEquals(
            0,
            viewModel.state.value.pendingImportMissingBlobRefCount,
            "the missing-file count describes the discarded staging and would otherwise decide the " +
                "next import's completion message",
        )
    }

    @Test
    fun `a first pick that fails still leaves nothing staged`() = runTest(dispatcher) {
        // The control: the ordinary wrong-password case, which was never broken.
        val vaultImporter = mock<VaultImporter>()
        whenever(vaultImporter.readVault(any(), any())).thenReturn(
            Outcome.Failure(AppError.Validation("wrong password")),
        )
        val viewModel = buildViewModel(vaultImporter)

        viewModel.stageImport(mock<Uri>(), "wrong".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.state.value.pendingImportVault)
        assertEquals(0, viewModel.state.value.pendingImportMissingBlobRefCount)
    }

    @Test
    fun `a successful pick still stages its vault and its missing-file count`() =
        runTest(dispatcher) {
            // The other control: the clearing must live on the failure branch only. Moved a line
            // up, it would disarm every successful staging and Merge/Replace would never appear.
            val vaultImporter = mock<VaultImporter>()
            whenever(vaultImporter.readVault(any(), any())).thenReturn(
                Outcome.Success(VaultImporter.ImportResult(aVault(), missingBlobRefCount = 3)),
            )
            val viewModel = buildViewModel(vaultImporter)

            viewModel.stageImport(mock<Uri>(), "right".toCharArray())
            dispatcher.scheduler.advanceUntilIdle()

            assertNotNull(viewModel.state.value.pendingImportVault)
            assertEquals(3, viewModel.state.value.pendingImportMissingBlobRefCount)
        }
}
