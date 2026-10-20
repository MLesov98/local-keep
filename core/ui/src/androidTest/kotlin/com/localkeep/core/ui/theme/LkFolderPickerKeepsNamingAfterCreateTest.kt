package com.localkeep.core.ui.theme

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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
 * What a create does to the picker, in its two modes.
 *
 * **Vault-list mode** (no `onCreateStart`: the card is open from the start and a parent is chosen
 * before the name). 2026-10-12: "What if I want to create 5 or 10 folders or 20?" — a create does
 * not leave: the card stays open and empty, the new row is on the list, and the way out says Done.
 * 2026-10-15 added the keyboard half, which the principal had asked for on 2026-10-14 and got only
 * in the editors: the card takes focus and raises the keyboard on arrival, and Create (button or the
 * IME's Done) puts the keyboard away. The next folder is a tap on the field.
 *
 * **Editor mode** (`onCreateStart` given: the item and file editors). 2026-10-14: "Clicking create
 * folder should hide the textbox menu for texting and focus on create new folder name etc — it has
 * to be a small reset each time one adds a new folder." Tapping New folder raises the keyboard on
 * the name field; Create puts the keyboard away and the caller collapses the card, so the next
 * folder starts again from New folder.
 *
 * Driven on a device because focus and the scroll are what is being asserted, and neither exists
 * under Robolectric (see `LkFolderPickerChevronA11yTest`).
 */
@RunWith(AndroidJUnit4::class)
class LkFolderPickerKeepsNamingAfterCreateTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val done = context.getString(R.string.core_ui_folder_screen_done)
    private val cancel = context.getString(R.string.core_ui_folder_screen_cancel)
    private val create = context.getString(R.string.core_ui_folder_create_ready_a11y)
    private val newFolder = context.getString(R.string.core_ui_folder_new_button)

    private val rows = mutableStateOf(listOf(LkFolderRow(id = 0, name = "Existing", depth = 0)))
    private val name = mutableStateOf("")
    private val arrived = mutableStateOf<Int?>(null)
    private val leaves = mutableListOf<String>()

    private fun open(status: (String) -> LkFolderNameStatus = { LkFolderNameStatus.Free }) {
        compose.setContent {
            LocalKeepTheme {
                LkFolderPickerScreen(
                    rows = rows.value,
                    selectedId = null,
                    onPick = {},
                    onBack = { leaves += "back" },
                    onCreateCancel = { leaves += "cancel" },
                    newFolderName = name.value,
                    onNewFolderNameChange = { name.value = it },
                    newFolderStatus = status(name.value),
                    // What every caller does on success: the row lands, the name is spent, and the
                    // new id is handed back as the arrival.
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
    }

    private fun makeFolder(called: String) {
        compose.onNode(hasSetTextAction()).performTextInput(called)
        compose.onNodeWithText(create).performClick()
        compose.waitForIdle()
    }

    @Test
    fun afterACreateTheFieldIsEmptyTheKeyboardIsDownAndTheNewRowIsOnTheList() {
        open()
        awaitKeyboard(up = true)
        makeFolder("Alpha")

        awaitKeyboard(up = false)
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        assertEquals("", name.value)
    }

    @Test
    fun aSecondFolderNeedsNoReopeningJustATapOnTheField() {
        open()
        makeFolder("Alpha")
        awaitKeyboard(up = false)

        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()
        awaitKeyboard(up = true)
        makeFolder("Beta")

        awaitKeyboard(up = false)
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun openingTheCardFocusesTheFieldAndRaisesTheKeyboard() {
        open()

        compose.onNode(hasSetTextAction()).assertIsFocused()
        awaitKeyboard(up = true)
    }

    @Test
    fun imeDoneOnTheCardIsTheSameResetAsTheButton() {
        open()
        awaitKeyboard(up = true)
        compose.onNode(hasSetTextAction()).performTextInput("Alpha")

        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        awaitKeyboard(up = false)
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        assertEquals("", name.value)
    }

    /**
     * Control: a name a sibling already has is refused before the tap — Create is inert and Done
     * does nothing — so nothing is created, the text stays, and the field keeps its focus and its
     * keyboard so the name can be fixed. The reset must be a consequence of a create, not of a tap.
     */
    @Test
    fun aRefusedNameKeepsTheTextTheFocusAndTheKeyboard() {
        open(status = { if (it.trim() == "Existing") LkFolderNameStatus.TakenHere else LkFolderNameStatus.Free })
        awaitKeyboard(up = true)
        compose.onNode(hasSetTextAction()).performTextInput("Existing")

        compose.onNodeWithText(create).performClick()
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        assertEquals("Existing", name.value)
        assertEquals(1, rows.value.size, "a refused name must not create a row")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertTrue(imeBottomPx() > 0, "the keyboard should still be up")
    }

    @Test
    fun afterACreateTheWayOutSaysDoneAndLeavesInsteadOfCancelling() {
        open()
        makeFolder("Alpha")

        compose.onNodeWithText(done).performClick()
        assertEquals(listOf("back"), leaves, "Done leaves; it must not report a cancelled name")
    }

    @Test
    fun aNameBeingTypedAfterACreateIsStillCancelledNotLeft() {
        open()
        makeFolder("Alpha")
        compose.onNode(hasSetTextAction()).performTextInput("Be")

        compose.onNodeWithText(cancel).performClick()
        assertEquals(listOf("cancel"), leaves)
    }

    /** Control: before anything has been made the way out is still Cancel. */
    @Test
    fun beforeAnyCreateTheButtonSaysCancel() {
        open()

        compose.onNodeWithText(cancel).assertIsDisplayed()
    }

    // ---- Editor mode -------------------------------------------------------------------------

    /** The editors' wiring: the card is closed until New folder, and a create spends it. */
    private val editorName = mutableStateOf<String?>(null)

    private fun openEditor() {
        compose.setContent {
            LocalKeepTheme {
                LkFolderPickerScreen(
                    rows = rows.value,
                    selectedId = null,
                    onPick = {},
                    onBack = { leaves += "back" },
                    onCreateStart = { editorName.value = "" },
                    onCreateCancel = { editorName.value = null },
                    newFolderName = editorName.value,
                    onNewFolderNameChange = { editorName.value = it },
                    onCreateConfirm = {
                        val id = rows.value.size
                        rows.value = rows.value + LkFolderRow(id = id, name = editorName.value.orEmpty(), depth = 0)
                        editorName.value = null
                        arrived.value = id
                    },
                    arrivedId = arrived.value,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun openNameCardInEditor() {
        openEditor()
        compose.onNodeWithText(newFolder).performClick()
        compose.waitForIdle()
    }

    @Test
    fun editorNewFolderFocusesTheNameFieldAndRaisesTheKeyboard() {
        openNameCardInEditor()

        compose.onNode(hasSetTextAction()).assertIsFocused()
        awaitKeyboard(up = true)
    }

    @Test
    fun editorCreateButtonHidesTheKeyboardAndCollapsesTheCard() {
        openNameCardInEditor()
        awaitKeyboard(up = true)
        compose.onNode(hasSetTextAction()).performTextInput("Alpha")

        compose.onNodeWithText(create).performClick()
        compose.waitForIdle()

        awaitKeyboard(up = false)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText(newFolder).assertIsDisplayed()
        assertEquals(null, editorName.value)
    }

    /** Done on the keyboard and the pinned button are one path. */
    @Test
    fun editorImeDoneIsTheSameResetAsTheButton() {
        openNameCardInEditor()
        awaitKeyboard(up = true)
        compose.onNode(hasSetTextAction()).performTextInput("Alpha")

        compose.onNode(hasSetTextAction()).performImeAction()
        compose.waitForIdle()

        awaitKeyboard(up = false)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(newFolder).assertIsDisplayed()
    }

    /** A second folder starts from New folder again, and focuses again. */
    @Test
    fun editorSecondFolderIsAFreshOpenWithTheKeyboardBackUp() {
        openNameCardInEditor()
        compose.onNode(hasSetTextAction()).performTextInput("Alpha")
        compose.onNodeWithText(create).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(newFolder).performClick()
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).assertIsFocused()
        awaitKeyboard(up = true)
    }

    private fun imeBottomPx(): Int {
        var bottom = 0
        compose.runOnUiThread {
            bottom = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        }
        return bottom
    }

    /** Waits for the real keyboard to be up (or gone); a dispatched inset would prove nothing. */
    private fun awaitKeyboard(up: Boolean) {
        compose.waitUntil(KEYBOARD_TIMEOUT_MS) {
            val shown = imeBottomPx() > 0
            if (shown != up) SystemClock.sleep(KEYBOARD_STEP_MS)
            shown == up
        }
        assertTrue((imeBottomPx() > 0) == up, "keyboard should be ${if (up) "up" else "hidden"}")
    }

    private companion object {
        const val KEYBOARD_TIMEOUT_MS = 10_000L
        const val KEYBOARD_STEP_MS = 100L
    }
}
