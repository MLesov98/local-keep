package com.localkeep.feature.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.annotation.StringRes
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.crypto.BiometricAuthException
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.isUserChoice
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.generator.PasswordGenerator
import com.localkeep.core.health.StrengthAnalyzer
import com.localkeep.core.vault.RecoveryKit
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.LocalKeepSettings
import com.localkeep.core.vault.VaultInitializer
import com.localkeep.core.vault.VaultLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OnboardingUiState(
    val step: Step = Step.WELCOME,
    val password: String = "",
    val confirmation: String = "",
    /** Live zxcvbn score, 0–4. Recomputed off the main thread as the user types (§3.2). */
    val strengthScore: Int = 0,
    /** zxcvbn's own specific advice ("add another word"), not a generic scold. */
    val strengthFeedback: String = "",
    /** Master password shown in the clear. Off by default; forced on by a generated suggestion. */
    val passwordVisible: Boolean = false,
    /**
     * The current password came from [OnboardingViewModel.suggestPassphrase] and has not been
     * edited since. Hides the Confirm field, and drops it from the [readyToCreate] gate.
     *
     * Confirmation exists to catch a typo in something the user typed from memory. A suggested
     * passphrase was neither typed nor memorised — it is on screen, revealed (see
     * [passwordVisible]), and `suggestPassphrase` already fills both fields, so the second field
     * was never a check. It was a filled, unrevealable box the user did not put anything in.
     *
     * Cleared by [OnboardingViewModel.onPasswordChanged], which is also where the stale
     * confirmation gets wiped — see the note there.
     */
    val passwordWasGenerated: Boolean = false,
    /**
     * Whether LocalKeep is already the device's autofill service.
     *
     * The Done step's "Set up autofill" button opens the system picker and, until 2026-09-11, that
     * was the end of it: the user chose LocalKeep, came back, and the screen was byte-identical —
     * same label, same emphasis, nothing said it had worked. Settings has had a two-state summary
     * for this since it was written, and its own comment argues exactly this ("a user who had never
     * turned autofill on and one who had read exactly the same sentence"); the Done step had the
     * identical shape and none of the treatment.
     */
    val autofillEnabled: Boolean = false,
    /**
     * The user has read what a weak master password costs and chosen it anyway.
     *
     * This exists because the app used to simply refuse: under twelve characters, or a zxcvbn score
     * below 3, and the button stayed dead. That is the wrong call for a vault that belongs entirely
     * to the person holding it — but so is saying nothing, because the failure mode here is not the
     * familiar one. There is no server to rate-limit guesses and no account to lock: the vault file
     * can be copied off the device and attacked offline, as fast as the attacker's hardware allows.
     * So the floor is now advice with a consequence attached, and the choice stays the user's.
     */
    val weakAccepted: Boolean = false,
    val biometricAvailable: Boolean = false,
    /**
     * Whether this device could do biometric unlock at all — asked once, before setup starts.
     *
     * Separate from [biometricAvailable], which additionally requires the freshly created vault to
     * have unlocked and therefore cannot be known until the password step is behind us. This one is
     * a pure device-capability question (secure lock screen, strong sensor) and the answer does not
     * change during setup, so the progress rail can count with it from the first screen.
     *
     * It exists because the rail was counting optimistically and then correcting itself: an
     * emulator with no enrolled fingerprint showed "2 of 7" on the password step and "3 of 6" on
     * the next one (device pass, 2026-07-29). A total that revises itself downward is worse than a
     * slightly wrong one — it tells the user the app does not know how long this will take.
     */
    val biometricPossibleOnDevice: Boolean = true,
    /** Whether the migration step will be offered. False when the post-creation unlock did not take. */
    val migrateAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    /** Why the biometric step was skipped, surfaced later rather than shown as a dead screen.
     *  A string resource id, not a string: a ViewModel has no Context to resolve one, and must
     *  not gain one for this (the i18n plan's ViewModel rule). */
    @param:StringRes val biometricSkipReason: Int? = null,
    val recoveryMnemonic: String = "",
    val recoveryQrData: String = "",
    /** True once the kit has actually been exported (PDF written, or QR shown and acknowledged).
     *  Gates leaving the Recovery Kit step — see [OnboardingViewModel.confirmRecoverySaved]. */
    val recoveryExported: Boolean = false,
    /**
     * Whether the user has opted `vault.enc` out of Android Auto Backup (design.md §8.2).
     *
     * Mirrors the stored preference rather than owning it, so this screen and Settings — which
     * both write the same key — cannot disagree about the current value.
     */
    val cloudBackupDisabled: Boolean = false,
    val qrVisible: Boolean = false,
    /** The user asked to bring passwords over on the migration step; acted on at [finish]. */
    val migrateRequested: Boolean = false,
    /** String resource ids, not strings — see [biometricSkipReason]. */
    @param:StringRes val notice: Int? = null,
    @param:StringRes val error: Int? = null,
    val isWorking: Boolean = false,
    val finish: Finish? = null,
) {
    /**
     * The six-screen first run of spec §3.2.
     *
     * There used to be a seventh, ENABLE_SERVICES, between MIGRATE and DONE. Spec §3.2 makes step
     * six "Done — brief confirmation, drops into the vault list", and an autofill screen ahead of it
     * sent the user out to Android's system settings before they had ever seen their own vault. The
     * offer now lives on DONE itself, where it is still made at the moment of highest intent but no
     * longer stands between the user and the thing they just built.
     *
     * The spec's list omits autofill/passkey enablement, but that is the app's whole daily value
     * and it already existed here — so it keeps its own screen rather than being buried in a "Done"
     * confirmation the spec says should stay brief. It sits *after* the Recovery Kit deliberately:
     * it sends the user out to system settings, and no excursion may happen while the one
     * unrecoverable artefact in the product is still unsaved.
     */
    enum class Step {
        WELCOME,
        CREATE_PASSWORD,
        ENABLE_BIOMETRIC,
        RECOVERY_KIT,

        /**
         * The cloud-backup choice the spec has always required ("a prominent **Disable cloud
         * backup** toggle … surfaced at onboarding", design.md §8.2) and that was never built.
         *
         * It sits immediately after the Recovery Kit because that screen is where the app says the
         * master password cannot be reset "because your vault never leaves this device" — a
         * sentence that was not true while Auto Backup was on by default. Either the user turns
         * backup off and the sentence becomes true, or they leave it on having been told plainly
         * what that means. What is not acceptable is the previous arrangement, where the claim was
         * made and the setting was three screens deep in Settings.
         */
        CLOUD_BACKUP,
        MIGRATE,
        DONE,
    }

    /**
     * True when the master password is below what this screen advises — and therefore when the
     * acknowledgement in [weakAccepted] is required.
     *
     * Derived rather than stored so the UI and [OnboardingViewModel.createVault] cannot disagree
     * about what "weak" means. Length is part of it because zxcvbn scores a short random string
     * generously and it is still short.
     */
    val isWeakPassword: Boolean
        get() = password.isNotEmpty() &&
            (strengthScore < ADVISED_STRENGTH_SCORE || password.length < ADVISED_LENGTH)

    /** Whether the Confirm field is shown at all — see [passwordWasGenerated]. */
    val confirmationRequired: Boolean
        get() = !passwordWasGenerated

    /**
     * Whether the two fields agree. Vacuously true while Confirm is empty, so the mismatch line
     * appears once there is something to mismatch rather than the instant the field gains focus,
     * and vacuously true when Confirm is not being asked for at all.
     */
    val confirmationMatches: Boolean
        get() = !confirmationRequired || confirmation.isEmpty() || confirmation == password

    /**
     * Whether Continue may fire. Derived here rather than in the composable for the same reason
     * [isWeakPassword] is: the button and [OnboardingViewModel.createVault] must not be able to
     * disagree, and this is the form of it a unit test can reach without Compose.
     */
    val readyToCreate: Boolean
        get() = password.isNotEmpty() &&
            (!confirmationRequired || confirmation.isNotEmpty()) &&
            confirmationMatches &&
            (!isWeakPassword || weakAccepted) &&
            !isWorking

    /**
     * Why Continue is dimmed, or null when it is not — said out loud for screen readers.
     *
     * The Recovery Kit step has done this since it was written, and its comment says why: "Sighted
     * users see a dimmed button next to the row that un-dims it. A screen reader gets neither
     * adjacency nor dimming, so the reason is said out loud." The password step had the identical
     * shape and none of the treatment, so a screen reader reaching Continue before the strength
     * meter or the mismatch line heard only "Continue, dimmed" with nothing saying what to fix.
     *
     * A `@StringRes Int` rather than a string: the ViewModel does not hold display text, and this
     * is the form a unit test can assert without a Compose runtime — the same reasoning
     * [readyToCreate] is derived here for.
     *
     * Ordered the way the user meets the fields. `isWorking` deliberately has no reason: the button
     * is dimmed because the vault is being created, and the deriving overlay already says so.
     */
    @get:StringRes
    val continueDisabledReason: Int?
        get() = when {
            password.isEmpty() -> R.string.onboarding_password_continue_disabled_empty
            confirmationRequired && confirmation.isEmpty() ->
                R.string.onboarding_password_continue_disabled_confirm
            !confirmationMatches -> R.string.onboarding_password_continue_disabled_mismatch
            isWeakPassword && !weakAccepted ->
                R.string.onboarding_password_continue_disabled_weak
            else -> null
        }

    /**
     * The Recovery Kit step opens only once the kit has actually been saved — a PDF written,
     * printed, or a QR captured (UI/UX spec §3.3: "must save/export before continuing", "No skip").
     *
     * The Continue button and [OnboardingViewModel.confirmRecoverySaved] both read this so they
     * cannot disagree about whether the mandatory ceremony is done. It once also accepted a ticked
     * "I'll do this later" consent row, which let a user finish onboarding with no kit at all — an
     * unratified bypass of the one step standing between them and an unrecoverable vault. That path
     * is gone; the only way forward is a real export.
     */
    val recoveryStepReady: Boolean
        get() = recoveryExported

    /**
     * The state after the user types into the master-password field.
     *
     * A function on the state rather than three lines inside the ViewModel, because the
     * stale-confirmation trap it closes is invisible from the outside and needs a regression test
     * that does not require constructing a ViewModel.
     *
     * Editing a suggestion makes it the user's own password, so Confirm comes back — and the
     * confirmation `suggestPassphrase` silently wrote has to go with it. Left in place it held the
     * entire old passphrase, so deleting one character from the password produced "These don't
     * match yet." against a value the user never typed and could not reveal (the Confirm field is
     * deliberately never revealable). The only way out was to clear a field that nothing on screen
     * said was full.
     */
    fun withPasswordEntered(value: String): OnboardingUiState = copy(
        password = if (passwordWasGenerated) typedOverSuggestion(password, value) else value,
        confirmation = if (passwordWasGenerated) "" else confirmation,
        passwordWasGenerated = false,
        // The suggestion's "write this down — it is your master password" goes with the suggestion.
        // Left up, it stood under a password the user had typed over it (QA 2026-10-03).
        notice = notice.takeUnless { it == R.string.onboarding_password_notice_suggested },
        error = null,
    )

    /**
     * Typing into the untouched suggestion replaces it (S1-F-04): the first keystroke or paste
     * leaves only what was inserted, where a plain edit would have landed inside six words the
     * user did not choose. The well takes a String and has no select-all-on-focus, so the same
     * result is reached where the edit arrives. A deletion, or anything that is not a single
     * insertion into [suggestion], is the user's own edit of it and is kept as given.
     */
    private fun typedOverSuggestion(suggestion: String, edited: String): String {
        val added = edited.length - suggestion.length
        if (added <= 0) return edited
        val at = suggestion.commonPrefixWith(edited).length
        val rest = edited.substring(0, at) + edited.substring(at + added)
        return if (rest == suggestion) edited.substring(at, at + added) else edited
    }

    /** Where onboarding hands off. */
    enum class Finish {
        /** Vault is unlocked — drop straight into the item list (§3.2 step 6). */
        OPEN_VAULT,

        /** Vault is unlocked and the user asked to migrate — open the wizard over the vault. */
        OPEN_IMPORT,

        /** The post-creation unlock did not take; fall back to the unlock screen. */
        NEEDS_UNLOCK,
    }

    companion object {
        /** zxcvbn's own "strong enough" band. Advice now, not a gate — see [weakAccepted]. */
        const val ADVISED_STRENGTH_SCORE = 3

        /** Advised minimum length. Same status: the screen says so, it does not enforce it. */
        const val ADVISED_LENGTH = 12
    }
}

