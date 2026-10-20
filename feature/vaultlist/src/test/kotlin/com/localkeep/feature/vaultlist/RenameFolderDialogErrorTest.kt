package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.localkeep.core.model.Folder
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.util.UUID
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The vault list's own rename dialog must show `FolderViewModel`'s sibling-name-collision error.
 *
 * Found 2026-09-13: `FolderViewModel.commitRename()` sets
 * `state.error` on a collision (`FolderViewModel.kt:229`), but `VaultListScreen.kt` never read
 * `folderState.error` anywhere in the file — its `RenameFolderDialog` had no `error` parameter at
 * all — so the dialog just sat there with the same text and an enabled Confirm button,
 * indistinguishable from a rename that silently did nothing. `FolderScreen.kt:196-198` renders the
 * identical `FolderViewModel.error` via `LkInlineError`; this pins the same rendering on the vault
 * list's dialog, which is the one `FolderViewModel.commitRename` describes the defect against.
 *
 * Rendered via [RenameFolderDialog] directly (a plain composable, no ViewModel/Hilt involved) rather
 * than the full `VaultListScreen` — this module has a Robolectric+Compose unit-test harness
 * ([FilterSheetContentTest]) but no Hilt test runtime, and the decision under test — does this
 * composable render the `error` it is handed — does not need one.
 */
@RunWith(RobolectricTestRunner::class)
// Tall window, matching FilterSheetContentTest: Robolectric's default (320x470px) is small enough
// that a dialog note could scroll out of the viewport, and assertIsDisplayed() fails on a node that
// exists but is scrolled away — this keeps the whole dialog on screen so that never masks the result.
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
class RenameFolderDialogErrorTest {

    @get:Rule
    val compose = createComposeRule()

    private val folder = Folder(id = UUID.randomUUID(), name = "Work")

    private fun show(error: Int?) {
        compose.setContent {
            LocalKeepTheme {
                RenameFolderDialog(
                    folder = folder,
                    value = "Personal",
                    onValueChange = {},
                    onDismiss = {},
                    onConfirm = {},
                    error = error,
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a sibling-name collision error is rendered on the dialog`() {
        show(error = R.string.vaultlist_folders_error_name_exists)
        val message = RuntimeEnvironment.getApplication()
            .getString(R.string.vaultlist_folders_error_name_exists)
        compose.onNodeWithText(message).assertIsDisplayed()
        // The dialog is still up with the unconfirmed rename showing, not dismissed or blank.
        compose.onNodeWithTag("rename-folder-field").assertIsDisplayed()
    }

    /**
     * Control. Must stay green: no error means no error node, on the same composable and the same
     * assertion style as the test above, so a fix that always renders an (empty) error row would
     * fail this rather than passing both by accident.
     */
    @Test
    fun `no error is rendered when the ViewModel reports none`() {
        show(error = null)
        val message = RuntimeEnvironment.getApplication()
            .getString(R.string.vaultlist_folders_error_name_exists)
        compose.onNodeWithText(message).assertDoesNotExist()
    }
}
