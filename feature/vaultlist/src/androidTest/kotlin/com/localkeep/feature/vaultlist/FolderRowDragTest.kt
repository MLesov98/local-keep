package com.localkeep.feature.vaultlist

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.model.Folder
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A folder is a payload, not only a destination.
 *
 * `FolderDragPayload`, the acceptance rules that reject a folder into its own subtree, the depth
 * guard, the ghost that draws a folder glyph and the write with its undo were all in place — and
 * the row that would produce one was still a plain `clickable`. Nothing could construct the payload,
 * so half the feature was unreachable and every test of the other half passed.
 *
 * That is the shape of defect a read-only review keeps missing: no code is wrong, one modifier is
 * absent. Found by driving the app on 2026-08-18 and asking why a folder would not lift.
 */
@RunWith(AndroidJUnit4::class)
class FolderRowDragTest {

    @get:Rule
    val rule = createComposeRule()

    private val work = Folder(id = UUID.randomUUID(), name = "Work")

    @Test
    fun holdingAFolderAndMovingItStartsADrag() {
        var arms = 0
        var starts = 0
        var opens = 0
        rule.setContent {
            LocalKeepTheme {
                FolderBrowseRow(
                    folder = work,
                    itemCount = 2,
                    onClick = { opens++ },
                    // Explicit, not defaulted. `onDragArmed` falls back to `onDragStart` for a
                    // caller that does not distinguish them, and this test very much does: arming
                    // fires when the hold registers, starting only once the finger crosses touch
                    // slop. Left on the default they alias to one counter and a single correct
                    // held-and-moved gesture counts two — which is exactly how this test read
                    // "expected:<1> but was:<2>" from behaviour that was never wrong.
                    // `LkPressAndDragTest` in core/ui carries the same warning over the same API.
                    onDragArmed = { _, _ -> arms++ },
                    onDragStart = { _, _ -> starts++ },
                    dragEnabled = true,
                )
            }
        }

        rule.onNodeWithContentDescription(work.name, substring = true).performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 140f))
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 260f))
            up()
        }
        rule.waitForIdle()

        assertEquals(1, arms, "the hold arms the row exactly once")
        assertEquals(1, starts, "a held-and-moved folder row must hand a payload to the drag")
        assertEquals(0, opens, "movement decides: that was a drag, not a tap into the folder")
    }

    @Test
    fun tappingAFolderStillOpensIt() {
        // The gesture replaced `clickable`, so navigation — the row's actual job, and the only one
        // most users will ever use — has to be re-asserted rather than assumed.
        var opens = 0
        rule.setContent {
            LocalKeepTheme {
                FolderBrowseRow(
                    folder = work,
                    itemCount = 2,
                    onClick = { opens++ },
                    dragEnabled = true,
                )
            }
        }

        rule.onNodeWithContentDescription(work.name, substring = true).performTouchInput { click() }
        rule.waitForIdle()

        assertEquals(1, opens)
    }

    @Test
    fun aFolderRowWithDragWithheldStillOpensAndNeverLifts() {
        // `dragEnabled` is false while searching at root. That must not cost the row its tap.
        var starts = 0
        var opens = 0
        rule.setContent {
            LocalKeepTheme {
                FolderBrowseRow(
                    folder = work,
                    itemCount = 2,
                    onClick = { opens++ },
                    onDragStart = { _, _ -> starts++ },
                    dragEnabled = false,
                )
            }
        }

        val node = rule.onNodeWithContentDescription(work.name, substring = true)
        node.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(center + androidx.compose.ui.geometry.Offset(0f, 260f))
            up()
        }
        rule.waitForIdle()
        assertEquals(0, starts)

        node.performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, opens)
    }
}
