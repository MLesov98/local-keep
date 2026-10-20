package com.localkeep.core.ui

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The AlarmManager backstop for the 30-second clipboard clear.
 *
 * On API 35 the cached-app freezer froze LocalKeep about 16 s after the user pressed HOME, and the
 * main-looper `Handler` that owns the clear ran only when the app was next opened — +86 s instead
 * of +30 s. An alarm is delivered by the system, which thaws the process to run the receiver.
 *
 * Robolectric's main looper is paused, so a `Handler` posted for 30 s does not run until the test
 * advances the clock: firing the alarm without advancing it is exactly the frozen-process case.
 */
@RunWith(RobolectricTestRunner::class)
class ClipboardClearAlarmTest {

    private val context = RuntimeEnvironment.getApplication()
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val alarms: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    private fun pending(): String? =
        context.getSharedPreferences("clipboard_pending", 0).getString("pending_clip_id", null)

    @AfterTest
    fun tearDown() {
        ClipboardHelper.clearNow(context)
        context.getSharedPreferences("clipboard_pending", 0).edit().clear().commit()
        ClipboardHelper.windowFocused = false
    }

    private fun theAlarm(): ShadowAlarmManager.ScheduledAlarm {
        val scheduled = alarms.scheduledAlarms
        assertEquals(1, scheduled.size, "copying a secret scheduled no clear alarm")
        return scheduled.single()
    }

    private fun deliver(intent: Intent) {
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `copying a secret schedules an inexact idle-safe alarm 30 s out`() {
        val before = SystemClock.elapsedRealtime()
        ClipboardHelper.copy(context, "Password", "not-a-real-secret")

        val alarm = theAlarm()
        assertTrue(
            alarm.type == AlarmManager.ELAPSED_REALTIME_WAKEUP ||
                alarm.type == AlarmManager.ELAPSED_REALTIME,
            "an RTC alarm would move with a wall-clock change",
        )
        assertEquals(before + ClipboardHelper.CLEAR_DELAY_MS, alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle, "a plain set() is deferred in Doze")
        val operation = assertNotNull(alarm.operation)
        // Through the shadow: `PendingIntent.isImmutable` is API 31, and this runs at minSdk.
        assertTrue(shadowOf(operation).isImmutable, "a mutable PendingIntent can be re-aimed")
        assertTrue(shadowOf(operation).isBroadcast)
    }

    @Test
    fun `the alarm targets a receiver nothing outside the app can invoke and carries no secret`() {
        ClipboardHelper.copy(context, "Password", "not-a-real-secret")
        val intent = shadowOf(theAlarm().operation).savedIntent

        val component = assertNotNull(intent.component, "an implicit broadcast can be intercepted")
        val info = context.packageManager.getReceiverInfo(component, 0)
        assertFalse(info.exported, "an exported receiver lets any app wipe the clipboard on demand")
        val extras = intent.extras
        extras?.keySet()?.forEach { key ->
            assertFalse(
                extras.get(key).toString().contains("not-a-real-secret"),
                "the copied text travelled in the alarm intent ($key)",
            )
        }
    }

    /** The finding: the Handler never runs because the process is frozen; the alarm clears. */
    @Test
    fun `the alarm clears our clip while the in-process timer is frozen`() {
        ClipboardHelper.copy(context, "Password", "not-a-real-secret")

        alarms.fireAlarm(theAlarm())
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(clipboard.hasPrimaryClip(), "the secret stayed on the clipboard")
        assertNull(pending())
        assertNull(ClipboardHelper.activeClip.value)
    }

    /** Control: what another app put there since is not ours to wipe. */
    @Test
    fun `the alarm does not clear a clip another app has put there since`() {
        ClipboardHelper.copy(context, "Password", "not-a-real-secret")
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "someone else's text"))

        alarms.fireAlarm(theAlarm())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(clipboard.hasPrimaryClip())
        assertEquals("someone else's text", clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    /** Control: a stale alarm for the first copy must not cut the second copy's countdown short. */
    @Test
    fun `a stale alarm for an earlier copy does not clear a newer copy`() {
        ClipboardHelper.copy(context, "Password", "first-secret")
        val stale = Intent(shadowOf(theAlarm().operation).savedIntent)
        ClipboardHelper.copy(context, "Password", "second-secret")

        deliver(stale)

        assertEquals("second-secret", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        assertNotNull(ClipboardHelper.activeClip.value)
    }

    @Test
    fun `a second copy replaces the first alarm rather than adding one`() {
        ClipboardHelper.copy(context, "Password", "first-secret")
        ClipboardHelper.copy(context, "Password", "second-secret")

        val alarm = theAlarm()
        alarms.fireAlarm(alarm)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(clipboard.hasPrimaryClip(), "the surviving alarm belongs to the second copy")
    }

    @Test
    fun `clear now cancels the alarm`() {
        ClipboardHelper.copy(context, "Password", "not-a-real-secret")
        theAlarm()

        ClipboardHelper.clearNow(context)

        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `the in-process timer clearing first cancels the alarm`() {
        ClipboardHelper.copy(context, "Password", "not-a-real-secret")
        theAlarm()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ClipboardHelper.CLEAR_DELAY_MS))

        assertFalse(clipboard.hasPrimaryClip())
        assertTrue(alarms.scheduledAlarms.isEmpty(), "a spent alarm would wake the device for nothing")
    }
}
