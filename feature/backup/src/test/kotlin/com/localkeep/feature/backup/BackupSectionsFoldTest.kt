package com.localkeep.feature.backup

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import kotlin.test.assertEquals
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
 * Export, Import and Encryption fold, and start folded.
 *
 * The principal's complaint was a page with every form dumped open: a Backup & export screen that
 * showed two password wells, a checkbox and three buttons before it showed anything he came for.
 * The sections now sit as one row each, with a summary that answers "is anything in here I should
 * know about" without a tap.
 *
 * The real screen, NATIVE graphics, like [BackupOpensOnTheImportSectionTest] — text needs real
 * heights for scroll and visibility assertions to mean anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupSectionsFoldTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun tearDown() {
        BackupFocus.clear()
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun text(res: Int) = app.getString(res)

    private fun show() {
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

    private fun toggle(sectionTitle: Int) {
        compose.onNodeWithText(text(sectionTitle)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `export import and encryption start folded`() {
        show()

        compose.onNodeWithText(text(R.string.backup_export_button)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.backup_import_choose_button)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.backup_kdf_check_button)).assertDoesNotExist()
        // The headers themselves are there, or "folded" would be indistinguishable from "gone".
        compose.onNodeWithText(text(R.string.backup_export_section)).assertExists()
        compose.onNodeWithText(text(R.string.backup_import_section)).assertExists()
        compose.onNodeWithText(text(R.string.backup_kdf_section)).assertExists()
    }

    @Test
    fun `the folded rows say what is inside without opening`() {
        show()

        compose.onNodeWithText(text(R.string.backup_kdf_summary_unchecked)).assertExists()
        compose.onNodeWithText(text(R.string.backup_export_summary_never)).assertExists()
    }

    @Test
    fun `opening a section shows its controls`() {
        show()

        toggle(R.string.backup_export_section)
        compose.onNodeWithText(text(R.string.backup_export_button)).assertExists()

        toggle(R.string.backup_kdf_section)
        compose.onNodeWithText(text(R.string.backup_kdf_check_button)).assertExists()
    }

    @Test
    fun `the import hand-off arrives with the import section open`() {
        BackupFocus.request(BackupFocus.IMPORT)
        show()

        compose.onNodeWithText(text(R.string.backup_import_choose_button)).assertExists()
        // The control: the hand-off opens that one section and nothing else.
        compose.onNodeWithText(text(R.string.backup_export_button)).assertDoesNotExist()
    }

    @Test
    fun `folding export drops the password typed into it`() {
        show()
        toggle(R.string.backup_export_section)

        val well = compose.onAllNodes(hasSetTextAction())[0]
        well.performTextInput("hunter2-hunter2")
        fun typed() = compose.onAllNodes(hasSetTextAction())[0]
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        // Masked in the semantics tree too, so the length is the observable: bullets, one per character.
        assertEquals(15, typed().length, "the control: the text really went in before folding")

        toggle(R.string.backup_export_section)
        toggle(R.string.backup_export_section)

        assertTrue(
            typed().isEmpty(),
            "a master password typed into a folded section was still in the well on reopening",
        )
    }
}
