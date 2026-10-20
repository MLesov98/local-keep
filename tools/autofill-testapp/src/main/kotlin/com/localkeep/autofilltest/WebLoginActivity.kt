package com.localkeep.autofilltest

import android.app.Activity
import android.os.Bundle
import android.graphics.Rect
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.autofill.AutofillManager

class WebLoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            WebView(this).apply {
                fitsSystemWindows = true
                importantForAutofill = WebView.IMPORTANT_FOR_AUTOFILL_YES
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(
                            "document.getElementById('username').focus()",
                            null,
                        )
                        view.postDelayed(
                            {
                                getSystemService(AutofillManager::class.java).requestAutofill(
                                    view,
                                    65_536,
                                    Rect(220, 180, 720, 280),
                                )
                            },
                            500,
                        )
                    }
                }
                loadUrl("http://10.0.2.2:8765/")
            },
        )
    }
}
