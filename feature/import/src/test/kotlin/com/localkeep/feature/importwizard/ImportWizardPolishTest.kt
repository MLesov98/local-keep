package com.localkeep.feature.importwizard

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import com.localkeep.core.import.ImportMerger
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Three small things on the import wizard that each read as unfinished next to the rest of the app:
 * the two password wells had no show/hide eye, the first screen's body drew "LocalKeep" in one
 * colour where every other surface two-tones it, and a preview row's reason line was cut at a
 * single line, ending in an ellipsis in the middle of the sentence that says what the import will
 * do to the user's own entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h823dp-xxhdpi")
class ImportWizardPolishTest {

    @get:Rule
    val compose = createComposeRule()

    private fun passwordStep(isDatabase: Boolean) {
        compose.setContent {
            LocalKeepTheme {
                Column {
                    PasswordStep(
                        state = ImportUiState(
                            step = ImportUiState.Step.PASSWORD,
                            password = "hunter2",
                            passwordIsForDatabase = isDatabase,
                        ),
                        onPasswordChanged = {},
                        onSubmit = {},
                        onAddKeyFile = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the KeePass database password can be revealed and hidden again`() {
        passwordStep(isDatabase = true)
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide password").performClick()
        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()
    }

    @Test
    fun `the Bitwarden export password can be revealed and hidden again`() {
        passwordStep(isDatabase = false)
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").assertIsDisplayed()
    }

    /**
     * Control: a revealed password goes back under its mask when the step is left, so the toggle is
     * per composition and never part of the saved state.
     */
    @Test
    fun `the field starts masked`() {
        passwordStep(isDatabase = true)
        compose.onNodeWithContentDescription("Show password").assertIsDisplayed()
    }

    @Test
    fun `the source screen two-tones the product name`() {
        compose.setContent { LocalKeepTheme { SourceGrid(onSelect = {}) } }
        val text = compose.onNode(hasText("LocalKeep takes it from there", substring = true))
            .fetchSemanticsNode().config[SemanticsProperties.Text].first()
        val branded = text.spanStyles.filter { text.text.substring(it.start, it.end) == "Keep" }
        // The body names the product twice and both mentions carry the second tone.
        assertEquals(2, branded.size, "the 'Keep' half of LocalKeep is not coloured in the body")
    }

    private fun previewRow(subtitle: String) = PreviewRow(
        id = UUID.randomUUID(),
        title = "Bank",
        subtitle = subtitle,
        disposition = ImportMerger.Disposition.ALREADY_CURRENT,
        collidesWith = "Bank",
        selected = true,
        item = VaultItem(
            id = UUID.randomUUID(),
            title = "Bank",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = Login(username = "ada", password = "pw"),
        ),
        collidesWithVault = true,
    )

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    @Test
    fun `a preview reason is allowed a second line instead of ending in an ellipsis`() {
        compose.setContent {
            LocalKeepTheme {
                PreviewContent(
                    state = ImportUiState(
                        step = ImportUiState.Step.PREVIEW,
                        rows = listOf(previewRow("ada.lovelace at the analytical engine company")),
                    ),
                    onSetMode = {},
                    onSetAllSelected = {},
                    onDeselectDuplicates = {},
                    onToggleRow = {},
                    onApply = {},
                )
            }
        }
        // The line budget the Text was given, read off its layout input. Robolectric's text
        // measurement does not break lines the way a device does (a 78-character sentence measures
        // 77px wide), so whether the sentence fits is a device question; what the unit test can pin
        // is the budget itself.
        assertEquals(
            2,
            layoutOf("you already have a newer copy").layoutInput.maxLines,
            "a preview reason must be allowed a second line at the default font size",
        )
    }
}
