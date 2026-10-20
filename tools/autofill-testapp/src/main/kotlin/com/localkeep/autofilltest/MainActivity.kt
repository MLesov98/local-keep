package com.localkeep.autofilltest

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            column("Autofill test").apply {
                addView(action("Single-step login") {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                })
                addView(action("Two-step login") {
                    startActivity(Intent(this@MainActivity, UsernameActivity::class.java))
                })
                addView(action("Web login") {
                    startActivity(Intent(this@MainActivity, WebLoginActivity::class.java))
                })
            },
        )
    }
}
