package com.localkeep.credential.webauthn

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

/** Raw WebAuthn byte layouts and response JSON. */
internal object WebAuthnCodec {
    private const val FLAG_UP = 0x01
    private const val FLAG_UV = 0x04
    private const val FLAG_BE = 0x08
    private const val FLAG_BS = 0x10
    private const val FLAG_AT = 0x40

    fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun base64UrlDecode(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value)

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun clientDataJson(
        type: String,
        challenge: String,
        origin: String,
        androidPackageName: String?,
    ): ByteArray =
        JSONObject()
            .put("type", type)
            .put("challenge", challenge)
            .put("origin", origin)
            .put("crossOrigin", false)
            .apply {
                if (androidPackageName != null) {
                    put("androidPackageName", androidPackageName)
                }
            }
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun authenticatorData(
        rpId: String,
        userVerified: Boolean,
        signCount: Long,
        attestedCredentialData: ByteArray?,
    ): ByteArray {
        require(signCount in 0..UINT32_MAX) { "sign count must fit uint32" }
        var flags = FLAG_UP or FLAG_BE or FLAG_BS
        if (userVerified) flags = flags or FLAG_UV
        if (attestedCredentialData != null) flags = flags or FLAG_AT
        val counter = byteArrayOf(
            (signCount ushr 24).toByte(),
            (signCount ushr 16).toByte(),
            (signCount ushr 8).toByte(),
            signCount.toByte(),
        )
        return sha256(rpId.toByteArray(Charsets.UTF_8)) +
            byteArrayOf(flags.toByte()) +
            counter +
            (attestedCredentialData ?: byteArrayOf())
    }

    fun attestedCredentialData(
        credentialId: ByteArray,
        coseKey: ByteArray,
    ): ByteArray {
        require(credentialId.isNotEmpty() && credentialId.size <= UShort.MAX_VALUE.toInt()) {
            "credential id length is invalid"
        }
        val length = byteArrayOf(
            (credentialId.size ushr 8).toByte(),
            credentialId.size.toByte(),
        )
        return ByteArray(AAGUID_SIZE) + length + credentialId + coseKey
    }

    fun registrationResponseJson(
        credentialId: ByteArray,
        clientDataJson: ByteArray,
        attestationObject: ByteArray,
    ): String {
        val id = base64Url(credentialId)
        val response = JSONObject()
            .put("clientDataJSON", base64Url(clientDataJson))
            .put("attestationObject", base64Url(attestationObject))
            .put("transports", JSONArray().put("internal"))
        return responseEnvelope(id, response)
    }

    fun authenticationResponseJson(
        credentialId: ByteArray,
        clientDataJson: ByteArray,
        authenticatorData: ByteArray,
        signature: ByteArray,
        userHandle: ByteArray,
    ): String {
        val id = base64Url(credentialId)
        val response = JSONObject()
            .put("clientDataJSON", base64Url(clientDataJson))
            .put("authenticatorData", base64Url(authenticatorData))
            .put("signature", base64Url(signature))
            .put("userHandle", base64Url(userHandle))
        return responseEnvelope(id, response)
    }

    private fun responseEnvelope(
        id: String,
        response: JSONObject,
    ): String =
        JSONObject()
            .put("id", id)
            .put("rawId", id)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put("response", response)
            .put("clientExtensionResults", JSONObject())
            .toString()

    /** Required placeholder when a privileged caller supplied `clientDataHash`. */
    val privilegedClientDataPlaceholder: ByteArray
        get() = "{}".toByteArray(Charsets.UTF_8)

    private const val AAGUID_SIZE = 16
    private const val UINT32_MAX = 0xffff_ffffL
}
