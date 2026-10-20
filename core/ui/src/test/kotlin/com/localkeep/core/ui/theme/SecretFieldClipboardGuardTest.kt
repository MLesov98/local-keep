package com.localkeep.core.ui.theme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.contextmenu.data.ProcessTextKey
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuDropdownProvider
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.awaitCancellation
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A secret field must offer no way to put its value on the clipboard except the app's own Copy
 * button, which marks the clip sensitive and wipes it after 30s.
 *
 * The guard used to be a [androidx.compose.ui.platform.TextToolbar] swap alone, and the source-text
 * tests in `:feature:itemedit` (`NoteBodyIsClipboardGuardedTest`, `PhraseWordFieldIsClipboardGuardedTest`)
 * confirm only that the swap is *wrapped around* a field. They stayed green while every guarded field
 * leaked on device: Compose foundation 1.11 builds its selection menu from text-context-menu
 * components by default (`ComposeFoundationFlags.isNewContextMenuEnabled`) and never calls
 * `TextToolbar.showMenu`, and a hardware Ctrl+C goes through the text field's own key handling,
 * which consults no toolbar at all. Both ended in a plain platform copy — unmarked, and never cleared.
 *
 * So this drives the real component. The menu half installs a recording
 * [TextContextMenuProvider] in place of the platform toolbar and dropdown, long-presses or
 * right-clicks the field, and reads back which items the field asked the menu to show. The keyboard
 * half selects the whole value, sends Ctrl+C and the other copy/cut chords, and reads the platform
 * clipboard. Each has a control on a plain [LkTextField] that must still copy, so a change that
 * stops the menu or the key handling from working at all cannot read as a pass.
 *
 * Shift+Delete is blocked by the guard too, but has no test here: on Android Compose maps it to
 * nothing, so a plain field does not cut on it either and a test would pass with or without the
 * guard. The two shadows ([ModifierAwareKeyCharacterMap], [InertMagnifier]) each correct one place
 * where Robolectric differs from a device; their KDoc says which.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ModifierAwareKeyCharacterMap::class, InertMagnifier::class])
class SecretFieldClipboardGuardTest {

    @get:Rule
    val compose = createComposeRule()

