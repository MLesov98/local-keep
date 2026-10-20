package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A field label wraps to a second line before it ellipsizes (RG-41, SCL-014).
 *
 * The label used to be `maxLines = 1` with an ellipsis, written down as a contract. The contract
 * protected the well below from a German compound running into it, and it cost the label its
 * meaning: "CARD NUMBER (REQU...", "NATIONAL ID NUMB...", "NATIONALE IDENTIFI..." at 308dp. Two lines
 * keep the protection (the label is still bounded) and let the sentence finish.
 *
 * Heights, not text, are the observable: [GraphicsMode.Mode.NATIVE] gives real line breaking, and
 * a label cut to one line is exactly one single-line label tall.
 *
 * FAILS WITHOUT THE FIX: the long label measured the same height as the short one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LkFieldLabelWrapsTest {

    @get:Rule
    val compose = createComposeRule()

    /** All three labels in one composition (a rule accepts one `setContent`), each in 110dp. */
    private fun heights(): Map<String, Float> {
        var density = 0f
        compose.setContent {
            density = LocalDensity.current.density
            LocalKeepTheme {
                Column {
                    for ((tag, text) in LABELS) {
                        Box(Modifier.width(110.dp)) { LkFieldLabel(text, Modifier.testTag(tag)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        return LABELS.associate { (tag, _) ->
            tag to compose.onNodeWithTag(tag).fetchSemanticsNode().size.height / density
        }
    }

    @Test
    fun aLongLabelWrapsToASecondLine() {
        val h = heights()
        assertTrue(
            h.getValue(LONG) > h.getValue(SHORT) * 1.5f,
            "the label measured ${h.getValue(LONG)}dp against ${h.getValue(SHORT)}dp for one line - it " +
                "stayed on one line and ended in an ellipsis, so \"(required)\" is lost.",
        )
    }

    @Test
    fun theSecondLineIsTheLastBeforeTheEllipsis() {
        val h = heights()
        assertTrue(
            h.getValue(VERY_LONG) < h.getValue(SHORT) * 2.5f,
            "the label measured ${h.getValue(VERY_LONG)}dp against ${h.getValue(SHORT)}dp for one " +
                "line - more than two lines, which is the bound that keeps a German compound out " +
                "of the well below.",
        )
    }

    @Test
    fun aLabelThatFitsStaysOnOneLine() {
        val h = heights()
        assertTrue(
            h.getValue(SHORT) <= h.getValue(CONTROL) + 0.5f,
            "a label that fits wrapped: ${h.getValue(SHORT)}dp against ${h.getValue(CONTROL)}dp",
        )
    }

    private companion object {
        const val SHORT = "short"
        const val CONTROL = "control"
        const val LONG = "long"
        const val VERY_LONG = "very-long"
        val LABELS = listOf(
            SHORT to "Card",
            CONTROL to "Name",
            LONG to "Card number (required)",
            VERY_LONG to "National identification number issued by the registry",
        )
    }
}
