package com.localkeep.feature.settings

import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.File
import com.localkeep.core.model.HiddenLease
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.UriMatch
import com.localkeep.core.vault.ForegroundVisibilityCoordinator
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.HideOutcome
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.KdfNeedsMoreMemoryException
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultSessionEndedException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One explicitly-leased record the authenticated screen can act on.
 *
 * [name] is vault data (an item title, a folder name), not display text, which is why it is held
 * here at all — the same way `TrustedApp.label` is. [until] is the raw lease instant; the composable
 * turns it into "Hidden until <date>" or the indefinite wording, so no localised string lives in the
 * ViewModel.
 *
 * Only records with a lease of their own get a row. A descendant concealed because an ancestor is
 * hidden has nothing this screen could stop, and a "Make visible" that silently did nothing (or, worse,
 * wrote a lease onto a record the user never hid) would be a lie about what the action does.
 */
data class HiddenEntryRow(
    val target: HiddenTarget,
    val name: String,
    val isFolder: Boolean,
    val until: Instant,
    /** What the record is, so the row's glyph can say so instead of drawing every item as a note. */
    val kind: HiddenEntryKind = HiddenEntryKind.NOTE,
    /**
     * Non-secret summary lines for the view-only peek — a username, a filename, a note body. Vault
     * data like [name], never display text, and never a high-value field: no password, card number,
     * TOTP secret, CVV, PIN or national ID ever lands here. The full entry stays reachable only by
     * making the record visible again; the peek exists so the user can tell *which* entry this is
     * without doing that.
     */
    val peekLines: List<String> = emptyList(),
    /** A timestamp the peek may show (a coordinate's capture time); the composable formats it. */
    val peekInstant: Instant? = null,
    /**
     * True when the record asks to re-prompt per open. The peek then withholds [peekLines] —
     * the gate into this screen re-authenticated the *screen*, not each reprompt-guarded record.
     */
    val repromptGuarded: Boolean = false,
) {
    val indefinite: Boolean get() = until == HiddenLease.INDEFINITE
}

/** The vocabulary the row's icon draws from — the vault categories plus the folder container. */
enum class HiddenEntryKind { FOLDER, LOGIN, TOTP, NOTE, CARD, IDENTITY, FILE, COORDINATE, SEEDPHRASE }

/**
 * [authorized] is the whole gate: the screen composes no entry content while it is false, so a
 * cancelled prompt, a Back press and an auto-lock all leave a screen that has never held a name.
 */
data class HiddenItemsUiState(
    val authorized: Boolean = false,
    val biometricAvailable: Boolean = false,
    val isWorking: Boolean = false,
    val entries: List<HiddenEntryRow> = emptyList(),
    val message: SettingsMessage? = null,
    val error: SettingsMessage? = null,
)

/**
 * The authenticated Hidden-items management screen (spec §7 "Settings → Privacy → Hidden items").
 *
 * Two properties decide everything in here.
 *
 * **The gate is session-scoped and fail-closed.** Authorization is plain memory on this ViewModel —
 * never `SavedStateHandle`, which survives process recreation and would restore a granted gate that
 * nobody re-authenticated. It is granted only by a `BIOMETRIC_STRONG` prompt or by
 * [VaultLockManager.reauthenticateForCurrentVault], and it is dropped the moment the shared
 * projection reports `Locked`, together with every cached entry id.
 *
 * **Nothing here knows about duress.** The knowledge factor asks only "is this the *current* vault's
 * password", which `:core:vault` answers against slot 0 in a normal session and slot 1 in a decoy;
 * every outcome is reported with the same resource ids either way. A branch on `isDuress` — even one
 * that only changed a word — is what would let a coercer tell the two sessions apart.
 */
