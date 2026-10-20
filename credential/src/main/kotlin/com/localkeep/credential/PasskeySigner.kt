package com.localkeep.credential

import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.VaultItem
import com.localkeep.credential.webauthn.WebAuthnCodec
import org.json.JSONObject
import java.security.Signature

internal object PasskeySigner {
    data class RequestOptions(
        val rpId: String,
        val challenge: String,
        val allowedCredentialIds: List<ByteArray>,
    )

    /**
     * [callerOrigin] supplies the WebAuthn default for a request that omits `rpId`.
     *
     * `getString("rpId")` used to stand here, so an omission threw — and the caller swallowed that
     * into an empty entry list, meaning a site the user demonstrably has a passkey for reported no
     * saved passkeys at all, with nothing shown or logged.
     */
    fun parseRequestJson(
        requestJson: String,
        callerOrigin: String? = null,
    ): RequestOptions {
        val json = JSONObject(requestJson)
        val declaredRpId = json.optString("rpId").takeIf { it.isNotBlank() }
            ?: RpIdValidator.effectiveDomain(callerOrigin)
        val rpId = requireNotNull(RpIdValidator.canonicalRpId(declaredRpId)) {
            "invalid rpId"
        }
        val challenge = json.getString("challenge").also {
            require(WebAuthnCodec.base64UrlDecode(it).isNotEmpty()) { "challenge must not be empty" }
        }
        val allowed = json.optJSONArray("allowCredentials")
        val allowedIds =
            if (allowed == null) {
                emptyList()
            } else {
                (0 until allowed.length()).mapNotNull { index ->
                    allowed.getJSONObject(index)
                        .takeIf { it.optString("type") == "public-key" }
                        ?.getString("id")
                        ?.let(WebAuthnCodec::base64UrlDecode)
                }
            }
        return RequestOptions(rpId, challenge, allowedIds)
    }

    fun isAllowed(
        passkey: Passkey,
        options: RequestOptions,
    ): Boolean =
        passkey.rpId == options.rpId &&
            (
                options.allowedCredentialIds.isEmpty() ||
                    options.allowedCredentialIds.any(passkey.credentialId::contentEquals)
                )

    /**
     * Signs one assertion.
     *
     * [persistSignCount] is handed the counter this assertion will carry and must have stored it
     * durably before it returns — it is called *before* the signature is produced, deliberately.
     * The counter exists so a relying party can notice a cloned authenticator (WebAuthn §6.1.1:
     * it must strictly increase between assertions from the same credential), which means the only
     * unsafe direction is issuing a value twice. Persisting first can at worst skip a number if the
     * signature then fails, and a gap is explicitly allowed; persisting afterwards would re-issue
     * the same counter whenever the write lost a race with the process dying, which is precisely
     * the pattern an RP reads as a clone.
     */
    suspend fun sign(
        item: VaultItem,
        options: RequestOptions,
        caller: CallerIdentity,
        clientDataHash: ByteArray?,
        dek: ByteArray,
        persistSignCount: suspend (Long) -> Unit,
    ): String {
        val passkey = item.content as? Passkey ?: error("selected item is not a passkey")
        require(isAllowed(passkey, options)) { "selected credential is not allowed by the request" }
        require(RpIdValidator.validateForAssertion(caller, item, passkey.rpId)) {
            "caller is not authorized for this rpId"
        }

        val (clientData, hash) =
            if (caller.origin != null) {
                require(clientDataHash?.size == SHA256_SIZE) {
                    "privileged caller must supply a SHA-256 clientDataHash"
                }
                WebAuthnCodec.privilegedClientDataPlaceholder to clientDataHash
            } else {
                require(clientDataHash == null) { "native caller supplied unexpected clientDataHash" }
                val json = WebAuthnCodec.clientDataJson(
                    type = "webauthn.get",
                    challenge = options.challenge,
                    origin = caller.nativeOrigin,
                    androidPackageName = caller.packageName,
                )
                json to WebAuthnCodec.sha256(json)
            }
        // Every assertion used to carry a hardcoded 0. A counter that never moves is not a
        // conservative default — it silently disables the one clone-detection signal WebAuthn gives
        // a relying party, because `0 > 0` is false so no RP can ever fault it.
        //
        // The field is a uint32 on the wire. Saturating at the top rather than wrapping: wrapping
        // sends the counter backwards, which an RP is entitled to treat as a cloned authenticator
        // and refuse. Saturating merely stops the signal, which is where it was before this change.
        // Neither is reachable short of four billion sign-ins with one credential.
        val signCount = (passkey.signCounter + 1).coerceIn(0L, UINT32_MAX)
        persistSignCount(signCount)
        val authData = WebAuthnCodec.authenticatorData(
            rpId = passkey.rpId,
            userVerified = true,
            signCount = signCount,
            attestedCredentialData = null,
        )

        val pkcs8 = PasskeyCrypto.unwrap(dek, passkey.privateKeyRef)
        try {
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(PasskeyCrypto.privateKeyFromPkcs8(pkcs8))
                update(authData)
                update(hash)
                sign()
            }
            return WebAuthnCodec.authenticationResponseJson(
                credentialId = passkey.credentialId,
                clientDataJson = clientData,
                authenticatorData = authData,
                signature = signature,
                userHandle = passkey.userHandle,
            )
        } finally {
            Zeroizer.wipe(pkcs8)
        }
    }

    private const val SHA256_SIZE = 32
    private const val UINT32_MAX = 0xFFFFFFFFL
}
