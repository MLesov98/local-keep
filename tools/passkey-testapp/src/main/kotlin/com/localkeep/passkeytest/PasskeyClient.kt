package com.localkeep.passkeytest

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The relying-party side of one WebAuthn ceremony, in the shape LocalKeep's provider parses.
 *
 * [RP_ID] is `example.test`, chosen against `credential/.../RpIdValidator.kt`'s `registrableRpId`
 * rather than picked for looks: that check requires an internal dot and a rightmost label that is
 * not all-numeric, so a bare `localhost` is rejected outright and `127.0.0.1` is only accepted by
 * an explicit special case that exists for the loopback *origin* arm. `.test` is the RFC 2606
 * reserved TLD, so the name can never resolve to a real site. The same string is the WebView's
 * origin host in [WebLoginActivity], which keeps one domain across both halves of this app.
 */
internal object PasskeyClient {
    const val RP_ID = "example.test"
    const val RP_NAME = "LocalKeep Passkey Test"
    const val USER_NAME = "passkey@example.test"
    const val USER_DISPLAY_NAME = "Passkey Test"

    /** A fresh challenge per ceremony — a reused one proves nothing about the signature. */
    fun challenge(): String = base64Url(randomBytes(32))

    fun userId(): String = base64Url(randomBytes(16))

    /**
     * `pubKeyCredParams` offers ES256 only. `PasskeyCreator.parseRequestJson` requires alg -7 to be
     * present and rejects the request otherwise, so offering anything else would only make a
     * failure here ambiguous between "the provider refused" and "we asked for the wrong algorithm".
     */
    fun createRequestJson(challenge: String, userId: String): String =
        JSONObject()
            .put("challenge", challenge)
            .put("rp", JSONObject().put("id", RP_ID).put("name", RP_NAME))
            .put(
                "user",
                JSONObject()
                    .put("id", userId)
                    .put("name", USER_NAME)
                    .put("displayName", USER_DISPLAY_NAME),
            )
            .put(
                "pubKeyCredParams",
                JSONArray().put(JSONObject().put("type", "public-key").put("alg", -7)),
            )
            .put(
                "authenticatorSelection",
                JSONObject()
                    .put("residentKey", "required")
                    .put("userVerification", "required"),
            )
            .put("timeout", 120_000)
            .toString()

    /**
     * No `allowCredentials`: a discoverable-credential request, which is what makes the provider
     * list whatever it holds for [RP_ID] instead of being told which credential to use.
     */
    fun getRequestJson(challenge: String): String =
        JSONObject()
            .put("challenge", challenge)
            .put("rpId", RP_ID)
            .put("userVerification", "required")
            .put("timeout", 120_000)
            .toString()

    /**
     * What the ceremony is actually for: the response must prove it answered *this* challenge.
     *
     * The provider echoes the challenge verbatim into clientDataJSON for a native caller
     * (`PasskeyCreator.responseClientData`), so a string comparison is the whole check. [type] is
     * checked alongside it because a `webauthn.create` client data replayed into a sign-in would
     * otherwise pass on the challenge alone.
     */
    fun verify(responseJson: String, type: String, expectedChallenge: String): List<String> {
        val response = runCatching { JSONObject(responseJson) }.getOrElse {
            return listOf("FAIL response is not JSON: ${it.javaClass.simpleName}")
        }
        val inner = response.optJSONObject("response")
            ?: return listOf("FAIL response has no \"response\" object")
        val clientDataB64 = inner.optString("clientDataJSON").takeIf { it.isNotBlank() }
            ?: return listOf("FAIL response carries no clientDataJSON")
        val clientData = runCatching {
            JSONObject(String(base64UrlDecode(clientDataB64), Charsets.UTF_8))
        }.getOrElse {
            return listOf("FAIL clientDataJSON did not decode: ${it.javaClass.simpleName}")
        }
        val actualChallenge = clientData.optString("challenge")
        val actualType = clientData.optString("type")
        return listOf(
            "credential id : ${response.optString("id")}",
            "clientData type: $actualType",
            "origin        : ${clientData.optString("origin")}",
            "androidPackage: ${clientData.optString("androidPackageName")}",
            if (actualType == type) {
                "PASS type is $type"
            } else {
                "FAIL type was \"$actualType\", expected \"$type\""
            },
            if (actualChallenge == expectedChallenge) {
                "PASS challenge echoed: $expectedChallenge"
            } else {
                "FAIL challenge was \"$actualChallenge\", sent \"$expectedChallenge\""
            },
        )
    }

    /** Fields worth reading on screen once the ceremony itself has been judged. */
    fun assertionDetail(responseJson: String): List<String> {
        val inner = runCatching { JSONObject(responseJson).getJSONObject("response") }
            .getOrElse { return emptyList() }
        return listOf(
            "authenticatorData: ${inner.optString("authenticatorData").take(48)}…",
            "signature        : ${inner.optString("signature").take(48)}…",
            "userHandle       : ${inner.optString("userHandle")}",
        )
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun base64UrlDecode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
