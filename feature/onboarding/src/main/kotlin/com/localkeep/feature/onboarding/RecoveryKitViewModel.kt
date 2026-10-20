package com.localkeep.feature.onboarding

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.vault.LocalKeepSettings
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.RecoveryKit
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.KdfNeedsMoreMemoryException
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.core.vault.VaultSessionEndedException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class RecoveryKitUiState(
    /**
     * Whether this session has passed a fresh challenge. Defaults to false and is never restored
     * from saved state — see the class KDoc for why minting a kit costs a challenge at all.
     */
    val authorized: Boolean = false,
    /**
     * Whether this device can raise a `BIOMETRIC_STRONG` prompt, so the gate can offer one beside
     * the vault password. Read once at construction, like the invisible-items gate reads it.
     */
    val biometricAvailable: Boolean = false,
    /** Empty until the user asks for a kit. Twenty-four words are not something to render unasked. */
    val mnemonic: String = "",
    val qrData: String = "",
    val qrVisible: Boolean = false,
    val exported: Boolean = false,
    val isWorking: Boolean = false,
    /** String resource ids, not strings: a ViewModel has no Context to resolve one, and must not
     *  gain one for this (the i18n plan's ViewModel rule). */
    @param:StringRes val notice: Int? = null,
    @param:StringRes val error: Int? = null,
    /**
     * Whole seconds left on the gate's throttle, or null when the gate is not throttled.
     *
     * Separate from [error] rather than folded into it: [error] is a plain `@StringRes` and this
     * screen's throttle message is a `@PluralsRes` that needs the count as a format argument —
     * `R.plurals.onboarding_recoverykit_gate_error_throttled`, resolved by the composable with
     * `pluralStringResource`, the same way `UnlockViewModel` and `SettingsViewModel` already report
     * the identical [com.localkeep.core.vault.VaultLockedException]. Never set alongside [error]:
     * [authorize] treats the two as mutually exclusive outcomes of the same attempt.
     */
    val throttleSeconds: Int? = null,
)

/**
 * Creates a Recovery Kit for a vault that already exists.
 *
 * Onboarding used to be the only place a kit could ever be made, and it made exactly one. That is
 * two problems. It forced a first-run detour on someone who only wanted to look at the app — and
 * more seriously, changing the master password rotates the data key and silently voids the kit, so
 * anyone who has ever changed their password has been carrying a piece of paper that no longer
 * opens anything.
 *
 * Making one later needs only the session data key, which is in memory whenever the vault is open:
 * a kit is a random BIP-39 mnemonic and that key wrapped under a key derived from it. The wrapped
 * half is written into the vault container, replacing whatever was there — so each new kit revokes
 * the last. See [createKit].
 *
 * **Reaching this screen costs a fresh challenge.** An unlocked, unattended phone was two taps from
 * a durable credential: the drawer and the Settings row both land here, "Create a Recovery Kit"
 * asked nothing, and "Show the QR code" then "I captured the QR code" commits — entirely in-app,
 * with no system dialog anywhere in the path. That leaves the attacker holding 24 words that open
 * the vault after it locks, and revokes the kit the owner has on paper. Revealing a *stored* seed
 * phrase already costs a challenge (`seedPhrase` is in `ALWAYS_REAUTH_FIELDS`), so minting the
 * vault's own recovery words costing nothing was the asymmetry; the threat model puts opportunistic
 * physical access (T2) in scope. Found 2026-08-30.
 *
 * The gate is the shape [com.localkeep.feature.settings.HiddenItemsViewModel] established: plain
 * memory rather than `SavedStateHandle`, which survives process recreation and would restore a gate
 * nobody re-authenticated; dropped the instant the vault locks; and epoch-guarded, so a challenge
 * that outlives its session grants nothing.
 *
 * **Strong biometric, or the vault password — the same two doors the invisible-items gate offers.**
 * This gate used to be knowledge-factor only, on the argument that a biometric proves only that the
 * phone is still unlocked. That argument is sound and it is not this gate’s to make alone: the
 * invisible-items gate guards a list of the user’s most sensitive records and accepts a strong
 * biometric, so a user with a fingerprint enrolled met two different answers to the same question
 * two rows apart in the same drawer (release sweep 2026-09-20, 2A-F17). One policy for "prove you
 * are still the owner", and it is the invisible-items one.
 *
 * What the alignment does **not** do is widen the credential. This prompt is `BIOMETRIC_STRONG`
 * only — never `RevealAuth`, which permits `DEVICE_CREDENTIAL` alongside it. The device PIN is the
 * secret a coercer standing over the user is most likely to have watched being typed, and it is not
 * a door onto the vault’s recovery words. The knowledge factor offered beside it is the *vault*
 * password, which the device lock screen does not know.
 *
 * Nothing here branches on duress. [VaultLockManager.reauthenticateForCurrentVault] asks only
 * whether this is the *current* vault's password, and every outcome below reports the same resource
 * id in both sessions — a message that differed by a word would make the gate a duress oracle. The
 * label says "vault password" for the same reason.
 */
