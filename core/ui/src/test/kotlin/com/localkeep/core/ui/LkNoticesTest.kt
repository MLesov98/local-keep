package com.localkeep.core.ui

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LkNoticesTest {

    @BeforeTest
    @AfterTest
    fun reset() = LkNotices.clear()

    @Test
    fun `posting announces the message`() {
        LkNotices.post("Moved to trash")
        assertEquals(LkNotices.Message.Text("Moved to trash"), LkNotices.current.value?.message)
    }

    @Test
    fun `the newest post replaces the one before it`() {
        LkNotices.post("Item saved")
        LkNotices.post("Moved to trash")
        assertEquals(LkNotices.Message.Text("Moved to trash"), LkNotices.current.value?.message)
    }

    @Test
    fun `dismissing retires the notice that asked`() {
        LkNotices.post("Item restored")
        val shown = LkNotices.current.value!!
        LkNotices.dismiss(shown)
        assertNull(LkNotices.current.value)
    }

    @Test
    fun `a stale dismiss cannot cut short the notice that replaced it`() {
        // The host's auto-dismiss is a delayed coroutine. If a second action lands while the first
        // timer is still pending, that timer must not take the new confirmation down with it —
        // otherwise the message for what the user just did vanishes almost immediately.
        LkNotices.post("Item saved")
        val first = LkNotices.current.value!!
        LkNotices.post("Moved to trash")
        LkNotices.dismiss(first)
        assertEquals(LkNotices.Message.Text("Moved to trash"), LkNotices.current.value?.message)
    }

    @Test
    fun `two identical messages are distinct notices`() {
        // Trashing two rows in a row posts the same text twice. Without distinct identities the
        // second post would be indistinguishable from the first, and the first notice's pending
        // dismiss would retire it early.
        LkNotices.post("Moved to trash")
        val first = LkNotices.current.value!!
        LkNotices.post("Moved to trash")
        val second = LkNotices.current.value!!
        assertNotEquals(first, second)
        LkNotices.dismiss(first)
        assertEquals(LkNotices.Message.Text("Moved to trash"), LkNotices.current.value?.message)
    }

    @Test
    fun `an action gets the longer duration`() {
        LkNotices.post("Moved to trash", actionLabel = "Undo") { }
        assertEquals(LkNotices.ACTION_MS, LkNotices.current.value?.durationMs)

        LkNotices.post("Item saved")
        assertEquals(LkNotices.SHORT_MS, LkNotices.current.value?.durationMs)
    }

    /**
     * A notice that lands with the unlock, reporting something the lock lost or replaced, outlasts
     * the transition it lands in. Three device testers on 2026-10-05 unlocked straight past
     * "your unsaved changes were discarded" at four seconds and reported the loss as silent.
     */
    @Test
    fun `a loss reported at unlock stays up for the unlock duration`() {
        LkNotices.postAtUnlock(android.R.string.copy)
        assertEquals(LkNotices.UNLOCK_MS, LkNotices.current.value?.durationMs)
        assertEquals(10_000L, LkNotices.UNLOCK_MS, "the duration chosen for these notices")

        LkNotices.postAtUnlock(LkNotices.Message.Plural(R.plurals.core_ui_clipboard_clears_in_seconds, 2))
        assertEquals(LkNotices.UNLOCK_MS, LkNotices.current.value?.durationMs, "the counted form too")
    }

    /** The control: nothing else got longer, so an ordinary confirmation still clears at four seconds. */
    @Test
    fun `an ordinary notice keeps the short duration`() {
        LkNotices.postAtUnlock(android.R.string.copy)
        LkNotices.post(android.R.string.cut)
        assertEquals(LkNotices.SHORT_MS, LkNotices.current.value?.durationMs)
        assertEquals(4_000L, LkNotices.SHORT_MS)
    }

    /** What a lock queued for the next unlock is posted with the unlock duration, once. */
    @Test
    fun `a queued unlock notice is posted for the unlock duration and then forgotten`() {
        LkUnlockNotices.queue(android.R.string.copy)
        LkUnlockNotices.postPending()
        assertEquals(LkNotices.Message.Res(android.R.string.copy), LkNotices.current.value?.message)
        assertEquals(LkNotices.UNLOCK_MS, LkNotices.current.value?.durationMs)

        LkNotices.clear()
        LkUnlockNotices.postPending()
        assertNull(LkNotices.current.value, "a second unlock must not repeat it")
    }

    @Test
    fun `an action label without a handler is dropped rather than shown as a dead control`() {
        LkNotices.post("Item saved", actionLabel = "Undo", onAction = null)
        assertNull(LkNotices.current.value?.actionLabel)
    }

    @Test
    fun `the action is handed back to the caller intact`() {
        var undone = false
        LkNotices.post("Moved to trash", actionLabel = "Undo") { undone = true }
        LkNotices.current.value?.onAction?.invoke()
        assertTrue(undone)
    }

    /**
     * The overload a ViewModel uses. Without it, four files posted English literals — "Moved to
     * trash", "Item saved", "Custom field removed", "Undo" — because a ViewModel holds no Context
     * to resolve a resource with, and they shipped that way in all four translated languages.
     * The id is carried, not resolved: [LkNoticeHost] does that, where a Context exists.
     */
    @Test
    fun `a resource id survives the post without being resolved`() {
        LkNotices.post(android.R.string.copy, actionLabelRes = android.R.string.cancel) { }
        assertEquals(LkNotices.Message.Res(android.R.string.copy), LkNotices.current.value?.message)
        assertEquals(LkNotices.Message.Res(android.R.string.cancel), LkNotices.current.value?.actionLabel)
    }

    @Test
    fun `a resource action label without a handler is dropped too`() {
        // Same contract as the String overload: an action label with nothing behind it is a dead
        // control, and routing through one implementation is what keeps the two from drifting.
        LkNotices.post(android.R.string.copy, actionLabelRes = android.R.string.cancel, onAction = null)
        assertNull(LkNotices.current.value?.actionLabel)
    }

    @Test
    fun `clear drops whatever is showing`() {
        // Called on lock: a confirmation naming what was just trashed has no business surviving
        // onto the unlock screen.
        LkNotices.post("Moved to trash", actionLabel = "Undo") { }
        LkNotices.clear()
        assertNull(LkNotices.current.value)
    }
}
