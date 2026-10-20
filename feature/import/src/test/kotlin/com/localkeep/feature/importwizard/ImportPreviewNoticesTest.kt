package com.localkeep.feature.importwizard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.import.ImportMerger
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two things an import quietly did not carry, said where the user is looking.
 *
 * Both were already true on the device and reported nowhere that mattered. A KeePass field
 * reference nothing answers arrives as its raw `{REF:U@I:...}` token in place of a username
 * (1A-F7), and a Bitwarden login whose only credential is a passkey arrives with no credential at
 * all — its caveat sits on the *steps* screen, four taps earlier (1A-F8). The preview is the last
 * screen before the vault is written and the Done step is the last one before the wizard offers to
 * delete the export, so those are the two places the sentence has to appear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h823dp-xxhdpi")
class ImportPreviewNoticesTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row() = PreviewRow(
        id = UUID.randomUUID(),
        title = "webauthn.io",
        subtitle = "ada",
        disposition = ImportMerger.Disposition.NEW,
        collidesWith = null,
        selected = true,
        item = VaultItem(
            id = UUID.randomUUID(),
            title = "webauthn.io",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = Login(username = "ada", password = ""),
        ),
    )

    private fun show(state: ImportUiState) {
        compose.setContent {
            LocalKeepTheme {
                PreviewContent(
                    state = state,
                    onSetMode = {},
                    onSetAllSelected = {},
                    onDeselectDuplicates = {},
                    onToggleRow = {},
                    onApply = {},
                )
            }
        }
    }

    @Test
    fun `the preview says how many items kept an unresolved field reference`() {
        show(
            ImportUiState(
                step = ImportUiState.Step.PREVIEW,
                rows = listOf(row()),
                unresolvedFieldReferenceCount = 1,
            ),
        )
        compose.onNodeWithText("kept as text instead of a value", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the preview says how many logins carried only a passkey`() {
        show(
            ImportUiState(
                step = ImportUiState.Step.PREVIEW,
                rows = listOf(row()),
                passkeyOnlyLoginCount = 1,
            ),
        )
        compose.onNodeWithText("carried only a passkey", substring = true).assertIsDisplayed()
    }

    /** Neither sentence appears on an import that lost nothing — or it would appear on every one. */
    @Test
    fun `an import with nothing to report shows neither notice`() {
        show(ImportUiState(step = ImportUiState.Step.PREVIEW, rows = listOf(row())))
        compose.onNodeWithText("kept as text instead of a value", substring = true).assertDoesNotExist()
        compose.onNodeWithText("carried only a passkey", substring = true).assertDoesNotExist()
    }

    /**
     * The Done step carries both notices too, asserted against the source for the reason
     * [ImportDoneCleanupWarningTest] gives: `Done` is private and takes the ViewModel, so it cannot
     * be rendered here. The step matters more than the preview, not less — it is the one that then
     * offers to delete the export.
     */
    @Test
    fun `the done step shows both notices as well`() {
        val screen = File("src/main/kotlin/com/localkeep/feature/importwizard/ImportWizardScreen.kt")
        if (!screen.isFile) fail("cannot find ${screen.absolutePath} — this test reads the source it guards")
        val done = screen.readText().substringAfter("private fun Done(")
        listOf("UnresolvedFieldReferences(state)", "PasskeyOnlyLogins(state)").forEach {
            assertTrue(it in done, "the Done step must call $it")
        }
    }
}
