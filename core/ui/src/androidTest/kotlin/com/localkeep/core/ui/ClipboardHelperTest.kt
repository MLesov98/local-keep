package com.localkeep.core.ui

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-shot record that clears a clip whose 30-second timer died with its process.
 *
 * This is the whole of the stale-clip safety net, and it had two holes, both reproduced on a device
 * by the F05 QA persona on 2026-08-30 with a plaintext password left readable on the system
 * clipboard afterwards. The first was ordering: `rememberPending` used `apply()` and ran after the
 * clip was set, so a process death within about a second of the copy — swiping the app out of
 * recents, the ordinary case — lost the marker outright. The second is what these tests cover: the
 * record was forgotten whenever the ownership read merely returned without throwing, and a denied
 * read arrives as a `null` description at least as often as it is thrown. The sweep cleared nothing
 * and then discarded the only record that would have let a later sweep try again.
 *
 * The distinction that fixes it is that "I cannot identify the clip" is a different answer from
 * "someone else owns it", and only one of them means the job is done. Each case below is one of the
 * four the function now separates; the last two are the ones that were previously conflated.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardHelperTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun prefs() = context.getSharedPreferences("clipboard_pending", 0)

    private fun givenPending(clipId: String) {
        prefs().edit().putString("pending_clip_id", clipId).commit()
    }

    private fun pending(): String? = prefs().getString("pending_clip_id", null)

    @AfterTest
    fun tearDown() {
        prefs().edit().clear().commit()
        // Process-wide state, and the tests below write it deliberately. `false` is the value it is
        // seeded with, and the conservative one — see ClipboardHelper.windowFocused.
        ClipboardHelper.windowFocused = false
    }

    /** A clipboard that refuses inspection tells us nothing, so the record has to survive. */
    @Test
    fun aDeniedInspectionKeepsThePendingClip() {
        givenPending(CLIP)
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { true },
            currentClipId = { throw SecurityException("clipboard access denied in background") },
            clearClipboard = {},
        )
        assertEquals(CLIP, pending())
    }

    /**
     * The hole. A readable clipboard whose description carries no id is exactly what a silently
     * denied read looks like, and it used to end with the record discarded and the clip still
     * sitting there.
     */
    @Test
    fun anUnidentifiableClipKeepsThePendingClip() {
        givenPending(CLIP)
        var cleared = false
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { true },
            currentClipId = { null },
            clearClipboard = { cleared = true },
        )
        assertEquals(CLIP, pending(), "an unreadable clip is unknown ownership, not someone else's")
        assertFalse(cleared, "nothing may be cleared on a clipboard we cannot identify")
    }

    /** Our own clip: clear it, then the record has done its job. */
    @Test
    fun ourOwnClipIsClearedAndForgotten() {
        givenPending(CLIP)
        var cleared = false
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { true },
            currentClipId = { CLIP },
            clearClipboard = { cleared = true },
        )
        assertTrue(cleared)
        assertNull(pending())
    }

    /**
     * A clear that is itself denied leaves the secret where it was, so the record must stay even
     * though ownership was confirmed. This is the case a naive "clear then forget" gets wrong.
     */
    @Test
    fun aDeniedClearKeepsThePendingClip() {
        givenPending(CLIP)
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { true },
            currentClipId = { CLIP },
            clearClipboard = { throw SecurityException("clear denied in background") },
        )
        assertEquals(CLIP, pending())
    }

    /** Another app owns the clipboard now. Not ours to clear, and nothing left to retry. */
    @Test
    fun anotherAppsClipIsLeftAloneAndForgotten() {
        givenPending(CLIP)
        var cleared = false
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { true },
            currentClipId = { "some-other-apps-clip" },
            clearClipboard = { cleared = true },
        )
        assertFalse(cleared, "clearing here would wipe another app's clipboard content")
        assertNull(pending())
    }

    /** An empty clipboard means our clip is already gone; keeping the record would be for ever. */
    @Test
    fun anEmptyClipboardForgetsThePendingClip() {
        givenPending(CLIP)
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { false },
            currentClipId = { null },
            clearClipboard = {},
            // Explicit since X8 (2026-09-13). "Empty" only means the job is done when we were
            // allowed to look; backgrounded, the platform serves a false `hasPrimaryClip()` that
            // means "denied". This case was always the readable one — it just never had to say so,
            // and under instrumentation there is no resumed activity, so the default is false.
            clipboardReadable = true,
        )
        assertNull(pending())
    }

    private companion object {
        const val CLIP = "timer-regression-clip"
    }

    /**
     * The second hole, and the one that actually mattered: `hasPrimaryClip()`.
     *
     * From API 29 the platform serves a **background** app a plain `false` rather than throwing —
     * it logs "Denying clipboard access to com.localkeep.foss, application is not in focus" and
     * returns. The `!clipboardHeld` branch read that as "our clip is already gone" and dropped the
     * recovery record, so the next foreground sweep had nothing to retry and a copied password
     * stayed on the system clipboard indefinitely. Measured on a device 2026-09-13 (X8): copy,
     * press HOME, wait past the 30 s timer, come back — the paste still yielded the password, and
     * `clipboard_pending.xml` had gone to `<map />` while the clip was still live.
     *
     * The same ambiguity the class KDoc resolves for `currentClipId()`; this accessor never got it.
     */
    @Test
    fun anEmptyClipboardWeWereNotAllowedToReadKeepsThePendingClip() {
        givenPending(CLIP)
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { false },
            currentClipId = { null },
            clearClipboard = {},
            clipboardReadable = false,
        )
        assertEquals(
            CLIP,
            pending(),
            "a denied hasPrimaryClip() is not proof the clip is gone — the record must survive " +
                "for the next foreground sweep, which is the earliest the platform allows a clear",
        )
    }

    /**
     * The third hole, and the one that made the second fix miss the path that matters: what the
     * *default* `clipboardReadable` is computed from. Found 2026-09-13.
     *
     * The two tests above both pass `clipboardReadable` explicitly, so the default itself had zero
     * coverage — and the default was `ActivityManager.getMyMemoryState`, i.e. process importance.
     * The platform gates the clipboard on *window focus* ("application is not in focus"), which is
     * strictly narrower: a process is already at IMPORTANCE_FOREGROUND during `Activity.onStart`,
     * before any window has focus, and stays there under a system dialog or the notification shade.
     * Measured on emulator-5554 (API 35) on 2026-09-13: returning to the app logged two `Denying
     * clipboard access` lines at `onStart`, the recovery record went to `<map />` within 1.5 s, and
     * `KEYCODE_PASTE` still produced the copied password.
     *
     * This test calls `completePendingClear` with NO `clipboardReadable` argument, so it is the
     * default that is under test and not the caller.
     */
    @Test
    fun anEmptyClipboardReadWhileTheWindowIsUnfocusedKeepsThePendingClip() {
        givenPending(CLIP)
        ClipboardHelper.windowFocused = false
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { false },
            currentClipId = { null },
            clearClipboard = {},
        )
        assertEquals(
            CLIP,
            pending(),
            "with no window focused the platform denies the read and serves a false " +
                "hasPrimaryClip(); the default must not read that as \"the clip is gone\"",
        )
    }

    /**
     * The control for the test above. Without it, a "fix" that simply never forgets anything —
     * hardcoding the default to `false`, say — would pass, and the one-shot record would then be
     * immortal: kept for ever on a clipboard the user has long since overwritten.
     */
    @Test
    fun anEmptyClipboardReadWhileTheWindowIsFocusedForgetsThePendingClip() {
        givenPending(CLIP)
        ClipboardHelper.windowFocused = true
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { false },
            currentClipId = { null },
            clearClipboard = {},
        )
        assertNull(
            pending(),
            "a focused window's read is trustworthy, so an empty clipboard means the job is done",
        )
    }

    /**
     * The other half of the fix: the signal has to actually be written, and by every activity.
     *
     * `SecureActivity.onWindowFocusChanged` is the single writer, and `SecureActivityUnderTest` is a
     * bare subclass of it — so this asserts on a real window gaining real input focus rather than on
     * the flag being settable. Polled rather than read once: focus arrives a frame or more after
     * RESUMED, which is the entire point of the finding.
     */
    @Test
    fun aFocusedSecureActivityPublishesTheFocusSignal() {
        ClipboardHelper.windowFocused = false
        ActivityScenario.launch(SecureActivityUnderTest::class.java).use {
            val deadline = System.currentTimeMillis() + 5_000
            while (!ClipboardHelper.windowFocused && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(
                ClipboardHelper.windowFocused,
                "a SecureActivity that has taken window focus must say so, or the stale-clip " +
                    "sweep can never learn that the platform will serve it a clipboard read",
            )
        }
    }
}
