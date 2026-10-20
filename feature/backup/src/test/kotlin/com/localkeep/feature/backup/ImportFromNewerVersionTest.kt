package com.localkeep.feature.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.VaultSchemaTooNewException
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
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
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * A `.vault` file written by a newer LocalKeep is intact and its password is correct; only this
 * build is too old to read what is inside it.
 *
 * By the time `decryptThenRestore`'s `decrypt` runs, the KEK has unwrapped the DEK and
 * `KeyCommitment.verify` has passed — the password is *proven* right. The only remaining failure
 * the payload can produce is `VaultSerializer.decode` refusing a schema stamp above
 * `Vault.CURRENT_SCHEMA_VERSION`, which the blanket catch turned into "Couldn't decrypt the file
 * you chose." That sends the user to re-type a password that was never wrong, and says nothing
 * about the one action that would work: updating the app.
 *
 * The unlock path already draws this distinction — `VaultLockManager` catches
 * `VaultSchemaTooNewException` apart from every other decode failure for the same reason, and
 * `unlock_error_vault_newer_version` is the sentence this one is adapted from.
 *
 * Two halves have to hold together: the importer must produce a distinct `AppError`, and
 * `BackupSettingsViewModel.toBackupMessage` matches on the **exact** message literal, so an arm
 * the table does not know renders `backup_error_generic` and the distinction is lost between the
 * two files.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportFromNewerVersionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a newer-schema payload is not reported as a decryption failure`() {
        val boom = VaultSchemaTooNewException(
            stamped = 99,
            supported = 5,
            message = "vault schema 99 is newer than this build supports (5)",
        )
        var restored = false

        val outcome = decryptThenRestore<String>(
            decrypt = { throw boom },
            restoreBlobs = { restored = true },
        )

        val failure = assertIs<Outcome.Failure>(outcome)
        val error = assertIs<AppError.Validation>(failure.error)
        assertEquals("this backup was written by a newer version of LocalKeep", error.message)
        assertSame(boom, error.cause)
        assertFalse(restored, "blobs must not be restored from a payload this build cannot read")
    }

    @Test
    fun `the newer-version literal reaches its own string, not the generic one`() =
        runTest(dispatcher) {
            val vaultImporter = mock<VaultImporter>()
            whenever(vaultImporter.readVault(any(), any())).thenReturn(
                Outcome.Failure(
                    AppError.Validation("this backup was written by a newer version of LocalKeep"),
                ),
            )
            val application = ApplicationProvider.getApplicationContext<Application>()
            val viewModel = BackupSettingsViewModel(
                application = application,
                vaultExporter = mock(),
                vaultImporter = vaultImporter,
                rollingBackupManager = mock(),
                vaultLockManager = mock<VaultLockManager>(),
                vaultRepository = mock<VaultRepository>(),
            )

            viewModel.stageImport(mock<Uri>(), "pw".toCharArray())
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                BackupMessage.Res(R.string.backup_error_import_newer_version),
                viewModel.state.value.error,
            )
        }
}
