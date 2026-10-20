package com.localkeep.feature.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.VaultRepository
import java.io.File
import java.time.Instant
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
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A staged `.vault` restore must not be thrown away in silence.
 *
 * Picking a backup, typing its password and waiting for it to decrypt leaves a vault in memory with
 * Merge and Replace offered. Back and the header chevron both dropped it without a word, and
 * `MainActivity`'s lock collector posted no notice either — its test is
 * `AppNavGraph.droppedEditRoute(currentRoute)`, which names the import wizard and has never named
 * `backup` (fixed 2026-09-13).
 *
 * Two halves, and the second one is where the fix could have gone wrong. Adding `"backup"` to that
 * route list is the obvious move and is the *wrong* one: the route says where the user was, not
 * what they had, so every auto-lock on a backup screen someone was merely reading would claim their
 * changes were discarded. `droppedEditRoute` already carries a scar from exactly that shape — a
 * broad `startsWith("item/")` announcing dropped edits to people who had only viewed an item. The
 * question belongs to the ViewModel, and [BackupStaging] is how the answer reaches an Activity that
 * is about to pop the ViewModel's own back-stack entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StagedImportExitGuardTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        BackupStaging.take()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        BackupStaging.take()
    }

    @Test
    fun `a staged restore makes the screen ask before it lets go`() = runTest(dispatcher) {
        val viewModel = stagedViewModel()

        assertTrue(
            viewModel.state.value.asksBeforeExit,
            "a decrypted backup waiting on Merge or Replace must be confirmed before leaving",
        )
        assertTrue(BackupStaging.take(), "the lock collector has no other way to learn this")
    }

    /**
     * The control, and the one that keeps this from becoming a dialog people learn to dismiss.
     * `asksBeforeExit` is driven by the staged vault, never by "something is happening": an export
     * finishes on its own and touches nothing local, and the screen deliberately lets the user walk
     * away from one.
     */
    @Test
    fun `work in flight on its own never asks`() {
        val exporting = BackupSettingsUiState(isWorking = true, isExporting = true)
        val replacing = BackupSettingsUiState(isWorking = true)

        assertFalse(exporting.asksBeforeExit, "an export must not raise the discard question")
        assertFalse(replacing.asksBeforeExit, "a replace refuses to be left; it does not ask")
        assertTrue(replacing.blocksExit, "the control's control: blocksExit is the refusal")
        assertFalse(exporting.blocksExit)
    }

    @Test
    fun `discarding the staged restore lets the screen go`() = runTest(dispatcher) {
        val viewModel = stagedViewModel()

        viewModel.cancelImport()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.state.value.asksBeforeExit)
        assertFalse(BackupStaging.take(), "the dropped-restore notice must not outlive the discard")
    }

    /**
     * Both ways out go through the confirmation, not just Back. A chevron that leaves silently is
     * the same defect as a Back that does, and the screen's own comment says the two are halves of
     * one guard.
     *
     * Asserted on the source rather than by driving the composable, for the reason
     * [ExportPassphraseGateTest] states: `:feature:backup` declares no Compose UI test dependency.
     */
    @Test
    fun `both Back and the header chevron route through the discard confirmation`() {
        val screen = File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt")
            .readText()

        val backHandler = bounded(screen, "BackHandler(enabled =", "// Pinned, outside the scroller")
        assertTrue(
            backHandler.contains("state.asksBeforeExit") && backHandler.contains("confirmDiscardImport = true"),
            "Back must raise the discard confirmation while a restore is staged. Found:\n$backHandler",
        )
        assertTrue(
            backHandler.contains("state.blocksExit"),
            "the control: Back must still refuse outright while an operation owns the screen",
        )

        val chevron = bounded(screen, "R.string.backup_screen_back", "plateAlignment =")
        assertTrue(
            chevron.contains("state.asksBeforeExit") && chevron.contains("confirmDiscardImport = true"),
            "the header chevron must raise the same confirmation. Found:\n$chevron",
        )
        assertTrue(
            chevron.contains("onBack()"),
            "the control: the chevron must still leave when nothing is staged",
        )
    }

    /**
     * A slice bounded at both ends, or a loud failure — the idiom
     * [ReplaceControlIsWorkingGateTest] documents. `substringAfter`/`substringBefore` return the
     * whole receiver when the delimiter is absent, so a renamed anchor would widen the slice to the
     * rest of a 900-line file in which `confirmDiscardImport = true` also appears at the dialog.
     */
    private fun bounded(source: String, after: String, before: String): String {
        assertTrue(source.contains(after), "anchor \"$after\" no longer occurs in the screen")
        val tail = source.substringAfter(after)
        assertTrue(tail.contains(before), "delimiter \"$before\" no longer occurs after \"$after\"")
        return tail.substringBefore(before)
    }

    /** A ViewModel with a decrypted backup staged, through the real `stageImport` path. */
    private suspend fun stagedViewModel(): BackupSettingsViewModel {
        val now = Instant.now()
        val importer = mock<VaultImporter>()
        whenever(importer.readVault(any(), any())).thenReturn(
            Outcome.Success(
                VaultImporter.ImportResult(
                    vault = Vault(items = emptyList(), createdAt = now, modifiedAt = now),
                    missingBlobRefCount = 0,
                ),
            ),
        )
        val viewModel = BackupSettingsViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            vaultExporter = mock(),
            vaultImporter = importer,
            rollingBackupManager = mock(),
            vaultLockManager = mock(),
            vaultRepository = mock<VaultRepository>(),
        )
        viewModel.stageImport(mock<Uri>(), "password".toCharArray())
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }
}
