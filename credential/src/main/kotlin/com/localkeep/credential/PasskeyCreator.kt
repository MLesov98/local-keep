package com.localkeep.credential

import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.PasskeyAlgorithm
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultRepository
import com.localkeep.credential.webauthn.Cbor
import com.localkeep.credential.webauthn.WebAuthnCodec
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

internal object PasskeyCreator {
    data class CreationOptions(
        val rpId: String,
        val challenge: String,
        val userId: ByteArray,
        val userName: String,
        val userDisplayName: String,
        val excludedCredentialIds: List<ByteArray>,
    )

    /** [callerOrigin] supplies the WebAuthn default when `rp.id` is omitted, as the spec allows. */
    fun parseRequestJson(
        requestJson: String,
        callerOrigin: String? = null,
    ): CreationOptions {
        val json = JSONObject(requestJson)
        val rp = json.getJSONObject("rp")
        val user = json.getJSONObject("user")
        val parameters = json.getJSONArray("pubKeyCredParams")
        require((0 until parameters.length()).any { parameters.getJSONObject(it).optInt("alg") == ES256 }) {
            "relying party did not offer ES256"
        }
        val declaredRpId = rp.optString("id").takeIf { it.isNotBlank() }
            ?: RpIdValidator.effectiveDomain(callerOrigin)
        val rpId = requireNotNull(RpIdValidator.canonicalRpId(declaredRpId)) {
            "invalid rpId"
        }
        val challenge = json.getString("challenge").also {
            require(WebAuthnCodec.base64UrlDecode(it).isNotEmpty()) { "challenge must not be empty" }
        }
        val userId = WebAuthnCodec.base64UrlDecode(user.getString("id")).also {
            require(it.size in 1..MAX_USER_HANDLE_SIZE) { "user id must be 1..64 bytes" }
        }
        val excluded = json.optJSONArray("excludeCredentials")
        val excludedIds =
            if (excluded == null) {
                emptyList()
            } else {
                (0 until excluded.length()).mapNotNull { index ->
                    excluded.getJSONObject(index)
                        .takeIf { it.optString("type") == "public-key" }
                        ?.getString("id")
                        ?.let(WebAuthnCodec::base64UrlDecode)
                }
            }
        return CreationOptions(
            rpId = rpId,
            challenge = challenge,
            userId = userId,
            userName = user.optString("name"),
            userDisplayName = user.optString("displayName"),
            excludedCredentialIds = excludedIds,
        )
    }

    suspend fun create(
        options: CreationOptions,
        caller: CallerIdentity,
        clientDataHash: ByteArray?,
        dek: ByteArray,
        repository: VaultRepository,
        nativeBindingConfirmed: Boolean,
    ): String {
        when (RpIdValidator.validateForCreate(caller, options.rpId)) {
            RpIdValidator.CreateValidation.Rejected ->
                error("caller origin does not match rpId")

            is RpIdValidator.CreateValidation.NativeUnconfirmed ->
                require(nativeBindingConfirmed) { "native caller binding was not confirmed" }

            RpIdValidator.CreateValidation.WebOk -> Unit
        }
        val excluded = options.excludedCredentialIds
        val visible = repository.visibilitySnapshot().liveItems
        check(
            // Through the projection. WebAuthn's exclude list exists so a relying party can stop a
            // second passkey being registered for an account it already has — and the failure it
            // produces is visible to the *calling site*, not just to this app. Answering it from the
            // raw vault would let any web page discover a concealed passkey by trying to create one:
            // "an excluded credential already exists" is a disclosure. A concealed passkey therefore
            // does not exclude, and a duplicate may be created while it is hidden.
            visible.none { item ->
                val passkey = item.content as? Passkey
                passkey?.rpId == options.rpId &&
                    excluded.any(passkey.credentialId::contentEquals)
            },
        ) { "an excluded credential already exists" }
        // CTAP2 keeps one discoverable credential per rpId and user handle, so a registration for an
        // account this vault already holds replaces it — a plain add left two identical rows in the
        // sign-in chooser, one of which the site no longer accepted. A different user handle is a
        // different account and is kept. Read from the same projection as the exclude list, and for
        // the same reason: a concealed passkey is neither replaced nor touched, so a registration
        // from a stranger's page never acts on an entry the user hid, and no Trash row appears for
        // it once a reveal ends. Hidden, it may be duplicated, exactly as above.
        val replaced = visible.filter { item ->
            val passkey = item.content as? Passkey
            passkey?.rpId == options.rpId && passkey.userHandle.contentEquals(options.userId)
        }

        val credentialId = SecureRandomProvider.nextBytes(CREDENTIAL_ID_SIZE)
        val generated = PasskeyCrypto.generateAndWrap(dek)
        val (x, y) = PasskeyCrypto.publicKeyCoordinates(generated.publicKey)
        val coseKey = Cbor.coseP256PublicKey(x, y)
        val clientData = responseClientData(
            type = "webauthn.create",
            options = options.challenge,
            caller = caller,
            clientDataHash = clientDataHash,
        )
        val attestedData = WebAuthnCodec.attestedCredentialData(credentialId, coseKey)
        val authData = WebAuthnCodec.authenticatorData(
            rpId = options.rpId,
            userVerified = true,
            signCount = 0,
            attestedCredentialData = attestedData,
        )
        val response = WebAuthnCodec.registrationResponseJson(
            credentialId = credentialId,
            clientDataJson = clientData,
            attestationObject = Cbor.noneAttestationObject(authData),
        )

        val now = Instant.now()
        val label = options.userDisplayName.ifBlank { options.userName }.ifBlank { options.rpId }
        val customFields =
            if (caller.origin == null) {
                RpIdValidator.nativeBindingFields(caller)
            } else {
                emptyList()
            }
        repository.addItem(
            VaultItem(
                id = UUID.randomUUID(),
                title = "$label — ${options.rpId}",
                createdAt = now,
                updatedAt = now,
                customFields = customFields,
                content = Passkey(
                    rpId = options.rpId,
                    credentialId = credentialId,
                    userHandle = options.userId,
                    privateKeyRef = generated.wrappedPrivateKey,
                    algorithm = PasskeyAlgorithm.ES256,
                    signCounter = 0,
                    createdAt = now,
                    userName = options.userName,
                    userDisplayName = options.userDisplayName,
                ),
            ),
        )
        // Trashed, not deleted, and only after the new one is stored: until the relying party has the
        // response, the old credential is the only one it accepts, and it must stay recoverable.
        // Best-effort for the same reason — a lock or a user's own trashing in the gap since the
        // snapshot leaves the duplicate this used to produce, but failing here would tell the caller
        // the registration failed while a passkey it never received sits live in the vault.
        for (item in replaced) {
            try {
                repository.moveToTrash(item.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            }
        }
        return response
    }

    private fun responseClientData(
        type: String,
        options: String,
        caller: CallerIdentity,
        clientDataHash: ByteArray?,
    ): ByteArray =
        if (caller.origin != null) {
            require(clientDataHash?.size == SHA256_SIZE) {
                "privileged caller must supply a SHA-256 clientDataHash"
            }
            WebAuthnCodec.privilegedClientDataPlaceholder
        } else {
            require(clientDataHash == null) { "native caller supplied unexpected clientDataHash" }
            WebAuthnCodec.clientDataJson(
                type = type,
                challenge = options,
                origin = caller.nativeOrigin,
                androidPackageName = caller.packageName,
            )
        }

    private const val ES256 = -7
    private const val MAX_USER_HANDLE_SIZE = 64
    private const val CREDENTIAL_ID_SIZE = 32
    private const val SHA256_SIZE = 32
}
