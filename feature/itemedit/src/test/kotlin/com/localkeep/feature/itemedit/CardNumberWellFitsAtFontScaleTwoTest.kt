package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The editor's CARD NUMBER well shows the whole number at font scale 2.0 (2026-09-20, sweep 2B-3).
 *
 * The bug this closes: the well was single-line, so at font scale 2.0 a masked Visa drew about
 * eleven and a half of its sixteen bullets and a revealed one drew `4111111111111` plus half a
 * digit — no ellipsis, no wrap, no scroll affordance — while `desc` still announced all sixteen
 * and the *detail* screen wrapped the same value onto two lines correctly. UI/UX spec line 43 asks
 * for dynamic type "without truncation of critical content", and a card number you cannot read
 * back off the screen is that content.
 *
 * ### Why this measures `LkPasswordField` rather than `SecretField`
 *
 * `SecretField` takes the `ItemEditViewModel`, which cannot be constructed on a JVM (see
 * `ItemEditViewModelTest`). So the test is in two halves: the measurement below shows that the
 * parameter the fix passes really does end the truncation *in this well, at this scale, with this
 * value*, and `the editor's card number well asks for it` pins that the editor passes it.
 *
 * [GraphicsMode.Mode.NATIVE] is load-bearing, for the reason `LkDialogBoundsTest` records in
 * `:core:ui`: under Robolectric's legacy graphics there is no real text layout and every assertion
 * about overflow passes against broken code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardNumberWellFitsAtFontScaleTwoTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Both wells in one composition — the rule allows a single `setContent` per test — each
     * full-width, so the only difference between them is the parameter under test.
     */
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                Column(Modifier.fillMaxWidth()) {
                    listOf(CLIPPED to true, WRAPPED to false).forEach { (tag, singleLine) ->
                        LkPasswordField(
                            value = VISA,
                            onValueChange = {},
                            label = "Card number",
                            revealed = true,
                            onToggleReveal = null,
                            leadingIcon = null,
                            singleLine = singleLine,
                            modifier = Modifier.fillMaxWidth().testTag(tag),
                            // The editor draws three 48dp glyph buttons inside this well — Show,
                            // Copy and Generate's slot. They are what leaves the value about a
                            // third less width than the well, and so are load-bearing for whether
                            // sixteen digits fit; an empty `actions` lambda measures a well the
                            // editor never draws.
                            actions = { repeat(3) { Spacer(Modifier.size(48.dp)) } },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The well's height in dp, as the editor draws it — revealed, with a card's actions beside it. */
    private fun wellHeight(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().size.height / compose.density.density

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 2.0f)
    fun `at font scale 2 the number no longer has to fit on one line`() {
        show()
        val clipped = wellHeight(CLIPPED)
        val wrapped = wellHeight(WRAPPED)
        assertTrue(
            wrapped > clipped,
            "at font scale 2.0 a 16-digit card number rendered in the same height whether the well " +
                "may wrap or not ($wrapped dp vs $clipped dp), so it is still being cut off at the " +
                "well's edge.",
        )
    }

    /**
     * And inert where the well already worked. The number fits on one line at font scale 1.0, so
     * allowing the wrap must not make the ordinary card form taller.
     */
    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun `at font scale 1 nothing moves`() {
        show()
        assertEquals(
            wellHeight(CLIPPED),
            wellHeight(WRAPPED),
            "allowing the wrap changed the well at the scale where it already fitted",
        )
    }

    @Test
    fun `the editor's card number well asks for it`() {
        val screen =
            File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
        val well = screen.substringAfter("viewModel::onCardNumberChanged,", "")
            .substringBefore(")\n")
        assertTrue(well.isNotEmpty(), "the card number well moved; this test no longer reads it")
        assertTrue(
            "singleLine = CARD_NUMBER_WELL_SINGLE_LINE" in well,
            "the editor's card number well is single-line again and truncates at font scale 2.0",
        )
    }

    private companion object {
        const val CLIPPED = "card-number-well-single-line"
        const val WRAPPED = "card-number-well-wrapping"
        const val VISA = "4111111111111111"
    }
}
