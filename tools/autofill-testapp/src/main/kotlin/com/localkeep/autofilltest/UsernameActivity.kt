package com.localkeep.autofilltest

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class UsernameActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val username = usernameField()
        val content = column("Step 1").apply {
            isFocusableInTouchMode = true
            addView(username)
            addView(action("Next") {
                startActivity(Intent(this@UsernameActivity, PasswordActivity::class.java))
            })
        }
        setContentView(content)
        content.requestFocus()
    }
}
