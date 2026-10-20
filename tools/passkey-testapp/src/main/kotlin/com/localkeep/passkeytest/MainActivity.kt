package com.localkeep.passkeytest

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A relying party for LocalKeep's CredentialProviderService, and nothing else.
 *
 * Nothing is stored and nothing is verified cryptographically: this app exists so a human or an
 * agent can drive the provider's real ceremonies on a device and read what came back. Signature
 * verification would need a server-side public key registry, which is a second system to get wrong.
 * The one property checked here is the one a broken provider actually breaks — that the response
 * answers the challenge that was sent.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var log: ResultLog
    private lateinit var credentialManager: CredentialManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = ResultLog(this)
        credentialManager = CredentialManager.create(this)

        val content = column("Passkey test").apply {
            addView(action("Create passkey") { createPasskey() })
            addView(action("Sign in with passkey") { signIn() })
            addView(action("Open web login") {
                startActivity(Intent(this@MainActivity, WebLoginActivity::class.java))
            })
            addView(action("Clear log") { log.clear() })
            addView(
                log.scroller,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f },
            )
        }
        setContentView(content)

        log.line("rpId: ${PasskeyClient.RP_ID}")
        log.line("API level: ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Said up front rather than left to a bare CreateCredentialException. Below 34 the
            // platform has no CredentialProviderService at all, so LocalKeep cannot be offered and
            // the failure has nothing to do with LocalKeep.
            log.line("WARN below API 34 there are no system credential providers; expect failures")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createPasskey() {
        val challenge = PasskeyClient.challenge()
        val request = CreatePublicKeyCredentialRequest(
            requestJson = PasskeyClient.createRequestJson(challenge, PasskeyClient.userId()),
        )
        log.line("")
        log.line("-- create: challenge $challenge")
        scope.launch {
            val response = runCatching {
                credentialManager.createCredential(this@MainActivity, request)
            }.getOrElse { cause ->
                return@launch log.line(failure("create", cause))
            }
            val registration = (response as? CreatePublicKeyCredentialResponse)
                ?.registrationResponseJson
                ?: return@launch log.line("FAIL create returned ${response.type}")
            PasskeyClient.verify(registration, "webauthn.create", challenge).forEach(log::line)
        }
    }

    private fun signIn() {
        val challenge = PasskeyClient.challenge()
        val request = GetCredentialRequest(
            listOf(GetPublicKeyCredentialOption(PasskeyClient.getRequestJson(challenge))),
        )
        log.line("")
        log.line("-- sign in: challenge $challenge")
        scope.launch {
            val response = runCatching {
                credentialManager.getCredential(this@MainActivity, request)
            }.getOrElse { cause ->
                return@launch log.line(failure("sign in", cause))
            }
            val assertion = (response.credential as? PublicKeyCredential)
                ?.authenticationResponseJson
                ?: return@launch log.line("FAIL sign in returned ${response.credential.type}")
            PasskeyClient.verify(assertion, "webauthn.get", challenge).forEach(log::line)
            PasskeyClient.assertionDetail(assertion).forEach(log::line)
        }
    }

    /**
     * The exception's own type is the useful half — the provider deliberately hands back fixed
     * strings across the process boundary (it will not leak a vault item's title into a message),
     * so "which exception" says more than "what did it say". Both are printed.
     */
    private fun failure(stage: String, cause: Throwable): String {
        val kind = when (cause) {
            is CreateCredentialException -> cause.type
            is GetCredentialException -> cause.type
            else -> cause.javaClass.name
        }
        return "FAIL $stage: $kind / ${cause.message ?: "no message"}"
    }
}
