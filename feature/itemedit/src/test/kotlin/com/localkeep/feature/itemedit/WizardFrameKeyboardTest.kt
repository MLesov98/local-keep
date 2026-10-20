package com.localkeep.feature.itemedit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.model.CardBrand
import com.localkeep.core.ui.theme.LkCardDigitGroups
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkStepRail
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wizards' frame obeys the app's keyboard-up rule, and puts every step back at the top.
 *
 * [WizardFrame] is what both the card and the identity wizard draw: a pinned rail, a scrolling
 * body, and the Continue stack pinned under it. With the keyboard up on a 308x548dp window at 2.0
 * text (about 250dp of keyboard) that was 78dp of gutters, a rail, 24dp and Continue claiming the
 * window before the scroll region got any of it, so the well being typed into had a sliver to live
 * in. The shared rule (`rememberLkKeyboardUnpinsChrome`) takes the lot into the scroll while the
 * keyboard leaves too little room, and pins it again when the keyboard closes.
 *
 * Composed with the real frame, the real card face, number well and Continue stack; only the
 * ViewModel-bound step bodies are stood in for, because `ItemEditViewModel` cannot be built on a
 * JVM. The keyboard is forced into its typing layout through `latched`, which is what the system
 * insets animation drives on a device but Robolectric never raises.
 *
 * Insets are dispatched by hand because Robolectric raises no keyboard (see
 * `PasswordStepLandscapeImeTest`). Asserted as geometry, not as a screenshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WizardFrameKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var step by mutableIntStateOf(0)
    private lateinit var scrollRef: ScrollState

    @Composable
    private fun Frame(keyboardUp: Boolean, withArrival: Boolean = false) {
        val arrival = remember { BringIntoViewRequester() }
        val kb = remember { CardWizardKeyboard().also { it.latched = keyboardUp } }
        val scroll = rememberScrollState().also { scrollRef = it }
        val state = remember { ItemEditUiState(isNew = true, cardNumber = "4111111111111111") }
        WizardFrame(
            keyboard = kb,
            scroll = scroll,
            step = step,
            railGap = 0.dp,
            rail = { LkStepRail(stepIndex = step, stepCount = 5, progressText = "Step ${step + 1} of 5", progressDescription = "Step") },
            actions = {
                CardWizardActions(
                    step = step,
                    canAdvance = true,
                    isSaving = false,
                    onAdvance = {},
                    onBack = {},
                    onEnterFullForm = null,
                    keyboard = kb,
                    typing = keyboardUp,
                )
            },
            arrival = arrival.takeIf { withArrival },
        ) {
            CardWizardFace(state, CardBrand.VISA, step, 3, kb, scroll)
            CardWizardHeading(step, CardBrand.VISA)
            // The number step as it is drawn: the label and the real well, which wraps at this size.
            Column(Modifier.testTag(FIELD).bringIntoViewRequester(arrival)) {
                LkFieldLabel("Card number")
                CardNumberTextField(
                    value = state.cardNumber,
                    onValueChange = {},
                    label = "Card number",
                    groups = LkCardDigitGroups.Default,
                )
            }
            // The rest of the step: a hint and the exits, tall enough that the body overflows
            // whenever the window is short.
            Box(Modifier.height(120.dp).testTag(TAIL))
        }
    }

    private fun show(keyboardUp: Boolean, withArrival: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent { LocalKeepTheme { Frame(keyboardUp, withArrival) } }
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

    private fun scrollBy(dy: Float) {
        val scroller = scroller()
        compose.runOnUiThread {
            scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** Scrolls until [target]'s bottom edge is inside the scroll region (not `performScrollTo`: see the onboarding test). */
    private fun scrollIntoView(target: SemanticsNodeInteraction) {
        repeat(6) {
            val s = scroller()
            val t = target.fetchSemanticsNode()
            val dy = (t.positionOnScreen.y + t.size.height) - (s.positionOnScreen.y + s.size.height)
            if (dy <= 0f) return
            scrollBy(dy)
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
    fun `keyboard up on a short window leaves a scroll region the well fits in and Continue within reach`() {
        show(keyboardUp = true)
        raiseKeyboard(500)
        val keyboardTop = dp((compose.activity.window.decorView.height - 500).toFloat())
        val viewport = dp(scroller().size.height.toFloat())
        val step = dp(compose.onNodeWithTag(FIELD).fetchSemanticsNode().size.height.toFloat())
        assertTrue(
            viewport >= step,
            "the scroll region is ${viewport}dp tall with the keyboard up, so the ${step}dp label-and-well " +
                "block cannot be shown whole at any scroll position",
        )
        scrollIntoView(compose.onNodeWithTag(FIELD))
        assertTrue(
            bottomDp(compose.onNodeWithTag(FIELD)) <= keyboardTop + 1f,
            "the well ends below the keyboard's top edge at ${keyboardTop}dp",
        )
        val continueNode = compose.onNodeWithText(CONTINUE)
        scrollIntoView(continueNode)
        assertTrue(
            bottomDp(continueNode) <= keyboardTop + 1f,
            "Continue ends ${bottomDp(continueNode)}dp down, below the keyboard's top edge at ${keyboardTop}dp",
        )
    }

    /** The keyboard going away pins everything again: the rail is outside the scroll, so Continue is visible without scrolling. */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `with the keyboard down the chrome is pinned and Continue needs no scrolling`() {
        show(keyboardUp = false)
        val s = scroller()
        // Pinned: the scroll region starts below the 46dp gutter and the rail, never at the top edge.
        assertTrue(dp(s.positionOnScreen.y) >= 46f + 10f, "the scroll region starts at ${dp(s.positionOnScreen.y)}dp")
        val windowBottom = dp(compose.activity.window.decorView.height.toFloat())
        assertTrue(
            bottomDp(compose.onNodeWithText(CONTINUE)) <= windowBottom - 32f + 1f,
            "Continue is not pinned inside the bottom gutter",
        )
    }

    /** The control: a tall window with the keyboard up keeps today's arrangement. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tall window keeps the chrome pinned with the keyboard up`() {
        show(keyboardUp = true)
        raiseKeyboard(840)
        val top = dp(scroller().positionOnScreen.y)
        assertTrue(top >= 46f + 10f, "the scroll region starts ${top}dp down; with room to spare the rail stays pinned above it")
    }

    /** SCL-031: one scroll offset used to outlive the step it was scrolled for. */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `a step change puts the scroll back at the top`() {
        show(keyboardUp = false)
        scrollBy(300f)
        assertTrue(scrollRef.value > 0, "the harness could not scroll — body fits the window?")
        compose.runOnUiThread {
            step = 1
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("When does it expire?").assertExists()
        assertEquals(0, scrollRef.value, "step 2 arrived with the previous step's scroll offset")
    }

    /**
     * SCL-029: a step used to arrive with its primary field below the fold on a short window. 308x548dp
     * at 2.0 text, keyboard down: the face and the heading take the whole region.
     */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `a step arrives with its primary field in view`() {
        show(keyboardUp = false, withArrival = true)
        // Frames for the entrance effect to ask, and for the scroll it asks for to run.
        repeat(6) { compose.mainClock.advanceTimeBy(100) }
        compose.waitForIdle()
        val s = scroller()
        val field = compose.onNodeWithTag(FIELD).fetchSemanticsNode()
        val viewportBottom = s.positionOnScreen.y + s.size.height
        assertTrue(
            field.positionOnScreen.y + field.size.height <= viewportBottom + 1f,
            "the field ends ${dp(field.positionOnScreen.y + field.size.height)}dp down; the scroll region ends at " +
                "${dp(viewportBottom)}dp, so the step opened with its question below the fold",
        )
    }

    /** The control: a window the step already fits in does not move. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a step that already shows its field is not scrolled`() {
        show(keyboardUp = false, withArrival = true)
        repeat(6) { compose.mainClock.advanceTimeBy(100) }
        compose.waitForIdle()
        assertEquals(0, scrollRef.value, "a step that fits was scrolled on arrival")
    }

    private companion object {
        const val FIELD = "field"
        const val TAIL = "tail"
        const val CONTINUE = "Continue"
    }
}
