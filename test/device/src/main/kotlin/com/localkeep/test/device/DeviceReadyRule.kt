package com.localkeep.test.device

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Fails a test before it runs when the device cannot host it: the screen is off, or the keyguard
 * is showing.
 *
 * Both are checked, and the keyguard is the one that matters. A locked device stops the test
 * activity within about 70 ms of launch (logcat: RESUMED → PAUSED at 12 ms → STOPPED at 70 ms)
 * *whether or not the screen is on*. A stopped activity's `ViewRootImpl` runs no traversal, and
 * Compose's `ComposeRootRegistry` registers only resumed roots, so `waitForIdle` forces no layout
 * and there is no semantics tree. Downstream every symptom looks like a defect in the code under
 * test: `assertExists` throws `No compose hierarchies found`, `onGloballyPositioned` never fires
 * and a container stays at `Rect.Zero`, and `ModalNavigationDrawer` never lays out its anchors so
 * `DrawerState.open()` takes Material3's no-anchor branch and "animates" in 0 ms — which reads
 * exactly like a drawer that stopped animating. Three tests in `:core:ui` were red for twelve
 * days on that reading and were nearly fixed as motion and layout regressions; the same emulator,
 * woken and unlocked with no code changed, passed all of them. An `isInteractive`-only guard would
 * have let that straight through, because the screen was on the whole time.
 *
 * Declare it `@get:Rule(order = DeviceReadyRule.OUTERMOST)`. The message is the same either way —
 * inner to `createComposeRule`, the activity launches, the keyguard stops it, and this still throws
 * before the test body can misread the result — but outermost it throws before the launch, and that
 * is the difference between "fast" and not: measured on the sleeping emulator, `FontScaleTest`'s
 * ten cases took 52 s to fail inside the compose rule and under a second outside it.
 */
class DeviceReadyRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            assertDeviceReady()
            base.evaluate()
        }
    }

    /** The environment is wrong, not the code — so an error, not an assertion failure. */
    class DeviceNotReadyException(message: String) : IllegalStateException(message)

    companion object {

        /**
         * JUnit applies rules with a *higher* `order` inside those with a lower one, and the default
         * is -1 — so an unordered rule is already outside anything at 0, and the only way to sit
         * outside every unordered rule is to go below -1.
         */
        const val OUTERMOST: Int = Int.MIN_VALUE

        /** The check on its own, for a `@BeforeClass` or a runner that wants it without a rule. */
        fun assertDeviceReady() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val asleep = !power.isInteractive
            val locked = keyguard.isKeyguardLocked
            if (!asleep && !locked) return
            throw DeviceNotReadyException(message(asleep = asleep, locked = locked))
        }

        private fun message(asleep: Boolean, locked: Boolean): String {
            val state = listOfNotNull(
                "the screen is off (PowerManager.isInteractive = false)".takeIf { asleep },
                "the keyguard is showing (KeyguardManager.isKeyguardLocked = true)".takeIf { locked },
            ).joinToString(" and ")
            return """
                |Device is not usable for instrumented tests: $state.
                |  (${Build.MODEL}, API ${Build.VERSION.SDK_INT})
                |
                |A sleeping or locked device stops the test activity ~70 ms after launch. A stopped
                |activity lays nothing out and Compose registers no resumed root, so every assertion
                |downstream reads as a layout or motion defect in the code under test. It is not one.
                |This rule fires instead so the failure names the device.
                |
                |Wake and unlock it, then rerun (export ANDROID_SERIAL first, e.g. emulator-5554):
                |  adb shell input keyevent KEYCODE_WAKEUP
                |  adb shell input keyevent KEYCODE_MENU        # past the lock screen to the PIN pad
                |  adb shell input text <PIN> && adb shell input keyevent KEYCODE_ENTER
                |    (this repo's test emulators use PIN 1234 — see .gitlab-ci.yml)
                |To stop an emulator dozing off again mid-run:
                |  adb shell settings put global stay_on_while_plugged_in 7
                """.trimMargin()
        }
    }
}
