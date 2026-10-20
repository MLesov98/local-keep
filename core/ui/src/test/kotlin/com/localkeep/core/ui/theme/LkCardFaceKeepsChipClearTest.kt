package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The card face never draws its number row over the chip (SCL-035).
 *
 * The face is 1.586:1, and its bottom-aligned content scales with the system font size while its
 * width does not. At 308dp and font scale 2.0 the chip, the number's line and the expiry block need
 * more than the 256dp-wide rectangle is tall, and the first masked dots sat over the chip's lower
 * edge (`D-03-card__P1`). The face now grows past the aspect height when it has to.
 *
 * FAILS WITHOUT THE FIX: the number row's top is above the chip's bottom.
 * CONTROL: at 411dp and font scale 1.0 the face is still exactly 1.586:1, so a window with room
 * renders the card the comps draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkCardFaceKeepsChipClearTest {

    @get:Rule
    val compose = createComposeRule()

    private var showBack by mutableStateOf(false)

    private fun show() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = LkFrame.horizontal).testTag("card")) {
                    LkCardPreview(
                        numberSlots = lkCardNumberSlots("4111111111111111", maskedBefore = 16),
                        expiryDisplay = "12 / 30",
                        holder = "A HOLDER",
                        brandLabel = "Visa",
                        expiryCaption = "Expires",
                        holderPlaceholder = "Cardholder",
                        contentDescription = "Visa card",
                        showBack = showBack,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `at the 308dp floor and large type the number row stays below the chip`() {
        show()
        val chip = compose.onNodeWithTag("card-chip", useUnmergedTree = true).getBoundsInRoot()
        val number = compose.onNodeWithTag("card-number", useUnmergedTree = true).getBoundsInRoot()
        assertTrue(
            number.top.value >= chip.bottom.value,
            "the number row starts at ${number.top} but the chip ends at ${chip.bottom}",
        )
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `turning the card over keeps its size`() {
        show()
        val front = compose.onNodeWithTag("card").getBoundsInRoot().height.value
        showBack = true
        compose.waitForIdle()
        val back = compose.onNodeWithTag("card").getBoundsInRoot().height.value
        assertEquals(front, back, 1f, "front is ${front}dp tall and back ${back}dp")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `a roomy window keeps the card at ID-1 proportions`() {
        show()
        val card = compose.onNodeWithTag("card").getBoundsInRoot()
        assertEquals(card.width.value / 1.586f, card.height.value, 1f, "card is $card")
    }
}
