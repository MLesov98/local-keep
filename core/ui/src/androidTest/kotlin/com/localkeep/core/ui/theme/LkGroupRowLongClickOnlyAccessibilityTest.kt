package com.localkeep.core.ui.theme

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A [LkGroupRow] with only [LkGroupRow.onLongClick] set must not also advertise a click action.
 *
 * The bug: the `onLongClick != null` branch routed every long-press-only row through
 * `combinedClickable` with a no-op `onClick` standing in for the missing one, because
 * `combinedClickable` cannot supply a long-click action without a click action alongside it —
 * `AbstractClickableNode.applySemantics` calls `onClick(...)` unconditionally. TalkBack does not
 * know the tap is fake; it announces "double tap to activate" for a row that discards the tap on
 * the floor. `ItemDetailScreen`'s read-only-but-copyable field row is a live example: `onClick` is
 * null there whenever `onCommit` is, `onLongClick` never is.
 *
 * Both directions are pinned, plus the two- and one-action controls that must not regress: the row
 * still merges its child text into one accessible node (unchanged from before this fix), and a row
 * that carries both actions still exposes both, each under its own label.
 */
@RunWith(AndroidJUnit4::class)
class LkGroupRowLongClickOnlyAccessibilityTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val TAG = "row"
        const val TITLE = "Wi-Fi password"
        const val LONG_LABEL = "Copy"
        const val CLICK_LABEL = "Edit"
    }

    private fun <T> SemanticsConfiguration.getOrNull(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) this[key] else null

    @Test
    fun aLongClickOnlyRowRegistersNoClickAction() {
        var longPressed = false
        rule.setContent {
            LocalKeepTheme {
                LkGroupRow(
                    first = true,
                    modifier = Modifier.testTag(TAG),
                    onClick = null,
                    onLongClick = { longPressed = true },
                    onLongClickLabel = LONG_LABEL,
                ) {
                    Text(TITLE)
                }
            }
        }

        val config = rule.onNodeWithTag(TAG).fetchSemanticsNode().config
        assertNull(
            config.getOrNull(SemanticsActions.OnClick),
            "a row with no onClick must not expose a click action to a screen reader",
        )
        val longAction = config.getOrNull(SemanticsActions.OnLongClick)
        assertNotNull(longAction, "the long-click action must still be exposed")
        assertEquals(LONG_LABEL, longAction.label, "the long-click action must carry its label")

        // The accessibility action must actually reach the caller's lambda, not just be present.
        assertTrue(longAction.action?.invoke() == true, "the long-click semantics action should fire onLongClick")
        assertTrue(longPressed, "invoking the long-click semantics action must call onLongClick")
    }

    @Test
    fun aLongClickOnlyRowStillMergesItsChildTextIntoOneNode() {
        rule.setContent {
            LocalKeepTheme {
                LkGroupRow(
                    first = true,
                    modifier = Modifier.testTag(TAG),
                    onClick = null,
                    onLongClick = {},
                    onLongClickLabel = LONG_LABEL,
                ) {
                    Text(TITLE)
                }
            }
        }

        val text = rule.onNodeWithTag(TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        assertNotNull(text, "the row's merged node must still carry its child's text")
        assertTrue(
            text.any { it.text == TITLE },
            "expected \"$TITLE\" among $text — the row stopped merging its child into one node",
        )
    }

    @Test
    fun aClickOnlyRowIsUnaffected() {
        rule.setContent {
            LocalKeepTheme {
                LkGroupRow(
                    first = true,
                    modifier = Modifier.testTag(TAG),
                    onClick = {},
                    onClickLabel = CLICK_LABEL,
                ) {
                    Text(TITLE)
                }
            }
        }

        val config = rule.onNodeWithTag(TAG).fetchSemanticsNode().config
        assertNotNull(config.getOrNull(SemanticsActions.OnClick), "a click-only row must keep its click action")
        assertNull(config.getOrNull(SemanticsActions.OnLongClick), "a click-only row must not gain a long-click action")
    }

    @Test
    fun aRowWithBothActionsExposesBothUnderTheirOwnLabels() {
        rule.setContent {
            LocalKeepTheme {
                LkGroupRow(
                    first = true,
                    modifier = Modifier.testTag(TAG),
                    onClick = {},
                    onClickLabel = CLICK_LABEL,
                    onLongClick = {},
                    onLongClickLabel = LONG_LABEL,
                ) {
                    Text(TITLE)
                }
            }
        }

        val config = rule.onNodeWithTag(TAG).fetchSemanticsNode().config
        assertEquals(CLICK_LABEL, config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals(LONG_LABEL, config.getOrNull(SemanticsActions.OnLongClick)?.label)
    }

    @Test
    fun aRowWithNeitherActionExposesNeither() {
        rule.setContent {
            LocalKeepTheme {
                LkGroupRow(first = true, modifier = Modifier.testTag(TAG)) {
                    Text(TITLE)
                }
            }
        }

        val config = rule.onNodeWithTag(TAG).fetchSemanticsNode().config
        assertFalse(config.contains(SemanticsActions.OnClick))
        assertFalse(config.contains(SemanticsActions.OnLongClick))
    }
}
