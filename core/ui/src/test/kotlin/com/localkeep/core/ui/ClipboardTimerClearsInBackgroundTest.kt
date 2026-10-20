package com.localkeep.core.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The 30-second clear fires while the user is in the app they copied the secret *for* (S5-01,
 * 2026-10-02).
 *
 * That is the ordinary flow — copy a password, switch to the browser, paste — and at the 30 s mark
 * no LocalKeep window has focus. From API 29 the platform serves the ownership read a plain
 * `false` ("Denying clipboard access … not in focus"), and the timer took that as "cannot tell,
 * keep the record", clearing nothing. The password stayed on the clipboard until LocalKeep was next
 * brought forward. Only reads are focus-gated; AOSP's `clipboardAccessAllowed` answers
 * `OP_WRITE_CLIPBOARD` with "Writing is allowed without focus", so the timer can clear.
 *
 * Robolectric's clipboard never denies a read, so the denial is supplied as lambdas, the same way
 * [ClipboardReadableDefaultTest] drives the decision. `copy` is real: it is what makes this clip
 * the live one the timer belongs to.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardTimerClearsInBackgroundTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun pending(): String? =
        context.getSharedPreferences("clipboard_pending", 0).getString("pending_clip_id", null)

    @AfterTest
    fun tearDown() {
        ClipboardHelper.clearNow(context)
        context.getSharedPreferences("clipboard_pending", 0).edit().clear().commit()
        ClipboardHelper.windowFocused = false
    }

    private fun copyAndTakeId(): String {
        ClipboardHelper.copy(context, "Password", "not-a-real-secret")
        return requireNotNull(ClipboardHelper.activeClip.value).clipId
    }

    /** The finding: unfocused, denied, and the timer must clear anyway. */
    @Test
    fun `the timer clears its own clip when the platform will not let it look`() {
        val clipId = copyAndTakeId()
        ClipboardHelper.windowFocused = false
        var cleared = false

        ClipboardHelper.onClearTimer(
            context = context,
            clipId = clipId,
            hasClip = { false },
            currentClipId = { null },
            clearClipboard = { cleared = true },
        )

        assertTrue(cleared, "the secret stayed on the clipboard until LocalKeep was next focused")
        assertNull(pending(), "a clear that landed leaves nothing for the next sweep to do")
        assertNull(ClipboardHelper.activeClip.value)
    }

    /** A read that throws is the other shape the denial takes; the timer clears through it too. */
    @Test
    fun `the timer clears through a read that throws`() {
        val clipId = copyAndTakeId()
        var cleared = false

        ClipboardHelper.onClearTimer(
            context = context,
            clipId = clipId,
            hasClip = { throw SecurityException("denied") },
            currentClipId = { null },
            clearClipboard = { cleared = true },
        )

        assertTrue(cleared)
    }

    /**
     * Bounded to its own clip: a later LocalKeep copy owns the clipboard and its own countdown, so
     * the earlier timer must not cut it short.
     */
    @Test
    fun `a superseded timer does not clear a later copy`() {
        val first = copyAndTakeId()
        copyAndTakeId()
        var cleared = false

        ClipboardHelper.onClearTimer(
            context = context,
            clipId = first,
            hasClip = { false },
            currentClipId = { null },
            clearClipboard = { cleared = true },
        )

        assertFalse(cleared, "the first copy's timer wiped the second copy 30 s early")
    }

    /**
     * The control on the trade. The next-foreground sweep's record may be hours old, so an
     * unreadable clipboard there still clears nothing — that blind clear could wipe whatever the
     * user has copied since.
     */
    @Test
    fun `the stale sweep still never clears what it cannot see`() {
        context.getSharedPreferences("clipboard_pending", 0).edit()
            .putString("pending_clip_id", "an-old-clip").commit()
        var cleared = false

        ClipboardHelper.completePendingClear(
            context = context,
            clipId = "an-old-clip",
            hasClip = { false },
            currentClipId = { null },
            clearClipboard = { cleared = true },
        )

        assertFalse(cleared)
        assertEquals("an-old-clip", pending())
    }
}
