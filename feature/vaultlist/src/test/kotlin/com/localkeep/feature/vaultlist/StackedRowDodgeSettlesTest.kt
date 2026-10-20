package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.Card
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L-05: a card row's overflow button, left drawn behind the FAB, with taps there opening nothing.
 *
 * Device pass 2026-10-19, 308x548dp at font 2.0, Cards filter: the `Visa Everyday` row's overflow
 * was drawn at its resting spot behind the FAB in the screenshot and reported 174px to the left, 74px
 * lower, in the dump taken straight after. Both were real: the row never settled.
 *
 * ### The loop
 *
 * A card has no copy pill, so once stacked the dodge reserves width in the *title* column (SCL-009:
 * otherwise the plate slides onto the masked number). Narrower, the title wraps onto another line,
 * the row grows, and the overflow — bottom-anchored — moves down, out from under the FAB. The dodge
 * is decided by whether that button overlaps the FAB, so it switches off, the reserve goes, the
 * title unwraps, the row shrinks, the button is back under the FAB, and the dodge switches on. One
 * state per frame, forever. Under Robolectric the same geometry never reaches idle at all.
 *
 * What this asserts is the property that was lost: after the FAB lands on the row, the row's height
 * stops changing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w308dp-h548dp-xhdpi", fontScale = 2.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StackedRowDodgeSettlesTest {

    @get:Rule
    val compose = createComposeRule()

    private var fab by mutableStateOf<Rect?>(null)
    private val heights = mutableListOf<Int>()
    private val id = UUID(0, 9)

    @Test
    fun aCardRowCoveredByTheFabSettlesInsteadOfBreathing() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = VaultItem(
                        id = id,
                        title = "Visa Everyday",
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                        content = Card(number = "4111111111111111"),
                    ),
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                    avoidanceBounds = { fab },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .onSizeChanged {
                            heights += it.height
                            // The loop does not end on its own under Robolectric — it ran to an
                            // OutOfMemoryError after three minutes — so the test ends it.
                            check(heights.size < 300) {
                                "the row was re-measured ${heights.size} times and is still changing " +
                                    "height (${heights.takeLast(6)}): the dodge undoes itself by growing the row (L-05)"
                            }
                        },
                )
            }
        }
        compose.mainClock.advanceTimeBy(200)
        val resting = heights.last()

        // The FAB the way the device had it: over the top of the overflow plate, reaching a good
        // way left of it so the dodge has far to go and the title is squeezed hard.
        fab = Rect(left = 426f, top = -400f, right = 692f, bottom = resting - 90f)
        heights.clear()
        repeat(40) { compose.mainClock.advanceTimeByFrame() }

        val tail = heights.takeLast(10).ifEmpty { listOf(resting) }
        assertEquals(
            1,
            tail.distinct().size,
            "the row's height was still changing 30+ frames after the FAB landed: $tail — the dodge " +
                "switches itself off by growing the row (L-05)",
        )
        assertTrue(heights.size < 20, "the row was re-measured ${heights.size} times in 40 frames")
    }
}
