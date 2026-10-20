package com.localkeep.feature.importwizard

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.import.ImportMerger
import com.localkeep.core.import.SourceFormat
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The review step has to review — in the first view, not one swipe away.
 *
 * Two defects, one after the other. First the counts, notices and chips sat in fixed rows above a
 * `Box(weight(1f))`, so on a short window they took the whole height and the list was measured at
 * **zero** (1A-F4 / 1B-F8). Moving them into the `LazyColumn` as its header fixed that — and on the
 * device in landscape the step still showed no item row and no Select all / None, because the
 * header alone is taller than the 253dp the step gets there, with an enabled "Import 3 items"
 * underneath it (1B-F3 re-verify, 2026-09-20, emulator-5556).
 *
 * **The first version of this test passed against that layout**, because its state had no detected
 * format and no carried-columns footnote — a short header fits where the real one does not. So the
 * landscape case below now composes the state the device actually had, and asserts the row is
 * *displayed*, with no `performScrollToNode` to reach it. A review gate the user must discover by
 * scrolling is the defect, so the scroll cannot be part of the proof.
 *
 * The window is 731x253dp: emulator-5556's landscape (1080x1920 at 420dpi) minus the wizard's own
 * header, gutters and 28dp bottom pad, measured off the dump that reproduced the defect rather than
 * chosen.
 *
 * The clock runs normally here, unlike `SaveConfirmScreenContentTest`: this composes the step on its
 * own rather than inside `LkScreen`, so the slow-drift aura that never goes idle is not in the tree
 * and `lkEnter`'s entrance is one-shot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w731dp-h253dp-xxhdpi")
class ImportPreviewShortWindowTest {

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

    @Test
    fun `the preview lists its rows in a landscape window`() {
        compose.setContent {
            LocalKeepTheme {
                PreviewContent(
                    // Exactly what emulator-5556 held: a detected format on the count line and a
                    // two-column carried-columns footnote. Both are ordinary — a CSV with one
                    // unmapped column produces them — and together they are what pushed every row
                    // below the fold.
                    state = ImportUiState(
                        step = ImportUiState.Step.PREVIEW,
                        detectedFormat = SourceFormat.CSV,
                        carriedColumns = listOf("department", "costcentre"),
                        rows = listOf(row("Bank"), row("Card"), row("Social")),
                    ),
                    onSetMode = {},
                    onSetAllSelected = {},
                    onDeselectDuplicates = {},
                    onToggleRow = {},
                    onApply = {},
                )
            }
        }

        // The defect in one assertion: no row was on screen at all. Displayed, not merely present
        // — a node scrolled out of the viewport still exists in the tree.
        compose.onNodeWithText("Bank").assertIsDisplayed()

        // And the selection controls with it: the landscape dump held "Merge duplicates" and "Add
        // everything" but not these two, so the one way to deselect a row was off-screen too.
        compose.onNodeWithText("Select all").assertIsDisplayed()
        compose.onNodeWithText("None").assertIsDisplayed()

        // The list is still a list: the rows below the fold are reachable by scrolling it.
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Bank")))
            .assertHeightIsAtLeast(48.dp)
    }

    /** The control: the same content in portrait, which always worked and must keep working. */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h823dp-xxhdpi")
    fun `the preview lists its rows in a portrait window`() {
        compose.setContent {
            LocalKeepTheme {
                PreviewContent(
                    state = ImportUiState(
                        step = ImportUiState.Step.PREVIEW,
                        rows = listOf(row("Bank"), row("Card"), row("Social")),
                    ),
                    onSetMode = {},
                    onSetAllSelected = {},
                    onDeselectDuplicates = {},
                    onToggleRow = {},
                    onApply = {},
                )
            }
        }
        compose.onNodeWithText("Bank").assertIsDisplayed()
    }
}
