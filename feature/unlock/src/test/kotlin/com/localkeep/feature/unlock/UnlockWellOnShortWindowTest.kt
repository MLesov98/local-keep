package com.localkeep.feature.unlock

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse

/**
 * The master-password well is on screen, without a scroll, on a window short for its text size.
 *
 * On a 308x548dp window at font 1.5 the frame pinned Unlock, Use Recovery Kit and the footer, the
 * masthead filled what was left, and the well — the reason the screen exists — sat below the fold of
 * a scroll region that gave no sign it scrolled. The device sweep's own unlock step could not find
 * the field at 1.5 and 2.0 on that window (2026-10-28). [rememberUnlockWindowIsShort] now treats a
 * window shorter than pinned chrome needs at its text size as short, and the frame scrolls as one.
 *
 * Built like [UnlockKeyboardUnpinsTest]; the frame's arrangement is chosen by the real decision.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnlockWellOnShortWindowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var short = false

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                short = rememberUnlockWindowIsShort()
                UnlockFrame(
                    shortWindow = short,
                    scroll = rememberScrollState(),
                    hero = {
                        Column {
                            UnlockHero(subtitle = stringResource(R.string.unlock_subtitle), compact = short)
                        }
                    },
                    entry = {
                        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                            LkFieldLabel(stringResource(R.string.unlock_password_label))
                            LkPasswordField(
                                value = "",
                                onValueChange = {},
                                placeholder = stringResource(R.string.unlock_password_placeholder),
                                label = stringResource(R.string.unlock_password_label),
                                revealed = false,
                                onToggleReveal = {},
                                showCount = true,
                                modifier = Modifier.testTag(WELL),
                            )
                        }
                    },
                    actions = {
                        Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            LkPrimaryButton(text = stringResource(R.string.unlock_button), onClick = {}, enabled = false)
                            LkSecondaryButton(text = stringResource(R.string.unlock_use_recovery_kit_button), onClick = {})
                            Text(
                                stringResource(R.string.unlock_footer_disclaimer),
                                style = LkText.caption,
                                color = LkTheme.colors.textFaint,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                            )
                        }
                    },
                    overlay = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun assertWellOnScreen() {
        val well = compose.onNodeWithTag(WELL, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val rootHeight = compose.activity.window.decorView.height.toFloat()
        assertTrue(
            well.height > 0f && well.top >= 0f && well.bottom <= rootHeight,
            "the password well $well is not on screen in a ${rootHeight}px window (short=$short)",
        )
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 1.5f)
    fun `the well is on screen at 308x548 and font one and a half`() {
        show()
        assertWellOnScreen()
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xhdpi", fontScale = 2.0f)
    fun `the well is on screen at 308x548 and font two`() {
        show()
        assertWellOnScreen()
    }

    /** The control: a tall phone at the default size keeps the pinned arrangement it always had. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.0f)
    fun `a tall window at the default size is not short`() {
        show()
        assertFalse(short, "a 411x891 window at 1.0 took the short arrangement")
        assertWellOnScreen()
    }

    private companion object {
        const val WELL = "short-window-well"
    }
}
