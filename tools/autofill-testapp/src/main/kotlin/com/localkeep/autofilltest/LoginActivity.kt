package com.localkeep.autofilltest

import android.app.Activity
import android.os.Bundle
import android.view.autofill.AutofillManager

class LoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val username = usernameField()
        val password = passwordField()
        val content = column("Single-step login").apply {
            isFocusableInTouchMode = true
            addView(username)
            addView(password)
            addView(action("Submit") {
                getSystemService(AutofillManager::class.java).commit()
                finish()
            })
        }
        setContentView(content)
        content.requestFocus()
    }
}
