package com.localkeep.autofill

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The master-password gate shared by every autofill activity that has to open the vault.
 *
 * Built in code rather than XML because these activities are deliberately dependency-light, and
 * kept in one place so the hardening below (no autofill of our own prompt, no personalised-learning
 * keyboard, password characters copied out and the field cleared immediately) can't drift apart
 * between call sites.
 */
internal class MasterPasswordPrompt(
    private val context: Context,
    title: String,
    subtitle: String? = null,
    private val onSubmit: (CharArray) -> Unit,
) {
    private val submitState = PromptSubmitState()

    private val errorView = TextView(context).apply {
        setTextColor(ERROR_COLOR)
        // Announced, not just drawn. This prompt is raised over someone else's app, which is where
        // a screen-reader user has the least context to work out why nothing happened — and a plain
        // TextView whose text changes fires no accessibility event at all, so a wrong password, a
        // throttled attempt and an unreadable vault were all silence. The colour carries the same
        // meaning visually and nothing carried it otherwise.
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private val submitButton = Button(context).apply {
        text = context.getString(R.string.autofill_password_prompt_continue)
        // Left entirely unstyled until 2026-09-11, which meant it inherited the platform's own
        // button — a light grey slab, the only light-filled control in a dark-only app, and the
        // loudest thing on the one screen where the master password is typed over someone else's
        // app. The surrounding views had all been given colours; this one was missed.
        setBackgroundColor(JADE_COLOR)
        setTextColor(ON_JADE_COLOR)
    }

    private val passwordField = EditText(context).apply {
        hint = context.getString(R.string.autofill_password_prompt_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        // Never offer to autofill our own unlock screen from the vault it guards.
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        // The one password field in this app that is not a Compose `LkTextField`, and until now the
        // one that took the platform's own colours — a light well with dark text, in front of a
        // vault whose every other surface is near-black. The module does depend on `:core:ui` (for
        // `Theme.LocalKeep.Base`, which these activities now launch on), but this class stays free of
        // Compose types, so the tokens it needs are literal hex kept in sync by hand below.
        setBackgroundColor(FIELD_COLOR)
        setTextColor(TEXT_STRONG_COLOR)
        setHintTextColor(TEXT_FAINT_COLOR)
    }

    private var revealed = false

    /**
     * The show/hide eye, beside the well rather than inside it. Platform views, so an `ImageButton`
     * over a vector drawable rather than `LkPasswordField`; the labels are `:core:ui`'s own, so this
     * control announces itself in the same words as the unlock screen's.
     */
    private val revealButton = ImageButton(context).apply {
        val size = (TOUCH_TARGET_DP * context.resources.displayMetrics.density).toInt()
        minimumWidth = size
        minimumHeight = size
        setBackgroundColor(Color.TRANSPARENT)
        imageTintList = ColorStateList.valueOf(TEXT_FAINT_COLOR)
        setOnClickListener { setRevealed(!revealed) }
    }

    private val passwordRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(passwordField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            revealButton,
            LinearLayout.LayoutParams(
                (TOUCH_TARGET_DP * context.resources.displayMetrics.density).toInt(),
                (TOUCH_TARGET_DP * context.resources.displayMetrics.density).toInt(),
            ),
        )
    }

    /**
     * Switches the well between dots and text, keeping what was typed and where the cursor was.
     * Setting `inputType` rebuilds the field's key listener, which resets the selection.
     */
    private fun setRevealed(show: Boolean) {
        revealed = show
        val start = passwordField.selectionStart
        val end = passwordField.selectionEnd
        passwordField.inputType = InputType.TYPE_CLASS_TEXT or if (show) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        if (start >= 0 && end >= 0) passwordField.setSelection(start, end)
        revealButton.setImageResource(if (show) R.drawable.autofill_ic_eye_off else R.drawable.autofill_ic_eye)
        revealButton.contentDescription = context.getString(
            if (show) com.localkeep.core.ui.R.string.core_ui_field_password_hide else com.localkeep.core.ui.R.string.core_ui_field_password_show,
        )
    }

    /**
     * The content, with the screen margin on it.
     *
     * Separate from [view] because the root carries `fitsSystemWindows`, and that flag makes the
     * framework *overwrite* a view's padding with the system-bar insets on every pass. The margin
     * used to be set on the root, so it was silently discarded: the title sat flush against the left
     * edge and the field ran the full width of the screen, on the one screen in the app where the
     * master password is typed. Setting it on a child is what makes it survive.
     *
     * dp rather than the raw pixels that literal was, too — `setPadding` takes pixels, so the old
     * `48` meant ~18dp at 420dpi and ~12dp at 640dpi, the only margin in the app that moved with
     * screen density.
     */
    private val content: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (PADDING_DP * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(TEXT_STRONG_COLOR)
        })
        subtitle?.let {
            addView(TextView(context).apply {
                text = it
                textSize = 13f
                setTextColor(TEXT_FAINT_COLOR)
            })
        }
        addView(passwordRow)
        addView(errorView)
        addView(submitButton)
    }

    val view: LinearLayout = LinearLayout(context).apply {
        setBackgroundColor(GROUND_COLOR)
        fitsSystemWindows = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        orientation = LinearLayout.VERTICAL
        addView(content)
    }

    init {
        setRevealed(false)
        submitButton.setOnClickListener { submit() }
        // The field advertises IME_ACTION_DONE but had no listener, so the key only dismissed the
        // keyboard — the one action a password field's Done key is for went nowhere, and the user
        // had to reach back for the button the IME had been covering.
        passwordField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && submitButton.isEnabled) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        // An empty field is not a guess, so it must not be scored as one. Without this the Done key
        // and the Continue button both sent an empty CharArray into VaultLockManager.unlock, which
        // scanned it, recorded a KNOWLEDGE failure against the brute-force throttle, and answered
        // with the wrong-password message — costing the user a real attempt for a stray tap and
        // telling them their password was wrong when they had entered none. The unlock screen
        // already gates this way (`state.password.isNotEmpty()`); this is the same rule for the
        // three autofill entry points that share this prompt.
        if (passwordField.text.isEmpty()) return
        // The Argon2id derivation behind onSubmit runs multiple seconds; without this the button
        // just went dark with no explanation, unlike the main unlock screen's LkDerivingOverlay.
        submitState.beginSubmit()
        submitButton.isEnabled = false
        submitButton.text = context.getString(submitState.labelRes)
        errorView.text = ""
        // Copy into a CharArray we can wipe; Editable's own buffer is out of our control.
        val password = CharArray(passwordField.text.length)
        passwordField.text.getChars(0, password.size, password, 0)
        passwordField.text.clear()
        // Back to dots with the text gone: a retry after a wrong password starts hidden.
        setRevealed(false)
        onSubmit(password)
    }

    /** Re-enables the prompt after a failed attempt and explains why. */
    fun showError(message: String) {
        submitState.reset()
        errorView.text = message
        submitButton.isEnabled = true
        submitButton.text = context.getString(submitState.labelRes)
    }

    private companion object {
        const val PADDING_DP = 24f
        const val TOUCH_TARGET_DP = 48f

        // Kept in sync by hand with core/ui/src/main/kotlin/com/localkeep/core/ui/theme/LkColors.kt,
        // which is the source of truth. This class cannot import Compose types (see the class KDoc),
        // so the tokens it needs are literals. If LkColors moves, these move. The list is exhaustive
        // on purpose: `ERROR_COLOR` used to sit outside it holding Material's default error red
        // (0xffb3261e) rather than the app's coral, so a wrong password here was a different red from
        // every other failure in the app and nothing said the value needed syncing.
        const val GROUND_COLOR = 0xff0a0c0e.toInt() // LkColors.ground
        const val FIELD_COLOR = 0xff101416.toInt() // LkColors.field
        const val TEXT_STRONG_COLOR = 0xfff4f7f8.toInt() // LkColors.textStrong
        const val TEXT_FAINT_COLOR = 0xff7a858b.toInt() // LkColors.textFaint
        const val JADE_COLOR = 0xff3ed9ae.toInt() // LkColors.jade
        const val ON_JADE_COLOR = 0xff04211a.toInt() // LkColors.onJade
        const val ERROR_COLOR = 0xfff2705b.toInt() // LkColors.coral
    }
}
