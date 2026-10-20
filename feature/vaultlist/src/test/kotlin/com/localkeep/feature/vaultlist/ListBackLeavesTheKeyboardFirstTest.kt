package com.localkeep.feature.vaultlist

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Back on the vault list puts the keyboard away before it undoes anything.
 *
 * Device pass on the release build, API 36, 2026-10-05 (lane D3, finding 2): type "newuser" into the
 * search, press Back once with the keyboard up — the query was cleared and the list jumped back to
 * every item while the keyboard stayed up; only a second Back hid it. Reproduced here on
 * emulator-5562 with `dumpsys input_method` (`mInputShown=true`, field empty after one Back).
 *
 * The keyboard did not lose a race it should have won by design; it lost one on registration order.
 * With `enableOnBackInvokedCallback`, the window keeps one stack of back callbacks and the newest
 * registration at a priority wins. The IME registers its callback when it opens. The list's handler
 * is enabled only once there is something to undo — so typing the first letter of a search enabled
 * it *after* the IME had registered, and put the app's callback on top. Measured the other way round
 * on the same device: with the query already typed and the keyboard closed, tapping the field again
 * and pressing Back hid the keyboard and kept the query, because then the IME registered last.
 *
 * So the handler cannot rely on being underneath the IME. It checks for itself, and when the
 * keyboard is up the press only hides it — which is exactly what the IME's own Back does, focus and
 * query left where they were. The ordered stages in [resolveListBack] start at the second press.
 *
 * Robolectric raises no keyboard, so the IME inset is dispatched to the `AndroidComposeView` by
 * hand, the way `PasswordStepImeScrollTest` (onboarding) does it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListBackLeavesTheKeyboardFirstTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class CountingKeyboard : SoftwareKeyboardController {
        var hides = 0
        override fun show() = Unit
        override fun hide() {
            hides++
        }
    }

    private val keyboard = CountingKeyboard()
    private var undone = 0
    private var shown = false
    private var action by mutableStateOf<ListBackAction?>(null)

    /** Composes the handler once per test, then swaps the stage it is handed. */
    private fun show(stage: ListBackAction?) {
        action = stage
        if (!shown) {
            shown = true
            compose.setContent {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    ListBackHandler(action = action, onBack = { undone++ })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun raiseKeyboard(heightPx: Int = 800) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread {
            fun deliver(v: android.view.View) {
                if (v.javaClass.name.contains("AndroidComposeView")) ViewCompat.dispatchApplyWindowInsets(v, insets)
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) deliver(v.getChildAt(i))
            }
            deliver(compose.activity.window.decorView)
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `with the keyboard up, Back hides it and keeps the search`() {
        show(ListBackAction.ClearSearch)
        raiseKeyboard()

        pressBack()

        assertEquals(0, undone, "the first Back cleared the search while the keyboard was still up")
        assertEquals(1, keyboard.hides, "the first Back must put the keyboard away")
    }

    /** The same for every stage: the keyboard goes first whatever Back would undo next. */
    @Test
    fun `with the keyboard up, no stage is undone`() {
        ListBackAction.entries.forEach { stage ->
            undone = 0
            keyboard.hides = 0
            show(stage)
            raiseKeyboard()

            pressBack()

            assertEquals(0, undone, "$stage was undone under an open keyboard")
            assertEquals(1, keyboard.hides, "$stage: the keyboard stayed up")
        }
    }

    /** The control: with the keyboard down, Back undoes the stage exactly as before. */
    @Test
    fun `with the keyboard down, Back undoes the next stage`() {
        show(ListBackAction.ClearSearch)

        pressBack()

        assertEquals(1, undone)
        assertEquals(0, keyboard.hides)
    }

    /** Nothing to undo: the key is not taken, keyboard or not — it belongs to the IME and the system. */
    @Test
    fun `with nothing to undo the handler stays out of the way`() {
        show(stage = null)
        raiseKeyboard()

        assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }
}