@HiltViewModel
class RecoveryKitViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val lockManager: VaultLockManager,
    private val keystoreManager: KeystoreManager,
) : ViewModel() {
    private val _state = MutableStateFlow(
        // Not after a panic lock — see VaultLockManager.isBiometricSuspendedByPanic.
        RecoveryKitUiState(biometricAvailable = keystoreManager.isStrongBiometricAvailable() &&
                !lockManager.isBiometricSuspendedByPanic),
    )
    val state: StateFlow<RecoveryKitUiState> = _state.asStateFlow()

    /**
     * Invalidates a challenge that is still in flight. A password check runs through Argon2id and
     * takes real time; on the Aggressive preset the vault can lock underneath it. Without this, a
     * success arriving after the lock would open the gate on a session that no longer exists.
     */
    private var authGeneration = 0L

    init {
        viewModelScope.launch {
            lockManager.lockState.collect { lock ->
                if (lock !is LockState.Unlocked) revokeAuthorization()
            }
        }
    }

    /**
     * Applies a completed biometric challenge. Anything but a current-generation success is a no-op
     * — the epoch rule [authGeneration] documents, which is why this is separable from the prompt.
     * Internal because it is also the seam the JVM tests use: a `BiometricPrompt` cannot run under
     * Robolectric, and the rule it protects is exactly what needs a test.
     */
    internal fun applyBiometricOutcome(granted: Boolean, generation: Long) {
        if (!granted || generation != authGeneration) return
        _state.update { it.copy(authorized = true, error = null, throttleSeconds = null) }
    }

    /** The biometric door. Nothing here branches on duress; see the class KDoc. */
    fun authorizeWithBiometric(activity: FragmentActivity) {
        if (lockManager.isBiometricSuspendedByPanic) return
        if (_state.value.isWorking) return
        val generation = authGeneration
        viewModelScope.launch {
            // Resolved with Context.getString, not stringResource: BiometricPrompt is an imperative
            // Android API rather than a composable — the same exception OnboardingViewModel’s own
            // prompt strings take.
            val granted = promptForStrongBiometric(
                activity = activity,
                title = context.getString(R.string.onboarding_recoverykit_title),
                subtitle = context.getString(
                    R.string.onboarding_recoverykit_gate_biometric_subtitle,
                ),
                // The negative button is the other door, named: dismissing the prompt drops the
                // user back onto the vault-password well, which is exactly what the label says.
                negativeButtonText = context.getString(
                    R.string.onboarding_recoverykit_gate_password_label,
                ),
            )
            applyBiometricOutcome(granted, generation)
        }
    }

    /**
     * The knowledge-factor challenge. [password] is consumed and wiped by
     * [VaultLockManager.reauthenticateForCurrentVault]; nothing here keeps a copy.
     */
    fun authorize(password: CharArray) {
        if (_state.value.isWorking) return
        val generation = authGeneration
        _state.update { it.copy(isWorking = true, error = null, throttleSeconds = null, notice = null) }
        viewModelScope.launch {
            var error: Int? = null
            var throttleSeconds: Int? = null
            try {
                if (lockManager.reauthenticateForCurrentVault(password)) {
                    if (generation == authGeneration) {
                        _state.update { it.copy(authorized = true, error = null) }
                    }
                } else {
                    error = R.string.onboarding_recoverykit_gate_error_password
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ended: VaultSessionEndedException) {
                // Never `ended.message`: an exception's own text is English, names an internal
                // operation, and is not what the user needs to be told.
                error = R.string.onboarding_recoverykit_gate_error_session_ended
            } catch (_: KdfNeedsMoreMemoryException) {
                // The gate's Argon2id run did not fit beside the open UI (2026-09-14).
                error = R.string.onboarding_recoverykit_gate_error_memory
            } catch (throttled: VaultLockedException) {
                // The same backoff the unlock screen and the master-password change share — this is
                // one more place the vault password can be guessed at. Reports how long, the way
                // UnlockViewModel and SettingsViewModel already do for the same exception — this
                // path used to drop retryAfterMs entirely and tell the user only that the attempt
                // failed, with no way to know when trying again might work.
                throttleSeconds = ((throttled.retryAfterMs + 999) / 1_000).toInt()
            }
            _state.update { it.copy(isWorking = false, error = error, throttleSeconds = throttleSeconds) }
        }
    }

    /**
     * A lock takes the gate, the words, and the prepared kit with it.
     *
     * The mnemonic goes too, not only the flag: leaving 24 words in state behind a gate that has
     * closed would put them back on screen the moment the gate reopened, without them having been
     * re-derived for the new session.
     */
    private fun revokeAuthorization() {
        authGeneration++
        if (!_state.value.authorized && _state.value.mnemonic.isBlank()) return
        lockManager.clearPreparedRecoveryKit()
        _state.value = RecoveryKitUiState()
    }

    /**
     * Shows 24 new words. Does NOT yet revoke the kit the vault already has.
     *
     * There is one recovery block, so storing a new wrapped key kills the old words the instant it
     * lands. This used to happen here, before the user had saved anything: tapping "Create" and
     * then leaving the screen destroyed the printed kit in their drawer and gave them nothing back,
     * silently, with the app reporting success. The words are now committed only once they are
     * demonstrably somewhere the user can reach — see [markSaved].
     *
     * Refused in the duress session by [VaultLockManager.prepareRecoveryKit] itself. A kit minted
     * there would wrap the decoy's key and be indistinguishable from a real one — a recovery
     * document that opens the wrong vault is the exact failure duress exists to prevent.
     */
    fun createKit() {
        if (_state.value.isWorking) return
        // Fail closed. The screen does not compose the Create button until the gate is open, so
        // reaching here unauthorized means the state changed underneath a tap already in flight.
        if (!_state.value.authorized) return
        if (lockManager.lockState.value !is LockState.Unlocked) {
            _state.update { it.copy(error = R.string.onboarding_recoverykit_error_locked) }
            return
        }
        _state.update { it.copy(isWorking = true, error = null, notice = null) }
        viewModelScope.launch {
            val mnemonic = lockManager.prepareRecoveryKit()
            _state.update {
                if (mnemonic == null) {
                    it.copy(
                        isWorking = false,
                        error = R.string.onboarding_recoverykit_error_create_failed,
                    )
                } else {
                    it.copy(
                        isWorking = false,
                        mnemonic = mnemonic,
                        qrData = RecoveryKit.formatForQrCode(mnemonic),
                        qrVisible = false,
                        exported = false,
                        notice = null,
                    )
                }
            }
        }
    }

    /**
     * Makes the shown words the vault's kit, now that they are saved somewhere.
     *
     * Every "I have it" path funnels through here — PDF written, QR photographed — because
     * each of them is the first moment at which revoking the old kit costs the user nothing. If the commit fails, the export still happened but those words open
     * nothing, so it says exactly that rather than congratulating them.
     */
    private suspend fun markSaved(@StringRes successNotice: Int) {
        // A kit commits exactly once. Every save path lands here, and the first success consumes
        // the prepared kit inside VaultLockManager — so a second call gets `false` back for the
        // most benign reason there is (nothing left to commit) and used to paint the alarming
        // "this vault kept its previous Recovery Kit" over a kit that was in fact committed.
        //
        // Reaching this needs no race: after Save-as-PDF succeeds, nothing hides the QR card, so
        // one further tap on "I captured the QR code" does it deterministically. Print was a third
        // door into the same failure (removed 2026-10-13) and never set isWorking at all, which is
        // why guarding the buttons individually would not have been enough — the guard belongs at
        // the funnel.
        if (_state.value.exported) {
            // Already committed by an earlier save path on this screen, so a kit really does exist.
            LocalKeepSettings.setRecoveryKitSaved(context)
            _state.update { it.copy(isWorking = false, qrVisible = false, notice = successNotice) }
            return
        }
        val committed = lockManager.commitPreparedRecoveryKit()
        _state.update {
            if (committed) {
                // Recorded here and nowhere earlier. Writing it before this call looks harmless —
                // the export did happen — but `commitPreparedRecoveryKit` returns false for reasons
                // that are not benign: the vault relocking mid-flow, a changed session epoch, an I/O
                // failure in AtomicVaultWriter. In every one of those the user holds 24 words that
                // open nothing, and a flag set beforehand would clear the Settings warning over a
                // vault with no working kit at all — the exact thing UI/UX spec §3.3 forbids
                // ("nothing but a real export may ever be recorded as a saved kit").
                LocalKeepSettings.setRecoveryKitSaved(context)
                // qrVisible clears on success too: leaving the QR card up after the kit is
                // committed is what put a second commit one tap away.
                it.copy(isWorking = false, exported = true, qrVisible = false, notice = successNotice)
            } else {
                it.copy(
                    isWorking = false,
                    exported = false,
                    error = R.string.onboarding_recoverykit_error_commit_failed,
                )
            }
        }
    }

    @StringRes
    fun suggestedPdfNameRes(): Int = R.string.onboarding_recovery_pdf_filename

    /**
     * Choosing where to write the kit is part of saving it, not leaving the app. Without this the
     * background grace timer runs while the user picks a folder, and the vault relocks before
     * [exportPdf] can commit — losing the one thing this screen exists to produce.
     * See [com.localkeep.core.ui.rememberExternalPickerLauncher], which pairs these for the caller.
     */
    fun onPickerOpened() = lockManager.beginExternalPicker()

    fun onPickerClosed() = lockManager.endExternalPicker()

    fun exportPdf(target: Uri) {
        val mnemonic = _state.value.mnemonic
        if (mnemonic.isBlank()) return
        _state.update { it.copy(isWorking = true, error = null, notice = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    openRecoveryKitDocument { mode ->
                        context.contentResolver.openOutputStream(target, mode)
                    }?.use { out ->
                        RecoveryKitExport.writePdf(context, out, mnemonic)
                    } ?: error("could not open the chosen file")
                }
            }
            if (result.isSuccess) {
                markSaved(R.string.onboarding_recovery_export_pdf_notice)
            } else {
                // This path streams straight into the document the user picked, so a failure part
                // way through `writePdf` leaves a TRUNCATED PDF at a location of their choosing —
                // and a half-written Recovery Kit that someone later trusts is the exact Tier B
                // harm this flow exists to prevent. Reopening in "wt" (write + truncate) empties
                // it: an empty file is obviously not a Recovery Kit, where a partial one is not.
                //
                // Truncate rather than delete, deliberately. The picker can point at a file the
                // user already had, and deleting a document this app did not create is a
                // destructive act on data that was never ours. Emptying the thing we were told to
                // overwrite is the most we are entitled to do. Best-effort: if this fails too, the
                // error copy already tells the user the file is incomplete and to delete it.
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(target, "wt")?.close()
                    }
                }
                _state.update {
                    it.copy(isWorking = false, error = R.string.onboarding_recovery_export_pdf_error)
                }
            }
        }
    }

    fun showQr() {
        _state.update { it.copy(qrVisible = true, error = null) }
    }

    /**
     * Closes the QR card without committing anything.
     *
     * The card had no exit that was not one of two bad ones. The button that opened it is disabled
     * while it is up, so the only controls left were "I captured the QR code" — which runs
     * [markSaved] and revokes the kit already on paper — and leaving the screen, which disposes the
     * composable, runs [forget], and takes the freshly generated 24 words with it. A user who opened
     * the QR merely to look at it therefore either committed a kit they had not saved or lost the
     * words they were part way through transcribing.
     *
     * The onboarding twin already solves this: [OnboardingViewModel.back] intercepts the gesture
     * with "The QR is drawn inline on the Recovery Kit step and has no other dismissal, so back
     * closes it before it moves anywhere." This is that, for the standalone screen.
     *
     * Nothing else in state is touched — not `exported`, not the mnemonic. Hiding the card is a
     * statement about what is on screen, not about whether a kit was saved.
     */
    fun hideQr() {
        _state.update { it.copy(qrVisible = false) }
    }

    fun confirmQrCaptured() {
        _state.update { it.copy(qrVisible = false) }
        viewModelScope.launch {
            markSaved(R.string.onboarding_recovery_qr_captured_notice)
        }
    }

    /**
     * Drops the words from memory on the way out, so leaving the screen ends the exposure.
     *
     * Also discards a kit that was generated and never saved. Nothing was written for it, so the
     * vault's existing kit — the one on paper somewhere — is still the working one.
     */
    fun forget() {
        lockManager.clearPreparedRecoveryKit()
        _state.value = RecoveryKitUiState()
    }
}

