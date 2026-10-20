package com.localkeep.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.localkeep.core.ui.theme.LkSheet
import com.localkeep.core.ui.theme.LocalKeepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every bottom sheet's drag handle must have a name.
 *
 * `LkSheet` draws its own handle in place of `BottomSheetDefaults.DragHandle`, for the size
 * and colour — and dropped that one's contentDescription with it. Material wraps whatever the
 * `dragHandle` slot emits in a node that merges its descendants and carries the expand/collapse
 * actions, so the result was a focusable, clickable node with no name: `uiautomator` reported
 * `NAF="true"` with `content-desc=""` on every sheet in the app, and TalkBack landed on it and said
 * nothing (device, 2026-09-20).
 *
 * One sheet is enough — there is one handle, in one wrapper, for all of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
class SheetDragHandleIsNamedTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the drag handle is named`() {
        compose.setContent {
            LocalKeepTheme {
                LkSheet(onDismiss = {}) {}
            }
        }
        compose.waitForIdle()

        val name = RuntimeEnvironment.getApplication()
            .getString(R.string.core_ui_sheet_drag_handle)
        compose.onNodeWithContentDescription(name, useUnmergedTree = false).assertIsDisplayed()
    }
}
