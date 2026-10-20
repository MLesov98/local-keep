package com.localkeep.feature.backup

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.LkUnlockNotices
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A lock that lands while a file picker is open leaves a notice for the next unlock.
 *
 * S9-F-09 (functional pass, 2026-10-15): the idle lock came up over the picker, the pick was then
 * handed to a screen that no longer existed, and after unlocking the user stood on the vault list
 * with nothing to say the file had been dropped. The screen leaving composition while it still has
 * a picker outstanding is the one moment that knows; a picker that has answered, or a screen that
 * leaves without one, queues nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockDuringPickerLeavesANoticeTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var onScreen by mutableStateOf(true)

    @Before
    fun setUp() {
        LkUnlockNotices.take()
    }

    @After
    fun tearDown() {
        LkUnlockNotices.take()
        BackupFocus.clear()
    }

    private fun show(): BackupSettingsViewModel {
        val viewModel = BackupSettingsViewModel(
            application = app,
            vaultExporter = mock(),
            vaultImporter = mock(),
            rollingBackupManager = mock(),
            vaultLockManager = mock<VaultLockManager>(),
            vaultRepository = mock<VaultRepository>(),
        )
        compose.setContent {
            LocalKeepTheme {
                if (onScreen) {
                    BackupSettingsScreen(
                        viewModel = viewModel,
                        onBack = {},
                        recoveryKitVoidedNotice = R.string.backup_status_import_complete,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        return viewModel
    }

    private fun leave() {
        onScreen = false
        compose.waitForIdle()
    }

    @Test
    fun `leaving with a picker outstanding queues the dropped-pick notice`() {
        val viewModel = show()
        viewModel.onPickerOpened()

        leave()

        assertEquals(
            R.string.backup_notice_locked_while_choosing_file,
            LkUnlockNotices.take(),
            "the pick was dropped with no word to the user at the next unlock",
        )
    }

    @Test
    fun `control - leaving after the picker answered queues nothing`() {
        val viewModel = show()
        viewModel.onPickerOpened()
        viewModel.onPickerClosed()

        leave()

        assertNull(LkUnlockNotices.take())
    }

    @Test
    fun `control - leaving with no picker open queues nothing`() {
        show()

        leave()

        assertNull(LkUnlockNotices.take())
    }
}
