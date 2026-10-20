package com.localkeep.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localkeep.app.navigation.AppShortcuts
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * A launcher shortcut tapped while the app is open reaches the running activity instead of
 * replacing it.
 *
 * Device pass 2026-10-04, release build: with an item editor open and a title typed, the "Search
 * the vault" shortcut landed on the list and the editor and its text were gone, with no prompt and
 * no notice. The platform starts a static shortcut with NEW_TASK | CLEAR_TASK (`dumpsys shortcut`
 * showed 0x1000c000), and shortcuts.xml named MainActivity, so the clear destroyed it.
 *
 * The intent is built from what shortcuts.xml actually declares, with the flags the platform adds,
 * so this follows the XML rather than restating it.
 */
@RunWith(AndroidJUnit4::class)
class ShortcutKeepsTheRunningActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @AfterTest
    fun clearPending() = AppShortcuts.clear()

    @Test
    fun theSearchShortcutDoesNotDestroyTheOpenActivity() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var running: Activity? = null
        scenario.onActivity { running = it }
        val original = assertNotNull(running)

        val (targetPackage, targetClass) = declaredTargetOf("search")
        // The installed package, written out: a resource reference here was stored unresolved
        // and the shortcut could never start at all.
        assertEquals(context.packageName, targetPackage)
        context.startActivity(
            Intent(AppShortcuts.ACTION_SEARCH)
                .setClassName(targetPackage, targetClass)
                .addFlags(PLATFORM_SHORTCUT_FLAGS),
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(SETTLE_MS)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        // FAILS WITHOUT THE FIX: CLEAR_TASK on MainActivity destroys this instance.
        assertFalse(original.isDestroyed, "the shortcut destroyed the running MainActivity")
        // Control: the shortcut still arrived. A trampoline that dropped it would also pass the line above.
        assertEquals(AppShortcuts.Target.SEARCH, AppShortcuts.pending.value)
        // Not scenario.close(): it waits for DESTROYED from the lifecycle it launched, and after
        // the task switch this harness leaves it reporting PAUSED, although on a device the same
        // activity is in front (checked by hand on emulator-5554, 2026-10-04).
        scenario.onActivity { it.finish() }
    }

    /** The `targetPackage` and `targetClass` shortcuts.xml gives the shortcut with [id], as written. */
    private fun declaredTargetOf(id: String): Pair<String, String> {
        val parser = context.resources.getXml(R.xml.shortcuts)
        var inShortcut = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "shortcut" -> inShortcut = parser.getAttributeValue(ANDROID_NS, "shortcutId") == id
                "intent" -> if (inShortcut) {
                    return parser.getAttributeValue(ANDROID_NS, "targetPackage") to
                        parser.getAttributeValue(ANDROID_NS, "targetClass")
                }
            }
        }
        error("shortcuts.xml declares no shortcut \"$id\"")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val PLATFORM_SHORTCUT_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME
        const val SETTLE_MS = 2_000L
    }
}