    private val clipboard: ClipboardManager
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)

    /**
     * Holds the open menu's data source, and reads it when asked rather than when the menu opens.
     * The components are live: a long-press opens the toolbar before the word under the finger is
     * selected, so a snapshot taken at open time shows a menu with nothing selected — no Copy on
     * any field, guarded or not — and the guarded assertions would pass for that reason alone.
     */
    private class RecordingMenu : TextContextMenuProvider {
        private var open: TextContextMenuDataProvider? = null
        val shown: List<Any>? get() = open?.data()?.components?.map { it.key }

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            open = dataProvider
            try {
                // A real menu stays open until dismissed; returning would be read as "closed".
                awaitCancellation()
            } finally {
                open = null
            }
        }
    }

    private enum class Subject { Plain, Guarded, RevealedPassword }

    @Before
    fun emptyClipboard() {
        clipboard.clearPrimaryClip()
    }

    private fun show(subject: Subject, menu: RecordingMenu = RecordingMenu()): RecordingMenu {
        compose.setContent {
            CompositionLocalProvider(
                LocalTextContextMenuToolbarProvider provides menu,
                LocalTextContextMenuDropdownProvider provides menu,
            ) {
                LocalKeepTheme {
                    Column { Field(subject) }
                }
            }
        }
        compose.waitForIdle()
        return menu
    }

    @Composable
    private fun Field(subject: Subject) {
        var value by remember { mutableStateOf(SECRET) }
        when (subject) {
            Subject.Plain -> LkTextField(value = value, onValueChange = { value = it }, label = "Plain")
            Subject.Guarded -> GuardedSecretField {
                LkTextField(value = value, onValueChange = { value = it }, label = "Hidden")
            }
            Subject.RevealedPassword -> LkPasswordField(
                value = value,
                onValueChange = { value = it },
                revealed = true,
            )
        }
    }

    private fun menuItemsAfterLongPress(subject: Subject): List<Any> {
        val menu = show(subject)
        // On the first word, not the centre: the field is full width and the value is not, so the
        // centre is past the end of the text, where a long-press only places the cursor and the
        // menu that opens has nothing selected to copy.
        compose.onNode(hasSetTextAction()).performTouchInput { longClick(Offset(width * 0.05f, centerY)) }
        runCatching { compose.waitUntil(5_000) { menu.shown != null } }
        compose.waitForIdle()
        return assertNotNull(menu.shown, "$subject: the long-press never opened the selection menu")
    }

    private fun menuItemsAfterRightClick(subject: Subject): List<Any> {
        val menu = show(subject)
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInputSelection(TextRange(0, 7))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        field.performMouseInput { rightClick(center) }
        // The menu is opened from a coroutine, so it can land a frame or more after the click;
        // the first test in a fresh sandbox reliably needs the wait.
        runCatching { compose.waitUntil(5_000) { menu.shown != null } }
        compose.waitForIdle()
        return assertNotNull(menu.shown, "$subject: the right-click never opened the context menu")
    }

    private fun clipAfterChord(subject: Subject, chord: () -> Any): CharSequence? {
        show(subject)
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInputSelection(TextRange(0, SECRET.length))
        compose.waitForIdle()
        chord()
        compose.waitForIdle()
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
    }

    private fun ctrl(key: Key) = {
        compose.onNode(hasSetTextAction()).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
    }

    // ---- The selection menu ----------------------------------------------------------------
    //
    // Two entry points, because they are two menus: a long-press raises the floating toolbar a
    // phone user sees, and a right-click with a mouse or trackpad raises the dropdown. Both read
    // the same component list, and both leaked.

    @Test
    fun `the control - a plain field's long-press menu still offers Copy`() {
        val items = menuItemsAfterLongPress(Subject.Plain)
        assertTrue(TextContextMenuKeys.CopyKey in items, "plain field menu lost Copy: ${names(items)}")
    }

    @Test
    fun `the control - a plain field's right-click menu still offers Copy`() {
        val items = menuItemsAfterRightClick(Subject.Plain)
        assertTrue(TextContextMenuKeys.CopyKey in items, "plain field menu lost Copy: ${names(items)}")
    }

    @Test
    fun `a guarded secret field's long-press menu offers neither Copy nor Cut`() {
        assertGuardedMenu("guarded field long-press", menuItemsAfterLongPress(Subject.Guarded))
    }

    @Test
    fun `a guarded secret field's right-click menu offers neither Copy nor Cut`() {
        assertGuardedMenu("guarded field right-click", menuItemsAfterRightClick(Subject.Guarded))
    }

    @Test
    fun `a revealed password well's long-press menu offers neither Copy nor Cut`() {
        assertGuardedMenu("revealed password long-press", menuItemsAfterLongPress(Subject.RevealedPassword))
    }

    @Test
    fun `a revealed password well's right-click menu offers neither Copy nor Cut`() {
        assertGuardedMenu("revealed password right-click", menuItemsAfterRightClick(Subject.RevealedPassword))
    }

    private fun assertGuardedMenu(what: String, items: List<Any>) {
        assertTrue(TextContextMenuKeys.CopyKey !in items, "$what offers Copy: ${names(items)}")
        assertTrue(TextContextMenuKeys.CutKey !in items, "$what offers Cut: ${names(items)}")
        // Paste and Select all are what typing a secret in actually needs; the guard must not
        // take the whole menu with it. (Paste is offered only when the clipboard holds text, and
        // it is emptied before each test, so Select all is the item that must survive here.)
        assertTrue(TextContextMenuKeys.SelectAllKey in items, "$what lost Select all: ${names(items)}")
    }

    // ---- Process-text items -----------------------------------------------------------------
    //
    // Every installed app that answers ACTION_PROCESS_TEXT gets a row in the selection menu —
    // Google Translate, "Read aloud", any dictionary — and tapping it starts that app with the
    // selected text in EXTRA_PROCESS_TEXT. On a revealed secret that hands the plaintext to another
    // app, no clipboard involved. Robolectric has no such app, so one is registered here; it is
    // queried every time the menu is built, so registering it before the field is shown is enough.

    private fun installProcessTextApp(label: String = "Translate") {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val info = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.translate"
                name = "com.example.translate.ProcessTextActivity"
                exported = true
                applicationInfo = ApplicationInfo().apply { packageName = "com.example.translate" }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, info)
    }

    private fun processTextItems(items: List<Any>) = items.filterIsInstance<ProcessTextKey>()

    @Test
    fun `the control - a plain field's long-press menu still offers a process-text app`() {
        installProcessTextApp()
        val items = menuItemsAfterLongPress(Subject.Plain)
        assertTrue(processTextItems(items).isNotEmpty(), "plain field menu offers no process-text item: ${names(items)}")
    }

    @Test
    fun `a guarded secret field's long-press menu offers no process-text app`() {
        installProcessTextApp()
        val items = menuItemsAfterLongPress(Subject.Guarded)
        assertTrue(processTextItems(items).isEmpty(), "guarded field offers a process-text item: ${names(items)}")
        assertTrue(TextContextMenuKeys.SelectAllKey in items, "guarded field lost Select all: ${names(items)}")
    }

    @Test
    fun `a revealed password well's long-press menu offers no process-text app`() {
        installProcessTextApp()
        val items = menuItemsAfterLongPress(Subject.RevealedPassword)
        assertTrue(processTextItems(items).isEmpty(), "revealed password offers a process-text item: ${names(items)}")
        assertTrue(TextContextMenuKeys.SelectAllKey in items, "revealed password lost Select all: ${names(items)}")
    }

    @Test
    fun `a revealed password well's right-click menu offers no process-text app`() {
        installProcessTextApp()
        val items = menuItemsAfterRightClick(Subject.RevealedPassword)
        assertTrue(processTextItems(items).isEmpty(), "revealed password offers a process-text item: ${names(items)}")
    }

    private fun names(items: List<Any>) = items.map {
        when (it) {
            TextContextMenuKeys.CopyKey -> "Copy"
            TextContextMenuKeys.CutKey -> "Cut"
            TextContextMenuKeys.PasteKey -> "Paste"
            TextContextMenuKeys.SelectAllKey -> "SelectAll"
            TextContextMenuKeys.AutofillKey -> "Autofill"
            is ProcessTextKey -> "ProcessText#${it.id}"
            else -> it.toString()
        }
    }

    // ---- Hardware keyboard -----------------------------------------------------------------

    @Test
    fun `the control - Ctrl+C on a plain field copies`() {
        assertEquals(SECRET, clipAfterChord(Subject.Plain, ctrl(Key.C))?.toString())
    }

    @Test
    fun `Ctrl+C on a guarded secret field leaves the clipboard empty`() {
        assertNull(clipAfterChord(Subject.Guarded, ctrl(Key.C)), "Ctrl+C copied a guarded secret")
    }

    @Test
    fun `Ctrl+X on a guarded secret field leaves the clipboard empty`() {
        assertNull(clipAfterChord(Subject.Guarded, ctrl(Key.X)), "Ctrl+X cut a guarded secret")
    }

    @Test
    fun `Ctrl+Insert on a guarded secret field leaves the clipboard empty`() {
        assertNull(clipAfterChord(Subject.Guarded, ctrl(Key.Insert)), "Ctrl+Insert copied a guarded secret")
    }

    @Test
    fun `the dedicated Copy key on a guarded secret field leaves the clipboard empty`() {
        val press = { compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Copy) } }
        assertNull(clipAfterChord(Subject.Guarded, press), "the Copy key copied a guarded secret")
    }

    @Test
    fun `the dedicated Cut key on a guarded secret field leaves the clipboard empty`() {
        val press = { compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Cut) } }
        assertNull(clipAfterChord(Subject.Guarded, press), "the Cut key cut a guarded secret")
    }

    /** The other control: the key handler must swallow copy and cut, not every chord. */
    @Test
    fun `Ctrl+V still pastes into a guarded secret field`() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "pasted"))
        clipAfterChord(Subject.Guarded, ctrl(Key.V))
        compose.onNode(hasSetTextAction()).assert(hasText("pasted", substring = false))
    }

    @Test
    fun `Ctrl+C on a revealed password well leaves the clipboard empty`() {
        assertNull(clipAfterChord(Subject.RevealedPassword, ctrl(Key.C)), "Ctrl+C copied a revealed password")
    }

    private companion object {
        const val SECRET = "correct horse battery"
    }
}