/**
 * Opens a picked SAF document for a Recovery Kit write, truncating whatever was there first.
 *
 * `ContentResolver.openOutputStream(uri)` is mode `"w"`, and `"w"` does not imply `O_TRUNC`:
 * `FileUtils.translateModeStringToPosix` maps a leading `w` to `O_WRONLY | O_CREAT` and adds
 * `O_TRUNC` only for an explicit `t`, which is why the platform's own javadoc concedes that `"w"`
 * "may or may not truncate". `CreateDocument` normally hands back a fresh document, but the picker
 * can be pointed at a file the user already had — and overwriting a *longer* existing kit through
 * `"w"` leaves the old file's tail sitting past the new document's `%%EOF`. A PDF reader locates
 * the cross-reference table by scanning backwards from the end of the file, so the kit then either
 * fails to open or resolves to the previous document, while the app has already recorded that a kit
 * exists. The discovering moment is recovery time.
 *
 * The fallback is the other half of the same provider variance. A DocumentsProvider is free to
 * reject a mode it does not implement, so one that accepts `"w"` but throws on `"wt"` would turn a
 * working export into a hard failure. Truncation is worth asking for, not worth losing the export
 * over — so `"wt"` first, `"w"` only when the provider refuses it.
 *
 * Both Recovery Kit writers go through here — [RecoveryKitViewModel.exportPdf] and
 * [OnboardingViewModel.exportRecoveryPdf] — so a third one cannot quietly reintroduce mode `"w"`.
 */
