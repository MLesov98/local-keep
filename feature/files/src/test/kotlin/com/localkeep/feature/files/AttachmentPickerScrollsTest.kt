package com.localkeep.feature.files

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.res.stringResource
import com.localkeep.core.model.Attachment
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SCL-002 (scaling pass, 2026-10-19): Files & media was a plain `Column` with a `LazyColumn` only
 * for the files, so nothing above the list could scroll. On a 308dp window at font scale 2.0 the
 * header, the add button and the amber disclosure fill the screen: the Tier G video-metadata
 * sentence was cut off and the empty state's title fell off the bottom with no way to reach it.
 *
 * These drive a real touch swipe, not a semantics scroll action, because the defect is that a
 * finger cannot get there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentPickerScrollsTest {

    @get:Rule
    val compose = createComposeRule()

    private var videoSentence = ""
    private var emptyTitle = ""

    private fun show(state: AttachmentPickerUiState) {
        compose.setContent {
            LocalKeepTheme {
                videoSentence = stringResource(R.string.files_attachments_video_metadata_note)
                emptyTitle = stringResource(R.string.files_attachments_empty_title)
                AttachmentPickerContent(
                    state = state,
                    cameraDenial = null,
                    gateUnavailable = false,
                    onBack = {},
                    onAttach = {},
                    onCancelAttach = null,
                    onOpenSettings = {},
                    onOpen = {},
                    onRemove = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun rootHeight(): Int = compose.onRoot().fetchSemanticsNode().size.height

    /** True when the node is composed and its whole layout box sits inside the window. */
    private fun fullyInWindow(node: SemanticsNodeInteraction): Boolean {
        val n = runCatching { node.fetchSemanticsNode() }.getOrNull() ?: return false
        val top = n.positionInRoot.y
        return top >= 0f && top + n.size.height <= rootHeight()
    }

    /**
     * True when the node's LAST line is on screen. For a block taller than the window (the
     * disclosure is 672dp at 308dp / font 2.0, in a 548dp window) the end of it is what a user has
     * to reach; its top will have scrolled away by then.
     */
    private fun endInWindow(node: SemanticsNodeInteraction): Boolean {
        val n = runCatching { node.fetchSemanticsNode() }.getOrNull() ?: return false
        val bottom = n.positionInRoot.y + n.size.height
        return bottom > 0f && bottom <= rootHeight()
    }

    /** Swipes the screen up with a finger until [visible] holds for [node]. */
    private fun swipeUntil(
        node: SemanticsNodeInteraction,
        visible: (SemanticsNodeInteraction) -> Boolean = ::fullyInWindow,
    ): Boolean {
        repeat(40) {
            if (visible(node)) return true
            // Slow and short, so it does not fling: a fling skips past the very position being looked for.
            compose.onRoot().performTouchInput {
                swipe(
                    start = Offset(centerX, height * 0.75f),
                    end = Offset(centerX, height * 0.55f),
                    durationMillis = 1_500,
                )
            }
            compose.waitForIdle()
        }
        return visible(node)
    }

    private fun attachment(name: String) = Attachment(
        id = UUID.randomUUID(), filename = name, mime = "image/jpeg", sizeBytes = 120_000L,
    )

    @Test
    @Config(qualifiers = "w308dp-h548dp-xxhdpi", fontScale = 2.0f)
    fun `308dp at font 2_0 can reach the end of the video disclosure`() {
        show(AttachmentPickerUiState())
        assertTrue(
            swipeUntil(compose.onNode(hasText(videoSentence, substring = true)), ::endInWindow),
            "the Tier G video-metadata sentence cannot be scrolled fully into view",
        )
    }

    @Test
    @Config(qualifiers = "w308dp-h548dp-xxhdpi", fontScale = 2.0f)
    fun `308dp at font 2_0 can reach the empty-state title`() {
        show(AttachmentPickerUiState())
        assertTrue(
            swipeUntil(compose.onNodeWithText(emptyTitle)),
            "the empty state's title cannot be scrolled into view",
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h823dp-xxhdpi", fontScale = 2.0f)
    fun `411dp at font 2_0 can reach the empty-state title`() {
        show(AttachmentPickerUiState())
        assertTrue(swipeUntil(compose.onNodeWithText(emptyTitle)))
    }

    /** Control: a roomy window needs no scrolling, and a file row is still composed. */
    @Test
    @Config(qualifiers = "w411dp-h900dp-xxhdpi")
    fun `411dp at default font shows everything with no swipe`() {
        show(AttachmentPickerUiState(attachments = listOf(attachment("passport.jpg"))))
        assertTrue(fullyInWindow(compose.onNode(hasText(videoSentence, substring = true))))
        assertTrue(fullyInWindow(compose.onNodeWithText("passport.jpg")))
    }

    /** Control: the last of many files is reachable by swiping, so the single list really scrolls. */
    @Test
    @Config(qualifiers = "w308dp-h548dp-xxhdpi", fontScale = 2.0f)
    fun `the last of many files is reachable at 308dp`() {
        val files = (1..12).map { attachment("scan_%02d.jpg".format(it)) }
        show(AttachmentPickerUiState(attachments = files))
        assertTrue(swipeUntil(compose.onNodeWithText("scan_12.jpg", substring = true)))
    }
}
