package com.localkeep.core.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What `completePendingClear`'s default `clipboardReadable` is computed from — on the JVM, so this
 * runs on every push rather than only when a device is attached.
 *
 * Added 2026-09-13. The instrumented suite covers the same gate against a
 * real window; this covers the wiring, which is where the defect lived. Until 2026-09-13 the default
 * was `ActivityManager.getMyMemoryState` — process importance — and the platform gates the clipboard
 * on *window focus* instead ("Denying clipboard access to com.localkeep.foss, application is not in
 * focus"). Importance is the wider state of the two: a process is already at IMPORTANCE_FOREGROUND
 * throughout `Activity.onStart`, which is exactly when the stale-clip sweep ran, and before any
 * window can hold focus. Measured on emulator-5554 (API 35) that day: foregrounding the app logged
 * two `Denying clipboard access` lines at `onStart`, the recovery record went to `<map />` within
 * 1.5 s, and a paste still yielded the copied password.
 *
 * Both tests below deliberately omit the `clipboardReadable` argument. Passing it — which is what
 * the two X8 tests in the instrumented suite do — is precisely what left the default uncovered.
 *
 * The clipboard itself is never touched here: `completePendingClear` takes its three reads and its
 * one write as lambdas, so the four-case decision can be driven directly. Only the persisted
 * recovery record is real, and it is a `SharedPreferences` entry holding a random id, never a
 * secret.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardReadableDefaultTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun prefs() = context.getSharedPreferences("clipboard_pending", 0)

    private fun givenPending(clipId: String) {
        prefs().edit().putString("pending_clip_id", clipId).commit()
    }

    private fun pending(): String? = prefs().getString("pending_clip_id", null)

    @AfterTest
    fun tearDown() {
        prefs().edit().clear().commit()
        ClipboardHelper.windowFocused = false
    }

    /**
     * The regression. With no window focused the platform denies the read and serves a plain
     * `false` from `hasPrimaryClip()` — indistinguishable from an empty clipboard through
     * `ClipboardManager` alone. Reading that as "our clip is gone" deletes the one-shot recovery
     * record while the plaintext password is still on the system clipboard, and nothing retries.
     */
    @Test
    fun `an unfocused window keeps the pending clip`() {
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
            "a denied read is not proof the clip is gone; the record must survive for the sweep " +
                "that runs on the next window-focus gain",
        )
    }

    /**
     * The control. Without it a default hardcoded to `false` would pass the test above and make the
     * record immortal — kept for ever on a clipboard the user has long since overwritten.
     */
    @Test
    fun `a focused window forgets the pending clip on an empty clipboard`() {
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
     * The gate is on the *record*, never on whose clipboard it is. Failing closed on focus must not
     * become licence to clear something that is not ours, so this pins the ownership check that has
     * always been there: an id that is not ours is left alone whatever the focus flag says.
     */
    @Test
    fun `an unfocused window still never clears another app's clip`() {
        givenPending(CLIP)
        ClipboardHelper.windowFocused = false
        var cleared = false
        ClipboardHelper.completePendingClear(
            context = context,
            clipId = CLIP,
            hasClip = { true },
            currentClipId = { "some-other-apps-clip" },
            clearClipboard = { cleared = true },
        )
        assertEquals(false, cleared, "clearing here would wipe another app's clipboard content")
    }

    private companion object {
        const val CLIP = "focus-gate-regression-clip"
    }
}
