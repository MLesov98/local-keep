package com.localkeep.feature.unlock

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.crypto.BiometricAuthException
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.vault.DeviceKeyStore
import com.localkeep.core.vault.RecoveryKit
import com.localkeep.core.vault.RestoreOutcome
import com.localkeep.core.vault.StaleContainerSweeper
import com.localkeep.core.vault.UnlockPhase
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.VaultLoader
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.feature.backup.VaultImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the derivation is actually doing, e.g. "Argon2id · 3 passes · 256 MiB" once resolved by
 * the composable via `R.string.unlock_kdf_summary` and `R.plurals.unlock_kdf_passes`.
 *
 * [passes] and [memoryMib] are the raw numbers; the ViewModel does not format them into a
 * sentence so it never has to hold a locale-specific string.
 */
data class UnlockKdfSummary(val passes: Int, val memoryMib: Int)

/**
 * A resolved error to show on the unlock screen. Never a raw String — the ViewModel exposes a
 * resource id (plus whatever numeric args the message needs) and the composable resolves it with
 * `stringResource`/`pluralStringResource`, per `GeneratorViewModel.error`.
 */
sealed interface UnlockError {
    /** A fixed message with no arguments. */
    data class Simple(@param:StringRes val messageRes: Int) : UnlockError

    /** A lockout countdown — the plurals resource decides the "second"/"seconds" wording. */
    data class RetryAfter(@param:PluralsRes val pluralsRes: Int, val seconds: Int) : UnlockError

    /**
     * A message that carries the one action which can actually fix it.
     *
     * Exists for exactly one failure — `UnlockResult.VAULT_UNREADABLE` — and the restriction is the
     * point, not an accident of there being only one caller today:
     *
     * - **Never `WRONG_CREDENTIAL`.** A restore control that appears when a password is mistyped is
     *   a control that appears for whoever picked the phone up, on the screen they are already
     *   staring at, after the one action they are certain to perform.
     * - **Never `VAULT_FROM_NEWER_VERSION`.** That container is intact and *newer* than this build;
     *   restoring an older backup over it destroys real data and cannot be undone, which is why
     *   `UnlockResult` splits the two cases at all and why `unlock_error_vault_newer_version` is
     *   deliberately silent about backups.
     *
     * `UnlockErrorActionIsUnreadableOnlyTest` pins both exclusions.
     */
    data class Actionable(
        @param:StringRes val messageRes: Int,
        @param:StringRes val actionRes: Int,
    ) : UnlockError
}

/**
 * One rolling snapshot offered by the pre-unlock restore screen.
 *
 * [path] is the absolute file path and never reaches the UI — `vault-<UTC stamp>.enc` is an
 * internal storage id, and repo law keeps paths out of user-facing text. [savedAtMillis] is what
 * the row is labelled with, formatted by the composable so the format follows the device locale.
 */
data class RestoreSnapshot(val path: String, val savedAtMillis: Long)

/**
 * What the user picked to restore from.
 *
 * [key] is the proof's identity half — see `VerifiedBackup`. It is a file path for a ring snapshot
 * and the document URI for a picked file, and it never reaches the UI: repo law keeps paths and
 * URIs out of user-facing text, and a SAF URI can carry a provider-chosen name.
 */
sealed interface RestoreChoice {
    val key: String

    /** A rolling snapshot this device wrote. */
    data class Ring(val snapshot: RestoreSnapshot) : RestoreChoice {
        override val key: String get() = snapshot.path
    }

    /**
     * A `.vault` export the user picked through the document picker.
     *
     * The URI is held as a String so the UI state stays free of platform types; it is parsed back
     * only at the two moments bytes are read. Attacker-supplied by definition — the file is chosen
     * by whoever is holding the phone — which is why nothing downstream of this treats it more
     * kindly than a snapshot.
     */
    data class PickedFile(val uri: String) : RestoreChoice {
        override val key: String get() = uri
    }
}

/** Which step of the pre-unlock restore the user is on. */
enum class RestorePhase {
    /** Choosing which backup to restore from. */
    CHOOSE,

    /** A backup is chosen; the screen is asking for the password that opens *it*. */
    PROVE,

    /** The password opened the backup; the screen is asking permission to replace the vault. */
    CONFIRM,

    /** The vault has been replaced. The user unlocks normally from here. */
    DONE,
}

/**
 * The pre-unlock restore flow (spec §12.5 MUST: "on failure, surface a restore-from-backup flow").
 *
 * Held inside [UnlockUiState] rather than behind a nav route on purpose. A route is reachable by
 * anything that can navigate; this state is reachable only by the one branch of [publishResult]
 * that runs when the container will not open, so there is no second way in for an attacker holding
 * the phone to find.
 */
data class RestoreUiState(
    val phase: RestorePhase = RestorePhase.CHOOSE,
    val snapshots: List<RestoreSnapshot> = emptyList(),
    val selected: RestoreChoice? = null,
    val password: String = "",
    val isWorking: Boolean = false,
    val error: UnlockError? = null,
) {
    /** Redacted for the same reason [UnlockUiState] is: this one holds a master password too. */
    override fun toString(): String = "RestoreUiState(redacted)"
}

data class UnlockUiState(
    val password: String = "",
    val error: UnlockError? = null,
    val unlocked: Boolean = false,
    val biometricAvailable: Boolean = false,
    /**
     * Biometric unlock is enrolled but deliberately suspended by a panic lock. Distinct from
     * "unavailable": the user turned it on, and silently hiding the button would look like a bug.
     */
    val biometricBlockedByPanicLock: Boolean = false,
    val isWorking: Boolean = false,
    /**
     * Shown under the working indicator. Argon2id at these settings genuinely takes tens of
     * seconds on a mid-range phone, and a bare spinner for that long is indistinguishable from a
     * hang — the user's only options are to wait blindly or kill the app. Naming the parameters
     * turns the wait into evidence that the vault is expensive to attack. Null when the header
     * cannot be read, in which case the line is simply omitted rather than guessed at.
     */
    val kdfSummary: UnlockKdfSummary? = null,
    /** The Recovery Kit sheet is open, and the password field is out of the way. */
    val recoveryVisible: Boolean = false,
    /** The 24 words as typed. Free text, because a 24-field form is a worse way to type them. */
    val recoveryWords: String = "",
    /** The Recovery Kit QR scanner is open over the words field. */
    val showRecoveryQrScanner: Boolean = false,
    /**
     * How far the current unlock has got, 0f..1f, or null when nothing is running.
     *
     * Cleared on every exit from the working state — success, wrong password, throttle, failure.
     * Leaving a finished percentage on screen would let whoever picks the phone up next learn that
     * an unlock had been attempted, and how far it got, without typing anything.
     */
    val unlockProgress: Float? = null,
    /** Which sentence the overlay is showing. Null while [unlockProgress] is null. */
    val unlockPhase: UnlockPhase? = null,
    /**
     * Non-null only while the pre-unlock restore screen is open. See [RestoreUiState] for why this
     * is state on the unlock screen and not a route of its own.
     */
    val restore: RestoreUiState? = null,
    /**
     * A counter, not a flag: each increment asks the screen to put the caret in the password well
     * once. A boolean would have to be reset by the screen after it acted, and a state that needs
     * its consumer to clear it is a state that fires again on the next rotation.
     */
    val passwordFocusRequests: Int = 0,
) {
    /**
     * Redacted, like `ItemEditUiState` and `ImportUiState` (V27, 2026-09-12). This state holds the
     * typed master password and the full 24-word Recovery Kit phrase, and the compiler-generated
     * `toString()` would print both verbatim into a crash report, a `Log.d` added during a
     * debugging session, or an exception message that interpolates the state. Nothing interpolates
     * it today; this is the guard for the next person who does (added
     * 2026-09-13).
     */
    override fun toString(): String = "UnlockUiState(redacted)"
}

