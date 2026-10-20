package com.localkeep.passkeytest

import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * The bundled login page, served to the WebView on an `https://example.test/` origin.
 *
 * The point is the origin, not the bytes: LocalKeep's autofill service reads the WebView's web
 * domain out of the AssistStructure, and `FillResponseFactory` sends any (package, domain) pair it
 * has not seen before through `ConfirmDomainActivity` — the one-time browser-domain trust gate.
 * A `file://` or `http://10.0.2.2` page does not exercise that path the way a real browser tab does.
 *
 * Every request is answered from `assets/`, so the origin is a claim about naming and not about
 * reachability — `.test` is reserved by RFC 2606 and resolves nowhere. This is what
 * `WebViewAssetLoader` does internally; it is hand-rolled here because `androidx.webkit` is not in
 * this repo's version catalog and adding it is an edit outside this work package's ownership.
 * Doing it by hand also frees the host name, which the loader otherwise pins to
 * `appassets.androidplatform.net` — so the WebView origin and the passkey rpId can be one domain.
 */
class WebLoginActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            WebView(this).apply {
                fitsSystemWindows = true
                importantForAutofill = WebView.IMPORTANT_FOR_AUTOFILL_YES
                settings.javaScriptEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url
                        if (!url.host.equals(PasskeyClient.RP_ID, ignoreCase = true)) return null
                        return when (url.path) {
                            null, "", "/", "/login.html" -> asset()
                            // Anything else fails closed rather than reaching the network, which is
                            // what keeps the origin honest even though INTERNET is declared.
                            else -> notFound()
                        }
                    }
                }
                loadUrl("https://${PasskeyClient.RP_ID}/login.html")
            },
        )
    }

    private fun asset(): WebResourceResponse =
        WebResourceResponse("text/html", "utf-8", assets.open("login.html"))

    private fun notFound(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
}
