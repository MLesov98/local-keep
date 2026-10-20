package com.localkeep.autofill

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The master-password gate on the fill path has a show/hide eye, like the unlock screen's.
 *
 * Without one a typo made on a phone keyboard, in front of another app's form, could not be
 * checked; the only recourse was a throttled attempt. The prompt stays on platform views by
 * design - these activities are dependency-light - so the eye is an `ImageButton` and the labels
 * are the ones `:core:ui` already ships for the same control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterPasswordPromptRevealTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val show = context.getString(com.localkeep.core.ui.R.string.core_ui_field_password_show)
    private val hide = context.getString(com.localkeep.core.ui.R.string.core_ui_field_password_hide)

    private fun prompt() = MasterPasswordPrompt(context, title = "Unlock", onSubmit = {})

    private fun <T : View> View.find(type: Class<T>, match: (T) -> Boolean): T? {
        if (type.isInstance(this) && match(type.cast(this)!!)) return type.cast(this)
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).find(type, match)?.let { return it }
        return null
    }

    private fun MasterPasswordPrompt.eye(): ImageButton? =
        view.find(ImageButton::class.java) { it.contentDescription == show || it.contentDescription == hide }

    private fun MasterPasswordPrompt.field(): EditText = view.find(EditText::class.java) { true }!!

    private fun EditText.variation() = inputType and InputType.TYPE_MASK_VARIATION

    @Test
    fun `the password is hidden and the eye offers to show it`() {
        val prompt = prompt()

        val eye = assertNotNull(prompt.eye(), "the prompt has no show/hide control")
        assertEquals(show, eye.contentDescription)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, prompt.field().variation())
    }

    @Test
    fun `tapping the eye reveals the typed text and tapping again hides it`() {
        val prompt = prompt()
        val field = prompt.field()
        field.setText("hunter2")
        field.setSelection(3)
        val eye = assertNotNull(prompt.eye())

        eye.performClick()
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, field.variation())
        assertEquals(hide, eye.contentDescription)
        assertEquals("hunter2", field.text.toString(), "revealing must not disturb what was typed")
        assertEquals(3, field.selectionStart, "the cursor should stay where the user left it")

        eye.performClick()
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, field.variation())
        assertEquals(show, eye.contentDescription)
        assertEquals("hunter2", field.text.toString())
    }

    @Test
    fun `the eye is a full-size touch target`() {
        val eye = assertNotNull(prompt().eye())
        val minPx = (48 * context.resources.displayMetrics.density).toInt()
        assertTrue(eye.minimumWidth >= minPx && eye.minimumHeight >= minPx, "the eye is smaller than 48dp")
    }

    /** Control: the hardening the field already had must survive adding a button beside it. */
    @Test
    fun `the field is still excluded from autofill and the personalised keyboard`() {
        val field = prompt().field()
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, field.importantForAutofill)
        assertTrue(field.imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }
}