internal inline fun <T> openRecoveryKitDocument(open: (String) -> T): T =
    try {
        open("wt")
    } catch (e: IllegalArgumentException) {
        open("w")
    } catch (e: UnsupportedOperationException) {
        open("w")
    }

/**
 * A strong-biometric-only presence prompt.
 *
 * Deliberately **not** `RevealAuth`, which allows `DEVICE_CREDENTIAL` alongside the biometric, and
 * deliberately not `BiometricUnlock.authenticate`, which requires a `CryptoObject` over the app’s
 * Keystore bio key and would therefore offer this door only to users who have LocalKeep’s biometric
 * *unlock* turned on. The question here is presence, not key material.
 *
 * `setNegativeButtonText` is required (and permitted) precisely because no device credential is
 * allowed; the builder rejects one when it is.
 *
 * The twin of `HiddenItemsViewModel.promptForStrongBiometric` in `:feature:settings`, duplicated
 * rather than shared because the two live in sibling feature modules with no dependency between
 * them. If a third gate needs it, that is the moment it moves into `:core:crypto` beside
 * [com.localkeep.core.crypto.BiometricUnlock] — not before.
 */
private suspend fun promptForStrongBiometric(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
    negativeButtonText: String,
): Boolean = suspendCancellableCoroutine { continuation ->
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancel, Back, lockout and hardware errors alike: authorize nothing.
                if (continuation.isActive) continuation.resume(false)
            }

            // A non-matching finger is not terminal — the prompt stays up until it errors or passes.
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setAllowedAuthenticators(BIOMETRIC_STRONG)
        .setNegativeButtonText(negativeButtonText)
        .build()
    continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    prompt.authenticate(info)
}
