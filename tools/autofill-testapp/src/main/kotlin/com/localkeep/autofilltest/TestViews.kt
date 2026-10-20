package com.localkeep.autofilltest

import android.app.Activity
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

internal fun Activity.column(title: String): LinearLayout =
    LinearLayout(this).apply {
        fitsSystemWindows = true
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 48, 48)
        addView(TextView(this@column).apply {
            text = title
            textSize = 22f
        })
    }

internal fun Activity.usernameField(): EditText =
    EditText(this).apply {
        id = View.generateViewId()
        hint = "Username"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    }

internal fun Activity.passwordField(): EditText =
    EditText(this).apply {
        id = View.generateViewId()
        hint = "Password"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
    }

internal fun Activity.action(label: String, block: () -> Unit): Button =
    Button(this).apply {
        text = label
        setOnClickListener { block() }
    }
