package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The new-folder card's "At the top level" / "Inside X" line was 12sp faint caption text that was
 * secretly the only way to pick a parent. It is a row with a chevron now, so it reads as a control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
class NewFolderParentRowTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(onClick: () -> Unit = {}) {
        compose.setContent {
            LocalKeepTheme {
                NewFolderParentRow(caption = "At the top level", chooseLabel = "Choose where", onClick = onClick)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theRowShowsAChevronSoItReadsAsTappable() {
        show()
        compose.onNodeWithTag("new-folder-parent-chevron", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theRowKeepsTheTouchFloor() {
        show()
        compose.onNodeWithTag("new-folder-parent-row").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun tappingTheRowChoosesAParent() {
        var taps = 0
        show(onClick = { taps++ })
        compose.onNodeWithTag("new-folder-parent-row").performClick()
        assertEquals(1, taps)
    }
}
