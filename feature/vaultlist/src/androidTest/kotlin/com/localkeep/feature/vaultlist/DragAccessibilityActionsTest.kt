package com.localkeep.feature.vaultlist

import android.content.Context
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Every capability the drag adds, reachable without making a gesture.
 *
 * Drag is a touch gesture with no keyboard, D-pad or switch-access equivalent, and TalkBack's own
 * long-press is not the app's — so a capability that exists only inside the drag is a capability a
 * screen-reader user does not have. §8 of the drag design audits the five and finds three already
 * covered by tapping a folder, the breadcrumb, and `BulkMoveSheet`. These are the two that were not.
 *
 * Asserted on the semantics tree rather than by driving TalkBack, because that is where the defect
 * lives: an action attached to the wrong node, or announced while wired to nothing, looks perfectly
 * correct in the source and on screen. Each action is also **invoked**, so a label pointing at a
 * dead callback fails here rather than in someone's ear.
 */
@RunWith(AndroidJUnit4::class)
class DragAccessibilityActionsTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = Folder(id = UUID.randomUUID(), name = "Work")

    private fun item() = VaultItem(
        id = UUID.randomUUID(),
        title = "Acme Mail",
        content = Login(username = "ada@example.com"),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun SemanticsNodeInteraction.customActions(): List<CustomAccessibilityAction> =
        fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }

    private fun row(name: String) = rule.onNodeWithContentDescription(name, substring = true)

    @Test
    fun anItemRowOffersMoveToFolderAndTheActionRuns() {
        var opened = 0
        rule.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item(),
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                    onMoveToFolder = { opened++ },
                )
            }
        }

        val label = context.getString(R.string.vaultlist_item_action_move_to_folder)
        val action = row("Acme Mail").customActions().firstOrNull { it.label == label }
        assertTrue(action != null, "the item row announced no \"$label\" action")
        assertTrue(action.action(), "the action reported failure")
        rule.waitForIdle()
        assertEquals(1, opened, "the label is announced but wired to nothing")
    }

    @Test
    fun aRowWithNoPickerToOpenAnnouncesNoMoveAction() {
        // The picker and the tests pass no callback. An action announced there would be read out and
        // do nothing, which is worse than its absence — this is the negative control for the test
        // above, and without it that one passes just as well on an action added unconditionally.
        rule.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item(),
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                )
            }
        }

        val label = context.getString(R.string.vaultlist_item_action_move_to_folder)
        assertTrue(
            row("Acme Mail").customActions().none { it.label == label },
            "a row with nowhere to move to still offered to move",
        )
    }

    @Test
    fun aFolderRowOffersBothMovingItAndTrashingIt() {
        // Two capabilities, not one. Trashing a folder had no non-gesture route at all before the
        // drag; moving one had none either, because `BulkMoveSheet` moves *items* and folders are
        // not selectable. Both are asserted here so neither can be dropped for the other.
        var moved = 0
        var trashed = 0
        rule.setContent {
            LocalKeepTheme {
                FolderBrowseRow(
                    folder = work,
                    itemCount = 2,
                    onClick = {},
                    onLongPress = { trashed++ },
                    onMove = { moved++ },
                )
            }
        }

        val moveLabel = context.getString(R.string.vaultlist_folder_action_move_folder, work.name)
        val trashLabel = context.getString(R.string.vaultlist_folder_action_move_to_trash, work.name)
        val actions = row(work.name).customActions()

        assertEquals(
            listOf(moveLabel, trashLabel),
            actions.map { it.label },
            "constructive first, destructive last — as on the item row",
        )
        assertTrue(actions[0].action())
        assertTrue(actions[1].action())
        rule.waitForIdle()
        assertEquals(1, moved)
        assertEquals(1, trashed)
    }

    @Test
    fun aFolderRowWithNeitherCallbackAnnouncesNoActions() {
        rule.setContent {
            LocalKeepTheme {
                FolderBrowseRow(folder = work, itemCount = 2, onClick = {})
            }
        }

        assertEquals(emptyList(), row(work.name).customActions().map { it.label })
    }
}
