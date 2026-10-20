package com.localkeep.feature.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultSessionEndedException
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
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The export re-auth ran inside a `try`/`finally` with no `catch`, so the
 * two typed failures [VaultLockManager.reauthenticate] raises left `viewModelScope.launch`
 * uncaught. It now runs in [BackupSettingsViewModel.requestExportDestination], in front of the
 * destination picker rather than after it (F-39), and these two cases must still be caught there.
 *
 * Nothing in this app installs a `CoroutineExceptionHandler` or a default
 * `Thread.UncaughtExceptionHandler`, so an escaping throwable there is process death, not a logged
 * warning — and this is the one screen that makes the trigger ordinary: the export form clears the
 * password field after every attempt, so repeated wrong entries are the expected interaction, and
 * enough of them arm the shared master-password backoff that [VaultLockedException] reports.
 *
 * Both cases must render an error and leave the ViewModel idle instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSettingsViewModelLockFailureTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(vaultLockManager: VaultLockManager): BackupSettingsViewModel {
        val application = ApplicationProvider.getApplicationContext<Application>()
        return BackupSettingsViewModel(
            application = application,
            vaultExporter = mock(),
            vaultImporter = mock(),
            rollingBackupManager = mock(),
            vaultLockManager = vaultLockManager,
            vaultRepository = mock<VaultRepository>(),
        )
    }

    @Test
    fun `requesting a destination renders a vault that locked mid-check instead of letting it kill the process`() =
        runTest(dispatcher) {
            val vaultLockManager = mock<VaultLockManager>()
            whenever(vaultLockManager.reauthenticate(any())).thenAnswer {
                throw VaultSessionEndedException("reauthenticate")
            }
            val viewModel = buildViewModel(vaultLockManager)

            viewModel.requestExportDestination("pw".toCharArray())
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                BackupMessage.Res(R.string.backup_error_export_vault_locked),
                viewModel.state.value.error,
            )
            assertFalse(viewModel.state.value.isWorking)
        }

    @Test
    fun `requesting a destination renders an armed master-password lockout instead of letting it kill the process`() =
        runTest(dispatcher) {
            val vaultLockManager = mock<VaultLockManager>()
            whenever(vaultLockManager.reauthenticate(any())).thenAnswer {
                throw VaultLockedException(retryAfterMs = 4_000L)
            }
            val viewModel = buildViewModel(vaultLockManager)

            viewModel.requestExportDestination("pw".toCharArray())
            dispatcher.scheduler.advanceUntilIdle()

            // The generic message, not a countdown: rendering "retry in N seconds" the way
            // SettingsViewModel does needs a plural this module does not have, and a new string
            // cannot land without translations for all five languages.
            assertEquals(
                BackupMessage.Res(R.string.backup_error_generic),
                viewModel.state.value.error,
            )
            assertFalse(viewModel.state.value.isWorking)
        }

    /**
     * The same defect on the same screen, in the path the audit missed:
     * [BackupSettingsViewModel.applyKdfRecalibration] had no `try` at all, and `rewrapKdfParams`
     * opens on `realSessionSnapshot`, which reports a locked vault with a bare
     * `IllegalStateException` — so a third throwable, not just the two typed ones, escaped there.
     *
     * Drives the real [BackupSettingsViewModel.checkKdfCalibration] first because `kdfSuggestion`
     * has no other setter, which means a real vault container on disk and a real Argon2id
     * calibration; both are held to [KdfParams.FLOOR] to keep the cost down. The lock manager is
     * mocked with a throwing default answer rather than a stubbed `rewrapKdfParams` because that
     * function is `internal` to `:core:vault` and cannot be named from this module.
     */
    @Test
    fun `applying a recalibration renders a locked vault instead of letting it kill the process`() =
        runTest(dispatcher) {
            writeVaultContainer()
            val vaultLockManager = mock<VaultLockManager>(
                defaultAnswer = Answer<Any> { throw IllegalStateException("vault is locked") },
            )
            val viewModel = buildViewModel(vaultLockManager)

            viewModel.checkKdfCalibration()
            awaitState(viewModel) { it.kdfSuggestion != null }

            viewModel.applyKdfRecalibration("pw".toCharArray())
            awaitState(viewModel) { it.error != null }

            assertEquals(
                BackupMessage.Res(R.string.backup_error_generic),
                viewModel.state.value.error,
            )
            assertFalse(viewModel.state.value.isWorking)
        }

    /**
     * The same escape, one exception family later, and the one the `IllegalStateException` arm
     * above cannot take.
     *
     * `rewrapKdfParams` ends in `AtomicVaultWriter.write`, whose `FileOutputStream` constructor,
     * `fd.sync()` and two `Files.move(ATOMIC_MOVE)` calls are all plain `IOException` — a full
     * disk, a filesystem that will not fsync, a move the platform refuses to make atomic. None is
     * an `IllegalStateException`, so each escaped `viewModelScope.launch` uncaught, and there is
     * nothing in this app for it to land on. The user was recalibrating right after a restore onto
     * new hardware — the one flow that offers this button — and got process death.
     *
     * Production line under test: the `catch (cause: IOException)` arm in
     * `BackupSettingsViewModel.applyKdfRecalibration`. Delete that arm and this test goes red; the
     * `IllegalStateException` test above stays green either way, which is what makes it the control
     * for this one.
     */
    @Test
    fun `applying a recalibration renders a failed write instead of letting it kill the process`() =
        runTest(dispatcher) {
            writeVaultContainer()
            val vaultLockManager = mock<VaultLockManager>(
                defaultAnswer = Answer<Any> { throw IOException("No space left on device") },
            )
            val viewModel = buildViewModel(vaultLockManager)

            viewModel.checkKdfCalibration()
            awaitState(viewModel) { it.kdfSuggestion != null }

            viewModel.applyKdfRecalibration("pw".toCharArray())
            awaitState(viewModel) { it.error != null }

            assertEquals(
                BackupMessage.Res(R.string.backup_error_generic),
                viewModel.state.value.error,
            )
            // As load-bearing as the message: without it the screen stays behind its own in-flight
            // guard and every control on it is dead for the rest of the session.
            assertFalse(viewModel.state.value.isWorking)
        }

    /**
     * A container `VaultLoader` will accept, so `VaultKdfInspector.storedParams` can read cost
     * parameters out of its header. The filename is spelled out because `AtomicVaultWriter`'s
     * constants are `internal` to `:core:vault`.
     */
    private fun writeVaultContainer() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kdfParams = KdfParams.FLOOR
        val kek = EnvelopeCrypto.deriveKek("password".toCharArray(), salt, kdfParams)
        val header = VaultHeaderCodec.create(
            salt,
            kdfParams,
            EnvelopeCrypto.wrapDek(kek, dek),
            EnvelopeCrypto.slotCommitment(dek),
        )
        val now = Instant.now()
        val bytes = VaultFileCodec.encrypt(
            Vault(items = emptyList(), createdAt = now, modifiedAt = now),
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
        File(application.filesDir, "vault.enc").writeBytes(bytes)
    }

    /**
     * Both KDF paths hop to `Dispatchers.Default`, which [StandardTestDispatcher] does not control,
     * so `advanceUntilIdle()` on its own returns while a real Argon2id derivation is still running.
     * Alternate draining the test scheduler with a short real wait until the state settles.
     */
    private fun awaitState(
        viewModel: BackupSettingsViewModel,
        predicate: (BackupSettingsUiState) -> Boolean,
    ) {
        repeat(600) {
            dispatcher.scheduler.advanceUntilIdle()
            if (predicate(viewModel.state.value)) return
            Thread.sleep(50)
        }
        error("state never settled; last value was ${viewModel.state.value}")
    }
}