/**
 * The Done step as it stands once the import wizard has been opened from it.
 *
 * Onboarding stays on the back stack under the wizard, so Back from the wizard lands on this step
 * again, with its autofill and passkey shortcuts. [OnboardingUiState.finish] has to be forgotten
 * for that to work: left at OPEN_IMPORT it would never change again, and the primary button, which
 * calls `finish()`, would set the same value and do nothing. [OnboardingUiState.migrateRequested]
 * goes with it, because pressing Back out of the wizard is the answer "not now" and the button
 * should offer the vault, not the wizard they just left.
 */
internal fun OnboardingUiState.afterImportHandoff(): OnboardingUiState =
    copy(finish = null, migrateRequested = false)

/**
 * The step the back gesture returns to from [step], or null when there is nothing earlier to
 * return to.
 *
 * Everything from [OnboardingUiState.Step.ENABLE_BIOMETRIC] on runs after the vault exists on
 * disk, so back walks the remaining ceremony instead of unwinding it — creation is not reversible
 * from here, and the first post-creation step therefore has no earlier step at all.
 *
 * [biometricStepShown] and [migrateStepShown] mirror the forward transitions: both screens are
 * skipped when they have nothing to offer, and a back gesture must not land on one that was never
 * shown.
 */
internal fun previousStep(
    step: OnboardingUiState.Step,
    biometricStepShown: Boolean,
    migrateStepShown: Boolean,
): OnboardingUiState.Step? = when (step) {
    OnboardingUiState.Step.WELCOME -> null
    OnboardingUiState.Step.CREATE_PASSWORD -> OnboardingUiState.Step.WELCOME
    OnboardingUiState.Step.ENABLE_BIOMETRIC -> null
    OnboardingUiState.Step.RECOVERY_KIT ->
        OnboardingUiState.Step.ENABLE_BIOMETRIC.takeIf { biometricStepShown }
    OnboardingUiState.Step.CLOUD_BACKUP -> OnboardingUiState.Step.RECOVERY_KIT
    OnboardingUiState.Step.MIGRATE -> OnboardingUiState.Step.CLOUD_BACKUP
    OnboardingUiState.Step.DONE -> if (migrateStepShown) {
        OnboardingUiState.Step.MIGRATE
    } else {
        OnboardingUiState.Step.CLOUD_BACKUP
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val lockManager: VaultLockManager,
    private val keystoreManager: KeystoreManager,
    private val biometricUnlock: BiometricUnlock,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /**
     * How many [openSettings] excursions this ViewModel has begun and not yet ended. See
     * [settingsExcursionObserver] for why this, rather than an `ActivityResultLauncher` callback,
     * is what pairs [VaultLockManager.beginExternalPicker] with [VaultLockManager.endExternalPicker]
     * here — 4-2.
     *
     * 22-1: a counter, not a boolean. Neither Done-step button is debounced, and both are on screen
     * at once above API 34, so two taps before the Settings activity covers us call
     * [VaultLockManager.beginExternalPicker] twice. A boolean would record only one of them and the
     * single `onStart` that follows would decrement `externalPickerDepth` once — leaving it
     * permanently positive, which pins `useGrace` false and silently downgrades every future
     * backgrounding to the long idle timeout for the life of the process.
     */
    private var pendingSettingsReturns = 0

    /**
     * Regression 4-2: the autofill/passkey buttons on the Done step (`openAutofillSettings`,
     * `openCredentialProviderSettings`) send the user to Android's Settings with a plain
     * `startActivity` — there is no activity-result callback to pair an excursion the way the
     * Recovery Kit's document picker and print dialog do (`onPickerOpened`/`onPickerClosed` via
     * `rememberExternalPickerLauncher`). `ProcessLifecycleOwner` is the only signal this app gets
     * for "the user came back", so it stands in for that callback: [openSettings] begins the
     * excursion and counts it in [pendingSettingsReturns], and the next process-level `onStart`
     * ends it. Without this, a background trip to Settings that outlasts the Aggressive/Balanced
     * background grace relocks the vault, which pops the whole nav graph including ONBOARDING and
     * silently discards whatever the migration-choice ViewModel state held.
     *
     * One `onStart` closes *every* outstanding excursion: the process foregrounds once no matter
     * how many Settings launches were stacked up behind it, so ending only one would leak the rest.
     */
    private val settingsExcursionObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            endPendingSettingsExcursions()
        }
    }

    /**
     * Balances every excursion [openSettings] has begun and not yet ended, one
     * [VaultLockManager.endExternalPicker] per [VaultLockManager.beginExternalPicker]. Never
     * assigns `externalPickerDepth` — that counter is shared process-wide with the file pickers,
     * the print dialog and the billing flow, so forcing it to zero would cancel someone else's
     * genuinely open excursion.
     */
    private fun endPendingSettingsExcursions() {
        while (pendingSettingsReturns > 0) {
            pendingSettingsReturns--
            lockManager.endExternalPicker()
        }
        refreshAutofillEnabled()
    }

    /**
     * Re-reads whether LocalKeep is the autofill service.
     *
     * Called on every return from a Settings excursion and once at start-up, because the user may
     * have turned it on before ever reaching this step — or from the system picker this screen just
     * sent them to.
     */
    private fun refreshAutofillEnabled() {
        val enabled = runCatching {
            context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices()
        }.getOrNull() == true
        _state.update { it.copy(autofillEnabled = enabled) }
    }

    init {
        // Asked before the first screen draws, so the step count is right from "1 of N" rather
        // than being corrected halfway through. Cheap: two synchronous KeyStore/BiometricManager
        // queries, the same pair biometricPossible() makes later.
        _state.update { it.copy(biometricPossibleOnDevice = biometricPossible()) }
        refreshAutofillEnabled()
        ProcessLifecycleOwner.get().lifecycle.addObserver(settingsExcursionObserver)
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(settingsExcursionObserver)
        // A lock that popped this ViewModel mid-excursion must not leave the counter incremented
        // for the rest of the process — see beginExternalPicker's own "never loosen a guard" shape.
        endPendingSettingsExcursions()
        super.onCleared()
    }

    /** Debounces strength scoring so zxcvbn doesn't run once per keystroke. */
    private var strengthJob: Job? = null

    /**
     * True once the vault created here has been unlocked with the password the user just chose.
     * Biometric enrollment and the migration wizard both need the DEK, so neither step is reachable
     * without it — and the flow degrades to the unlock screen rather than offering dead controls.
     */
    private var vaultUnlocked = false

    fun beginSetup() {
        _state.update { it.copy(step = OnboardingUiState.Step.CREATE_PASSWORD, error = null) }
    }

    /**
     * Walks the setup sequence backwards; a no-op where [previousStep] says there is nowhere to go.
     *
     * Leaving the create-password step drops what was typed. A half-entered master password is not
     * worth carrying across a step the user chose to leave, and the fields are the one place on
     * this screen where a secret sits in state.
     */
    fun back() {
        val current = _state.value
        if (current.isWorking) return
        // The QR is drawn inline on the Recovery Kit step and has no other dismissal, so back
        // closes it before it moves anywhere.
        if (current.qrVisible) {
            _state.update { it.copy(qrVisible = false) }
            return
        }
        val target = previousStep(
            step = current.step,
            biometricStepShown = current.biometricAvailable,
            migrateStepShown = vaultUnlocked,
        ) ?: run {
            // Refusing to go back is right — the vault is already on disk and creation does not
            // unwind — but refusing in silence is not. The gesture was consumed and nothing moved
            // or spoke, which is indistinguishable from a screen that has stopped responding.
            // A polite live region, so a screen-reader user hears the reason a sighted user reads.
            _state.update {
                it.copy(notice = R.string.onboarding_notice_no_back)
            }
            return
        }
        _state.update {
            if (target == OnboardingUiState.Step.WELCOME) {
                it.copy(
                    step = target,
                    password = "",
                    confirmation = "",
                    strengthScore = 0,
                    strengthFeedback = "",
                    passwordVisible = false,
                    passwordWasGenerated = false,
                    weakAccepted = false,
                    error = null,
                    notice = null,
                )
            } else {
                it.copy(step = target, error = null, notice = null)
            }
        }
    }

    fun onPasswordChanged(value: String) {
        _state.update { it.withPasswordEntered(value) }
        scoreStrength(value)
    }

    fun togglePasswordVisible() {
        _state.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    /**
     * Records that the user wants a password this screen considers weak.
     *
     * Deliberately not reset on every keystroke. The acknowledgement is an intent — "I know, and I
     * want this one" — not a verdict on one exact string, and unticking it under the user as they
     * edited would read as the app arguing back. It only has any effect while
     * [OnboardingUiState.isWeakPassword] holds, so a password that becomes strong ignores it.
     */
    fun setWeakAccepted(accepted: Boolean) {
        _state.update { it.copy(weakAccepted = accepted, error = null) }
    }

    fun onConfirmationChanged(value: String) {
        _state.update { it.copy(confirmation = value, error = null) }
    }

    /**
     * Offers an EFF-wordlist passphrase (§3.2). Filled into both fields: making the user retype a
     * six-word phrase they did not choose is how a suggestion feature ends up unused.
     *
     * Filling Confirm silently is why the field is now hidden outright in this case
     * ([OnboardingUiState.passwordWasGenerated]). A pre-filled, unrevealable box asking the user to
     * confirm something they never typed checks nothing — it just occupies the screen and invites
     * the question of what is in it.
     */
    fun suggestPassphrase() {
        val suggestion = runCatching { PasswordGenerator.generatePassphrase().value }.getOrNull()
        if (suggestion == null) {
            // Names both ways out. The old wording sent the user away from the feature over what
            // is almost certainly a transient failure, and tapping Suggest again is the cheaper
            // thing to try first.
            _state.update {
                it.copy(error = R.string.onboarding_password_error_suggest_failed)
            }
            return
        }
        _state.update {
            it.copy(
                password = suggestion,
                // Still filled, even though the field is now hidden: nothing downstream should have
                // to care whether Confirm was shown, and createVault reads a state where the two
                // agree either way.
                confirmation = suggestion,
                passwordWasGenerated = true,
                // Revealed, necessarily: a suggestion the user cannot read is a suggestion they
                // cannot write down, and this is the one password with no recovery path but the kit.
                passwordVisible = true,
                error = null,
                notice = R.string.onboarding_password_notice_suggested,
            )
        }
        scoreStrength(suggestion)
    }

    private fun scoreStrength(password: String) {
        strengthJob?.cancel()
        if (password.isEmpty()) {
            _state.update { it.copy(strengthScore = 0, strengthFeedback = "") }
            return
        }
        strengthJob = viewModelScope.launch {
            delay(STRENGTH_DEBOUNCE_MS)
            // zxcvbn's matching is superlinear in length; keep it off the frame.
            val score = withContext(Dispatchers.Default) { StrengthAnalyzer.score(password) }
            val feedback = withContext(Dispatchers.Default) { StrengthAnalyzer.feedback(password) }
            _state.update { it.copy(strengthScore = score, strengthFeedback = feedback) }
        }
    }

    fun createVault() {
        val current = _state.value
        if (current.isWorking) return
        if (current.password.isEmpty()) {
            _state.update { it.copy(error = R.string.onboarding_password_error_empty) }
            return
        }
        // Set before the measurement below, not after it. The strength gate is a zxcvbn run, and
        // until this write nothing on screen had changed — so the first thing a new user got on the
        // mandatory first-run screen was a dead tap on Create with no spinner and no visual change.
        _state.update { it.copy(isWorking = true, error = null, notice = null) }
        viewModelScope.launch {
            // Scored rather than read off state: the live score is debounced, so it may not have
            // caught up with the last keystroke, and this is the decision that depends on it. That
            // is a reason about ordering, not about threads — awaiting an off-thread measurement
            // satisfies it just as well, and zxcvbn's matching is superlinear in length (the live
            // meter seventeen lines above keeps it off the frame for exactly that reason, as does
            // SettingsViewModel's master-password gate). The result is written back to state so the
            // acknowledgement appears even when the user beat the debounce to the button.
            val score = withContext(Dispatchers.Default) { StrengthAnalyzer.score(current.password) }
            val weak = score < OnboardingUiState.ADVISED_STRENGTH_SCORE ||
                current.password.length < OnboardingUiState.ADVISED_LENGTH
            if (weak && !current.weakAccepted) {
                // Not a refusal — the acknowledgement below the fields is what unblocks it. Anything
                // stronger than this would be the app overruling the owner of the vault about their own
                // vault; anything weaker would let a "1122" master password through without ever saying
                // that the file is attackable offline, with no server between it and a guess.
                _state.update {
                    it.copy(
                        isWorking = false,
                        strengthScore = score,
                        error = R.string.onboarding_password_error_weak,
                    )
                }
                return@launch
            }
            if (current.password != current.confirmation) {
                _state.update {
                    it.copy(isWorking = false, error = R.string.onboarding_password_error_mismatch)
                }
                return@launch
            }
            val password = current.password.toCharArray()
            // VaultInitializer.create consumes and wipes its copy, so the unlock below needs its own.
            val forUnlock = password.copyOf()
            _state.update {
                it.copy(
                    password = "",
                    confirmation = "",
                    strengthScore = 0,
                    strengthFeedback = "",
                    passwordVisible = false,
                    passwordWasGenerated = false,
                    weakAccepted = false,
                    error = null,
                    notice = null,
                    isWorking = true,
                )
            }
            val kit = try {
                VaultInitializer.create(context, password)
            } catch (e: CancellationException) {
                // "Existing data was not overwritten" is a claim about the disk, and cancellation
                // cannot support it: `create` suspends around the KDF and again around the write, so
                // a cancellation may land on either side of the file existing. Rethrow rather than
                // assert something this handler does not know.
                forUnlock.fill(Char.MIN_VALUE)
                throw e
            } catch (_: Exception) {
                // Creation failed, so nothing consumed the unlock copy — wipe it here.
                forUnlock.fill(Char.MIN_VALUE)
                _state.update {
                    it.copy(
                        // The second sentence is here for the user who took suggestPassphrase up
                        // on "Write this down before continuing", wrote it on paper, and then
                        // landed here. No vault was created, so that paper now opens nothing, and
                        // without being told they will either keep it or spend the next attempt
                        // trying to reproduce a passphrase that never had any authority.
                        error = R.string.onboarding_password_error_creation_failed,
                        isWorking = false,
                    )
                }
                return@launch
            }
            // Past this point the vault exists on disk. A failure now must never be reported as a
            // creation failure — that would send the user looking for a vault that is right there.
            // The Auto-Backup choice is now owed for this vault. Marked here, at creation, rather
            // than when Step.CLOUD_BACKUP is reached: that step is several screens away (biometric
            // enrollment, the Recovery Kit ceremony), and a user who kills the app before reaching
            // it must not have their vault silently keep the Auto-Backup default. See
            // LocalKeepSettings.isCloudBackupChoicePending for why this is safe on upgrade — no
            // vault created before this line shipped can ever have this called for it.
            LocalKeepSettings.markCloudBackupChoicePending(context)
            // Unlock with what the user just typed twice. This is not a weaker gate than the
            // unlock screen — it is the same proof of knowledge, seconds earlier — and it is
            // what makes biometric enrollment and the migration offer possible at all.
            vaultUnlocked = runCatching {
                lockManager.unlock(forUnlock) == UnlockResult.REAL
            }.getOrDefault(false)
            val available = vaultUnlocked && biometricPossible()
            _state.update {
                it.copy(
                    // The kit is the mandatory ceremony; biometric is a convenience. If the
                    // convenience is unavailable, skip its screen rather than show a dead one.
                    step = if (available) {
                        OnboardingUiState.Step.ENABLE_BIOMETRIC
                    } else {
                        OnboardingUiState.Step.RECOVERY_KIT
                    },
                    biometricAvailable = available,
                    // The rail needs this for the same reason it needs biometricAvailable, and
                    // back() has needed it all along — previousStep already takes
                    // migrateStepShown = vaultUnlocked. chooseCloudBackup jumps straight to DONE
                    // when the vault did not open, so MIGRATE never appears on that path, and a
                    // rail that keeps counting it reports one step further from the end than the
                    // user actually is.
                    migrateAvailable = vaultUnlocked,
                    biometricSkipReason = if (available) null else biometricSkipReason(),
                    recoveryMnemonic = kit.mnemonic,
                    recoveryQrData = RecoveryKit.formatForQrCode(kit.mnemonic),
                    isWorking = false,
                )
            }
        }
    }

    private fun biometricPossible(): Boolean =
        keystoreManager.isDeviceSecure() && keystoreManager.isStrongBiometricAvailable()

    /** Names the actual obstacle rather than "unavailable", so the user knows what to change. */
    @StringRes
    private fun biometricSkipReason(): Int? = when {
        !vaultUnlocked -> null
        !keystoreManager.isDeviceSecure() -> R.string.onboarding_biometric_skip_no_lock
        !keystoreManager.isStrongBiometricAvailable() -> R.string.onboarding_biometric_skip_no_sensor
        else -> null
    }

    /**
     * Enrolls biometric unlock. Explicitly a *convenience layered over* the master password: the
     * password keeps working, and losing the sensor never locks anyone out.
     */
    fun enableBiometric(activity: FragmentActivity) {
        if (_state.value.isWorking || !_state.value.biometricAvailable) return
        _state.update { it.copy(isWorking = true, error = null, notice = null) }
        viewModelScope.launch {
            val attempt = runCatching {
                val cipher = biometricUnlock.encryptCipher()
                val authenticated = biometricUnlock.authenticate(
                    activity = activity,
                    cipher = cipher,
                    // Not a Compose string: BiometricPrompt is a system dialog resolved at the
                    // moment it is shown, not something a composable ever renders, so resolving it
                    // here with the ApplicationContext already held for settings intents is the
                    // right place — see UnlockViewModel for the same pattern.
                    title = context.getString(R.string.onboarding_biometric_enable_cta),
                    subtitle = context.getString(R.string.onboarding_biometric_prompt_subtitle),
                    negativeButtonText = context.getString(
                        com.localkeep.core.ui.R.string.core_ui_biometric_negative_button,
                    ),
                )
                lockManager.enableBiometricUnlock(authenticated)
            }
            val outcome = biometricEnrolOutcome(attempt)
            _state.update {
                when (outcome) {
                    BiometricEnrolOutcome.ENABLED -> it.copy(
                        isWorking = false,
                        biometricEnabled = true,
                        notice = R.string.onboarding_biometric_enabled_notice,
                    )
                    // Said by the step itself: Enable and Skip are still on screen and nothing has
                    // gone wrong. An error here painted the coral failure line over a choice the
                    // prompt had just offered.
                    BiometricEnrolOutcome.DECLINED -> it.copy(isWorking = false)
                    BiometricEnrolOutcome.FAILED -> it.copy(
                        isWorking = false,
                        error = R.string.onboarding_biometric_enable_failed,
                    )
                }
            }
        }
    }

    fun skipBiometric() {
        _state.update {
            it.copy(step = OnboardingUiState.Step.RECOVERY_KIT, error = null, notice = null)
        }
    }

    /** Default filename offered to the document picker. */
    @StringRes
    fun suggestedPdfNameRes(): Int = R.string.onboarding_recovery_pdf_filename

    /**
     * Choosing where to write the kit is part of saving it, not leaving the app. Without this the
     * background grace timer runs while the user picks a folder, and the vault relocks before
     * [exportRecoveryPdf] can write — losing the one artefact this step exists to produce.
     * See [com.localkeep.core.ui.rememberExternalPickerLauncher], which pairs these for the caller.
     */
    fun onPickerOpened() = lockManager.beginExternalPicker()

    fun onPickerClosed() = lockManager.endExternalPicker()

    /**
     * Writes the kit as a PDF to the user-chosen [target] (SAF). This is the one deliberate place
     * recovery material leaves the app, so it is always user-initiated and never automatic.
     */
    fun exportRecoveryPdf(target: Uri) {
        val mnemonic = _state.value.recoveryMnemonic
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
            if (result.isFailure) {
                // Same reasoning, and the same best-effort shape, as the sibling flow in
                // [RecoveryKitViewModel.exportPdf]: this streams straight into the document the
                // user picked, so a throw inside `writePdf`'s final `doc.writeTo(out)` — or on the
                // flush that closes the stream — leaves a TRUNCATED PDF where they chose to put it,
                // and a half-written Recovery Kit that someone later trusts is the harm this step
                // exists to prevent. Reopening in "wt" (write + truncate) empties it: an empty file
                // is obviously not a Recovery Kit, where a partial one is not. Truncate rather than
                // delete, because the picker can point at a file the user already had and removing
                // a document this app did not create is a destructive act on data that was never
                // ours. Best-effort: the provider that failed the write may be gone by now, and the
                // error copy already tells the user the file is incomplete and to delete it.
                //
                // This branch was missing here for three months after the standalone screen grew
                // it (commit d5b7a11, 2026-08-10, which reasoned about "the SAF export path" in the
                // singular and touched only the other file). `RecoveryKitWriteModeTest` now asserts
                // both writers carry it.
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(target, "wt")?.close()
                    }
                }
            }
            _state.update {
                if (result.isSuccess) {
                    // Recorded outside this screen too: onboarding can now be finished without a
                    // kit, so something has to outlive the wizard and remember whether one exists.
                    LocalKeepSettings.setRecoveryKitSaved(context)
                    it.copy(
                        isWorking = false,
                        recoveryExported = true,
                        notice = R.string.onboarding_recovery_export_pdf_notice,
                    )
                } else {
                    it.copy(isWorking = false, error = R.string.onboarding_recovery_export_pdf_error)
                }
            }
        }
    }

    fun showRecoveryQr() {
        _state.update { it.copy(qrVisible = true, error = null) }
    }

    /** The user says they photographed the QR with another device — counts as an export. */
    fun confirmQrCaptured() {
        LocalKeepSettings.setRecoveryKitSaved(context)
        _state.update {
            it.copy(
                recoveryExported = true,
                qrVisible = false,
                notice = R.string.onboarding_recovery_qr_captured_notice,
            )
        }
    }

    /**
     * Leaves the Recovery Kit step *having saved the kit*.
     *
     * Refuses until the kit has actually been exported — a PDF written, printed, or a QR captured
     * (UI/UX spec §3.3). The three save paths all set [OnboardingUiState.recoveryExported], so any
     * one of them opens the gate, and an interrupted export (picker cancelled) simply leaves the
     * user here to try again.
     *
     * The gate itself is unchanged and deliberately so. [deferRecoveryKit] is a *different action
     * with a different label*, not a second way to satisfy this one — which is precisely what was
     * wrong with the ticked "I'll do this later" consent row that used to be OR-ed into
     * [OnboardingUiState.recoveryStepReady]. One control said "I have saved it" and meant "I have
     * not", and nothing downstream could tell the difference.
     */
    fun confirmRecoverySaved() {
        val current = _state.value
        if (!current.recoveryStepReady) {
            // A notice, not an error. Nothing has failed here — the user has been asked to save the
            // kit and has not done it yet. Dressing an instruction in the failure colour is how
            // people learn that the red text is usually nothing, which is a habit worth avoiding on
            // a screen whose real errors are about losing access to the vault.
            _state.update {
                it.copy(
                    error = null,
                    notice = R.string.onboarding_recovery_confirm_notice,
                )
            }
            return
        }
        _state.update {
            it.copy(
                step = OnboardingUiState.Step.CLOUD_BACKUP,
                // Read once on arrival rather than held from construction: Settings writes the same
                // key, and onboarding can be re-entered after a restore that carried the prefs file
                // across without the vault.
                cloudBackupDisabled = LocalKeepSettings.isCloudBackupDisabled(context),
                error = null,
                notice = null,
            )
        }
    }

    /**
     * Leaves the Recovery Kit step *without* saving the kit.
     *
     * Making this ceremony a wall was a mistake, and it was made twice. Writing down 24 words is
     * the single heaviest thing the app asks of anyone, and it is asked in the first two minutes,
     * before the vault holds anything worth recovering — a person setting the app up on a bus, or
     * trying it out, has no pen and no reason to want one yet. Blocking there does not produce a
     * saved kit; it produces an abandoned install, and the user who does push through it in a hurry
     * writes the words somewhere they will not survive anyway.
     *
     * So the step yields, and the debt is recorded instead: [SettingsKeys.RECOVERY_KIT_SAVED] stays
     * false, the Done step says plainly that the vault has no way back in, and Settings → Recovery
     * Kit carries the warning until a real export clears it. Nothing here writes
     * [OnboardingUiState.recoveryExported] — that flag means "a kit exists", and it would be a lie.
     *
     * This is *not* the old skip. That one was a tick box OR-ed into the same readiness flag the
     * Save buttons set, so the app could no longer tell a saved kit from an acknowledged warning.
     * This is a separate action, with its own label, that leaves every downstream answer truthful.
     */
    fun deferRecoveryKit() {
        _state.update {
            it.copy(
                step = OnboardingUiState.Step.CLOUD_BACKUP,
                cloudBackupDisabled = LocalKeepSettings.isCloudBackupDisabled(context),
                error = null,
                notice = null,
            )
        }
    }

    /**
     * Records the cloud-backup choice and moves on (design.md §8.2).
     *
     * Written straight through to the shared preference rather than deferred to [finish]: this is
     * the one decision in onboarding that governs whether data leaves the device, and a user who
     * kills the app on the next screen must not silently get the opposite of what they picked.
     */
    fun chooseCloudBackup(disabled: Boolean) {
        LocalKeepSettings.setCloudBackupDisabled(context, disabled)
        // The choice this screen exists to collect has now been made — see
        // LocalKeepSettings.isCloudBackupChoicePending. This is the only place that clears it in
        // the normal flow; an interruption before this call is exactly the case that flag exists
        // to detect.
        LocalKeepSettings.clearCloudBackupChoicePending(context)
        _state.update {
            it.copy(
                cloudBackupDisabled = disabled,
                // The migration wizard writes into the vault, so it is only worth offering when
                // the vault is actually open. Otherwise go straight on and let Settings host it.
                step = if (vaultUnlocked) {
                    OnboardingUiState.Step.MIGRATE
                } else {
                    OnboardingUiState.Step.DONE
                },
                error = null,
                notice = null,
            )
        }
    }

    /** Records the migration choice. Acted on at [finish]. */
    fun chooseMigration(migrate: Boolean) {
        _state.update {
            it.copy(
                migrateRequested = migrate,
                step = OnboardingUiState.Step.DONE,
                error = null,
                notice = null,
            )
        }
    }

    fun finish() {
        _state.update {
            it.copy(
                recoveryMnemonic = "",
                recoveryQrData = "",
                finish = when {
                    !vaultUnlocked -> OnboardingUiState.Finish.NEEDS_UNLOCK
                    it.migrateRequested -> OnboardingUiState.Finish.OPEN_IMPORT
                    else -> OnboardingUiState.Finish.OPEN_VAULT
                },
            )
        }
    }

    /** The wizard is open over this step; see [afterImportHandoff]. */
    fun importHandedOff() {
        _state.update { it.afterImportHandoff() }
    }

    fun openAutofillSettings() {
        openSettings(
            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    fun openCredentialProviderSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        // Needs the `package:` data URI — Settings declares the action only with that scheme, so a
        // bare intent resolves to nothing and the button appears dead. See SettingsViewModel.
        openSettings(
            Intent(Settings.ACTION_CREDENTIAL_PROVIDER).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    private fun openSettings(intent: Intent) {
        lockManager.beginExternalPicker()
        pendingSettingsReturns++
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Nothing opened, so no onStart is coming to balance this. Same shape as
            // rememberExternalPickerLauncher's own catch block. Only this launch is undone —
            // an earlier excursion that did open is still outstanding and still owed its own end.
            pendingSettingsReturns--
            lockManager.endExternalPicker()
            _state.update { it.copy(error = R.string.onboarding_settings_unavailable) }
        }
    }

    private companion object {
        const val STRENGTH_DEBOUNCE_MS = 150L
    }
}

/** How an attempt to enrol biometric unlock ended. */
internal enum class BiometricEnrolOutcome { ENABLED, DECLINED, FAILED }

/**
 * Classifies the enrolment attempt, keeping a user's "no" apart from a failure.
 *
 * The attempt was folded to a boolean with `getOrDefault(false)`, so the prompt's negative button
 * and dismissing it landed in the same coral "wasn't enabled" error as a hardware fault. The code
 * rides on [BiometricAuthException]; `isUserChoice` is the one test for the two codes that mean
 * the person declined, shared with the unlock screen.
 */
internal fun biometricEnrolOutcome(attempt: Result<Boolean>): BiometricEnrolOutcome = when {
    attempt.getOrNull() == true -> BiometricEnrolOutcome.ENABLED
    (attempt.exceptionOrNull() as? BiometricAuthException)?.isUserChoice() == true ->
        BiometricEnrolOutcome.DECLINED
    else -> BiometricEnrolOutcome.FAILED
}
