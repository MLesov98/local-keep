package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keyboard defect: `LkSlider`'s `.onPreviewKeyEvent` dispatches on
 * `event.nativeKeyEvent.keyCode` alone, with no `KeyEventType` check, and every matched branch
 * unconditionally returns `true`. `performKeyInput { pressKey(...) }` sends a real down/up
 * `KeyEvent` pair for one physical press (`RenameFolderHardwareEnterTest.kt:26`), so both halves
 * match the same keyCode branch: one press calls `onValueChange` twice, and DPAD_UP/DOWN are
 * swallowed as well as LEFT/RIGHT, leaving a D-pad-only user with no way to leave the slider via
 * focus traversal (WCAG 2.1.2 No Keyboard Trap).
 *
 * Robolectric rather than androidTest for the same reason as [LkButtonTouchTargetFloorTest]: this
 * is a key-dispatch/callback-count assertion, not a pixel, so it belongs in the suite that runs on
 * every push.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LkSliderFocusAndKeyboardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun focusedSlider(onValueChange: (Int) -> Unit) {
        compose.setContent {
            LocalKeepTheme {
                LkSlider(
                    value = 4,
                    range = 0..10,
                    onValueChange = onValueChange,
                    label = "Length",
                    modifier = Modifier.width(200.dp).testTag(TAG),
                )
            }
        }
        val node = compose.onNodeWithTag(TAG)
        node.requestFocus()
        node.assertIsFocused()
    }

    @Test
    fun oneDpadRightPressCallsOnValueChangeExactlyOnce() {
        var calls = 0
        focusedSlider { calls++ }

        compose.onNodeWithTag(TAG).performKeyInput { pressKey(Key.DirectionRight) }

        assertEquals(
            1,
            calls,
            "one DPAD_RIGHT press called onValueChange $calls times — performKeyInput sends a " +
                "real KeyDown/KeyUp pair for one physical press (RenameFolderHardwareEnterTest.kt:26) " +
                "and the handler matches the keyCode on both halves instead of gating on " +
                "KeyEventType.KeyDown, so one press moves the value by two.",
        )
    }

    @Test
    fun dpadUpDoesNotCallOnValueChange() {
        var calls = 0
        focusedSlider { calls++ }

        compose.onNodeWithTag(TAG).performKeyInput { pressKey(Key.DirectionUp) }

        assertEquals(
            0,
            calls,
            "DPAD_UP called onValueChange $calls times. LkSlider only has a horizontal axis; " +
                "UP/DOWN must fall through unconsumed so Compose's focus traversal can move off " +
                "the slider instead of being trapped on it.",
        )
    }

    /**
     * Focus-order defect: `lkFocusRing`'s own contract (`LkModifiers.kt`: "Place this *before*
     * `.clickable`/`.focusable` in the chain") is violated by `LkSlider`, which places
     * `.focusable()` first — so the ring's `onFocusChanged` never sees the focus target and the
     * ring can never light. This reads the source rather than driving the composable, in the idiom
     * [NamingSheetAutoFocusTest] already uses in this module: a drawn ring's lit/unlit state is a
     * pixel, and the honest way to check *that* is a device; the ordering that determines whether
     * it can ever light at all is a source-level fact this test can pin without one.
     */
    @Test
    fun lkFocusRingComesBeforeFocusableInLkSlidersModifierChain() {
        val body = lkSliderSource()
        val ringIndex = body.indexOf(".lkFocusRing(")
        val focusableIndex = body.indexOf(".focusable()")
        assertTrue(ringIndex >= 0, "did not find .lkFocusRing( in LkSlider's modifier chain")
        assertTrue(focusableIndex >= 0, "did not find .focusable() in LkSlider's modifier chain")
        assertTrue(
            ringIndex < focusableIndex,
            "LkSlider places .lkFocusRing() after .focusable() — lkFocusRing's onFocusChanged " +
                "only observes focus targets that come after it in the chain (LkModifiers.kt's " +
                "own contract), so with .focusable() first the ring's onFocusChanged never fires " +
                "and it can never light.",
        )
    }

    /** The control for the test above: prove it read LkSlider's own chain, not some other function's. */
    @Test
    fun theSourceReadReallyIsLkSlider() {
        val body = lkSliderSource()
        assertTrue("fun LkSlider(" in body, "read a file that is not LkIndicators.kt's LkSlider")
        assertTrue(".onPreviewKeyEvent" in body, "read a LkSlider with no key handler")
    }

    /** The text of `fun LkSlider(...) { ... }`, from its declaration up to the next top-level `fun`. */
    private fun lkSliderSource(): String {
        val text = File(REPO, "core/ui/src/main/kotlin/com/localkeep/core/ui/theme/LkIndicators.kt")
            .also { assertTrue(it.isFile, "LkIndicators.kt not found at ${it.absolutePath}") }
            .readText()
        val start = text.indexOf("fun LkSlider(")
        assertTrue(start >= 0, "fun LkSlider( not found in LkIndicators.kt")
        val end = text.indexOf("\n@Composable\nfun ", start)
        assertTrue(end > start, "could not find the next top-level fun after LkSlider")
        return text.substring(start, end)
    }

    private companion object {
        const val TAG = "slider-keyboard-subject"
    }
}

private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")
