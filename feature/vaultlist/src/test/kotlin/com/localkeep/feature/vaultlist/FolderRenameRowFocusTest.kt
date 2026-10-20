package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import com.localkeep.core.ui.theme.LocalKeepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The folder pencil opens the inline rename row with the name well already focused (D3-L-07).
 *
 * On the device the row appeared but the well did not take focus, so the keyboard needed a second
 * tap on the field. Asserted as focus on the well immediately after the row composes, with no click.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
class FolderRenameRowFocusTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRenameWellHasFocusWhenTheRowOpens() {
        compose.setContent {
            LocalKeepTheme {
                FolderRenameRow(
                    folderName = "Work",
                    first = true,
                    text = "Work",
                    onTextChange = {},
                    onCommit = {},
                    onCancel = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }
}