@HiltViewModel
class UnlockViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val lockManager: VaultLockManager,
    private val keystoreManager: KeystoreManager,
    private val biometricUnlock: BiometricUnlock,
    /**
     * Read-only, and used for exactly one call: `readContainerBytes`, which hands back the vault
     * container inside a picked `.vault` without trying a password or writing anything. The bundle
     * format is `internal` to `:feature:backup`, so re-implementing it here would be a second copy
     * of a format the app must agree with itself about.
     */
    private val vaultImporter: VaultImporter,
) : ViewModel() {
    private val _state = MutableStateFlow(
        UnlockUiState(
            biometricAvailable = biometricAvailable(),
            biometricBlockedByPanicLock = biometricBlockedByPanicLock(),
        ),
    )
    val state: StateFlow<UnlockUiState> = _state.asStateFlow()

    /**
     * The ticker behind the lockout sentence, so a later lockout can replace an earlier one. It
     * needs no `onCleared` override: it runs in [viewModelScope], which the lifecycle library
     * cancels when this view-model is cleared.
     */
    private var retryCountdownJob: Job? = null

    /**
     * The backup [checkSelectedBackup] last proved openable — **by content**, not only by name —
     * and the only bytes [confirmRestore] will write.
     *
     * The digest is the load-bearing half. Binding the proof to the path alone says "a file with
     * this name opened", and the file that gets written is read from disk again a moment later; a
     * path-only proof authorises whatever is at that path *then*, which is not what the user
     * agreed to. Re-hashing at the write closes that gap, and it is also what makes the guard
     * testable — with a path-only check, the phase guard hides it and a test that thinks it is
     * proving the authorisation is proving nothing (measured, 2026-09-13).
     *
     * Kept off [UnlockUiState] deliberately: it is an authorisation, not something to render, and
     * state that is rendered is state that gets saved, logged and copied. Cleared on every
     * selection change, every cancel and every exit, so a proof can never outlive the choice it
     * was about.
     */
    private var verifiedBackup: VerifiedBackup? = null

    /** Failed pre-unlock restore password attempts, and the resulting lockout. See the KDoc on
     * [checkSelectedBackup] for why this mirrors `VaultLockManager`'s backoff rather than reusing
     * it. */
    private var restoreFailures = 0
    private var restoreLockoutUntilMs = 0L

    /**
     * The ticker behind the restore screen's lockout sentence, so a later lockout replaces an
     * earlier one. Needs no `onCleared` override for the same reason [retryCountdownJob] does not:
     * it runs in [viewModelScope].
     */
    private var restoreCountdownJob: Job? = null

    init {
        // Filled in asynchronously rather than in the state above. It is a *header* read, but
        // `VaultLoader.kdfParams` gets at the header by reading the whole container — and the
        // container is padded to a plaintext bucket, so "read the two Argon2 numbers" is really
        // "read every byte of the vault file" on whichever thread constructs the view-model, which
        // is the main one. The line it produces is decoration under a spinner; it must not be the
        // reason the unlock screen is slow to appear. Absent until it arrives, which the state
        // already models as null.
        viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) { kdfSummary() }
            _state.update { it.copy(kdfSummary = summary) }
        }
        // A lockout outlives the process — the backoff is persisted — so a screen opening into one
        // shows its countdown at once. It used to look like an ordinary lock screen with Unlock
        // enabled until a submit came back refused (QA 2026-10-03). Off the main thread: the
        // backoff is a SharedPreferences read.
        viewModelScope.launch {
            val remaining = withContext(Dispatchers.IO) { lockManager.knowledgeLockoutRemainingMs() }
            if (remaining <= 0L || _state.value.isWorking || _state.value.error != null) return@launch
            _state.update {
                it.copy(
                    error = UnlockError.RetryAfter(
                        R.plurals.unlock_retry_after_seconds,
                        ((remaining + 999) / 1_000).toInt(),
                    ),
                )
            }
            startRetryCountdown(R.plurals.unlock_retry_after_seconds, remaining)
        }
    }

    /**
     * Needs no key. `memoryKib` is printed in MiB because "262144 KiB" is a number nobody can hold;
     * "256 MiB" is a quantity of phone.
     */
    private fun kdfSummary(): UnlockKdfSummary? = VaultLoader.kdfParams(context)?.let { params ->
        UnlockKdfSummary(passes = params.iterations, memoryMib = params.memoryKib / 1024)
    }

    fun onPasswordChanged(value: String) {
        _state.update { it.copy(password = value, error = null) }
    }

    fun unlockWithPassword() {
        val current = _state.value
        if (current.isWorking || current.password.isEmpty()) return
        val password = current.password.toCharArray()
        _state.update {
            it.copy(
                password = "",
                error = null,
                isWorking = true,
                unlockProgress = 0f,
                unlockPhase = UnlockPhase.READING,
            )
        }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    // Called from Dispatchers.Default; MutableStateFlow.update is safe from any
                    // thread. `maxOf` because a damaged primary sends the loop round a second
                    // candidate, which legitimately re-reports an earlier checkpoint — a percentage
                    // that walks backwards reads as a bug even when the underlying work is honest.
                    lockManager.unlock(password) { progress ->
                        _state.update {
                            if (!it.isWorking) return@update it
                            it.copy(
                                unlockProgress = maxOf(it.unlockProgress ?: 0f, progress.fractionDone),
                                unlockPhase = progress.phase,
                            )
                        }
                    }
                }
            } catch (locked: VaultLockedException) {
                _state.update {
                    it.copy(
                        error = UnlockError.RetryAfter(
                            R.plurals.unlock_retry_after_seconds,
                            ((locked.retryAfterMs + 999) / 1_000).toInt(),
                        ),
                        isWorking = false,
                        unlockProgress = null,
                        unlockPhase = null,
                    )
                }
                startRetryCountdown(R.plurals.unlock_retry_after_seconds, locked.retryAfterMs)
                return@launch
            } catch (e: CancellationException) {
                // Every generic handler in this file rethrows cancellation first. Argon2id runs for
                // tens of seconds, so the odds of a scope cancellation landing inside one of these
                // calls are not small — and a cancelled derivation is not a failed one. Reporting it
                // as "Unlock failed" would tell someone holding the right password to type it again.
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        error = UnlockError.Simple(R.string.unlock_error_generic_failed),
                        isWorking = false,
                        unlockProgress = null,
                        unlockPhase = null,
                    )
                }
                return@launch
            }
            publishResult(result)
        }
    }

    fun showRecovery() {
        _state.update { it.showingRecovery() }
    }

    fun hideRecovery() {
        _state.update {
            it.copy(
                recoveryVisible = false,
                recoveryWords = "",
                showRecoveryQrScanner = false,
                error = null,
            )
        }
    }

    fun onRecoveryWordsChanged(value: String) {
        _state.update { it.copy(recoveryWords = value, error = null) }
    }

    fun showRecoveryQrScanner() {
        _state.update { it.copy(showRecoveryQrScanner = true, error = null) }
    }

    fun dismissRecoveryQrScanner() {
        _state.update { it.copy(showRecoveryQrScanner = false) }
    }

    /**
     * What the camera read, run through [RecoveryKit.parseQrCode].
     *
     * `parseQrCode` was written and tested the same day the kit's export screen started drawing
     * the QR it now reads back, and then never called from anywhere — the printed QR was
     * decoration beside the mnemonic grid, unreadable by anything in this app or out of it. This
     * is the other half of that redemption: [unlockWithRecoveryKit] fixed the write side in
     * 2026-08-30, this fixes the read side.
     *
     * A payload that is not a Recovery Kit — a stray QR code, a half-read frame, someone else's
     * TOTP secret — must not land in the words field silently or verbatim: `parseQrCode` already
     * validates the header and the BIP-39 checksum, so a `null` here means the code genuinely
     * is not this vault's kit, not that the scan failed to focus. The raw payload is never shown;
     * echoing it back would put an unrelated QR's content on screen with no way for the user to
     * tell whether it was safe to have typed there.
     */
    fun scanRecoveryQrCode(raw: String) {
        val mnemonic = RecoveryKit.parseQrCode(raw)
        _state.update {
            if (mnemonic != null) {
                it.copy(
                    recoveryWords = mnemonic,
                    showRecoveryQrScanner = false,
                    error = null,
                )
            } else {
                it.copy(
                    showRecoveryQrScanner = false,
                    error = UnlockError.Simple(R.string.unlock_recovery_qr_invalid_message),
                )
            }
        }
    }

    /**
     * Opens the vault from a Recovery Kit.
     *
     * This is the redemption path the kit never had: [VaultLockManager.unlockWithRecoveryKit] was
     * written and tested and then never called from anywhere, so the one artefact in the product
     * that first-run made mandatory could not be used.
     *
     * The words go through the same backoff as the master password. They are a guessable secret
     * typed by a human — a weaker gate would make the kit the cheapest way in.
     */
    fun unlockWithRecoveryKit() {
        val current = _state.value
        if (current.isWorking) return
        val words = current.recoveryWords.trim()
        if (words.isBlank()) return
        _state.update { it.copy(error = null, isWorking = true) }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.Default) {
                    lockManager.unlockWithRecoveryKit(words)
                }
            } catch (locked: VaultLockedException) {
                _state.update {
                    it.copy(
                        error = UnlockError.RetryAfter(
                            R.plurals.unlock_retry_after_seconds,
                            ((locked.retryAfterMs + 999) / 1_000).toInt(),
                        ),
                        isWorking = false,
                        unlockProgress = null,
                        unlockPhase = null,
                    )
                }
                startRetryCountdown(R.plurals.unlock_retry_after_seconds, locked.retryAfterMs)
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        error = recoveryFailureMessage(),
                        isWorking = false,
                        unlockProgress = null,
                        unlockPhase = null,
                    )
                }
                return@launch
            }
            if (result == UnlockResult.WRONG_CREDENTIAL) {
                // Not "wrong password" — nothing about a password was tested here. And not "this
                // vault has no kit" either, which is deliberately indistinguishable: the vault file
                // does not answer that question for anyone holding it.
                // The words survive the rejection. The error asks the user to check the order and
                // the spelling, and clearing the field made that impossible to act on: a 24-word
                // phrase transcribed from paper fails most often on a single transposition, and
                // this is the flow someone reaches only after losing their password. Retaining them
                // exposes nothing new either — they are already on screen in the clear while being
                // typed, by design, for exactly the same transcription reason. Found by the F14 QA
                // persona, 2026-08-30. They are still cleared on success, just below.
                _state.update {
                    it.copy(
                        error = recoveryFailureMessage(),
                        isWorking = false,
                        unlockProgress = null,
                        unlockPhase = null,
                    )
                }
                return@launch
            }
            _state.update { it.copy(recoveryWords = "") }
            publishResult(result)
        }
    }

    /**
     * Walks the lockout sentence down to zero and then takes it off the screen.
     *
     * The caller has already put the first value on screen; this keeps it honest. The `takeWhile`
     * is how the ticker stops when it is no longer describing anything: every other exit from the
     * lockout — typing in the field, a new attempt, a successful unlock, a different error —
     * replaces or clears `error`, and the next tick then ends the collection rather than writing
     * over whatever took its place.
     *
     * Presentation only. `VaultLockManager.checkThrottle` stays the sole authority on whether an
     * attempt is allowed; clearing the sentence permits nothing.
     */
    private fun startRetryCountdown(@PluralsRes pluralsRes: Int, retryAfterMs: Long) {
        retryCountdownJob?.cancel()
        retryCountdownJob = viewModelScope.launch {
            retryCountdown(retryAfterMs)
                .takeWhile { _state.value.error is UnlockError.RetryAfter }
                .collect { seconds ->
                    _state.update {
                        if (seconds > 0) {
                            it.copy(error = UnlockError.RetryAfter(pluralsRes, seconds))
                        } else {
                            it.copy(error = null)
                        }
                    }
                }
        }
    }

    private fun recoveryFailureMessage(): UnlockError =
        UnlockError.Simple(R.string.unlock_recovery_failed_message)

    fun unlockWithBiometric(activity: FragmentActivity) {
        if (_state.value.isWorking) return
        if (lockManager.isBiometricUnlockBlocked) {
            // Say what actually happened. The unlock below would authenticate the fingerprint and
            // then fail, which reads as a broken sensor rather than the security state the user
            // asked for.
            _state.update {
                it.copy(
                    error = UnlockError.Simple(R.string.unlock_biometric_panic_lock_message),
                    biometricBlockedByPanicLock = true,
                    isWorking = false,
                    unlockProgress = null,
                    unlockPhase = null,
                )
            }
            return
        }
        val wrappedDek = DeviceKeyStore.load(context)
        if (wrappedDek == null) {
            _state.update {
                it.copy(error = UnlockError.Simple(R.string.unlock_error_biometric_not_enabled))
            }
            return
        }
        _state.update { it.copy(error = null, isWorking = true) }
        viewModelScope.launch {
            try {
                val cipher = biometricUnlock.decryptCipher(wrappedDek.nonce)
                val authenticated = biometricUnlock.authenticate(
                    activity = activity,
                    cipher = cipher,
                    // Not composable content — this text goes straight into the system
                    // BiometricPrompt dialog, so there is no `stringResource` call site to
                    // resolve it at. `context` is already injected for DeviceKeyStore/VaultLoader.
                    title = context.getString(R.string.unlock_biometric_prompt_title),
                    subtitle = context.getString(R.string.unlock_biometric_prompt_subtitle),
                    negativeButtonText = context.getString(
                        com.localkeep.core.ui.R.string.core_ui_biometric_negative_button,
                    ),
                )
                val result = withContext(Dispatchers.Default) {
                    lockManager.unlockWithBiometric(authenticated)
                }
                publishResult(result)
            } catch (locked: VaultLockedException) {
                // Biometrics has its own backoff, separate from the password's. Say which one is
                // waiting, and — the part that matters — that the password is still available.
                _state.update {
                    it.copy(
                        error = UnlockError.RetryAfter(
                            R.plurals.unlock_biometric_locked_retry,
                            ((locked.retryAfterMs + 999) / 1_000).toInt(),
                        ),
                        biometricAvailable = biometricAvailable(),
                        isWorking = false,
                        unlockProgress = null,
                        unlockPhase = null,
                    )
                }
                startRetryCountdown(R.plurals.unlock_biometric_locked_retry, locked.retryAfterMs)
            } catch (e: CancellationException) {
                // Note this is *not* the user dismissing the prompt: BiometricUnlock.authenticate
                // reports that through onAuthenticationError as a BiometricAuthException, which the
                // handler below still catches and still reports. Only a genuine scope cancellation
                // reaches here.
                throw e
            } catch (_: KeyPermanentlyInvalidatedException) {
                // The Keystore key was invalidated by an enrolment change (new fingerprint added,
                // all fingerprints removed, etc.) — see KeystoreManager.getOrCreateBioKey, which
                // deliberately mints this key with per-use biometric auth for exactly that reason.
                // Recreating or re-wrapping the key here would erase that protection, so instead
                // the stale wrap is discarded: DeviceKeyStore only holds the wrapped DEK, never the
                // vault, so clearing it cannot lose data, and the master password still opens it.
                onBiometricKeyInvalidated()
            } catch (_: IllegalStateException) {
                // getOrCreateBioKey's own check()s throw this when biometrics were removed
                // entirely (device no longer secure, or BIOMETRIC_STRONG no longer available) —
                // same "nothing left to retry" situation as an invalidated key.
                onBiometricKeyInvalidated()
            } catch (e: BiometricAuthException) {
                // The prompt's own negative button is "Use master password", and dismissing the
                // prompt is the other way a user says the same thing. Neither is a failure: the
                // user took an escape hatch the prompt offered them, and the password field they
                // asked for is already on screen behind it. Reporting that as
                // `unlock_error_biometric_incomplete` put a coral banner over a deliberate,
                // correct choice — and LkColors.kt's rule for coral is "is the user's data less
                // safe than they believe", which this is not.
                //
                // The code is what separates the two cases, and it was already being carried:
                // BiometricUnlock.onAuthenticationError puts `errorCode` on the exception, and
                // nothing read it until now. Every other code — lockout, hardware failure, a
                // result with no cipher (-1) — still reports, because those really did fail.
                val userChoseAnotherWay = biometricErrorIsUserChoice(e.code)
                // A transient lockout is the framework's own 30-second cool-off after too many
                // rejected fingerprints, and it is the one failure here that undoes itself.
                val cooling = biometricErrorIsTransientLockout(e.code)
                _state.update {
                    it.copy(
                        error = when {
                            userChoseAnotherWay -> null
                            // Named, not generic. "Biometric unlock was not completed" describes a
                            // sensor that failed and invites an immediate retry, which is the one
                            // thing that cannot work while the cool-off is running.
                            cooling -> UnlockError.Simple(R.string.unlock_error_biometric_lockout)
                            else -> UnlockError.Simple(R.string.unlock_error_biometric_incomplete)
                        },
                        // Left alone while the sensor is cooling off, and this is the whole of
                        // F25's fix: `isStrongBiometricAvailable()` answers *false* for the length
                        // of that window, so recomputing here removed `Unlock with biometrics`
                        // from the screen — and nothing recomputed it afterwards, so the button
                        // stayed gone for the life of the process, through a HOME and back, on a
                        // block the framework had already released (device, 2026-09-20). The
                        // enrolment is untouched; only the sensor is busy.
                        biometricAvailable = if (cooling) it.biometricAvailable else biometricAvailable(),
                        // "Use master password" is a request for the password well, and the prompt
                        // closing leaves focus nowhere: the keyboard is down and the next
                        // keystroke goes into no field at all.
                        passwordFocusRequests = nextPasswordFocusRequests(
                            it.passwordFocusRequests,
                            userChoseAnotherWay,
                        ),
                        isWorking = false,
                        unlockProgress = null,
                        unlockPhase = null,
                    )
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        error = UnlockError.Simple(R.string.unlock_error_biometric_incomplete),
                        biometricAvailable = biometricAvailable(),
                        isWorking = false,
                        unlockProgress = null,
                        unlockPhase = null,
                    )
                }
            }
        }
    }

    /**
     * The device-wrapped key can never be used again (see the callers above): drop it so
     * [biometricAvailable] stops offering a button that will fail identically on every retry, and
     * route the user to the one thing that still works. Deletes only the wrap file and its
     * Keystore entry — never the vault — so the master password remains a complete route in.
     */
    private fun onBiometricKeyInvalidated() {
        DeviceKeyStore.delete(context)
        keystoreManager.deleteKey(KeystoreManager.BIO_DEK_ALIAS)
        _state.update {
            it.copy(
                error = UnlockError.Simple(R.string.unlock_error_biometric_unavailable),
                biometricAvailable = biometricAvailable(),
                isWorking = false,
                unlockProgress = null,
                unlockPhase = null,
            )
        }
    }

    /**
     * Turns an [UnlockResult] into what the screen says.
     *
     * Every failure used to read "Wrong password", including a cancelled fingerprint and a vault
     * file that would not decode. That is the worst possible advice in both cases: it tells the
     * user to retype a password that was never the problem, and hides the one action that would
     * actually help.
     */
    private fun publishResult(result: UnlockResult) {
        if (result.opened) {
            _state.update {
                it.copy(
                    unlocked = true,
                    error = null,
                    isWorking = false,
                    unlockProgress = null,
                    unlockPhase = null,
                )
            }
            return
        }
        val error = unlockErrorFor(result) ?: return
        _state.update {
            it.copy(
                error = error,
                biometricBlockedByPanicLock = biometricBlockedByPanicLock(),
                biometricAvailable = biometricAvailable(),
                isWorking = false,
                unlockProgress = null,
                unlockPhase = null,
            )
        }
    }

    /**
     * Opens the pre-unlock restore flow — and refuses unless the screen is currently showing the
     * one error that offers it.
     *
     * The guard is the security boundary of this whole feature, not a tidiness check. Anyone
     * holding the phone reaches the unlock screen, so the flow has to be unreachable from every
     * state except "this container will not open at all" — which is the only state in which
     * replacing the container can destroy nothing, because there is nothing readable to destroy.
     * [UnlockError.Actionable] is produced by exactly one branch of [publishResult], so testing for
     * it here is testing for `UnlockResult.VAULT_UNREADABLE` without the view-model having to
     * remember a second copy of the same fact.
     */
    fun openRestore() {
        if (_state.value.error !is UnlockError.Actionable) return
        if (_state.value.restore != null) return
        _state.update { it.copy(restore = RestoreUiState(isWorking = true)) }
        viewModelScope.launch {
            val snapshots = withContext(Dispatchers.IO) {
                PreUnlockRestore
                    .snapshotsNewestFirst(java.io.File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR))
                    .map { RestoreSnapshot(path = it.absolutePath, savedAtMillis = it.lastModified()) }
            }
            _state.update { state ->
                val restore = state.restore ?: return@update state
                state.copy(restore = restore.copy(snapshots = snapshots, isWorking = false))
            }
        }
    }

    /** Leaves the restore flow, dropping the typed password with it. */
    fun closeRestore() {
        verifiedBackup = null
        _state.update { it.copy(restore = null) }
    }

    fun selectSnapshot(snapshot: RestoreSnapshot) = select(RestoreChoice.Ring(snapshot))

    /**
     * A `.vault` the user picked through the document picker.
     *
     * Nothing is read here. The picked file goes through the identical gate a snapshot does —
     * slot 0, region 0 authenticated, quarantine before any write, and the content-bound proof
     * re-checked at the write. That last one earns its keep on this path in particular: a content
     * URI is backed by a provider that is free to answer a second read with different bytes.
     */
    fun selectPickedFile(uri: Uri) = select(RestoreChoice.PickedFile(uri.toString()))

    private fun select(choice: RestoreChoice) {
        verifiedBackup = null
        _state.update { state ->
            val restore = state.restore ?: return@update state
            state.copy(
                restore = restore.copy(
                    phase = RestorePhase.PROVE,
                    selected = choice,
                    password = "",
                    error = null,
                ),
            )
        }
    }

    /**
     * The document picker is opening. Paired with [onPickerClosed] on every path including cancel.
     *
     * The vault is locked while this screen is up, so there is no auto-lock timer to suspend today
     * — `VaultLockManager` only reschedules when the state is `Unlocked`. Wired anyway, because the
     * alternative is a launcher that silently differs from every other picker in the app, and the
     * cost is two counter increments.
     */
    fun onPickerOpened() = lockManager.beginExternalPicker()

    fun onPickerClosed() = lockManager.endExternalPicker()

    /** Back from the password step to the list, without leaving the flow. */
    fun deselectSnapshot() {
        verifiedBackup = null
        _state.update { state ->
            val restore = state.restore ?: return@update state
            state.copy(
                restore = restore.copy(
                    phase = RestorePhase.CHOOSE,
                    selected = null,
                    password = "",
                    error = null,
                ),
            )
        }
    }

    fun onRestorePasswordChanged(value: String) {
        _state.update { state ->
            val restore = state.restore ?: return@update state
            state.copy(restore = restore.copy(password = value, error = null))
        }
    }

    /**
     * Tries the typed password against the chosen backup. **Writes nothing.**
     *
     * This is the proof step, and it is the reason the flow is safe to expose on a locked screen:
     * until it succeeds there is no path to [confirmRestore], so a person holding the phone without
     * a password can select backups all day and change nothing.
     *
     * Throttled with the same shape as `VaultLockManager`'s own backoff — five free attempts, then
     * an exponential lockout capped at five minutes — because a rolling snapshot is sealed under
     * the master password of the moment it was taken, so an unthrottled check here would be an
     * oracle for the very secret the unlock screen throttles. The lock manager's counters are
     * private to it and process-local (`lockoutUntilMs` is a plain map keyed on
     * `SystemClock.elapsedRealtime`), so a local counter is exactly as strong as borrowing theirs
     * would be: both reset when the process dies.
     */
    fun checkSelectedBackup() {
        val current = _state.value
        val restore = current.restore ?: return
        val selected = restore.selected ?: return
        if (restore.isWorking || restore.password.isEmpty()) return

        val waitMs = restoreLockoutUntilMs - SystemClock.elapsedRealtime()
        if (waitMs > 0) {
            updateRestore {
                it.copy(
                    error = UnlockError.RetryAfter(
                        R.plurals.unlock_retry_after_seconds,
                        ((waitMs + 999) / 1_000).toInt(),
                    ),
                )
            }
            startRestoreRetryCountdown(waitMs)
            return
        }

        val password = restore.password.toCharArray()
        verifiedBackup = null
        updateRestore { it.copy(password = "", isWorking = true, error = null) }
        viewModelScope.launch {
            val bytes = readChoice(selected)
            val outcome = if (bytes == null) {
                Zeroizer.wipe(password)
                BackupCheck.UNREADABLE
            } else {
                try {
                    withContext(Dispatchers.Default) { PreUnlockRestore.check(bytes, password) }
                } catch (e: CancellationException) {
                    Zeroizer.wipe(password)
                    throw e
                } catch (_: Exception) {
                    Zeroizer.wipe(password)
                    BackupCheck.UNREADABLE
                }
            }
            when (outcome) {
                BackupCheck.OPENS -> {
                    restoreFailures = 0
                    restoreLockoutUntilMs = 0L
                    // The digest is of the bytes that were actually opened, not of the file name
                    // they came from. See [VerifiedBackup].
                    verifiedBackup = VerifiedBackup(selected.key, sha256(bytes!!))
                    updateRestore { it.copy(phase = RestorePhase.CONFIRM, isWorking = false) }
                }
                BackupCheck.WRONG_PASSWORD -> {
                    recordRestoreFailure()
                    updateRestore {
                        it.copy(
                            isWorking = false,
                            error = UnlockError.Simple(R.string.unlock_restore_error_wrong_password),
                        )
                    }
                }
                // Not counted against the backoff: the password was never the thing that failed, so
                // counting it would throttle a user out of trying the *next* snapshot in the ring —
                // which is the whole point of there being five of them.
                BackupCheck.UNREADABLE -> updateRestore {
                    it.copy(
                        isWorking = false,
                        error = UnlockError.Simple(R.string.unlock_restore_error_unreadable),
                    )
                }
                BackupCheck.NEWER_VERSION -> updateRestore {
                    it.copy(
                        isWorking = false,
                        error = UnlockError.Simple(R.string.unlock_restore_error_newer_version),
                    )
                }
            }
        }
    }

    /** Back from the confirmation to the password step, forgetting the proof. */
    fun cancelRestoreConfirm() {
        verifiedBackup = null
        updateRestore { it.copy(phase = RestorePhase.PROVE, error = null) }
    }

    /**
     * Replaces the live container with the backup the user just proved they can open.
     *
     * Order is the safety argument and it is not negotiable:
     *
     * 1. refuse unless *this exact file* is the one [checkSelectedBackup] verified;
     * 2. copy every committed container aside, and abort the whole thing if that fails;
     * 3. only then hand the bytes to `VaultLockManager.restoreVaultFile`, which does the atomic
     *    write, reconciles the Recovery Kit block and re-locks.
     *
     * Step 2 is what keeps this from being a pre-unlock *overwrite*. `AtomicVaultWriter.write`
     * rotates `vault.enc` onto `vault.enc.bak` with `REPLACE_EXISTING`, so the damaged `.bak` — as
     * plausible a recovery candidate as the primary — is destroyed by the restore itself unless it
     * is copied first.
     */
    fun confirmRestore() {
        val restore = _state.value.restore ?: return
        val selected = restore.selected ?: return
        if (restore.isWorking || restore.phase != RestorePhase.CONFIRM) return
        val verified = verifiedBackup ?: return
        if (verified.key != selected.key) return

        updateRestore { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            val bytes = readChoice(selected)
            // Re-read and re-hashed rather than carried over from the check. What the user
            // authorised was a container that opened under a password they typed; what gets written
            // is whatever is at that path now. Those are the same thing almost always and not by
            // construction, and "almost always" is not a property a destructive write may rest on.
            if (bytes == null || !sha256(bytes).contentEquals(verified.sha256)) {
                failConfirmation(R.string.unlock_restore_error_unreadable)
                return@launch
            }
            val preserved = withContext(Dispatchers.IO) {
                PreUnlockRestore.preserveDamagedContainers(context, quarantineStamp())
            }
            if (!preserved) {
                failConfirmation(R.string.unlock_restore_error_not_kept)
                return@launch
            }
            val outcome = try {
                lockManager.restoreVaultFile(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (outcome == RestoreOutcome.OK) {
                verifiedBackup = null
                val summary = withContext(Dispatchers.IO) { kdfSummary() }
                _state.update { state ->
                    val current = state.restore ?: return@update state
                    state.copy(
                        // The KDF line describes the container that is now on disk, and that is a
                        // different container than the one this screen loaded with.
                        kdfSummary = summary,
                        restore = current.copy(phase = RestorePhase.DONE, isWorking = false),
                    )
                }
            } else {
                failConfirmation(R.string.unlock_restore_error_failed)
            }
        }
    }

    /**
     * Reports a failed replacement, and **leaves the confirmation** to do it.
     *
     * The phase change is the whole point. `LkDialog` is a Compose `Dialog` in its own window, and
     * the screen renders it on `phase == CONFIRM`, so a failure that only set `error` wrote its
     * sentence into the screen *behind* the dialog. Measured on `emulator-5554` (2026-09-13) with
     * the quarantine blocked: after "Replace vault" the dump held the dialog, unchanged, and no
     * error text anywhere in the tree. The user sees nothing happen and taps again.
     *
     * The proof is dropped with it. Two of the three failures mean the bytes changed under us, so
     * the proof is void; the third is a storage condition that a retry inside the app cannot
     * change. One rule — the write happens only immediately after a proof — is worth more than
     * saving one password entry on a path taken once in a lifetime.
     */
    private fun failConfirmation(@StringRes messageRes: Int) {
        verifiedBackup = null
        updateRestore {
            it.copy(
                phase = RestorePhase.PROVE,
                isWorking = false,
                error = UnlockError.Simple(messageRes),
            )
        }
    }

    /** Leaves the flow after a successful restore, clearing the stale unlock error with it. */
    fun finishRestore() {
        verifiedBackup = null
        _state.update { it.copy(restore = null, error = null, password = "") }
    }

    private fun updateRestore(block: (RestoreUiState) -> RestoreUiState) {
        _state.update { state ->
            val restore = state.restore ?: return@update state
            state.copy(restore = block(restore))
        }
    }

    /**
     * A proof that one specific backup opened: which file, and what was in it.
     *
     * Private and never rendered — see the field's KDoc for why the digest is half of it.
     */
    private class VerifiedBackup(val key: String, val sha256: ByteArray)

    private fun sha256(bytes: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * Ticks the restore screen's lockout sentence down, and clears it when the wait is over.
     *
     * `startRetryCountdown` cannot be reused: it writes `UnlockUiState.error`, and this lockout
     * lives in `restore.error`, a different field on a different screen. Without this the number was
     * computed once in the branch above and never again — "Try again in 43 seconds" for as long as
     * the user cared to look, with no way to learn the wait had ended except to guess, which a wrong
     * guess extends. That is the exact defect `retryCountdown`'s own KDoc was written about, and it
     * was reintroduced one screen over.
     *
     * Shares `retryCountdown` and the `unlock_retry_after_seconds` plural with the unlock screen, so
     * the two lockouts cannot drift in wording or in tick rate. Presentation only: whether an
     * attempt is allowed stays [checkSelectedBackup]'s own comparison against
     * [restoreLockoutUntilMs], so the final emission clears the sentence and grants nothing.
     */
    private fun startRestoreRetryCountdown(retryAfterMs: Long) {
        restoreCountdownJob?.cancel()
        restoreCountdownJob = viewModelScope.launch {
            retryCountdown(retryAfterMs)
                // Stops the moment the sentence is no longer a lockout — the user left the flow,
                // picked another backup, or typed again and got a different answer. Mirrors
                // startRetryCountdown's guard, against this screen's own error field.
                .takeWhile { _state.value.restore?.error is UnlockError.RetryAfter }
                .collect { seconds ->
                    updateRestore {
                        if (seconds > 0) {
                            it.copy(error = UnlockError.RetryAfter(R.plurals.unlock_retry_after_seconds, seconds))
                        } else {
                            it.copy(error = null)
                        }
                    }
                }
        }
    }

    private fun recordRestoreFailure() {
        restoreFailures += 1
        if (restoreFailures >= RESTORE_MAX_FAILED_ATTEMPTS) {
            val exponent = (restoreFailures - RESTORE_MAX_FAILED_ATTEMPTS).coerceAtMost(18)
            val backoff = minOf(1_000L shl exponent, RESTORE_MAX_BACKOFF_MS)
            restoreLockoutUntilMs = SystemClock.elapsedRealtime() + backoff
        }
    }

    /**
     * The chosen candidate's container bytes, whichever kind it is, or null if they cannot be read.
     *
     * The two sources differ only in how the bytes arrive. A picked `.vault` is a bundle, so its
     * container has to be lifted out of it by the module that owns that format; a ring snapshot is
     * a byte copy of `vault.enc` and is simply read. Everything after this point is identical for
     * both, deliberately: the validation, the quarantine and the content-bound proof are the same
     * code, so the attacker-supplied path cannot drift into a weaker one.
     *
     * A failure is null and nothing else. `readContainerBytes` returns an `AppError` whose message
     * can name the file the user chose, and repo law keeps paths and URIs out of the UI — the
     * caller renders `unlock_restore_error_unreadable`, which says what the user needs and nothing
     * about their filesystem.
     */
    private suspend fun readChoice(choice: RestoreChoice): ByteArray? = when (choice) {
        is RestoreChoice.Ring -> withContext(Dispatchers.IO) { readCandidate(choice.snapshot.path) }
        is RestoreChoice.PickedFile -> when (
            val read = vaultImporter.readContainerBytes(Uri.parse(choice.uri))
        ) {
            is Outcome.Success -> read.value
            is Outcome.Failure -> null
        }
    }

    /**
     * The chosen snapshot's bytes, or null if it is gone or implausibly large.
     *
     * The cap is not paranoia about this directory — the files in it are ones this app wrote — it
     * is that the read happens before anything has established the file is still a container, and
     * a `readBytes()` with no bound is an allocation the size of whatever is on disk.
     */
    private fun readCandidate(path: String): ByteArray? {
        val file = java.io.File(path)
        if (!file.isFile || file.length() > MAX_CANDIDATE_BYTES) return null
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    /**
     * Re-reads whether biometric unlock can be offered right now.
     *
     * Called from the screen on every resume. Both flags were computed in the constructor and then
     * only ever inside a failure handler, so a state that changes underneath the app — a lockout
     * expiring, a fingerprint enrolled in device settings while this screen sat in the background
     * — was never noticed. Refused while an unlock is in flight: the handler that is about to run
     * has a more complete answer than this does.
     */
    fun refreshBiometricAvailability() {
        if (_state.value.isWorking) return
        _state.update {
            it.copy(
                biometricAvailable = biometricAvailable(),
                biometricBlockedByPanicLock = biometricBlockedByPanicLock(),
            )
        }
    }

    /**
     * Whether the notice explaining the missing biometric button should be shown.
     *
     * The second term used to be `DeviceKeyStore.load(context) != null`, which made this
     * **unsatisfiable**: `panicLock()` sets the flag and then destroys the device-wrapped DEK in
     * the same call, so by the time any unlock screen asks, the store is already empty. The branch
     * this guards had therefore never run — the button vanished with no explanation at all, which
     * is exactly the reading its own comment says it exists to prevent (device, 2026-09-20).
     *
     * The device's own biometric availability replaces it, and it is the closest signal this
     * module has: nothing persists "LocalKeep's biometric unlock was enrolled" once the wrapped
     * key is gone. So a phone with no enrolled fingerprint still says nothing, and the copy is
     * written to be true for someone who had never turned LocalKeep's biometric on either — it
     * states where the feature stands now and what to do, not that something of theirs was taken.
     */
    private fun biometricBlockedByPanicLock(): Boolean =
        try {
            panicExplainsMissingBiometric(
                blockedThisProcess = lockManager.isBiometricUnlockBlocked,
                suspendedOnDisk = lockManager.isBiometricSuspendedByPanic,
            ) && keystoreManager.isStrongBiometricAvailable()
        } catch (_: Exception) {
            false
        }

    private fun biometricAvailable(): Boolean =
        try {
            keystoreManager.isStrongBiometricAvailable() &&
                DeviceKeyStore.load(context) != null &&
                !lockManager.isBiometricUnlockBlocked
        } catch (_: Exception) {
            false
        }

    /**
     * UTC, second-resolution, so a quarantined copy can be told from an earlier one and from the
     * live file. Never shown to the user — it is part of a filename in app-private storage.
     */
    private fun quarantineStamp(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())

    private companion object {
        /** Mirrors `VaultLockManager.MAX_FAILED_ATTEMPTS`. */
        const val RESTORE_MAX_FAILED_ATTEMPTS = 5

        /** Mirrors `VaultLockManager.MAX_BACKOFF_MS`. */
        const val RESTORE_MAX_BACKOFF_MS = 300_000L

        /**
         * The same ceiling `VaultBundle.MAX_VAULT_BYTES` puts on an imported container. Named again
         * rather than borrowed because that constant is `internal` to `:feature:backup`.
         */
        const val MAX_CANDIDATE_BYTES = 512L * 1024 * 1024
    }
}

/**
 * Whether a `BiometricPrompt` error code means the user chose another way in, not that anything
 * failed.
 *
 * The prompt's negative button on this screen is "Use master password", and dismissing the prompt
 * says the same thing. Neither is a failure, and the password field they asked for is already
 * behind the prompt — but both arrived at a catch-all that painted the coral
 * `unlock_error_biometric_incomplete` banner, the same treatment a lockout or a hardware fault
 * gets. `LkColors`' own rule for coral is "is the user's data less safe than they believe", which a
 * deliberate, offered choice is not.
 *
 * A free function so the decision can be tested without an Activity, a Keystore and a real prompt —
 * the same reason the other decisions in this module were pulled out of their coroutines.
 */
internal fun biometricErrorIsUserChoice(code: Int): Boolean =
    code == BiometricPrompt.ERROR_NEGATIVE_BUTTON || code == BiometricPrompt.ERROR_USER_CANCELED

/**
 * Whether [error] belongs to the credential well it is shown under, and so earns that well's coral
 * ring.
 *
 * The ring says "this input is the problem". Every error used to set it, so a biometric cool-off,
 * an unreadable vault and a sensor that would not complete all circled a password field the user
 * had not touched. The test for a field error is that retyping into this well is what could change
 * the outcome: a wrong password, a refused Recovery Kit, a scanned code that was not a kit, and the
 * password lockout that is waiting on the next attempt at it.
 *
 * Membership is by resource, not by type, so a new message is neutral until someone decides it names
 * the field. A free function for the same reason [unlockErrorFor] is one.
 */
internal fun UnlockError?.namesTheField(): Boolean = when (this) {
    is UnlockError.Simple -> messageRes == R.string.unlock_error_wrong_password ||
        messageRes == R.string.unlock_recovery_failed_message ||
        messageRes == R.string.unlock_recovery_qr_invalid_message
    is UnlockError.RetryAfter -> pluralsRes == R.plurals.unlock_retry_after_seconds
    is UnlockError.Actionable, null -> false
}

/**
 * Whether a panic lock is why biometric unlock is missing, from either of its two records.
 *
 * [blockedThisProcess] is `VaultLockManager.isBiometricUnlockBlocked`, which lives in memory and is
 * gone with the process; [suspendedOnDisk] is `isBiometricSuspendedByPanic`, which is persisted for
 * exactly that reason. Reading only the first meant a force-stop or a reboot after a panic lock
 * dropped the explanation and left a vault with no biometric button and no word about why.
 */
internal fun panicExplainsMissingBiometric(blockedThisProcess: Boolean, suspendedOnDisk: Boolean): Boolean =
    blockedThisProcess || suspendedOnDisk

/** The next value of [UnlockUiState.passwordFocusRequests]: bumped only when the user chose the password. */
internal fun nextPasswordFocusRequests(current: Int, userChoseAnotherWay: Boolean): Int =
    if (userChoseAnotherWay) current + 1 else current

/**
 * Whether the sensor is merely cooling off, as opposed to having failed.
 *
 * `ERROR_LOCKOUT` is the framework's 30-second window after too many rejected attempts; it ends by
 * itself and the enrolment survives it. `ERROR_LOCKOUT_PERMANENT` is deliberately NOT included:
 * that one holds until the device credential is entered, so offering the button again would be an
 * offer the app cannot keep.
 *
 * A free function for the same reason [biometricErrorIsUserChoice] is one.
 */
internal fun biometricErrorIsTransientLockout(code: Int): Boolean =
    code == BiometricPrompt.ERROR_LOCKOUT

/**
 * What the screen says about a failed unlock, and — for exactly one result — what it offers to do
 * about it.
 *
 * Every failure used to read "Wrong password", including a cancelled fingerprint and a vault file
 * that would not decode. That is the worst possible advice in both cases: it tells the user to
 * retype a password that was never the problem, and hides the one action that would actually help.
 *
 * A free function for the same reason [biometricErrorIsUserChoice] is one — the decision is
 * testable without a Context, a Keystore or a Hilt graph, and
 * `UnlockErrorActionIsUnreadableOnlyTest` walks every [UnlockResult] through it to prove that
 * exactly one of them offers an action. Returns null for the three results that are not failures.
 */
internal fun unlockErrorFor(result: UnlockResult): UnlockError? = when (result) {
    UnlockResult.WRONG_CREDENTIAL -> UnlockError.Simple(R.string.unlock_error_wrong_password)
    UnlockResult.BIOMETRIC_UNAVAILABLE -> UnlockError.Simple(R.string.unlock_error_biometric_unavailable)
    // Retyping cannot fix this one, so point at the thing that can — and, since 2026-09-13,
    // actually carry it. The message used to end "try restoring a backup from Settings", which is
    // advice the unlock screen cannot be followed from: Settings lives inside the authenticated
    // shell, so the one user the sentence was written for — the one who cannot authenticate — had
    // no route to it (F34). This is the single `UnlockError.Actionable` in the app; see that class
    // for why it is only this one.
    UnlockResult.VAULT_UNREADABLE -> UnlockError.Actionable(
        messageRes = R.string.unlock_error_vault_unreadable,
        actionRes = R.string.unlock_error_vault_unreadable_action,
    )
    // A lock landed while the key was still being derived — Argon2 runs long enough for an
    // auto-lock to fall inside it. This used to report VAULT_UNREADABLE, which sent someone
    // holding a correct password and a healthy vault off to restore a backup.
    UnlockResult.INTERRUPTED -> UnlockError.Simple(R.string.unlock_error_interrupted)
    // Deliberately does NOT offer the restore action, unlike VAULT_UNREADABLE above. This vault is
    // intact and newer than the app reading it — restoring an older backup over it is exactly the
    // wrong move, and it cannot be undone.
    UnlockResult.VAULT_FROM_NEWER_VERSION -> UnlockError.Simple(R.string.unlock_error_vault_newer_version)
    // Says "this device", not "your password" and not "your vault" — both are fine.
    //
    // Names the whole remedy rather than just its first step. Opening it elsewhere is not the fix,
    // it is where the fix can be performed: re-wrapping the header at a lower cost needs the vault
    // open, so the other phone is a means, and stopping the sentence there left the user thinking
    // they now have to keep that phone. Settings › Backup offers the change there — see
    // `KdfRecalibration.Verdict.OFFER_PORTABLE`, which exists precisely so the control is visible
    // on a device where nothing feels wrong.
    //
    // Deliberately silent about restoring a backup: the backup carries the same KDF parameters and
    // would fail identically.
    UnlockResult.VAULT_NEEDS_MORE_MEMORY -> UnlockError.Simple(R.string.unlock_error_needs_more_memory)
    // The vault fits this device; the process did not. Different remedy, different copy.
    UnlockResult.VAULT_MEMORY_TRANSIENT -> UnlockError.Simple(R.string.unlock_error_memory_transient)
    UnlockResult.REAL, UnlockResult.DURESS, UnlockResult.ALREADY_UNLOCKED -> null
}

/** How many words a Recovery Kit has. BIP-39 at 256 bits of entropy; see `RecoveryKit`. */
internal const val RECOVERY_KIT_WORDS = 24

/**
 * The state after the user switches from the password field to the Recovery Kit pane.
 *
 * The clear of [UnlockUiState.password] is the point. The pane replaces the password field in
 * place, so a half-typed master password stayed in the state behind it and was still sitting in the
 * well — dots and a character count — when the user came back through "Back to password" minutes
 * later, on a screen they had walked away from to go and find a piece of paper. [hideRecovery]
 * already clears the 24 words on the way back for the same reason; this is the other direction.
 *
 * A free function for the same reason [unlockErrorFor] is one: the decision is testable without a
 * Context, a Keystore or a Hilt graph.
 */
internal fun UnlockUiState.showingRecovery(): UnlockUiState =
    copy(recoveryVisible = true, error = null, password = "")

/** How many words have been typed into the Recovery Kit field so far. */
internal fun recoveryWordCount(input: String): Int =
    input.trim().split(Regex("\\s+")).count(String::isNotBlank)

/**
 * Whether what is typed is a whole, well-formed Recovery Kit — 24 words, all in the BIP-39
 * wordlist, checksum included.
 *
 * "Open with Recovery Kit" used to be live the moment the field was non-blank, so three words
 * bought a rejection reading "Those words didn't open this vault. Check the order and the
 * spelling — all 24 have to be right", which is advice about a transcription error the user has
 * not made yet. Worse, every one of those attempts is a wrong guess against a real vault and costs
 * the unlock backoff, on the screen reached by someone who has already lost their password.
 *
 * `deriveRecoveryKek` is the checksum check the app already owns — the word count is tested first
 * so the derivation runs at most once, when the last word lands, and the key it returns is wiped
 * immediately: this asks a question about the words, it does not want the key.
 */
internal fun recoveryKitIsComplete(input: String): Boolean {
    if (recoveryWordCount(input) != RECOVERY_KIT_WORDS) return false
    var kek: ByteArray? = null
    return try {
        kek = RecoveryKit.deriveRecoveryKek(input)
        true
    } catch (_: Exception) {
        false
    } finally {
        Zeroizer.wipe(kek)
    }
}

/**
 * Whether a *full* 24-word Recovery Kit fails its BIP-39 checksum — the one case
 * [recoveryKitIsComplete] refuses without saying why (N-01). An unfinished phrase and a
 * finished-but-wrong one both make the CTA inert, but only the second is something the screen
 * can name: the counter above already reads "24 of 24 words" in that state, so silence there
 * reads as a stuck button rather than a typo to go find.
 */
internal fun recoveryKitChecksumInvalid(input: String): Boolean =
    recoveryWordCount(input) == RECOVERY_KIT_WORDS && !recoveryKitIsComplete(input)

/**
 * Whether more than 24 words are typed — the other branch [recoveryKitChecksumInvalid] does not
 * cover, and the one that said nothing at all (V3-01).
 *
 * A kit is exactly 24 words, so 32 of them is not an unfinished phrase: the counter read
 * "32 of 24 words" in the neutral grey of a phrase still being typed, no inline message appeared,
 * and the disabled CTA was announced as "available once all 24 words are entered" — advice to do
 * the thing the user had already done twice over. Sighted users can infer the problem from the
 * counter; a screen-reader user was told the opposite of the truth.
 *
 * Cheap where [recoveryKitChecksumInvalid] is not: this counts words, it derives no key.
 */
internal fun recoveryKitTooManyWords(input: String): Boolean =
    recoveryWordCount(input) > RECOVERY_KIT_WORDS

/** What the "N of 24 words" counter is saying, as distinct from what it is counting. */
internal enum class RecoveryCountTone {
    /** Fewer than 24 words: nothing to say yet. */
    COUNTING,

    /** 24 words that fail the BIP-39 checksum. */
    CHECKSUM_INVALID,

    /** More than 24 words. */
    TOO_MANY,

    /** 24 words that are a whole, well-formed kit. */
    COMPLETE,
}

/**
 * The counter turned jade on the twenty-fourth word whatever that word was, so a kit failing its
 * checksum was congratulated by the one element on this screen that reads as a verdict — directly
 * above a CTA that stays dead and an inline error saying the opposite. Jade is reserved for a kit
 * that will actually open the vault; the checksum case takes amber, which `LkColors` defines as
 * nothing being wrong but something worth looking at.
 *
 * [checksumInvalid] is passed in rather than derived here because the caller already holds it,
 * memoised per phrase — [recoveryKitChecksumInvalid] runs a key derivation to answer.
 */
internal fun recoveryCountTone(count: Int, checksumInvalid: Boolean): RecoveryCountTone = when {
    checksumInvalid -> RecoveryCountTone.CHECKSUM_INVALID
    // Amber for the same reason the checksum case takes it: something is wrong and the counter is
    // the element that reads as a verdict. Grey said "still going" at 32 of 24 words (V3-01).
    // Disjoint from the branch above by construction — `recoveryKitChecksumInvalid` is false at any
    // count but exactly 24 — so the order of these two is documentation, not precedence.
    count > RECOVERY_KIT_WORDS -> RecoveryCountTone.TOO_MANY
    count == RECOVERY_KIT_WORDS -> RecoveryCountTone.COMPLETE
    else -> RecoveryCountTone.COUNTING
}
