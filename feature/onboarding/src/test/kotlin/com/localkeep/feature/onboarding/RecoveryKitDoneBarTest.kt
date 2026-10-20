package com.localkeep.feature.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What [RecoveryKitDoneBar] renders, either side of the kit actually being saved.
 *
 * The companion to `RecoveryKitDoneActionTest`, which pins that `RecoveryKitScreen` wires this to
 * `state.exported`. Together they cover the defect: a screen that told the user to continue and
 * composed nothing to continue with.
 *
 * [GraphicsMode.Mode.NATIVE] for the reason `PasswordStepFieldOnScreenTest` records — under
 * Robolectric's legacy graphics there is no real text layout, so assertions about a control being
 * displayed can pass against a control with no size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryKitDoneBarTest {

    @get:Rule
    val compose = createComposeRule()

    private var label = ""

    private fun show(exported: Boolean, onDone: () -> Unit = {}) {
        compose.setContent {
            LocalKeepTheme {
                label = stringResource(R.string.onboarding_recoverykit_done_cta)
                RecoveryKitDoneBar(exported = exported, onDone = onDone)
            }
        }
        compose.waitForIdle()
    }

    /**
     * Absent, not dimmed. Until a save path commits, the words on screen are a proposal — the
     * footnote promises the kit the user already has still works — so there is nothing to confirm
     * and nothing to press.
     */
    @Test
    fun `there is no way forward until the kit is saved`() {
        show(exported = false)
        compose.onAllNodes(hasText(label)).fetchSemanticsNodes().let {
            assertEquals(
                0,
                it.size,
                "A Done action was composed over an unsaved kit. It would tell the user they are " +
                    "finished while this vault still has whatever kit it had before.",
            )
        }
    }

    /** The fix: once the kit is committed, a control that says so and leads out. */
    @Test
    fun `a saved kit gets a way forward that is not the back chevron`() {
        var done = 0
        show(exported = true) { done++ }
        compose.onNode(hasText(label)).assertIsDisplayed().assertHasClickAction().performClick()
        assertEquals(1, done, "the Done action did not lead anywhere")
    }

    /**
     * The transition itself, because that is the moment the defect was observed in: the user taps a
     * save button, the notice flips to "Recovery Kit captured", and the screen has to change from
     * offering no exit to offering one.
     */
    @Test
    fun `the way forward appears when the export lands`() {
        var exported by mutableStateOf(false)
        compose.setContent {
            LocalKeepTheme {
                label = stringResource(R.string.onboarding_recoverykit_done_cta)
                RecoveryKitDoneBar(exported = exported, onDone = {})
            }
        }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasText(label)).fetchSemanticsNodes().size)
        exported = true
        compose.waitForIdle()
        assertEquals(
            1,
            compose.onAllNodes(hasText(label)).fetchSemanticsNodes().size,
            "the kit committed and the screen still offered no way forward",
        )
    }
}
