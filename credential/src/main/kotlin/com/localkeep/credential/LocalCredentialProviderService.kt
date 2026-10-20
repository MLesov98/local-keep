package com.localkeep.credential

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.localkeep.core.vault.AppSignatureCache
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@RequiresApi(34)
@AndroidEntryPoint
class LocalCredentialProviderService : CredentialProviderService() {
    @Inject
    lateinit var lockManager: VaultLockManager

    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var appSignatureCache: AppSignatureCache

    // A get-request response is now built off the binder thread because it awaits a signing-cert
    // check (a DataStore read) for native-package password matches — the same bridge autofill uses
    // (LocalAutofillService.serviceScope).
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (cancellationSignal.isCanceled) return
        val publicKeyRequest = request as? BeginCreatePublicKeyCredentialRequest
            ?: return callback.onError(CreateCredentialUnknownException("unsupported credential type"))
        val callingAppInfo = request.callingAppInfo
            ?: return callback.onError(CreateCredentialUnknownException("missing caller identity"))
        // Caller first: its origin is the WebAuthn default for a request that omits `rp.id`.
        val caller = runCatching {
            CallerIdentityResolver.resolve(this, callingAppInfo)
        }.getOrElse {
            return callback.onError(CreateCredentialUnknownException("untrusted privileged caller"))
        }
        val options = runCatching {
            PasskeyCreator.parseRequestJson(publicKeyRequest.requestJson, caller.origin)
        }.getOrElse {
            return callback.onError(CreateCredentialUnknownException("invalid passkey request"))
        }
        if (RpIdValidator.validateForCreate(caller, options.rpId) == RpIdValidator.CreateValidation.Rejected) {
            callback.onError(CreateCredentialUnknownException("caller origin does not match rpId"))
            return
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            options.rpId.hashCode(),
            Intent(this, CredentialActivity::class.java).apply {
                action = ACTION_CREATE
                data = Uri.Builder()
                    .scheme("localkeep")
                    .authority("credential")
                    .appendPath("create")
                    // A digest, not `hashCode()`. The data URI is what distinguishes one
                    // PendingIntent from another (extras are ignored by `filterEquals`), and with
                    // FLAG_UPDATE_CURRENT two rpIds colliding to the same 32-bit int would have the
                    // second request overwrite the first — tapping one site's suggestion serving
                    // the other's flow. Still opaque rather than the rpId itself: which sites hold
                    // a passkey is not something to write into a system-visible URI.
                    .appendPath(OpaqueKey.of(options.rpId))
                    .build()
            },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val entry = CreateEntry.Builder(
            getString(R.string.credential_provider_account_name),
            pendingIntent,
        )
            .setDescription(getString(R.string.credential_provider_create_description, options.rpId))
            .build()
        callback.onResult(BeginCreateCredentialResponse(createEntries = listOf(entry)))
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        if (cancellationSignal.isCanceled) return
        if (lockManager.lockState.value is LockState.Locked) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                UNLOCK_REQUEST_CODE,
                Intent(this, CredentialActivity::class.java).apply {
                    action = ACTION_UNLOCK
                    data = Uri.parse("localkeep://credential/unlock")
                },
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            callback.onResult(
                BeginGetCredentialResponse(
                    authenticationActions = listOf(
                        AuthenticationAction(
                            getString(R.string.credential_unlock_action_label),
                            pendingIntent,
                        ),
                    ),
                ),
            )
            return
        }
        val callingAppInfo = request.callingAppInfo
            ?: return callback.onError(GetCredentialUnknownException("missing caller identity"))
        val caller = runCatching {
            CallerIdentityResolver.resolve(this, callingAppInfo)
        }.getOrElse {
            return callback.onError(GetCredentialUnknownException("untrusted privileged caller"))
        }
        // Guarded because the lock check above is a *sample*, not a hold: auto-lock fires on
        // VaultLockManager's own timer, and a lock landing between line 93 and the read below turns
        // `findMatching` -> `visibilitySnapshot` -> `requireVault` into IllegalStateException("vault
        // is locked"). Thrown out of a system callback that is not ours, that is a crash of the
        // credential provider rather than a failed request — and both neighbours already know it:
        // `passkeyEntries` wraps its own read, and `LocalAutofillService.onFillRequest` wraps
        // `buildUnlockedResponse`. This path was the one left bare.
        serviceScope.launch {
            // A native-package password match must be signing-certificate-verified before it is
            // offered — the exact gate autofill applies (FillResponseFactory.verifyOrPin) and the
            // passkey branch already applies (RpIdValidator.validateForAssertion). Without it, any app
            // that acquires a bound package name is handed the saved password: Android reports the
            // real, unspoofable packageName, but a bare-name match never checks that the app signing
            // it is the one the login was saved against. A web caller is matched by domain through a
            // privileged, resolver-verified origin, not by package, so it does not pass through here.
            //
            // This pins on the query, before the user picks anything, and that is deliberate. The
            // entries returned here carry no password (each is a username plus a PendingIntent;
            // `CredentialActivity` runs this same `verifyOrPin` again before it releases one), but
            // they do put the saved account names on a sheet drawn over the caller. Pinning now
            // fixes the signer before anything from the vault is shown to or delivered for it, and
            // the delivery-time check then compares against that pin rather than taking a fresh
            // one. Same choice, and same side effect, as the autofill offer path: the app appears in
            // Settings -> Trusted apps with its signature remembered as soon as it queries this
            // provider, whether or not a saved login matches or a credential is then used.
            val signatureVerified = caller.origin == null &&
                runCatching { appSignatureCache.verifyOrPin(caller.packageName) }.getOrDefault(false)
            val includePasswords =
                PasswordProvider.passwordEntriesAllowed(caller.origin != null, signatureVerified)
            if (cancellationSignal.isCanceled) return@launch
            val response = runCatching {
                CredentialEntryFactory.response(
                    context = this@LocalCredentialProviderService,
                    request = request,
                    caller = caller,
                    repository = repository,
                    includePasswords = includePasswords,
                )
            }.getOrElse {
                callback.onError(GetCredentialUnknownException("could not read the vault"))
                return@launch
            }
            // Sampled again here, not just before the build: the platform (or caller) can cancel
            // the request while CredentialEntryFactory.response is being assembled above, and
            // delivering a result to a request that is already dead is the stale-delivery
            // LocalAutofillService.onFillRequest guards against with its own post-build check
            // before onSuccess.
            if (!shouldDeliverAfterBuild(cancellationSignal.isCanceled)) return@launch
            callback.onResult(response)
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        if (!cancellationSignal.isCanceled) {
            callback.onResult(null)
        }
    }

    private companion object {
        const val UNLOCK_REQUEST_CODE = 0x4c4b
    }
}

/**
 * Whether a get-credential response that finished building off the binder thread should still be
 * delivered. [isCanceledAfterBuild] is [CancellationSignal.isCanceled] sampled again after
 * [CredentialEntryFactory.response] returns, not the sample taken before the build started — a
 * cancellation racing that build must not result in a stale result being handed to a request the
 * caller already gave up on.
 */
internal fun shouldDeliverAfterBuild(isCanceledAfterBuild: Boolean): Boolean = !isCanceledAfterBuild