@HiltViewModel
class HiddenItemsViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    private val keystoreManager: KeystoreManager,
    private val visibilityCoordinator: ForegroundVisibilityCoordinator,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(
        // Not after a panic lock — see VaultLockManager.isBiometricSuspendedByPanic.
        HiddenItemsUiState(biometricAvailable = keystoreManager.isStrongBiometricAvailable() &&
                !lockManager.isBiometricSuspendedByPanic),
    )
    val state: StateFlow<HiddenItemsUiState> = _state.asStateFlow()

    /**
     * Invalidates an authorization challenge that is still in flight.
     *
     * A `BiometricPrompt` is a separate system window that can sit open for as long as the user
     * leaves it open — comfortably longer than the Aggressive preset's five-second background grace.
     * Without this, a success delivered after the vault locked would open the list on a session that
     * no longer exists. Every lock bumps it; a challenge only grants what it started.
     */
    private var authGeneration = 0L

    /** Whether this screen currently holds a reference on the shared foreground expiry timer. */
    private var holdingCoordinator = false

    init {
        viewModelScope.launch {
            // The one shared projection, so an exact lease expiry, a reveal/conceal and a save all
            // reach these rows through the same seam the list uses — no timer of this screen's own.
            repository.presentationState.collect { presentation ->
                when (presentation) {
                    is VaultPresentationState.Locked -> revokeAuthorization()
                    is VaultPresentationState.Unlocked -> if (_state.value.authorized) refreshEntries()
                }
            }
        }
    }

    /**
     * The screen entered `Lifecycle.State.STARTED`. Acquires the single app-wide nearest-expiry
     * timer, so it runs while something is on screen to see a lease end and not from the back stack.
     */
    fun onForegrounded() {
        if (holdingCoordinator) return
        holdingCoordinator = true
        visibilityCoordinator.acquire()
    }

    /** The screen left STARTED. Symmetric with [onForegrounded]; double calls are harmless. */
    fun onBackgrounded() {
        if (!holdingCoordinator) return
        holdingCoordinator = false
        visibilityCoordinator.release()
    }

    override fun onCleared() {
        onBackgrounded()
    }

    /**
     * Opens an authorization challenge and returns the generation that must still be current when it
     * completes. Internal because it is also the seam the JVM tests use: a `BiometricPrompt` cannot
     * run under Robolectric, but the epoch rule it protects is exactly what needs a test.
     */
    internal fun beginAuthChallenge(): Long = authGeneration

    /** Applies a completed biometric challenge. Anything but a current-generation success is a no-op. */
    internal fun applyBiometricOutcome(granted: Boolean, generation: Long) {
        if (!granted || generation != authGeneration) return
        grantAuthorization()
    }

    fun authorizeWithBiometric(activity: FragmentActivity) {
        if (lockManager.isBiometricSuspendedByPanic) return
        val generation = beginAuthChallenge()
        viewModelScope.launch {
            // Resolved with Context.getString, not stringResource: BiometricPrompt is an imperative
            // Android API rather than a composable — same exception SettingsViewModel's own prompt
            // strings take.
            val granted = promptForStrongBiometric(
                activity = activity,
                title = context.getString(R.string.settings_hidden_title),
                subtitle = context.getString(R.string.settings_hidden_gate_body),
            )
            applyBiometricOutcome(granted, generation)
        }
    }

    /**
     * The knowledge-factor fallback. [password] is consumed and wiped by
     * [VaultLockManager.reauthenticateForCurrentVault]; nothing here keeps a copy.
     */
    fun authorizeWithPassword(password: CharArray) {
        if (_state.value.isWorking) return
        val generation = beginAuthChallenge()
        _state.update { it.copy(isWorking = true, message = null, error = null) }
        viewModelScope.launch {
            val error = try {
                if (lockManager.reauthenticateForCurrentVault(password)) {
                    if (generation == authGeneration) grantAuthorization()
                    null
                } else {
                    SettingsMessage.Simple(R.string.settings_hidden_error_password)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ended: VaultSessionEndedException) {
                // Never `ended.message`: an exception's own text is English, names an internal
                // operation, and is not what the user needs to be told.
                SettingsMessage.Simple(R.string.settings_hidden_error_session_ended)
            } catch (_: KdfNeedsMoreMemoryException) {
                // The gate's Argon2id run did not fit beside the open UI (2026-09-14).
                SettingsMessage.Simple(R.string.settings_error_kdf_needs_more_memory)
            } catch (throttled: VaultLockedException) {
                // The same backoff the unlock screen and the master-password change share — this is
                // one more place the vault password can be guessed at.
                SettingsMessage.RetryAfter(
                    R.plurals.settings_error_retry_after,
                    ((throttled.retryAfterMs + 999) / 1_000).toInt(),
                )
            }
            _state.update { it.copy(isWorking = false, error = error) }
        }
    }

    fun stopHiding(row: HiddenEntryRow) = mutate(R.string.settings_hidden_stopped, row.name) {
        repository.stopHiding(row.target)
        // Re-arm the shared expiry timer: this screen acquired the coordinator when it opened, so
        // the timer is set for whatever the nearest lease was *then*. Dropping or re-dating a lease
        // here changes that instant, and nothing else recomputes it.
        visibilityCoordinator.reschedule()
    }

    fun changeDuration(row: HiddenEntryRow, until: Instant) =
        mutate(R.string.settings_hidden_duration_changed, row.name) {
            repository.changeHiddenUntil(row.target, until)
            visibilityCoordinator.reschedule()
        }

    /**
     * Rotates the reveal phrase in a single repository commit (Decision 7 — hidden state is not keyed
     * by the phrase, so nothing is re-keyed and no lease changes). [phrase] is consumed and wiped by
     * the repository.
     */
    fun changeRevealPhrase(phrase: CharArray) {
        if (!_state.value.authorized || _state.value.isWorking) return
        _state.update { it.copy(isWorking = true, message = null, error = null) }
        viewModelScope.launch {
            var message: SettingsMessage? = null
            var error: SettingsMessage? = null
            try {
                when (repository.changeRevealPhrase(phrase)) {
                    HideOutcome.Hidden ->
                        message = SettingsMessage.Simple(R.string.settings_hidden_phrase_changed)
                    HideOutcome.PhraseCollision ->
                        error = SettingsMessage.Simple(R.string.settings_hidden_phrase_collision)
                    else -> error = SettingsMessage.Simple(R.string.settings_error_generic)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ended: VaultSessionEndedException) {
                error = SettingsMessage.Simple(R.string.settings_hidden_error_session_ended)
            }
            _state.update { it.copy(isWorking = false, message = message, error = error) }
        }
    }

    fun dismissMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    /** Runs an authorized lease mutation and re-reads the rows from the repository afterwards. */
    private fun mutate(
        successRes: Int,
        name: String,
        block: suspend () -> Unit,
    ) {
        if (!_state.value.authorized || _state.value.isWorking) return
        _state.update { it.copy(isWorking = true, message = null, error = null) }
        viewModelScope.launch {
            var message: SettingsMessage? = null
            var error: SettingsMessage? = null
            try {
                block()
                message = SettingsMessage.WithArgs(successRes, listOf(name))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ended: VaultSessionEndedException) {
                error = SettingsMessage.Simple(R.string.settings_hidden_error_session_ended)
            } catch (locked: IllegalStateException) {
                if (locked.message == "vault is locked") {
                    error = SettingsMessage.Simple(R.string.settings_hidden_error_session_ended)
                } else {
                    throw locked
                }
            }
            _state.update { it.copy(isWorking = false, message = message, error = error) }
            if (error == null) refreshEntries()
        }
    }

    private fun grantAuthorization() {
        _state.update { it.copy(authorized = true, error = null) }
        refreshEntries()
    }

    /** A lock takes the gate and the rows with it, so no name outlives the session that held it. */
    private fun revokeAuthorization() {
        authGeneration++
        _state.update {
            it.copy(authorized = false, entries = emptyList(), isWorking = false, message = null)
        }
    }

    /**
     * Reads the rows, and only ever after [grantAuthorization]. The accessor is deliberately the
     * management-only one: it returns the explicitly-leased records active at the repository clock,
     * never the raw storage inventory, whose names and counts no UI may see.
     */
    private fun refreshEntries() {
        if (!_state.value.authorized) return
        val entries = try {
            val hidden = repository.getHiddenEntriesForManagement()
            val items = hidden.directlyHiddenItems.mapNotNull { item ->
                item.hiddenUntil?.let {
                    // The kind and the peek travel with the row so the screen can say what a
                    // record *is* without un-hiding it. Only identity-grade fields are picked
                    // out here; the high-value ones (password, card number, TOTP secret, CVV,
                    // PIN, national ID) are deliberately never read.
                    val content = item.content
                    val kind = hiddenEntryKindFor(content)
                    val peek = when (content) {
                        is Login -> listOfNotNull(
                            content.username.takeIf(String::isNotBlank),
                        ) + content.uris.map(UriMatch::uri).take(3)
                        is Totp -> listOfNotNull(
                            content.issuer.takeIf(String::isNotBlank),
                            content.account.takeIf(String::isNotBlank),
                        )
                        // Nothing. Every other type here has a *label* field — a username, an
                        // issuer, a cardholder — separate from the secret it protects, and the peek
                        // shows the label. A note has no such split: its body is the whole record,
                        // and in this app that body is precisely where the secrets that fit no
                        // structured field end up — recovery codes, PINs, security answers. Showing
                        // it made the peek a second detail screen for the one type whose content is
                        // arbitrary, which is what the sheet's own contract says it must not become.
                        //
                        // A first line or a truncation would not help: line one is exactly where a
                        // recovery code sits. The row's title already answers "what is this record",
                        // which is all the peek was ever for; a note falls through to the sheet's
                        // "nothing to preview" notice, and un-hiding remains the way to read one.
                        is Note -> emptyList()
                        is Card -> listOfNotNull(
                            content.holder.takeIf(String::isNotBlank),
                        )
                        is Identity -> listOfNotNull(
                            listOf(content.firstName, content.lastName)
                                .filter(String::isNotBlank)
                                .joinToString(" ")
                                .takeIf(String::isNotBlank),
                            content.email.takeIf(String::isNotBlank),
                        )
                        is File -> listOfNotNull(
                            content.filename.takeIf(String::isNotBlank),
                        )
                        else -> emptyList()
                    }
                    HiddenEntryRow(
                        HiddenTarget.Item(item.id),
                        item.title,
                        isFolder = false,
                        until = it,
                        kind = kind,
                        peekLines = peek,
                        peekInstant = (content as? Coordinate)?.capturedAt,
                        repromptGuarded = item.reprompt,
                    )
                }
            }
            val folders = hidden.directlyHiddenFolders.mapNotNull { folder ->
                folder.hiddenUntil?.let {
                    HiddenEntryRow(
                        HiddenTarget.Folder(folder.id),
                        folder.name,
                        isFolder = true,
                        until = it,
                        kind = HiddenEntryKind.FOLDER,
                    )
                }
            }
            // Soonest lease first, so what is about to come back on its own sits above what will not.
            (folders + items).sortedWith(compareBy({ it.until }, { it.name.lowercase() }))
        } catch (locked: IllegalStateException) {
            // The vault locked between the projection emission and this read. The lock collector owns
            // the state change; there is nothing to report and nothing to show.
            emptyList()
        }
        _state.update { it.copy(entries = entries) }
    }
}

/**
 * A strong-biometric-only prompt.
 *
 * Deliberately **not** `RevealAuth`, which allows `DEVICE_CREDENTIAL` alongside the biometric.
 * That is right where it is used — the vault is already open and the question is only "is this still
 * the owner" — and wrong here: the device PIN is the secret a coercer standing over the user is most
 * likely to have watched being typed, and this gate is the recovery route for exactly that person's
 * data. The knowledge factor offered instead is the *vault* password, which the device lock screen
 * does not know.
 *
 * `setNegativeButtonText` is required (and permitted) precisely because no device credential is
 * allowed; the builder rejects one when it is.
 */
private suspend fun promptForStrongBiometric(
    activity: FragmentActivity,
    title: String,
    subtitle: String,
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
        .setNegativeButtonText(activity.getString(R.string.settings_hidden_gate_password_label))
        .build()
    continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    prompt.authenticate(info)
}

/**
 * The row vocabulary a hidden record's content maps to — extracted so the mapping is testable on
 * its own, independent of the authorization gate `refreshEntries` sits behind.
 */
internal fun hiddenEntryKindFor(content: ItemContent): HiddenEntryKind = when (content) {
    is Login -> HiddenEntryKind.LOGIN
    is Totp -> HiddenEntryKind.TOTP
    is Note -> HiddenEntryKind.NOTE
    is Card -> HiddenEntryKind.CARD
    is Identity -> HiddenEntryKind.IDENTITY
    is File -> HiddenEntryKind.FILE
    is Coordinate -> HiddenEntryKind.COORDINATE
    is SeedPhrase -> HiddenEntryKind.SEEDPHRASE
    // A passkey renders as a login: it is a way of signing in, and the row vocabulary has no
    // reason to grow a glyph for a type the add sheet does not offer.
    else -> HiddenEntryKind.LOGIN
}
