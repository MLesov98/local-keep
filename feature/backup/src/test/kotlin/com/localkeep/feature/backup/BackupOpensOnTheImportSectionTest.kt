package com.localkeep.feature.backup

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Arriving from the import wizard's "Open Backup & export" lands on "Import a .vault file".
 *
 * Device report C3 finding 1 (2026-10-04): the wizard now recognises a LocalKeep backup and hands
 * the user to Backup & export — and that screen opens on Cloud backup, three sections above the one
 * the user was sent for, on a phone they set up minutes ago. The hand-off asks for the section the
 * same way the drawer's "Restore a snapshot" asks for its own ([BackupFocus]).
 *
 * The real screen, composed with a ViewModel built from mocks the way the other backup ViewModel
 * tests build it. NATIVE graphics so text has real heights and the screen is really taller than the
 * window — without that everything fits and every scroll assertion passes against broken code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupOpensOnTheImportSectionTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        BackupFocus.clear()
    }

    private fun show() {
        val viewModel = BackupSettingsViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            vaultExporter = mock(),
            vaultImporter = mock(),
            rollingBackupManager = mock(),
            vaultLockManager = mock<VaultLockManager>(),
            vaultRepository = mock<VaultRepository>(),
        )
        compose.setContent {
            LocalKeepTheme {
                BackupSettingsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    recoveryKitVoidedNotice = R.string.backup_status_import_complete,
                )
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    /**
     * Unclipped: `boundsInRoot` of a node scrolled wholly out of the viewport is `Rect.Zero`, which
     * reads as "at the very top" — the first draft of this test passed against unchanged code on
     * exactly that.
     */
    private fun importSectionTop(): Float =
        compose.onNodeWithTag("backup-import-section").fetchSemanticsNode().positionInRoot.y

    private fun windowHeight(): Float = compose.onRoot().fetchSemanticsNode().boundsInRoot.height

    @Test
    fun `the hand-off opens the screen on the import section`() {
        BackupFocus.request(BackupFocus.IMPORT)
        show()

        val top = importSectionTop()
        assertTrue(
            top >= 0f && top < windowHeight() / 2,
            "Import a .vault file starts at y=$top in a ${windowHeight()}px window — the user lands " +
                "on Cloud backup and has to find the section the wizard sent them to",
        )
    }

    @Test
    fun `arriving any other way still opens at the top`() {
        // The control: the screen's own order is unchanged for everyone the wizard did not send.
        show()

        assertTrue(
            importSectionTop() > windowHeight() / 2,
            "with no request the import section must sit where it always has, below Cloud backup " +
                "(it starts at y=${importSectionTop()} in a ${windowHeight()}px window)",
        )
    }
}
