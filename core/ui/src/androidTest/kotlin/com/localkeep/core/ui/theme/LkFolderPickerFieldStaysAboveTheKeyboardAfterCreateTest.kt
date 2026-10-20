package com.localkeep.core.ui.theme

import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localkeep.core.ui.R
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * After a create the keyboard is down — and when the user taps the field for the next folder, the
 * keyboard comes back with the field above it.
 *
 * Device pass, API 35, 2026-10-12, New folder from the add sheet: after the first folder landed the
 * keyboard was still up and the name field was gone. The new row had pushed the card down by one row
 * while its own size did not change, so nothing asked for it to be revealed, and with the keyboard
 * shrinking the list's viewport the well ended up behind the pinned Create button.
 *
 * 2026-10-15: a create now puts the keyboard away (the principal's "small reset"), which removes
 * that state — the reveal that fixed it is gone with it. What is left to pin is the other half of
 * the same interaction: the field is on screen with the keyboard down, and a tap that raises the
 * keyboard again leaves it wholly above the keyboard, with the new row in the list.
 *
 * Real keyboard, not a dispatched inset: the defect lives in the interaction of the list's scroll
 * with the keyboard's inset, and only a real IME produces both.
 */
@RunWith(AndroidJUnit4::class)
class LkFolderPickerFieldStaysAboveTheKeyboardAfterCreateTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val create = context.getString(R.string.core_ui_folder_create_ready_a11y)

    private val rows = mutableStateOf(List(12) { LkFolderRow(id = it, name = "Folder $it", depth = 0) })
    private val name = mutableStateOf("")
    private val arrived = mutableStateOf<Int?>(null)

    @Test
    fun theKeyboardGoesAfterACreateAndComesBackUnderTheFieldOnTap() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            @Suppress("DEPRECATION")
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        compose.setContent {
            LocalKeepTheme {
                LkFolderPickerScreen(
                    rows = rows.value,
                    selectedId = null,
                    onPick = {},
                    onBack = {},
                    newFolderName = name.value,
                    onNewFolderNameChange = { name.value = it },
                    onCreateConfirm = {
                        val id = rows.value.size
                        rows.value = rows.value + LkFolderRow(id = id, name = name.value, depth = 0)
                        name.value = ""
                        arrived.value = id
                    },
                    arrivedId = arrived.value,
                )
            }
        }
        compose.waitForIdle()

        // The card takes focus on arrival, so the keyboard is already coming up.
        awaitKeyboardSettled()
        compose.onNode(hasSetTextAction()).performTextInput("Alpha")
        // The control: with the keyboard up and a name typed, the field is wholly on screen.
        assertFieldWhollyAboveTheKeyboard("before the create")

        compose.onNodeWithText(create).performClick()
        compose.waitForIdle()
        awaitKeyboardGone()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performClick()
        awaitKeyboardSettled()

        assertFieldWhollyAboveTheKeyboard("after tapping the field for the next folder")
    }

    private fun assertFieldWhollyAboveTheKeyboard(moment: String) {
        val field = compose.onNode(hasSetTextAction())
        val visible = field.getBoundsInRoot()
        assertTrue(visible.bottom > visible.top, "$moment: the name field is entirely outside the list's viewport")
        assertEquals(field.getUnclippedBoundsInRoot(), visible, "$moment: the name field is cut off by the list's viewport")
        assertTrue(
            visible.bottom.value <= keyboardTopDp() + 0.5f,
            "$moment: the name field ends at ${visible.bottom} with the keyboard's top edge at ${keyboardTopDp()}dp",
        )
    }

    private fun imeBottomPx(): Int {
        var bottom = 0
        compose.runOnUiThread {
            bottom = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return bottom
    }

    private fun awaitKeyboardGone() {
        compose.waitUntil(TIMEOUT_MS) { imeBottomPx() == 0 }
    }

    /** Up, and finished moving: the same height twice, a step apart. */
    private fun awaitKeyboardSettled() {
        compose.waitUntil(TIMEOUT_MS) { imeBottomPx() > 0 }
        var last = -1
        compose.waitUntil(TIMEOUT_MS) {
            val now = imeBottomPx()
            val settled = now == last
            last = now
            if (!settled) SystemClock.sleep(SETTLE_STEP_MS)
            settled
        }
        compose.waitForIdle()
    }

    private fun keyboardTopDp(): Float {
        var heightPx = 0
        compose.runOnUiThread { heightPx = compose.activity.window.decorView.height }
        return (heightPx - imeBottomPx()) / context.resources.displayMetrics.density
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_STEP_MS = 250L
    }
}
