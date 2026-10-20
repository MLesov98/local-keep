package com.localkeep.feature.vaultlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Add, New folder, started inside the deepest folder (D8) must file under D7, never at the top level
 * (D3-F-01, device pass 2026-10-19).
 *
 * The seed clamp is right on a loaded tree ([NewFolderParentSeedTest]), but the screen's folders are
 * loaded by a ViewModel created with the destination, so the *first* composition sees an empty list.
 * The clamp ran once, there, found no ancestor and fell to the root, and `rememberSaveable` kept
 * that answer after the tree arrived. The folder was created at the top level without a word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp-xhdpi")
class NewFolderSeedWaitsForFoldersTest {

    @get:Rule
    val compose = createComposeRule()

    private fun chain(length: Int): List<Folder> {
        val out = mutableListOf<Folder>()
        var parent: UUID? = null
        repeat(length) { level ->
            val f = Folder(id = UUID.randomUUID(), name = "L${level + 1}", parentId = parent)
            out += f
            parent = f.id
        }
        return out
    }

    @Test
    fun theTreeArrivingAfterTheFirstFrameStillSeedsTheNearestListedAncestor() {
        val tree = chain(FolderTree.MAX_DEPTH)
        // ids differ per call to chain(), so resolve the expectation from the tree the screen got
        var seen: UUID? = UUID(0, -1)
        var folders by mutableStateOf<List<Folder>>(emptyList())
        compose.setContent {
            LocalKeepTheme {
                NewFolderScreen(
                    folders = folders,
                    folderItemCounts = emptyMap(),
                    initialParentId = tree.last().id,
                    onDismiss = {},
                    onDone = {},
                    onCreate = { _, parentId, _ -> seen = parentId },
                )
            }
        }
        compose.waitForIdle()
        folders = tree
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("W3Lvl9")
        compose.onNodeWithText("Create folder").performClick()
        compose.waitForIdle()
        assertEquals(tree[FolderTree.MAX_DEPTH - 2].id, seen, "D8 cannot hold a child; D7 is the nearest that can")
    }

    @Test
    fun controlAnAlreadyLoadedTreeSeedsTheSameParent() {
        val tree = chain(FolderTree.MAX_DEPTH)
        var seen: UUID? = UUID(0, -1)
        compose.setContent {
            LocalKeepTheme {
                NewFolderScreen(
                    folders = tree,
                    folderItemCounts = emptyMap(),
                    initialParentId = tree.last().id,
                    onDismiss = {},
                    onDone = {},
                    onCreate = { _, parentId, _ -> seen = parentId },
                )
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("W3Lvl9")
        compose.onNodeWithText("Create folder").performClick()
        compose.waitForIdle()
        assertEquals(tree[FolderTree.MAX_DEPTH - 2].id, seen)
    }
}
