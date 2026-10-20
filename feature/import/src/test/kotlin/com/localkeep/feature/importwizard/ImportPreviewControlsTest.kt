package com.localkeep.feature.importwizard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.import.ImportMerger
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two things the preview's own controls got wrong, both found on device 2026-09-20.
 *
 * A **0-item** preview still offered "Merge duplicates", "Add everything", "Select all" and "None"
 * — four controls with nothing to act on (1B-F10). That is what this pins.
 *
 * The sibling defect — at `font_scale 2.0` those chips were laid out in a `Row`, which measures its
 * later children with whatever width is left, and ellipsized them to "Add eve…" and "Skip dupli…"
 * (1A-F11 / 1B-F9) — is **not** pinned here, and deliberately so. Robolectric does not measure real
 * font metrics: the same chip reports the same 53dp at `fontScale` 1 and 2, so a width assertion
 * would pass against the broken layout and prove nothing. The fix (a `FlowRow`) is verified on a
 * device instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h823dp-xxhdpi")
class ImportPreviewControlsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(title: String) = PreviewRow(
        id = UUID.randomUUID(),
        title = title,
        subtitle = "ada",
        disposition = ImportMerger.Disposition.NEW,
        collidesWith = null,
        selected = true,
        item = VaultItem(
            id = UUID.randomUUID(),
            title = title,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = Login(username = "ada", password = "pw"),
        ),
    )

    private fun show(rows: List<PreviewRow>) {
        compose.setContent {
            LocalKeepTheme {
                PreviewContent(
                    state = ImportUiState(step = ImportUiState.Step.PREVIEW, rows = rows),
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
    fun `a preview with no rows offers none of the controls that act on rows`() {
        show(emptyList())

        listOf("Merge duplicates", "Add everything", "Select all", "None").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    /** The control: with rows, every one of them is there. */
    @Test
    fun `a preview with rows offers them`() {
        show(listOf(row("Bank")))

        listOf("Merge duplicates", "Add everything", "Select all", "None").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
