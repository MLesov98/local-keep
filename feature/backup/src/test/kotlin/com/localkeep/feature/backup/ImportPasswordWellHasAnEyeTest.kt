package com.localkeep.feature.backup

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The import section's password well has a show/hide eye, like both export wells.
 *
 * S9-F-03 (functional pass, 2026-10-15): "Password of the file being imported" ran edge to edge with
 * a character counter and no eye, so a password could not be checked before a read that costs an
 * Argon2id derivation. The import section opens straight away here (the wizard's hand-off), which
 * also leaves Export folded and its two eyes out of the tree, so exactly one is expected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportPasswordWellHasAnEyeTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        BackupFocus.clear()
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val show get() = app.getString(com.localkeep.core.ui.R.string.core_ui_field_password_show)
    private val hide get() = app.getString(com.localkeep.core.ui.R.string.core_ui_field_password_hide)

    private fun show() {
        BackupFocus.request(BackupFocus.IMPORT)
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

    private fun typed(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun `the import password well offers an eye and it unmasks what was typed`() {
        show()

        assertEquals(
            1,
            compose.onAllNodesWithContentDescription(show).fetchSemanticsNodes().size,
            "the import password well has no show/hide control",
        )
        compose.onNode(hasSetTextAction()).performTextInput("file-password-1")
        compose.waitForIdle()
        assertEquals(15, typed().length, "the control: the text went in")
        assertEquals(true, typed().all { it == '•' }, "the control: the well starts masked")

        compose.onNodeWithContentDescription(show).performClick()
        compose.waitForIdle()

        assertEquals("file-password-1", typed())
        compose.onNodeWithContentDescription(hide).assertExists()
    }
}
