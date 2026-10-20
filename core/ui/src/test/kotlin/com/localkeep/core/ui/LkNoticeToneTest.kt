package com.localkeep.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A refusal must not be dressed as a success.
 *
 * [LkNoticeHost] drew every notice with the jade check plate and played [Haptic.SUCCESS] for it, so
 * the folder screens' refusals ("Folders can nest eight levels deep") arrived with a tick and the
 * platform's "what you asked for worked" buzz, directly over the Cancel button of the screen that
 * had just refused. This drives the real host and records what it actually asks the haptic channel
 * to play, rather than reading the source for a name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkNoticeToneTest {

    @get:Rule
    val compose = createComposeRule()

    private val played = mutableListOf<HapticFeedbackType>()

    private val recorder = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    @BeforeTest
    @AfterTest
    fun reset() {
        LkNotices.clear()
        played.clear()
    }

    private fun showHost() = compose.setContent {
        LocalKeepTheme {
            CompositionLocalProvider(LocalHapticFeedback provides recorder) { LkNoticeHost() }
        }
    }

    @Test
    fun `a refusal draws no success check and plays no success haptic`() {
        showHost()
        LkNotices.postRefusal("Folders can nest eight levels deep")
        compose.waitForIdle()

        compose.onNodeWithTag(LkNoticeHostTags.REFUSAL_GLYPH).assertExists()
        compose.onNodeWithTag(LkNoticeHostTags.SUCCESS_GLYPH).assertDoesNotExist()
        assertEquals(
            listOf(HapticFeedbackType.Reject),
            played,
            "a refusal must play the platform's reject, and never its confirm",
        )
    }

    /** The control: the default tone is untouched, so every existing success caller still ticks. */
    @Test
    fun `a success keeps the jade check and the success haptic`() {
        showHost()
        LkNotices.post("Item saved")
        compose.waitForIdle()

        compose.onNodeWithTag(LkNoticeHostTags.SUCCESS_GLYPH).assertExists()
        compose.onNodeWithTag(LkNoticeHostTags.REFUSAL_GLYPH).assertDoesNotExist()
        assertEquals(listOf(HapticFeedbackType.Confirm), played)
    }

    @Test
    fun `the tone travels on the notice and defaults to success`() {
        LkNotices.post("Item saved")
        assertEquals(LkNotices.Tone.Success, LkNotices.current.value?.tone)
        LkNotices.postRefusal("No")
        assertEquals(LkNotices.Tone.Refusal, LkNotices.current.value?.tone)
        LkNotices.postRefusal(android.R.string.copy)
        assertEquals(LkNotices.Tone.Refusal, LkNotices.current.value?.tone)
    }
}

/**
 * What the lock lost or replaced is a loss, and is drawn as one; and a plain notice is as tall as one
 * carrying an Undo button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkNoticeUnlockToneAndHeightTest {

    @get:Rule
    val compose = createComposeRule()

    @BeforeTest
    @AfterTest
    fun reset() = LkNotices.clear()

    @Test
    fun `a loss reported at unlock is a refusal, not a success`() {
        LkNotices.postAtUnlock(android.R.string.copy)
        assertEquals(LkNotices.Tone.Refusal, LkNotices.current.value?.tone)
        LkNotices.postAtUnlock(LkNotices.Message.Text("x"))
        assertEquals(LkNotices.Tone.Refusal, LkNotices.current.value?.tone)
    }

    /** The control: a caller reporting a good outcome at unlock can still ask for the success tone. */
    @Test
    fun `an unlock notice can still be a success`() {
        LkNotices.postAtUnlock(android.R.string.copy, LkNotices.Tone.Success)
        assertEquals(LkNotices.Tone.Success, LkNotices.current.value?.tone)
        assertEquals(LkNotices.UNLOCK_MS, LkNotices.current.value?.durationMs)
    }

    @Test
    fun `a notice without an action is as tall as one with an Undo`() {
        compose.setContent { LocalKeepTheme { LkNoticeHost(Modifier.testTag("host")) } }
        LkNotices.post("Item saved")
        compose.waitForIdle()
        val plain = compose.onNodeWithTag("host").fetchSemanticsNode().size.height

        LkNotices.post("Moved to trash", "Undo") {}
        compose.waitForIdle()
        val withAction = compose.onNodeWithTag("host").fetchSemanticsNode().size.height

        assertEquals(withAction, plain, "both variants share one plate height")
        assertEquals(76 * 3, plain, "equalised upward to the 48dp touch target plus the plate's padding")
    }
}
