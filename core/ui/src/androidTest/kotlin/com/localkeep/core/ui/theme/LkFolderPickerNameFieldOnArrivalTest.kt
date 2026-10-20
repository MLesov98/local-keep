package com.localkeep.core.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "New folder" from the add sheet has to open with its name field on screen, however long the tree.
 *
 * The card sits under the folder list on purpose and scrolls itself into view once it is laid out.
 * That request was made once, keyed on the card's size, and the vault list's `New folder` screen
 * hands this picker a folder list its ViewModel loads off the main thread. When the load lost the
 * race to the first layout — the first open after launch, on a device pass of the shipped release
 * build, 2026-10-04 — the card was laid out at the top, asked to be shown, was already showing, and
 * was then pushed under 18 folders by the rows that arrived a frame later. The screen read
 * `Create folder, type a name first` with no field anywhere on it. Every later open had the folders
 * cached in time, which is why it looked intermittent.
 *
 * Driven on a device rather than read from source: the question is where the field ends up after a
 * real layout and a real scroll, and `assertIsDisplayed` answers it — the scroll container clips,
 * so a field below the fold is not displayed.
 */
@RunWith(AndroidJUnit4::class)
class LkFolderPickerNameFieldOnArrivalTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val compose = createComposeRule()

    /** Taller than any phone: 25 rows of 56dp is about 1,500dp of list above the card. */
    private val tree = List(25) { LkFolderRow(id = it, name = "Folder $it", depth = 0) }

    private fun openCreating(initialRows: List<LkFolderRow<Int>>, selectedId: Int? = null) =
        mutableStateOf(initialRows).also { rows ->
            compose.setContent {
                LocalKeepTheme {
                    LkFolderPickerScreen(
                        rows = rows.value,
                        selectedId = selectedId,
                        onPick = {},
                        onBack = {},
                        newFolderName = "",
                    )
                }
            }
            compose.waitForIdle()
        }

    @Test
    fun theNameFieldIsOnScreenWhenTheTreeArrivesAfterTheCard() {
        val rows = openCreating(emptyList())
        rows.value = tree
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    /** Opened from inside a folder: the parent's row carries the tick, mid-tree. */
    @Test
    fun theNameFieldIsOnScreenWhenTheTreeArrivesWithAParentChosen() {
        val rows = openCreating(emptyList(), selectedId = 12)
        rows.value = tree
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    /**
     * The control: the tree already there on the first frame, which is the case the original reveal
     * was written for and passed. It must keep passing, or the fix traded one arrival for the other.
     */
    @Test
    fun theNameFieldIsOnScreenWhenTheTreeIsThereFromTheStart() {
        openCreating(tree)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }
}
