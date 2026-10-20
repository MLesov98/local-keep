package com.localkeep.credential

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.GetPasswordOption
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialInterruptedException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.localkeep.core.crypto.BiometricAuthException
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.Passkey
import com.localkeep.core.ui.SecureActivity
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.AppSignatureCache
import com.localkeep.core.vault.DeviceKeyStore
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultLockedException as VaultThrottledException
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID

@RequiresApi(34)
@AndroidEntryPoint
class CredentialActivity : SecureActivity() {
    @Inject
    lateinit var lockManager: VaultLockManager

    @Inject
    lateinit var repository: VaultRepository

    @Inject
    lateinit var biometricUnlock: BiometricUnlock

    @Inject
    lateinit var appSignatureCache: AppSignatureCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val createRequest = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val getRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        val beginGetRequest = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        lifecycleScope.launch {
            when {
                createRequest != null -> handleCreate(createRequest)
                getRequest != null -> handleGet(getRequest)
                beginGetRequest != null -> handleUnlock(beginGetRequest)
                else -> cancel()
            }
        }
    }

    private suspend fun handleCreate(request: ProviderCreateCredentialRequest) {
        val publicKeyRequest = request.callingRequest as? CreatePublicKeyCredentialRequest
            ?: return finishCreateError("unsupported credential type")
        // Caller first: its origin is the WebAuthn default for a request that omits `rp.id`.
        val caller = runCatching {
            CallerIdentityResolver.resolve(this, request.callingAppInfo)
        }.getOrElse {
            return finishCreateError("untrusted privileged caller")
        }
        val options = runCatching {
            PasskeyCreator.parseRequestJson(publicKeyRequest.requestJson, caller.origin)
        }.getOrElse {
            return finishCreateError("invalid passkey request")
        }
        val validation = RpIdValidator.validateForCreate(caller, options.rpId)
        if (validation == RpIdValidator.CreateValidation.Rejected) {
            return finishCreateError("caller origin does not match rpId")
        }
        val nativeConfirmed =
            if (validation is RpIdValidator.CreateValidation.NativeUnconfirmed) {
                confirmNativeBinding(caller.packageName, options.rpId)
            } else {
                false
            }
        if (validation is RpIdValidator.CreateValidation.NativeUnconfirmed && !nativeConfirmed) {
            // Cancel, Back or a tap outside: the user declining, which the API has its own word for.
            // As Unknown it read to the calling app as an error to report or retry.
            return finishCreateCancelled("native app binding declined")
        }
        val created = ensurePasskeyVerification()
        if (created == Verification.INTERRUPTED) {
            return finishCreateInterrupted(created.message.orEmpty())
        }
        if (created == Verification.CANCELLED) {
            return finishCreateCancelled(created.message.orEmpty())
        }
        if (created != Verification.OK) {
            return finishCreateError(created.message.orEmpty())
        }

        val response = runCatching {
            withContext(Dispatchers.Default) {
                withSessionDekCopy(requireDek()) { dek ->
                    PasskeyCreator.create(
                        options = options,
                        caller = caller,
                        clientDataHash = publicKeyRequest.clientDataHash,
                        dek = dek,
                        repository = repository,
                        nativeBindingConfirmed = nativeConfirmed,
                    )
                }
            }
        }.getOrElse { cause ->
            // Typed, not textual. Every failure here used to collapse into one "passkey creation
            // failed", so a vault that had simply auto-locked mid-request was indistinguishable
            // from a permanent crypto misconfiguration — and the caller is another app, which can
            // only act on what it is handed. Interrupted is the API's own "this may work if you
            // try again"; Unknown stays for everything genuinely unrecoverable.
            //
            // The messages are fixed constants. The cause is never attached: this crosses a process
            // boundary into a third-party app, and an exception raised while reading the vault can
            // carry item titles or domains in its message.
            return if (cause is VaultLockedException) {
                finishCreateInterrupted(LOCKED_MESSAGE)
            } else {
                finishCreateError("passkey creation failed")
            }
        }
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(
            result,
            CreatePublicKeyCredentialResponse(response),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private suspend fun handleGet(request: ProviderGetCredentialRequest) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return finishGetError("missing credential selection")
        val caller = runCatching {
            CallerIdentityResolver.resolve(this, request.callingAppInfo)
        }.getOrElse {
            return finishGetError("untrusted privileged caller")
        }
        // `singleOrNull()` used to stand here, which failed the whole request the moment it carried
        // more than one CredentialOption — and the standard Credential Manager sign-in pattern
        // sends GetPasswordOption and GetPublicKeyCredentialOption together. The entries were built
        // for both, so the user picked one and then got "user verification failed".
        //
        // The entry itself records which *type* of option it answers (EXTRA_CREDENTIAL_TYPE),
        // rather than guessing from the shape of the list. Not matched by id: unlike
        // BeginGetCredentialOption on the begin side, the CredentialOption this request carries
        // exposes no id at all, so there is nothing here to match one by.
        val option = when (intent.getStringExtra(EXTRA_CREDENTIAL_TYPE)) {
            CREDENTIAL_TYPE_PASSKEY ->
                request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull()
            CREDENTIAL_TYPE_PASSWORD ->
                request.credentialOptions.filterIsInstance<GetPasswordOption>().firstOrNull()
            // An entry from before the extra existed, still held in a cached PendingIntent.
            else -> request.credentialOptions.singleOrNull()
        }
        val publicKeyOption = option as? GetPublicKeyCredentialOption
        val passwordOption = option as? GetPasswordOption
        // Captured before verification, because unlocking from Locked *is* a fresh biometric: the
        // reprompt check below would otherwise raise a second prompt immediately after the first.
        val wasLocked = lockManager.lockState.value is LockState.Locked
        val verified =
            when {
                publicKeyOption != null -> ensurePasskeyVerification()
                passwordOption != null -> ensureUnlocked()
                else -> Verification.FAILED
            }
        if (verified == Verification.INTERRUPTED) {
            return finishGetInterrupted(verified.message.orEmpty())
        }
        if (verified == Verification.CANCELLED) return finishGetCancelled(verified.message.orEmpty())
        if (verified != Verification.OK) return finishGetError(verified.message.orEmpty())

        // Through the projection, so a concealed entry resolves the same way a deleted one does.
        // The entry list this id came from was built from the projection, but a verification prompt
        // sits in between and a lease expiry, a lock or a background-grace conceal can land inside
        // it. The error text is deliberately the same in both cases — "no longer exists" discloses
        // nothing that "is hidden" would.
        //
        // Guarded because the auto-lock timer has no foreground gate: it can expire while the
        // prompt above is on screen — "minutes can pass there", as the sign path says below — and
        // `visibilitySnapshot` then goes through `requireVault`, whose `check(...)` throws. This
        // read sits inside the bare `lifecycleScope.launch` in `onCreate`, so an escaping throw
        // killed the process on top of whatever app raised the sheet instead of reporting the one
        // failure that unlocking and retrying fixes.
        val item = runCatching {
            repository.visibilitySnapshot().liveItems.firstOrNull { it.id == itemId }
        }.getOrElse {
            // The lock state rather than the exception type: the repository throws a plain
            // IllegalStateException, so there is nothing to match on. Anything else that made the
            // vault unreadable is not a lock and must not claim to be one.
            return if (lockManager.lockState.value !is LockState.Unlocked) {
                finishGetInterrupted(LOCKED_MESSAGE)
            } else {
                finishGetError("credential use failed")
            }
        } ?: return finishGetError("credential no longer exists")

        // The per-item reprompt flag has to hold here too. Passkeys already re-verify on every use
        // (`ensurePasskeyVerification`), and the Autofill route gates a flagged item behind a
        // dataset-auth PendingIntent — but the password route only asked for an *unlocked* vault,
        // so a flagged password was handed to the calling app with no fresh check at all. That is
        // the exposure the flag exists to prevent, one layer worse than an in-app reveal, because
        // the secret leaves the process.
        if (passwordOption != null && item.reprompt && !wasLocked) {
            // An unmeetable challenge is not a refusal — say which it was, or the calling app tells
            // the user to try again at something that cannot succeed.
            if (!canConfirmFreshly()) {
                return finishGetError(Verification.UNAVAILABLE.message.orEmpty())
            }
            val fresh = confirmFreshBiometric(getString(R.string.credential_verify_password_subtitle))
            if (fresh == Verification.CANCELLED) return finishGetCancelled(fresh.message.orEmpty())
            if (fresh != Verification.OK) {
                return finishGetError(Verification.FAILED.message.orEmpty())
            }
        }
        val passkeyContent = item.content as? Passkey
        val credential = runCatching {
            when {
                passkeyContent != null && publicKeyOption != null -> {
                    val options =
                        PasskeySigner.parseRequestJson(publicKeyOption.requestJson, caller.origin)
                    val response = withContext(Dispatchers.Default) {
                        withSessionDekCopy(requireDek()) { dek ->
                            PasskeySigner.sign(
                                item = item,
                                options = options,
                                caller = caller,
                                clientDataHash = publicKeyOption.clientDataHash,
                                dek = dek,
                            // Writes the whole vault, so it costs a file write per sign-in — but
                            // it is the only place the counter can live: the assertion is what a
                            // relying party compares against the previous one, so a value held in
                            // memory would reset on every process death, which is the majority of
                            // uses (this activity is started fresh by the system each time).
                            // `updatedAt` deliberately untouched: this is not a user edit and it
                            // should not reorder the vault list or look like one in a diff.
                            // Re-read under the vault lock rather than writing back the `item`
                            // captured before the biometric prompt: minutes can pass there, and
                            // committing that stale copy reverted anything the user (or the
                            // autofill save-confirm activity) changed meanwhile. Only the counter
                            // is this activity's to write.
                            persistSignCount = { count ->
                                repository.updateWith(itemId) { stored ->
                                    val passkey = stored.content as? Passkey
                                        ?: return@updateWith stored
                                    stored.copy(content = passkey.copy(signCounter = count))
                                }
                            },
                            )
                        }
                    }
                    PublicKeyCredential(response)
                }

                item.content is Login && passwordOption != null ->
                    PasswordProvider.credentialFor(item)

                else -> error("selected credential type does not match request")
            }
        }.getOrElse { cause ->
            // [VaultLockedException] is private to this file and only `requireDek` throws it, so it
            // cannot recognise a lock that surfaced from the repository instead — `updateWith` is
            // reached from inside this block and throws a plain IllegalStateException. The live
            // lock state is the second, type-independent test.
            return if (
                cause is VaultLockedException || lockManager.lockState.value !is LockState.Unlocked
            ) {
                finishGetInterrupted(LOCKED_MESSAGE)
            } else {
                finishGetError("credential use failed")
            }
        }
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(result, GetCredentialResponse(credential))
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    /** AuthenticationAction result: unlock, then let the system render the real credential picker. */
    private suspend fun handleUnlock(request: BeginGetCredentialRequest) {
        // `cancel()` here used to swallow the reason: the sheet simply vanished after the finger was
        // accepted, with nothing said. The other two entry points already report it — this one is
        // the tap on "Unlock LocalKeep", which is exactly where a user who has just panic-locked
        // arrives, and where "biometric unlock is unavailable, use your master password" is the
        // whole answer.
        val verified = ensureUnlocked()
        if (verified == Verification.INTERRUPTED) {
            return finishGetInterrupted(verified.message.orEmpty())
        }
        if (verified == Verification.CANCELLED) return finishGetCancelled(verified.message.orEmpty())
        if (verified != Verification.OK) return finishGetError(verified.message.orEmpty())
        val callingAppInfo = request.callingAppInfo ?: return cancel()
        val caller = runCatching {
            CallerIdentityResolver.resolve(this, callingAppInfo)
        }.getOrElse {
            return cancel()
        }
        // Same signing-cert gate as LocalCredentialProviderService: a native-package password match
        // is only offered when the caller's certificate verifies against the pinned identity.
        val signatureVerified = caller.origin == null &&
            runCatching { appSignatureCache.verifyOrPin(caller.packageName) }.getOrDefault(false)
        val includePasswords =
            PasswordProvider.passwordEntriesAllowed(caller.origin != null, signatureVerified)
        // Same guard as the read in `handleGet`, through a narrower window: nothing is armed while
        // Locked and the unlock above re-arms a full timeout, so only this stretch is exposed. The
        // passkey half already catches its own read; the password half does not.
        val response = runCatching {
            CredentialEntryFactory.response(
                context = this,
                request = request,
                caller = caller,
                repository = repository,
                includePasswords = includePasswords,
            )
        }.getOrElse {
            return if (lockManager.lockState.value !is LockState.Unlocked) {
                finishGetInterrupted(LOCKED_MESSAGE)
            } else {
                finishGetError("credential use failed")
            }
        }
        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(result, response)
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private suspend fun ensurePasskeyVerification(): Verification =
        when (lockManager.lockState.value) {
            LockState.Locked -> openLockedVault()
            is LockState.Unlocked -> when {
                !canConfirmFreshly() -> Verification.UNAVAILABLE
                else -> confirmFreshBiometric()
            }
        }

    private suspend fun ensureUnlocked(): Verification =
        when (lockManager.lockState.value) {
            LockState.Locked -> openLockedVault()
            is LockState.Unlocked -> Verification.OK
        }

    /** See the top-level [openLockedVault], which holds the routing so it can be tested. */
    private suspend fun openLockedVault(): Verification =
        openLockedVault(
            biometric = { unlockWithBiometric() },
            password = { unlockWithMasterPassword() },
        )

    /**
     * The master-password dialog, raised until it opens the vault or the user declines.
     *
     * A loop rather than one shot, because a wrong password, a throttled attempt and a vault the
     * process is too short of memory for are all states the user can act on without losing the
     * ceremony — which is the entire point of being here rather than back in the app. The dialog
     * stays up and says which one happened, in [credentialUnlockMessage]'s words.
     *
     * The `finally` lowers the dialog on every exit, including a coroutine cancellation: this runs
     * inside the bare `lifecycleScope.launch` in `onCreate`, so nothing else would.
     */
    private suspend fun unlockWithMasterPassword(): Verification {
        val visible = mutableStateOf(true)
        val working = mutableStateOf(false)
        val message = mutableStateOf<CredentialUnlockText>(CredentialUnlockText.None)
        // Unlimited rather than rendezvous: `trySend` is called from a composable's click handler,
        // which cannot suspend, and a conflating channel would drop the cancel that lands while an
        // attempt is being derived.
        val answers = Channel<CharArray?>(Channel.UNLIMITED)
        setContent {
            LocalKeepTheme {
                if (visible.value) {
                    CredentialUnlockDialog(
                        error = message.value.resolve(this@CredentialActivity),
                        working = working.value,
                        onSubmit = { answers.trySend(it.toCharArray()) },
                        onCancel = { answers.trySend(null) },
                    )
                }
            }
        }
        try {
            while (true) {
                val password = answers.receive() ?: return Verification.CANCELLED
                working.value = true
                message.value = CredentialUnlockText.None
                val outcome = withContext(Dispatchers.Default) { attemptMasterPasswordUnlock(password) }
                working.value = false
                if (outcome is CredentialUnlockOutcome.Success) return Verification.OK
                message.value = credentialUnlockMessage(outcome)
            }
        } finally {
            visible.value = false
            answers.close()
        }
    }

    /**
     * One attempt. [VaultLockManager.unlock] wipes [password] itself, on every path.
     *
     * Every failure used to be one boolean in the autofill twin and the same collapse would be
     * wrong here: a throttled attempt, an unreadable vault file and a lock landing inside Argon2
     * are not a mistyped password. The generic catch is the only arm that guesses, and it guesses
     * "this gate is at fault", never "you typed it wrong".
     */
    private suspend fun attemptMasterPasswordUnlock(password: CharArray): CredentialUnlockOutcome =
        try {
            lockManager.unlock(password).let {
                if (it.opened) CredentialUnlockOutcome.Success else CredentialUnlockOutcome.Failed(it)
            }
        } catch (throttled: VaultThrottledException) {
            CredentialUnlockOutcome.Throttled(throttled.retryAfterMs)
        } catch (_: Exception) {
            CredentialUnlockOutcome.Failed(UnlockResult.VAULT_UNREADABLE)
        }

    private suspend fun unlockWithBiometric(): Verification {
        val wrapped = DeviceKeyStore.load(this) ?: return Verification.UNAVAILABLE
        val cipher = try {
            biometricUnlock.decryptCipher(wrapped.nonce)
        } catch (_: Exception) {
            // KeyPermanentlyInvalidatedException and its neighbours land here, before any prompt is
            // shown: there is no user action in this failure at all.
            return Verification.UNAVAILABLE
        }
        return try {
            val authenticated = biometricUnlock.authenticate(
                activity = this,
                cipher = cipher,
                title = getString(R.string.credential_unlock_action_label),
                subtitle = getString(R.string.credential_unlock_biometric_subtitle),
                negativeButtonText = getString(
                    com.localkeep.core.ui.R.string.core_ui_biometric_negative_button,
                ),
            )
            // Exhaustive rather than `else`, for the reason `AuthPromptMessage` gives about its own
            // removed `else`: it read "anything I have not thought about is a verification
            // failure", and it said exactly that about ALREADY_UNLOCKED — a result whose own KDoc
            // calls it "not an error: the user asked for an open vault and the vault is open", and
            // names this surface as the reason it exists. `unlockWithBiometric` returns it whenever
            // something else opened the vault between the read at `ensureUnlocked` and its own
            // check, which the biometric prompt in between leaves plenty of room for; by then a
            // CryptoObject-bound Class 3 biometric has already passed, so OK asserts nothing that
            // REAL/DURESS does not. An exhaustive `when` makes the next enum addition a build
            // failure instead of a silent mislabel. 38-2.
            when (withContext(Dispatchers.Default) { lockManager.unlockWithBiometric(authenticated) }) {
                UnlockResult.REAL, UnlockResult.DURESS, UnlockResult.ALREADY_UNLOCKED ->
                    Verification.OK
                UnlockResult.BIOMETRIC_UNAVAILABLE -> Verification.UNAVAILABLE
                UnlockResult.INTERRUPTED -> Verification.INTERRUPTED
                // Retrying the fingerprint cannot fix any of these, and none of them is a statement
                // about the person holding the phone. Left on FAILED because correcting the words
                // needs a message decision, not because the mapping is right: WRONG_CREDENTIAL and
                // VAULT_FROM_NEWER_VERSION are unreachable from this path (no password is offered
                // here, and the header decode that raises the latter is inside the catch below),
                // and VAULT_UNREADABLE/VAULT_NEEDS_MORE_MEMORY are the two that would benefit.
                UnlockResult.WRONG_CREDENTIAL,
                UnlockResult.VAULT_UNREADABLE,
                UnlockResult.VAULT_FROM_NEWER_VERSION,
                UnlockResult.VAULT_NEEDS_MORE_MEMORY,
                UnlockResult.VAULT_MEMORY_TRANSIENT,
                -> Verification.FAILED
            }
        } catch (_: VaultThrottledException) {
            // The vault layer's throttle, not this file's own `VaultLockedException` — same name,
            // different meaning, which is why the import is aliased. It is thrown by `checkThrottle`
            // *before* any unwrap is attempted, so it used to fall into the bare catch below and
            // reach the calling app as "user verification failed": advice to retry, for the one
            // state where an immediate retry is guaranteed to fail again. The retryAfterMs is
            // deliberately not forwarded — this crosses into a third-party app.
            Verification.INTERRUPTED
        } catch (refused: BiometricAuthException) {
            // The prompt ended without a finger: its negative button, Back, or something else.
            biometricRefusal(refused.code, passwordOffered = true)
        } catch (_: Exception) {
            Verification.FAILED
        }
    }

    /**
     * A post-unlock freshness check: "is the person holding the phone still the owner?".
     *
     * Accepts the device credential alongside a Class 3 biometric, and reports
     * [Verification.UNAVAILABLE] when the device can offer neither. It used to demand
     * `BIOMETRIC_STRONG` alone with no availability check, which on a device with no enrolled
     * fingerprint could not succeed *at all*: the prompt errored, every error resumed `false`, and
     * the calling app was told "user verification failed" — advice to retry, for a challenge that
     * had no passing outcome. That stranded passkeys, and once the reprompt gate was added it
     * stranded flagged passwords too.
     *
     * No key material is involved here, so unlike [com.localkeep.core.crypto.BiometricUnlock] this
     * is not `CryptoObject`-bound and the device credential is a legitimate answer to the question
     * being asked. `core/ui`'s `RevealAuth` makes the same call for the in-app reveal gate, for the
     * same reason and in the same words.
     */
    private fun canConfirmFreshly(): Boolean =
        BiometricManager.from(this).canAuthenticate(FRESHNESS_AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private suspend fun confirmFreshBiometric(
        subtitle: String = getString(R.string.credential_verify_passkey_subtitle),
    ): Verification =
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(
                this,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        if (continuation.isActive) continuation.resume(Verification.OK)
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        if (continuation.isActive) {
                            continuation.resume(biometricRefusal(errorCode, passwordOffered = false))
                        }
                    }
                },
            )
            continuation.invokeOnCancellation {
                executor.execute(prompt::cancelAuthentication)
            }
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.credential_verify_title))
                    .setSubtitle(subtitle)
                    // No negative button: the builder rejects one when DEVICE_CREDENTIAL is allowed,
                    // and the system supplies its own cancel affordance in that mode.
                    .setAllowedAuthenticators(FRESHNESS_AUTHENTICATORS)
                    .build(),
            )
        }

    /**
     * The one consent in this app that did not look like this app (V3-06).
     *
     * It was a platform `AlertDialog` wearing `Theme.LocalKeep.Dialog`, on the reasoning that this
     * activity hosts no Compose tree and `LkDialog` was therefore out of reach. The theme bought
     * the colours and nothing else: a `uiautomator` dump on 2026-09-17 read `android:id/alertTitle`
     * and `android:id/button1`, square corners edge to edge, ALL-CAPS CANCEL and ALLOW in a
     * horizontal row — against the inset rounded card with stacked full-width buttons that every
     * other consent in the app draws. This is the dialog whose entire job is to be recognisably the
     * password manager speaking, and it was the only one that was not.
     *
     * Giving the activity a Compose tree is the whole fix. The copy, the two answers and the result
     * plumbing are unchanged, including the equivalence the old `setOnCancelListener` gave Back and
     * a tap outside — `LkDialog` is `dismissible` by default, so both route through `onDismiss`,
     * which denies.
     *
     * FLAG_SECURE is not weakened by the move and is slightly better for it. A dialog is its own
     * window either way; Compose's `DialogProperties` defaults to `SecureFlagPolicy.Inherit`, which
     * copies the flag [SecureActivity] sets on this activity, where a platform `AlertDialog`
     * inherits nothing.
     *
     * `visible` lives outside `setContent` so the cancellation path can lower the dialog from
     * whatever thread the coroutine was cancelled on, the way `dialog.dismiss()` used to.
     */
    private suspend fun confirmNativeBinding(
        packageName: String,
        rpId: String,
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            val visible = mutableStateOf(true)
            fun answer(allowed: Boolean) {
                visible.value = false
                if (continuation.isActive) continuation.resume(allowed)
            }
            setContent {
                LocalKeepTheme {
                    if (visible.value) {
                        NativeBindingDialog(
                            packageName = packageName,
                            rpId = rpId,
                            onAllow = { answer(true) },
                            onDeny = { answer(false) },
                        )
                    }
                }
            }
            continuation.invokeOnCancellation { runOnUiThread { visible.value = false } }
        }

    private fun requireDek(): ByteArray =
        (lockManager.lockState.value as? LockState.Unlocked)?.dek
            ?: throw VaultLockedException()

    private fun finishCreateError(message: String) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(
            result,
            CreateCredentialUnknownException(message),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishCreateInterrupted(message: String) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(
            result,
            CreateCredentialInterruptedException(message),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    /**
     * The user declined. `…CancellationException` is the API's "the user intentionally cancelled",
     * the same type the system's own sheet reports when it is dismissed — so a relying party treats
     * a "no" inside this provider as the choice it is rather than as an error.
     */
    private fun finishCreateCancelled(message: String) {
        val result = Intent()
        PendingIntentHandler.setCreateCredentialException(
            result,
            CreateCredentialCancellationException(message),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    /** See [finishCreateCancelled]. */
    private fun finishGetCancelled(message: String) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(
            result,
            GetCredentialCancellationException(message),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishGetInterrupted(message: String) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(
            result,
            GetCredentialInterruptedException(message),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun finishGetError(message: String) {
        val result = Intent()
        PendingIntentHandler.setGetCredentialException(
            result,
            GetCredentialUnknownException(message),
        )
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    private fun cancel() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}

/**
 * The vault was locked when a credential request needed it — the one failure in this activity that
 * is worth telling apart, because unlocking and retrying fixes it.
 *
 * A type rather than a message: the locked case was already distinguishable only by the text of an
 * `error(...)`, which is not something to branch on.
 */
private class VaultLockedException : IllegalStateException("vault locked")

/**
 * What the freshness re-check will accept. Mirrors `core/ui`'s `RevealAuth.ALLOWED`, deliberately:
 * both answer "is the owner still holding the phone?" on an already-unlocked vault, neither is
 * bound to a `CryptoObject`, and a device with no fingerprint sensor must still be able to pass.
 */
/**
 * Fixed, and deliberately says nothing about the vault's contents. Kept in this file, which
 * config/i18n-allowlist.txt names for exactly this protocol message: it reaches the calling app
 * inside a Create/GetCredentialException, never this app's UI. CredentialVerification.kt uses it too.
 */
internal const val LOCKED_MESSAGE = "LocalKeep is locked. Unlock it and try again."

private const val FRESHNESS_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

internal suspend fun <T> withSessionDekCopy(
    sessionDek: ByteArray,
    block: suspend (ByteArray) -> T,
): T {
    val operationDek = sessionDek.copyOf()
    return try {
        block(operationDek)
    } finally {
        Zeroizer.wipe(operationDek)
    }
}
