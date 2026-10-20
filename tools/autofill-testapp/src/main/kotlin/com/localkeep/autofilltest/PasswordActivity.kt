package com.localkeep.autofilltest

import android.app.Activity
import android.os.Bundle
import android.view.autofill.AutofillManager

class PasswordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val password = passwordField()
        val content = column("Step 2").apply {
            isFocusableInTouchMode = true
            addView(password)
            addView(action("Submit") {
                getSystemService(AutofillManager::class.java).commit()
                finishAffinity()
            })
        }
        setContentView(content)
        content.requestFocus()
    }
}
