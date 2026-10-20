package com.localkeep.core.ui

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.PersistableBundle
import android.os.SystemClock
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sensitive clipboard writes with ownership-safe automatic clearing. */
object ClipboardHelper {
    /**
     * UI/UX spec §2/§5.3. Previously 45s, which both contradicted the spec and — since nothing was
     * shown on screen — left the user with no way to know a clear was coming at all.
     */
    const val CLEAR_DELAY_MS = 30_000L

    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    private const val EXTRA_CLIP_ID = "com.localkeep.extra.CLIP_ID"
    internal const val EXTRA_ALARM_CLIP_ID = "com.localkeep.extra.ALARM_CLIP_ID"

    private const val PENDING_PREFS = "clipboard_pending"
    private const val KEY_PENDING_CLIP_ID = "pending_clip_id"

    /** What was copied and when it will be wiped, so the UI can show the pending clear. */
    data class ActiveClip(
        val label: String,
        val expiresAtUptimeMs: Long,
        internal val clipId: String,
    ) {
        /** Uses uptime, like the clearing timer itself, so a clock change can't skew the count. */
        fun remainingMs(): Long = (expiresAtUptimeMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
    }

    private val _activeClip = MutableStateFlow<ActiveClip?>(null)

    /** Non-null while a sensitive copy of ours is still expected to be on the clipboard. */
    val activeClip: StateFlow<ActiveClip?> = _activeClip.asStateFlow()

    /**
     * Whether one of this app's windows currently holds **input focus**, written by
     * [SecureActivity.onWindowFocusChanged] and read as the default for
     * [completePendingClear]'s `clipboardReadable`.
     *
     * **Focus, not process importance, is the signal — and the difference is the whole of X8.**
     * From API 29 `ClipboardManager` refuses a read to anything that is not the focused app; the
     * platform's own denial reads "Denying clipboard access to com.localkeep.foss, application is
     * not in focus" and it is keyed on `isUidFocused`, i.e. window/input focus. A RESUMED activity
     * is a strictly wider state than a focused window: an activity is RESUMED through
     * `onStart`/`onResume` before the window has focus at all, and stays RESUMED underneath a
     * system permission dialog, the notification shade or any other overlay that takes focus.
     *
     * This was `ActivityManager.getMyMemoryState` until 2026-09-13, whose KDoc claimed it "tells
     * them apart". It does not — it separates backgrounded from foregrounded, which coincides with
     * denied from allowed only while focus happens to track importance. Measured on emulator-5554
     * (API 35) that day: copy a password, press HOME,
     * wait 42 s — `clipboard_pending.xml` still held `pending_clip_id` (correct; two `Denying
     * clipboard access` lines when the 30 s timer fired). Then foreground the app: **within 1.5 s
     * the record was `<map />`, logcat showed two fresh `Denying clipboard access` lines at the
     * instant of `onStart`, and `KEYCODE_PASTE` still yielded the password.** Process importance
     * was already FOREGROUND at `onStart`, so the old default said "readable", the denied
     * `hasPrimaryClip()` returned false, and the sweep meant to clear the clip deleted its own
     * recovery record instead — with the plaintext still on the clipboard and no later sweep
     * possible, because `ProcessLifecycleOwner.onStart` fires once per foreground session.
     *
     * Seeded `false`, which is the fail-closed direction: a wrongly-`false` flag only keeps a
     * pending record alive for one more sweep, while a wrongly-`true` flag destroys it while the
     * secret is still exposed. The same asymmetry makes the multi-activity race benign — if an
     * outgoing activity reports `false` after an incoming one reported `true`, the flag lands on
     * the conservative value.
     */
    @Volatile
    internal var windowFocused: Boolean = false

    /**
     * Clears our copy immediately instead of waiting out the timer. No-op if the clipboard has
     * since been taken over by another app — we only ever clear what we put there.
     */
    fun clearNow(context: Context) {
        val appContext = context.applicationContext ?: context
        val clipboard =
            appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = _activeClip.value ?: return
        if (clipboard.primaryClipDescription?.extras?.getString(EXTRA_CLIP_ID) == current.clipId) {
            clipboard.clearPrimaryClip()
        }
        forgetPending(appContext, current.clipId)
        cancelClearAlarm(appContext)
        _activeClip.value = null
    }

    fun copy(
        context: Context,
        label: String,
        text: String,
    ) {
        val appContext = context.applicationContext ?: context
        val clipboard =
            appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipId = UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
                putString(EXTRA_CLIP_ID, clipId)
            }
        }
        // Recorded before the secret is put on the clipboard, and synchronously. Both halves were
        // wrong: `apply()` schedules the write, so a process death within about a second of the
        // copy — swiping the app out of recents, which is the ordinary case this whole mechanism
        // exists for — lost the marker outright and left the clip with no recovery record at all.
        // Reproduced twice on a device by the F05 QA persona, 2026-08-30: `clipboard_pending.xml`
        // was still `<map />` after a copy followed by `am force-stop`.
        //
        // `commit()` does synchronous disk I/O on the main thread, which is normally the thing to
        // avoid. It is one short string, and the alternative is a window in which a plaintext
        // password is on the system clipboard with nothing anywhere that knows to clear it.
        rememberPending(appContext, clipId)
        clipboard.setPrimaryClip(clip)
        _activeClip.value = ActiveClip(
            label = label,
            expiresAtUptimeMs = SystemClock.uptimeMillis() + CLEAR_DELAY_MS,
            clipId = clipId,
        )
        Handler(appContext.mainLooper).postDelayed({ runClearTimer(appContext, clipId) }, CLEAR_DELAY_MS)
        scheduleClearAlarm(appContext, clipId)
    }

    /** The clear itself, shared by the in-process timer and [ClipboardClearReceiver]. */
    internal fun runClearTimer(appContext: Context, clipId: String) {
        val clipboard =
            appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        onClearTimer(
            context = appContext,
            clipId = clipId,
            hasClip = clipboard::hasPrimaryClip,
            currentClipId = {
                clipboard.primaryClipDescription?.extras?.getString(EXTRA_CLIP_ID)
            },
            clearClipboard = clipboard::clearPrimaryClip,
        )
    }

    /**
     * A system-delivered backstop for the `Handler` above, which cannot fire while the process is
     * frozen.
     *
     * Android's cached-app freezer suspends a backgrounded app's process outright: no thread runs,
     * so a main-looper callback posted for 30 s simply waits. Measured on API 35, logcat
     * `ActivityManager: freezing <pid> com.localkeep.foss` about 16 s after HOME, and the clear ran
     * only when LocalKeep was reopened, at +86 s — the password sat on the clipboard the whole time.
     * An alarm is delivered by `system_server`, which thaws the process to run the receiver.
     *
     * The `Handler` stays the primary path: it is exact, it keeps the on-screen countdown honest,
     * and it is what fires in the ordinary case. Whichever of the two runs first retires the clip,
     * and the other then finds nothing of its own to clear — [onClearTimer] cancels this alarm, and
     * a superseded clip id is a no-op either way.
     *
     * **Inexact on purpose.** An exact alarm needs `SCHEDULE_EXACT_ALARM` or `USE_EXACT_ALARM`,
     * and the `foss` manifest's permission set is pinned by `checkFlavorPermissions`; widening it
     * for a backstop is the wrong trade. `setAndAllowWhileIdle` needs no permission, still fires in
     * Doze, and may be batched to land somewhat after the 30 s mark — which only matters on the path
     * where the alternative was "whenever the app is next opened".
     *
     * One `PendingIntent` identity (request code 0, no action, no data) is reused for every copy, so
     * a new copy's alarm replaces the previous one instead of stacking. Only the random clip id is
     * attached; the copied text never leaves this process.
     */
    private fun scheduleClearAlarm(appContext: Context, clipId: String) {
        val alarmManager = appContext.getSystemService(AlarmManager::class.java) ?: return
        val operation = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, ClipboardClearReceiver::class.java)
                .putExtra(EXTRA_ALARM_CLIP_ID, clipId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + CLEAR_DELAY_MS,
            operation,
        )
    }

    private fun cancelClearAlarm(appContext: Context) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(appContext, ClipboardClearReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        appContext.getSystemService(AlarmManager::class.java)?.cancel(operation)
        operation.cancel()
    }

    /**
     * The 30-second timer firing for [clipId].
     *
     * 2026-10-02 (S5-01): when it fires with no LocalKeep window focused — the ordinary case, since
     * the user copied in order to paste somewhere else — the platform serves the ownership read a
     * plain `false`, and this used to keep the record and clear nothing, leaving the secret on the
     * clipboard until LocalKeep was next brought forward. Only *reads* are focus-gated: AOSP's
     * `ClipboardService.clipboardAccessAllowed` answers `OP_WRITE_CLIPBOARD` with "Writing is
     * allowed without focus", and `clearPrimaryClip` is a write. So the timer clears anyway.
     *
     * That clear cannot check ownership, which is the trade made here, and it is bounded twice: it
     * happens only from this timer, within its own 30 s of the copy, and only while no later
     * LocalKeep copy or "Clear now" has superseded [clipId]. What it can wipe is something the user
     * copied in another app inside that window; what it prevents is a password left on the
     * clipboard indefinitely. The next-foreground sweep, whose record may be hours old, stays
     * ownership-gated — see [clearStaleClip].
     */
    internal fun onClearTimer(
        context: Context,
        clipId: String,
        hasClip: () -> Boolean,
        currentClipId: () -> String?,
        clearClipboard: () -> Unit,
    ) {
        completePendingClear(
            context = context,
            clipId = clipId,
            hasClip = hasClip,
            currentClipId = currentClipId,
            clearClipboard = clearClipboard,
            clearWhenUnreadable = _activeClip.value?.clipId == clipId,
        )
        // Only retire our own affordance. A later copy owns the indicator now, and its
        // timer will retire it in turn. The same test guards the alarm: a later copy's alarm is
        // the one scheduled now, and cancelling it from a superseded timer would strip its backstop.
        if (_activeClip.value?.clipId == clipId) {
            _activeClip.value = null
            cancelClearAlarm(context)
        }
    }

    /**
     * Clears a copy whose 30-second timer died with the process that started it.
     *
     * The timer above is a `Handler` on the main looper, so it exists only as long as the process
     * does — and the ordinary way it dies is the user swiping LocalKeep out of recents, seconds
     * after copying a password. The clip then stays on the clipboard indefinitely, which is the one
     * thing [CLEAR_DELAY_MS] exists to prevent.
     *
     * Only the clip *id* is persisted, never the copied text, and the clear is still gated on that
     * id still owning the clipboard — otherwise this would wipe whatever another app has since put
     * there. Deliberately a next-foreground sweep rather than a background job: from API 29 the
     * platform only lets the focused app *read* the clipboard, so a background job could not
     * confirm the clip is still ours, and a record that may be hours old is no grounds for wiping
     * whatever is there now. (Writes are not focus-gated, which is what [onClearTimer] relies on
     * inside its own 30 s; this sentence used to say the platform forbade both.)
     *
     * **Two call sites, deliberately, since 2026-09-13.** The one that does the work
     * is [SecureActivity.onWindowFocusChanged] with `hasFocus = true`: that is the earliest instant
     * the platform will actually serve this process a clipboard read, and every activity in the app
     * extends `SecureActivity`. The older `ProcessLifecycleOwner.onStart` call in
     * `LocalKeepApplication` is **kept** rather than moved, because it is now harmless and still
     * useful. Harmless: at `onStart` no window has focus yet, so [windowFocused] is `false` and a
     * denied `hasPrimaryClip()` takes the `!clipboardHeld -> return` branch, which keeps the record
     * for the focus sweep a few frames later. Useful: `minSdk` is 28 and the focus gate arrived in
     * API 29, so on API 28 that earlier call can read the clipboard and clear our own clip one
     * lifecycle step sooner. Calling this twice costs a `SharedPreferences` read on an already-warm
     * map — it returns at the first line when nothing is pending, and at the second when a live
     * in-process timer still owns the id.
     */
    fun clearStaleClip(context: Context) {
        val appContext = context.applicationContext ?: context
        val pending = appContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_CLIP_ID, null) ?: return
        // A live in-process timer still owns this one; leave it to run out its countdown so the
        // "clipboard clears in Ns" affordance stays honest.
        if (_activeClip.value?.clipId == pending) return
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        completePendingClear(
            context = appContext,
            clipId = pending,
            hasClip = clipboard::hasPrimaryClip,
            currentClipId = {
                clipboard.primaryClipDescription?.extras?.getString(EXTRA_CLIP_ID)
            },
            clearClipboard = clipboard::clearPrimaryClip,
        )
    }

    /**
     * Completes a pending clear only when clipboard ownership can be confirmed.
     *
     * Android can deny clipboard inspection and mutation while the app is in the background. In
     * that case the persisted id is still needed by the next foreground sweep.
     *
     * **A clipboard we cannot read is not a clipboard someone else owns.** This used to forget the
     * record whenever `currentClipId()` merely returned without throwing — and a denied read is
     * delivered as a `null` description at least as often as it is thrown, so the one-shot recovery
     * record was discarded on a sweep that had cleared nothing, and no later foreground event could
     * ever retry it. The F05 QA persona reproduced exactly that on a device, 2026-08-30: the
     * persisted id was removed while the plaintext password stayed on the clipboard.
     *
     * The four cases are now distinguished, because three of them mean different things:
     * an empty clipboard means our clip is already gone; our own id means clear it; someone
     * else's id means they own it now and it is not ours to touch; and no readable id at all
     * means we do not know, so the record has to survive for the next sweep.
     */
    internal fun completePendingClear(
        context: Context,
        clipId: String,
        hasClip: () -> Boolean,
        currentClipId: () -> String?,
        clearClipboard: () -> Unit,
        clipboardReadable: Boolean = windowFocused,
        /** True only from [onClearTimer]: clear even when the platform will not let us look. */
        clearWhenUnreadable: Boolean = false,
    ) {
        val clipboardHeld: Boolean
        val observedClipId: String?
        try {
            clipboardHeld = hasClip()
            observedClipId = currentClipId()
        } catch (_: SecurityException) {
            if (clearWhenUnreadable) clearUnverified(context, clipId, clearClipboard)
            return
        }
        when {
            // Nothing on the clipboard at all AND we were allowed to look: whatever we copied is
            // gone, and there is nothing left for a later sweep to do. "Allowed to look" means one
            // of our windows held input focus — see [windowFocused], and note that a RESUMED
            // activity is not enough.
            !clipboardHeld && clipboardReadable -> forgetPending(context, clipId)
            // We were not allowed to look. From API 29 the platform serves an unfocused app a
            // *false* `hasPrimaryClip()` rather than throwing — it logs "Denying clipboard access
            // to com.localkeep.foss, application is not in focus" and returns. Treating that as
            // "gone" discarded the recovery record while the plaintext password was still on the
            // clipboard, so the next foreground sweep had nothing to retry and the copy survived
            // indefinitely (X8, 2026-09-13). This is the same ambiguity the paragraphs above
            // resolved for `currentClipId()`; `hasPrimaryClip()` simply never got the same
            // treatment. Keep the record: `clearStaleClip` finishes the job on the next window-focus
            // gain — unless this is the timer's own 30 s, which clears now. See [onClearTimer].
            !clipboardHeld -> if (clearWhenUnreadable) clearUnverified(context, clipId, clearClipboard)
            observedClipId == clipId -> {
                try {
                    clearClipboard()
                } catch (_: SecurityException) {
                    // Denied. The clip is still ours and still exposed, so the record stays.
                    return
                }
                forgetPending(context, clipId)
            }
            // A readable id that is not ours: another app owns the clipboard now. Clearing it
            // would wipe their content, and our clip is no longer on it.
            observedClipId != null -> forgetPending(context, clipId)
            // A clip we can see but cannot identify. Keep the record and try again next time.
            else -> return
        }
    }

    /** A clear that could not confirm ownership first. The record goes only once the clear lands. */
    private fun clearUnverified(context: Context, clipId: String, clearClipboard: () -> Unit) {
        try {
            clearClipboard()
        } catch (_: SecurityException) {
            return
        }
        forgetPending(context, clipId)
    }

    // Lint's advice is right in general and wrong here, so it is refused in one place with the
    // reason attached rather than silenced globally: `apply()` is what let a process death within a
    // second of the copy lose the record entirely.
    @SuppressLint("ApplySharedPref")
    private fun rememberPending(appContext: Context, clipId: String) {
        appContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CLIP_ID, clipId)
            // `commit`, not `apply` — see the call site. The write has to be on disk before the
            // secret is on the clipboard, or a process death in between leaves no record of it.
            .commit()
    }

    /** Clears the record only if [clipId] still owns it — a later copy's record must survive. */
    private fun forgetPending(appContext: Context, clipId: String) {
        val prefs = appContext.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_PENDING_CLIP_ID, null) == clipId) {
            prefs.edit().remove(KEY_PENDING_CLIP_ID).apply()
        }
    }
}
