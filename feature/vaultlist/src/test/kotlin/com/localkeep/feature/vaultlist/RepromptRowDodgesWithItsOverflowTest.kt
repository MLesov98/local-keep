package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A row that dodges the FAB moves its whole trailing cluster, and the lock plate of a "reprompt"
 * row is part of that cluster.
 *
 * The overflow button and the copy pill take one shift between them (see `fabDodgeOffset`), because
 * dodged separately they land on the same target and one is drawn over the other. A reprompt row has
 * no copy pill — it shows a padlock plate where the pill would be — and the plate was left out of
 * the shift, so on the one row a resting FAB covers the overflow slid 126px left, straight onto the
 * plate, and painted over it (overflow [728,1813][854,1939] against plate [854,1834][896,1918] on a
 * device, a 126px overlap of a 42px control).
 *
 * Asserts the outcome, not the wiring: whatever moves the plate, the two controls must not share
 * pixels, and the plate must still be there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepromptRowDodgesWithItsOverflowTest {

    @get:Rule
    val compose = createComposeRule()

    private val item = VaultItem(
        id = ITEM_ID,
        title = TITLE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        reprompt = true,
        content = Login(username = "someone@example.com", password = "hunter2"),
    )

    private var fab by mutableStateOf<Rect?>(null)

    private fun show() {
        compose.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item,
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                    avoidanceBounds = { fab },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun lockPlateBounds(): Rect {
        val app = RuntimeEnvironment.getApplication()
        val description = app.getString(R.string.vaultlist_row_requires_confirmation, TITLE)
        return compose.onNodeWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow.let { Rect(it.left, it.top, it.right, it.bottom) }
    }

    private fun overflowBounds(): Rect =
        compose.onNodeWithTag("vaultlist-row-$ITEM_ID-overflow", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow.let { Rect(it.left, it.top, it.right, it.bottom) }

    /** A FAB drawn over the overflow button, as a resting one is on the row nearest the thumb. */
    private fun coverTheOverflow() {
        val overflow = overflowBounds()
        fab = Rect(overflow.center.x, overflow.top - 20f, overflow.right + 200f, overflow.bottom + 20f)
        compose.waitForIdle()
    }

    @Test
    fun theLockPlateIsNotPaintedUnderTheOverflowWhenTheFabCoversTheRow() {
        show()
        val restingPlate = lockPlateBounds()
        coverTheOverflow()

        val overflow = overflowBounds()
        val plate = lockPlateBounds()
        val overlap = overflow.intersect(plate)
        assertTrue(
            overlap.width <= 0f || overlap.height <= 0f,
            "overflow $overflow and the lock plate $plate share ${overlap.width}x${overlap.height}px " +
                "after the dodge — the overflow button is drawn on top of the padlock",
        )
        assertTrue(
            plate.left < restingPlate.left,
            "the plate stayed at ${restingPlate.left} while the cluster dodged: it has to move with it",
        )
    }

    /** Control: with nothing covering the row, nothing moves — the dodge stays an exception. */
    @Test
    fun withNoFabOverTheRowTheClusterStaysWhereItWas() {
        show()
        val plateBefore = lockPlateBounds()
        val overflowBefore = overflowBounds()
        fab = Rect(0f, 5000f, 10f, 5010f)
        compose.waitForIdle()
        assertTrue(lockPlateBounds() == plateBefore, "the plate moved with no FAB over the row")
        assertTrue(overflowBounds() == overflowBefore, "the overflow moved with no FAB over the row")
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000bb")
        const val TITLE = "Bank"
    }
}
