package com.localkeep.feature.itemedit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The item editor's Save stack obeys the app's keyboard-up rule.
 *
 * [ItemEditFrame] is what `ItemEditScreen` draws: the form scrolling above a Save stack (Save, and
 * "Scan the QR code" on a 2FA item) pinned over the keyboard. On a 308x548dp window at 2.0 text with
 * about 250dp of keyboard, the two pinned buttons took the window before the form got any of it, so
 * the field being typed into had a sliver. The shared rule (`rememberLkKeyboardUnpinsChrome`, via the
 * scaffold's `LocalLkChromeUnpinned`) moves the stack to the end of the form's own scroll while the
 * keyboard leaves too little room, and pins it again when the keyboard closes.
 *
 * Composed with the real frame, the real scaffold, header, well and buttons; only the form's
 * ViewModel-bound fields are stood in for, because `ItemEditViewModel` cannot be built on a JVM.
 * Insets are dispatched by hand, as in `PasswordStepLandscapeImeTest`. Geometry, not a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemEditFrameKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Frame() {
        ItemEditFrame(
            formScroll = rememberScrollState(),
            hasActions = true,
            actions = {
                LkPrimaryButton(text = "Save", onClick = {}, modifier = Modifier.testTag(SAVE))
                LkSecondaryButton(text = "Scan the QR code", onClick = {}, modifier = Modifier.testTag(SCAN))
            },
        ) {
            LkScreenHeader(title = "Login")
            Spacer(Modifier.height(16.dp))
            Column(Modifier.testTag(FIELD)) {
                LkFieldLabel("Username")
                LkTextField(value = "", onValueChange = {}, label = "Username")
            }
            Spacer(Modifier.height(400.dp))
        }
    }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalKeepTheme { Frame() } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun raiseKeyboard(heightPx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread {
            fun deliver(v: android.view.View) {
                if (v.javaClass.name.contains("AndroidComposeView")) {
                    ViewCompat.dispatchApplyWindowInsets(v, insets)
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) deliver(v.getChildAt(i))
            }
            deliver(compose.activity.window.decorView)
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun scroller() = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )[0].fetchSemanticsNode()

    private fun scrollIntoView(target: SemanticsNodeInteraction) {
        repeat(6) {
            val s = scroller()
            val t = target.fetchSemanticsNode()
            val dy = (t.positionOnScreen.y + t.size.height) - (s.positionOnScreen.y + s.size.height)
            if (dy <= 0f) return
            compose.runOnUiThread { s.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy) }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
        }
    }

    private fun dp(px: Float) = px / compose.density.density

    private fun bottomDp(n: SemanticsNodeInteraction): Float {
        val node = n.fetchSemanticsNode()
        return dp(node.positionOnScreen.y + node.size.height)
    }

    /** 308x548dp at xhdpi (density 2), 2.0 text, 250dp of keyboard: the window the defect was found on. */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `keyboard up on a short window leaves the form room and keeps Save within reach`() {
        show()
        raiseKeyboard(500)
        val keyboardTop = dp((compose.activity.window.decorView.height - 500).toFloat())
        val viewport = dp(scroller().size.height.toFloat())
        val field = dp(compose.onNodeWithTag(FIELD).fetchSemanticsNode().size.height.toFloat())
        assertTrue(
            viewport >= 2 * field,
            "the form's scroll region is ${viewport}dp tall with the keyboard up, so a ${field}dp " +
                "label-and-well block shares it with almost nothing",
        )
        scrollIntoView(compose.onNodeWithTag(FIELD))
        assertTrue(bottomDp(compose.onNodeWithTag(FIELD)) <= keyboardTop + 1f, "the well ends below the keyboard's top edge")
        for (tag in listOf(SAVE, SCAN)) {
            scrollIntoView(compose.onNodeWithTag(tag))
            assertTrue(
                bottomDp(compose.onNodeWithTag(tag)) <= keyboardTop + 1f,
                "$tag ends ${bottomDp(compose.onNodeWithTag(tag))}dp down, below the keyboard's top edge at ${keyboardTop}dp",
            )
        }
    }

    /** The keyboard closed: Save is pinned again, so it is on screen without any scrolling. */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `with the keyboard down Save is pinned and needs no scrolling`() {
        show()
        val windowBottom = dp(compose.activity.window.decorView.height.toFloat())
        assertTrue(bottomDp(compose.onNodeWithTag(SAVE)) <= windowBottom, "Save is below the window")
        assertTrue(bottomDp(compose.onNodeWithTag(SCAN)) <= windowBottom, "Scan is below the window")
    }

    /** The control: a tall window with the keyboard up keeps Save pinned just over the keys. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tall window keeps Save pinned over the keyboard`() {
        show()
        raiseKeyboard(840)
        val keyboardTop = dp((compose.activity.window.decorView.height - 840).toFloat())
        assertTrue(
            bottomDp(compose.onNodeWithTag(SAVE)) <= keyboardTop + 1f &&
                bottomDp(compose.onNodeWithTag(SAVE)) > 0f,
            "Save is not sitting over the keyboard without scrolling",
        )
    }

    private companion object {
        const val FIELD = "field"
        const val SAVE = "save"
        const val SCAN = "scan"
    }
}
