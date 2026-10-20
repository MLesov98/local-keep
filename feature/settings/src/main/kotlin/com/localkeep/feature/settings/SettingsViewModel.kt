package com.localkeep.feature.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.autofill.AutofillManager
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.billing.PurchaseOutcome
import com.localkeep.core.crypto.BiometricAuthException
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.isUserChoice
import com.localkeep.core.health.BreachCorpusVintage
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.health.StrengthAnalyzer
import com.localkeep.core.model.Vault
import com.localkeep.core.ui.A11yPreferences
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.AutoLockPreset
import com.localkeep.core.vault.ChangeResult
import com.localkeep.core.vault.DuressPasswordCollisionException
import com.localkeep.core.vault.DeviceKeyStore
import com.localkeep.core.vault.DuressUnlocker
import com.localkeep.core.vault.LocalKeepSettings
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.MasterPasswordChanger
import com.localkeep.core.vault.ProTypeQuota
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.KdfNeedsMoreMemoryException
import com.localkeep.core.vault.VaultSessionEndedException
import com.localkeep.feature.files.BlobGarbageCollector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A resolved message or error to show on the Settings screen. Never a raw String — the ViewModel
 * exposes a resource id (plus whatever args the message needs) and the composable resolves it with
 * `stringResource`/`pluralStringResource`, per `GeneratorViewModel.error` and `UnlockViewModel`'s
 * `UnlockError`.
 */
/**
 * The duress row’s form: the two new-password wells, the vault-password well both of its
 * confirmations share, and which confirmation is open.
 *
 * **Why the ViewModel and not the composable.** Rotating with either confirmation open destroyed it
 * and emptied every well (device, 2026-09-20, 3A-F24). The wells could not simply be made
 * `rememberSaveable`: saved instance state is written to disk by the system and a duress password
 * is the last thing that belongs there. Making only the dialog flags saveable was worse — the
 * dialog would come back over emptied wells and its Confirm would submit an empty `CharArray`.
 *
 * A ViewModel survives a configuration change and does not survive process death, which is exactly
 * the lifetime these four fields want: the flags and the secrets they act on are restored together
 * or not at all, and nothing about either reaches the instance-state Bundle. That is why the flags
 * live here too rather than staying in `rememberSaveable` beside a ViewModel-held password.
 *
 * These are `String` because that is what a text field edits; like every other password well in the
 * app they cannot be wiped, and this does not pretend otherwise. They are turned into a `CharArray`
 * once, at the moment of submission, by [SettingsViewModel.submitDuressSetup].
 */
data class DuressFormState(
    val password: String = "",
    val confirmPassword: String = "",
    /** The *vault* password, which both confirmations ask for. Only one can be open at a time. */
    val gatePassword: String = "",
    val confirmSetup: Boolean = false,
    val confirmRemoval: Boolean = false,
)

/**
 * The change-master-password row's form: the credential well (or, in the "forgot" mode, the
 * Recovery Kit words in its place), the replacement and its confirmation.
 *
 * 2026-10-02 (S5-08): held here for exactly the reason [DuressFormState] is. These lived in plain
 * `remember`, so a rotation, a font-scale or language change, or entering split screen emptied all
 * three wells under a row that stayed open — on the one form where retyping a long password is
 * where the typo comes from. `rememberSaveable` is not the alternative: none of this may reach the
 * instance-state Bundle, and a ViewModel outlives the configuration change and dies with the process.
 */
data class PasswordFormState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val usingRecoveryKit: Boolean = false,
    val recoveryWords: String = "",
)

sealed interface SettingsMessage {
    /** A fixed message with no arguments. */
    data class Simple(@param:StringRes val textRes: Int) : SettingsMessage

    /** A message with positional format args (numbers, or an opaque string from another module). */
    data class WithArgs(@param:StringRes val textRes: Int, val args: List<Any>) : SettingsMessage

    /** A lockout countdown — the plurals resource decides the "second"/"seconds" wording. */
    data class RetryAfter(@param:PluralsRes val pluralsRes: Int, val seconds: Int) : SettingsMessage

    /**
     * The vault auto-locked mid-operation. [operationRes] is the localised operation phrase
     * substituted into [textRes] — never the exception's own English literal, which is why this
     * is not a [WithArgs] (see [vaultOperationRes]).
     */
    data class SessionEnded(
        @param:StringRes val textRes: Int,
        @param:StringRes val operationRes: Int,
    ) : SettingsMessage

    /**
     * The change-master-password success message, assembled from several optional clauses (see
     * [SettingsViewModel.changePassword]). Kept structured rather than pre-built into one String so
     * every clause — including the two counted, pluralised ones — resolves in the composable.
     */
    data class PasswordChanged(
        val recoveryKitVoided: Boolean,
        val biometricDisabled: Boolean,
        val reKeyedCount: Int,
        val destroyedCount: Int,
        val leftBehindCount: Int,
        /**
         * Stored files the re-key pass could not convert this time, and will retry on the next
         * unlock. Distinct from [leftBehindCount], which counts local backup copies the old
         * password still opens and is a warning; this one is a transient state that repairs itself,
         * so it is said plainly rather than alarmingly. It was computed and then dropped, which
         * meant a password change reported unqualified success while some of the user's files were
         * still pending.
         */
        val blobsPendingCount: Int,
    ) : SettingsMessage
}

/**
 * Whether this confirms a change that has already happened and asks nothing more of the user.
 *
 * Those go to `LkNotices`, which floats them and retires them after four seconds, as every other
 * screen's confirmations do. Settings drew them inline instead, where nothing but the next
 * operation or leaving Settings ever cleared them: "Auto-lock updated" was still on screen two
 * minutes after the chip was tapped (device, 2026-10-04).
 *
 * Deliberately not here, and so still inline until the next operation: [SettingsMessage.PasswordChanged],
 * which tells the user to make a new Recovery Kit and enable biometrics again and counts backup
 * copies the old password still opens — security copy a four-second timer would retire before it
 * was read — and the two purchase states, one of which has not happened yet and one of which names
 * an action on this screen. A confirmation added later and not listed here stays inline, which is
 * the old behaviour, not a lost message.
 */
internal fun SettingsMessage.isTransientConfirmation(): Boolean =
    this is SettingsMessage.Simple && textRes in transientConfirmations

private val transientConfirmations = setOf(
    R.string.settings_message_autolock_updated,
    R.string.settings_message_biometric_enabled,
    R.string.settings_message_biometric_disabled,
    R.string.settings_message_duress_configured,
    R.string.settings_message_duress_removed,
    R.string.settings_message_pro_restored,
)

/**
 * The About row's breach-corpus vintage. Not a plain String: [Available] carries data straight out
 * of the shipped corpus (a date/version stamp, not translatable UI text), while [Unavailable] is a
 * genuine message that must resolve through a string resource.
 *
 * [Available.yearMonth] is the parsed date and is what the row shows. [Available.text] is the raw
 * header string — kept, but only as the fallback for a corpus whose vintage carries no parseable
 * date. It used to be what the row showed unconditionally, which put `SecLists Pwdb_top-10000000,
 * 2026-07` on screen: the build pipeline's own vocabulary, in a place a user came to for a date.
 */
sealed interface BreachVintage {
    data class Available(val text: String, val yearMonth: YearMonth?) : BreachVintage
    data object Unavailable : BreachVintage
}

data class SettingsUiState(
    val autoLockPreset: AutoLockPreset = AutoLockPreset.DEFAULT,
    val biometricAvailable: Boolean = false,
    /**
     * Why biometric unlock is unavailable, when it is — a resource id, resolved by the
     * composable. Null while it is available, so the row falls through to its on/off summaries.
     */
    @StringRes val biometricUnavailableReason: Int? = null,
    /** Whether LocalKeep is the device's current autofill service. */
    val autofillEnabled: Boolean = false,
    /**
     * Whether the system's own touch-feedback switch is off, which makes "Reduce haptics" moot.
     * Lives here rather than in the row so the resume re-read reaches it — see
     * [SettingsViewModel.refreshSystemCapabilities].
     */
    val systemHapticsOff: Boolean = false,
    val biometricEnabled: Boolean = false,
    val isWorking: Boolean = false,
    val message: SettingsMessage? = null,
    val error: SettingsMessage? = null,
    /**
     * False while the vault has no saved Recovery Kit — the debt left behind by "Set this up later"
     * during onboarding.
     *
     * Defaults to true so the row never flashes a warning before the preference has been read. The
     * stored value defaults the other way, which is the safe direction there: an unknown kit state
     * is treated as no kit.
     */
    val recoveryKitSaved: Boolean = true,
    /**
     * Whether the vault currently ships inside Android Auto Backup — read from the same
     * preference the Backup screen's switch writes ([LocalKeepSettings.isCloudBackupDisabled]), so
     * this row's summary can never disagree with the live setting the way the removed second
     * switch used to.
     */
    val cloudBackupEnabled: Boolean = false,
    // Gates the duress vault, and raises the free caps on attachments, cards and identity
    // documents. It no longer gates password health or the importers, and it has not gated the
    // Card and Identity item *types* since 2026-09-03 — a free vault creates one of each.
    val isPro: Boolean = false,
    val canPurchase: Boolean = false,
    /**
     * Play's own localised price, or null when this build cannot know it — see
     * [com.localkeep.core.billing.Entitlement.displayPrice]. Null selects the no-price wording;
     * it must never be replaced by a literal amount.
     */
    val proPrice: String? = null,
    /**
     * True while the open vault is the decoy. Everything naming the duress feature is withheld:
     * a Settings row that offers to configure a decoy, or copy promising "your real vault is
     * untouched", proves to a coercer reading over the user's shoulder that a real vault is being
     * withheld — the one fact duress exists to hide.
     */
    val isDuressSession: Boolean = false,
    /** TEMPORARY (QA): true only in a debuggable foss build. See [Entitlement.setDebugPro]. */
    val debugTierOverridable: Boolean = false,
    /** About section (10.1). Read from the installed package, not a hardcoded string. */
    val appVersion: String = "",
    /**
     * Vintage of the bundled breach corpus, or null until it has been asked for. Loading the
     * filter allocates tens of MB, so it happens only when the About row is actually on screen.
     */
    val breachVintage: BreachVintage? = null,
    /**
     * What the open vault holds against each free-tier cap, or null when there is nothing true to
     * say — the vault is locked, or the counts have not been computed yet.
     *
     * Null renders no counts at all, and that is the point: `0 of 7` is not a neutral placeholder,
     * it is a false statement about a vault holding seven files. A row that cannot count says
     * nothing rather than guessing.
     */
    val proUsage: ProUsageCounts? = null,
)

/**
 * Held counts for the three metered categories.
 *
 * All three travel together and all three are always rendered. Showing only the nearly-spent ones
 * would turn a disclosure into a prompt: the state that argues *against* buying — `0 of 1`
 * identities, where Pro would buy the reader nothing today — is exactly the one a nagging design
 * would suppress, so it is the one that must survive.
 *
 * These are *held* counts, not headroom. `ProTypeQuota` reports the truth rather than clamping, so
 * an imported vault legitimately reads above its cap; see [SettingsViewModel] for how that case is
 * worded, since a bare "9 of 7" implies a deficit the quota invariant explicitly denies.
 */
data class ProUsageCounts(
    val files: Int,
    val cards: Int,
    val identities: Int,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val lockManager: VaultLockManager,
    private val repository: VaultRepository,
    private val keystoreManager: KeystoreManager,
    private val biometricUnlock: BiometricUnlock,
    private val entitlement: Entitlement,
    private val breachFilter: BreachFilter,
    private val blobGarbageCollector: BlobGarbageCollector,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SettingsUiState(
            autoLockPreset = SettingsPreferences.autoLockPreset(context),
            biometricAvailable = keystoreManager.isStrongBiometricAvailable(),
            biometricUnavailableReason = biometricUnavailableReason(keystoreManager.strongBiometricStatus()),
            autofillEnabled = autofillServiceEnabled(context),
            systemHapticsOff = A11yPreferences.systemHapticsDisabled(context),
            // Seeded false, not read here: DeviceKeyStore.load reads a file from disk, and this
            // constructor runs on the main thread. false is the safe default while the real
            // answer loads in `init` below — it never offers biometric unlock before the check
            // has actually run, which is the direction a security-relevant field must default.
            biometricEnabled = false,
            // Seeded, not left to the collector in `init` — that runs on viewModelScope, so until
            // its first emission a Pro user reads as free and Settings offers them the upsell.
            isPro = entitlement.isPro.value,
            canPurchase = entitlement.canPurchase,
            proPrice = entitlement.displayPrice.value,
            debugTierOverridable = entitlement.debugTierOverridable,
            appVersion = readAppVersion(context),
            // Seeded for the same reason as `isPro`: until the collector below first emits, a
            // duress session would read as a real one and Settings would draw the duress row.
            isDuressSession = (lockManager.lockState.value as? LockState.Unlocked)?.isDuress == true,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /**
     * Which row the screen should open on — taken here, once, rather than in composition.
     *
     * [SettingsFocus.take] is one-shot by design and its value is deliberately never saveable: a
     * row title in the instance-state Bundle is “the user went looking for the duress vault”,
     * written to disk. Taking it inside a `remember` honoured that and lost it to every
     * configuration change — the second `take()` returned null, [query] re-seeded to empty, and
     * the list the drawer had narrowed to one row came back with all twenty. Measured on device
     * 2026-09-21: drawer → `Duress / decoy` → rotate, at 0.3 s dwell and again at 12 s, identical
     * both times. A ViewModel is the lifetime this needs — it outlives the rotation and dies with
     * the process, which is the same argument [DuressFormState] is here for.
     */
    @StringRes
    val focusEntryTitleRes: Int? = SettingsFocus.take()

    private val _query = MutableStateFlow<String?>(null)

    /**
     * The settings search text, or null while it is still whatever [focusEntryTitleRes] seeded it
     * with. Null rather than an empty string because the seed is a resource that only the
     * composable can resolve, so this holds what the user has typed since and nothing else.
     */
    val query: StateFlow<String?> = _query.asStateFlow()

    /** The screen’s one way to edit the search text. */
    fun onQueryChanged(value: String) {
        _query.value = value
    }

    private val _duressForm = MutableStateFlow(DuressFormState())

    /** See [DuressFormState]: the duress row’s inputs, owned here so a rotation cannot empty them. */
    val duressForm: StateFlow<DuressFormState> = _duressForm.asStateFlow()

    /** The screen’s one way to edit the duress form. Every field goes through here. */
    fun updateDuressForm(form: DuressFormState) {
        // A changed form also clears a standing error, the rule [updatePasswordForm] states: the
        // too-short refusal arrives after the vault-password gate, and it sat above a form the user
        // had already started correcting. An unchanged form is not an edit and leaves it.
        if (form != _duressForm.value) _state.update { it.copy(error = null) }
        _duressForm.value = form
    }

    /**
     * Confirms the setup dialog: takes the two passwords out of the form, empties it, and runs
     * [setupDuress].
     *
     * The copy-out lives here rather than in the composable so the only place either secret is
     * turned into a `CharArray` is the place that hands it to the lock manager, which consumes and
     * wipes it.
     */
    fun submitDuressSetup() {
        val form = _duressForm.value
        _duressForm.value = DuressFormState()
        setupDuress(form.gatePassword.toCharArray(), form.password.toCharArray())
    }

    private val _passwordForm = MutableStateFlow(PasswordFormState())

    /** See [PasswordFormState]: the change-password row's inputs, owned here for the same reason. */
    val passwordForm: StateFlow<PasswordFormState> = _passwordForm.asStateFlow()

    /**
     * The screen's one way to edit the change-password form.
     *
     * A changed form also clears a standing error. Errors persist until dealt with — that is
     * `LkNotices`' rule, and why they are not handed to it — and editing the form is dealing with
     * it: "That password is too easy to guess…" stayed beside a corrected form until the next
     * submit (device, 2026-10-04). An unchanged form is not an edit and leaves it.
     */
    fun updatePasswordForm(form: PasswordFormState) {
        if (form != _passwordForm.value) _state.update { it.copy(error = null) }
        _passwordForm.value = form
    }

    /**
     * Drops [message] from the screen's status once the screen has handed it to `LkNotices` — see
     * [isTransientConfirmation]. Only while it is still the one showing: a message that replaced it
     * in the meantime is not this one's to clear.
     */
    fun consumeMessage(message: SettingsMessage) {
        _state.update { if (it.message == message) it.copy(message = null) else it }
    }

    /** Confirms the removal dialog. Symmetric with [submitDuressSetup]. */
    fun submitDuressRemoval() {
        val form = _duressForm.value
        _duressForm.value = DuressFormState()
        removeDuress(form.gatePassword.toCharArray())
    }

    /**
     * Leaving the row, or answering either dialog with Cancel. Drops the vault-password well and
     * both dialog flags, and deliberately keeps the two new-password wells: dismissing a
     * confirmation is not the same act as abandoning the form behind it.
     */
    fun dismissDuressConfirmations() {
        _duressForm.update { it.copy(gatePassword = "", confirmSetup = false, confirmRemoval = false) }
    }

    init {
        refreshRecoveryKitState()
        refreshCloudBackupState()
        viewModelScope.launch {
            val enabled = withContext(Dispatchers.IO) { DeviceKeyStore.load(context) != null }
            _state.update { it.copy(biometricEnabled = enabled) }
        }
        viewModelScope.launch {
            launch {
                entitlement.displayPrice.collect { p -> _state.update { it.copy(proPrice = p) } }
            }
            entitlement.isPro.collect { pro -> _state.update { it.copy(isPro = pro) } }
        }
        viewModelScope.launch {
            lockManager.lockState.collect { lock ->
                val duress = (lock as? LockState.Unlocked)?.isDuress == true
                _state.update { it.copy(isDuressSession = duress) }
            }
        }
        collectProUsage()
    }

    /**
     * Keeps [SettingsUiState.proUsage] honest for as long as a vault is open.
     *
     * Collects `presentationState` rather than computing once in `init`, because it recomputes on
     * lock/unlock, reveal/conceal and every save — so adding a file elsewhere and coming back to
     * Settings shows the new number instead of a stale one. A count that lags is worse than no
     * count: it is a wrong statement about the reader's own vault, presented beside a purchase.
     *
     * The `is Unlocked` gate is necessary and not sufficient, which is why the walk below is also
     * wrapped. The gate keeps a locked *emission* from being read at all; it cannot keep the vault
     * open across the dispatcher hop that follows, and both quota functions re-read the lock state
     * when they get there.
     *
     * In a duress session these are deliberately the *decoy's* counts. `requireVault()` returns
     * whichever vault the session opened, and that is the only answer that keeps the promise there
     * is no tell: suppressing the counts under duress, or reaching past the decoy for the real
     * vault's numbers, would each be a signal that a second vault exists.
     *
     * Off the main thread because both functions are synchronous O(items) walks over the whole
     * inventory, and this app is expected to hold ten thousand of them.
     */
    private fun collectProUsage() {
        viewModelScope.launch {
            repository.presentationState.collect { presentation ->
                val counts = if (presentation is VaultPresentationState.Unlocked) {
                    withContext(Dispatchers.Default) {
                        // `runCatching`, and it is not belt-and-braces. Testing the emission is not
                        // the same as holding the lock: both quota functions re-read
                        // `lockManager.lockState` through `requireVault()`, so auto-lock firing
                        // between this emission and the walk throws `IllegalStateException` inside
                        // `viewModelScope.launch` with nothing to catch it — a crash, and one that
                        // recurs on every save while Settings sits on the back stack. Failing to
                        // null renders no counts, which is already the right answer for a vault
                        // that just shut. ItemEditViewModel guards the identical reads the same
                        // way: "the vault locking mid-capture is the ordinary cause".
                        runCatching {
                            val byKind = ProTypeQuota.usedFromRepository(repository)
                            ProUsageCounts(
                                files = AttachmentQuota.used(repository),
                                cards = byKind[ProTypeQuota.Kind.CARD] ?: 0,
                                identities = byKind[ProTypeQuota.Kind.IDENTITY] ?: 0,
                            )
                        }.getOrNull()
                    }
                } else {
                    // Not the last known counts. They were computed from data no longer in memory,
                    // and a locked vault has nothing to say about what it holds.
                    null
                }
                _state.update { it.copy(proUsage = counts) }
            }
        }
    }

    /**
     * Re-reads whether a Recovery Kit has been saved.
     *
     * Called on every entry to Settings, not just at construction: the Recovery Kit screen is one
     * tap away and clears this the moment a kit is exported, so a ViewModel that read the flag once
     * would keep the warning up over a kit that now exists.
     */
    fun refreshRecoveryKitState() {
        _state.update { it.copy(recoveryKitSaved = LocalKeepSettings.isRecoveryKitSaved(context)) }
    }

    /**
     * Re-reads whether the vault ships inside Android Auto Backup.
     *
     * The Backup screen's switch is the only writer of this preference; this row only reads it, so
     * it needs the same re-read-on-entry as [refreshRecoveryKitState] to notice a change made one
     * screen away rather than repeat the stale value it was constructed with.
     */
    fun refreshCloudBackupState() {
        _state.update { it.copy(cloudBackupEnabled = !LocalKeepSettings.isCloudBackupDisabled(context)) }
    }

    /**
     * Re-reads whether LocalKeep is the autofill service, why biometrics are unavailable, and
     * whether the system has switched touch feedback off.
     *
     * All three are settings the user changes in the *system* Settings app, which this screen sends
     * them to and which never comes back with a result. Seeding them once in the constructor meant
     * the row that had just sent someone to enrol a fingerprint still said the device had none when
     * they returned. Re-read rather than observed, for the same reason [refreshRecoveryKitState]
     * is: there is no callback to observe, and the value changes at most once per visit. The
     * touch-feedback read joined on 2026-10-04: the haptics row kept its own `remember {}` copy and
     * stayed unchecked and enabled after the system switch went off, until Settings was re-entered.
     */
    fun refreshSystemCapabilities() {
        val status = keystoreManager.strongBiometricStatus()
        _state.update {
            it.copy(
                biometricAvailable = status == BiometricManager.BIOMETRIC_SUCCESS,
                biometricUnavailableReason = biometricUnavailableReason(status),
                autofillEnabled = autofillServiceEnabled(context),
                systemHapticsOff = A11yPreferences.systemHapticsDisabled(context),
            )
        }
    }

    /**
     * TEMPORARY — on-device QA only. Flips the tier in a debuggable `foss` build so the Pro side of
     * the app can be exercised at all; a no-op anywhere else (see [Entitlement.setDebugPro]).
     * Nothing updates state here: the `isPro` collector above is already the single path by which
     * a tier change reaches the UI, and going around it is how the two ever drift apart.
     */
    fun setDebugPro(pro: Boolean) = entitlement.setDebugPro(pro)

    /**
     * Locks immediately and blocks biometric unlock until the master password is entered again
     * (10.2). Distinct from ordinary auto-lock: this is the "someone is standing over me" action,
     * and a fingerprint the coercer can compel must not undo it.
     */
    fun panicLock() {
        // No confirmation message: MainActivity observes lockState and routes to the unlock screen
        // immediately, so anything set here would be torn down before it could be read. The unlock
        // screen itself explains that biometric unlock is suspended.
        lockManager.panicLock()
    }

    fun openAutofillSettings() {
        // The `$` was escaped here as `${'$'}`, so the URI was the literal text
        // "package:${context.packageName}" rather than this app's package. Settings could not
        // match that against anything, so the row led to the generic picker instead of to
        // LocalKeep's own entry. It compiles, and the string looks right in a diff.
        openSystemSettings(
            Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    /**
     * The one biometric refusal the user can act on: nothing enrolled yet.
     *
     * ACTION_BIOMETRIC_ENROLL is API 30; below that the enrolment screen has no stable public
     * action, so the row falls back to the device's security settings rather than to a guess that
     * resolves to nothing. `openSystemSettings` already reports an unresolvable intent.
     */
    fun openBiometricEnrollSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        openSystemSettings(intent)
    }

    fun openCredentialProviderSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        // Settings declares this action only with a `package:` data scheme. Sent bare, it matched
        // no activity at all and the row did nothing visible — the ActivityNotFoundException was
        // caught and reported into a banner at the top of a screen the user had scrolled well past
        // to reach this row.
        openSystemSettings(
            Intent(Settings.ACTION_CREDENTIAL_PROVIDER).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    private fun openSystemSettings(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            setError(SettingsMessage.Simple(R.string.settings_error_screen_unavailable))
        }
    }

    /**
     * Resolves the breach-corpus vintage for About. Deliberately pull-based: [BreachFilter.ensureLoaded]
     * decompresses a multi-megabyte set, and paying that on every Settings open — to render one line
     * of text most people never scroll to — would be indefensible.
     */
    fun loadBreachVintage() {
        if (_state.value.breachVintage != null) return
        viewModelScope.launch {
            val vintage = withContext(Dispatchers.IO) {
                // Same reason as SecurityDashboard.analyze: this screen makes the same claim about
                // the corpus, so a transient load failure must not be the last word on it either.
                breachFilter.retryLoadIfTransientlyFailed()
                breachFilter.ensureLoaded()
                if (breachFilter.hasData) {
                    val raw = breachFilter.filterVintage()
                    BreachVintage.Available(raw, BreachCorpusVintage.parseYearMonth(raw))
                } else {
                    // Never dress this up as "up to date": a build with no corpus can only ever
                    // report "not breached", which would be a lie about what was checked.
                    BreachVintage.Unavailable
                }
            }
            _state.update { it.copy(breachVintage = vintage) }
        }
    }

    fun unlockPro(activity: Activity) {
        // The Play sheet is another process, so opening it backgrounds the app and starts the
        // auto-lock grace timer — 5s on the Aggressive preset, which choosing a payment method
        // comfortably beats. The purchase itself now survives that (PlayBillingEntitlement
        // acknowledges from a process-scoped listener), but locking mid-checkout still throws the
        // user back to the unlock screen having lost their place. Same excursion the import
        // wizard's file picker declares, for the same reason.
        lockManager.beginExternalPicker()
        viewModelScope.launch {
            try {
                reportPurchase(entitlement.purchase(activity))
            } finally {
                lockManager.endExternalPicker()
            }
        }
    }

    /**
     * Says what a finished purchase did, for the outcomes that would otherwise say nothing at all.
     *
     * The result used to be discarded here and at the vault list's and item editor's copies of this
     * function, so the paths that return before Play's sheet ever appears — no billing connection,
     * no product details, a launch Play refused — left the price button looking inert. Being offline
     * is the ordinary state of this app's users, so that was the ordinary experience of trying to pay.
     */
    private fun reportPurchase(outcome: PurchaseOutcome) = when (outcome) {
        // A completed purchase flips `isPro`, which announces itself; a cancel is the user's own
        // doing; a refused second tap has the flow that refused it still on screen.
        PurchaseOutcome.Purchased,
        PurchaseOutcome.Cancelled,
        PurchaseOutcome.AlreadyInFlight,
        -> Unit
        // Not an error, and deliberately not on the error channel: nothing failed and there is
        // nothing to retry.
        PurchaseOutcome.Pending -> _state.update {
            it.copy(
                message = SettingsMessage.Simple(
                    com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_pending,
                ),
                error = null,
            )
        }
        // Not the error channel either, for the same reason as Pending: nothing failed. It goes on
        // `message` so it does not read as a fault the user caused, and because the action it names
        // — Restore purchases — is on this very screen.
        PurchaseOutcome.AlreadyOwned -> _state.update {
            it.copy(
                message = SettingsMessage.Simple(
                    com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_already_owned,
                ),
                error = null,
            )
        }
        PurchaseOutcome.Failed -> setError(
            SettingsMessage.Simple(com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_failed),
        )
    }

    /**
     * Re-asks Play what this account owns.
     *
     * Until this existed, [Entitlement.restore] had no caller anywhere in the app: the only route
     * back to Pro was the once-per-process sync in `PlayBillingEntitlement.init`. A user who
     * reinstalled while offline, or whose startup sync lost its five connection attempts, was free
     * tier for the rest of the process lifetime with no button to press.
     */
    fun restorePurchases() {
        // Not routed through `runOperation`: that helper maps a null result message to
        // GENERIC_FAILURE, and a restore that found nothing to restore is not a failure — see the
        // comment below. The guard below is `runOperation`'s own `if (isWorking) return`, copied
        // rather than shared, so this can neither start while another operation (a master-password
        // rotation, most importantly) is mid-flight, nor clear a flag it did not raise.
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true, message = null, error = null) }
        viewModelScope.launch {
            val restored = runCatching { entitlement.restore() }.getOrDefault(false)
            _state.update {
                it.copy(
                    isWorking = false,
                    message = if (restored) {
                        SettingsMessage.Simple(R.string.settings_message_pro_restored)
                    } else {
                        null
                    },
                    // Deliberately covers both causes. `restore()` answers with a single boolean,
                    // so "nothing owned" and "couldn't reach Play" arrive here indistinguishable —
                    // and naming only one of them would be a guess told to the user as fact.
                    error = if (restored) {
                        null
                    } else {
                        SettingsMessage.Simple(R.string.settings_error_no_pro_purchase)
                    },
                )
            }
        }
    }

    fun setAutoLockPreset(preset: AutoLockPreset) {
        SettingsPreferences.setAutoLockPreset(context, preset)
        lockManager.applyAutoLockPreset(preset)
        _state.update {
            it.copy(
                autoLockPreset = preset,
                message = SettingsMessage.Simple(R.string.settings_message_autolock_updated),
                error = null,
            )
        }
    }

    fun changePassword(
        currentPassword: CharArray,
        newPassword: CharArray,
    ) {
        // A new password equal to the current one is not a change, and it was accepted as one: the
        // rotation ran, the user was told "Master password changed", and the Recovery Kit they had
        // written down stopped opening the vault — for a password that still does (device,
        // 2026-09-20). Refused before anything is derived.
        //
        // Here rather than in [runPasswordChange] because the kit route has no current password to
        // compare against: someone proving ownership with 24 words may legitimately re-set the
        // password they already had, and nothing is silently revoked when they do.
        if (currentPassword.contentEquals(newPassword)) {
            currentPassword.fill('\u0000')
            newPassword.fill('\u0000')
            setError(SettingsMessage.Simple(R.string.settings_error_password_unchanged))
            return
        }
        // Which slot the form is acting on. Read once, before the coroutine, so the whole operation
        // reports on the session it started in.
        val isDuressSession = _state.value.isDuressSession
        runPasswordChange(
            newPassword = newPassword,
            alsoWipeOnRefusal = currentPassword,
            // `success = false` has exactly one source in VaultLockManager.changeMasterPassword — the
            // current-password check — and every other failure there throws. Reporting it as
            // "Operation could not be completed" told someone who had simply mistyped that
            // something unknown had gone wrong with their vault, next to a form they could have
            // just retried. The wording matches the unlock screen, because it is the same mistake.
            refusedMessage = R.string.settings_error_wrong_current_password,
            isDuressSession = isDuressSession,
        ) {
            // The decoy session changes the decoy's own password. Same form, same refusals, same
            // sentence afterwards — the row used to be withheld here entirely, which is what made
            // a decoy session identifiable from Settings alone (3A-F23).
            if (isDuressSession) {
                MasterPasswordChanger.changeDecoyPassword(lockManager, currentPassword, newPassword)
            } else {
                MasterPasswordChanger.changePassword(lockManager, currentPassword, newPassword)
            }
        }
    }

    /**
     * The same change, proved with the Recovery Kit instead, for a user who has forgotten the
     * password the form above asks for.
     *
     * [mnemonic] is a String because that is what the Kit is typed and read as everywhere else in
     * the app; it cannot be wiped, and this does not pretend to. The words are re-verified inside
     * [VaultLockManager.setMasterPasswordWithRecoveryKit] rather than trusted from the unlock that
     * got the user here — see its KDoc for why the authority has to be contemporaneous.
     */
    fun changePasswordWithRecoveryKit(
        mnemonic: String,
        newPassword: CharArray,
    ) = runPasswordChange(
        newPassword = newPassword,
        alsoWipeOnRefusal = null,
        refusedMessage = R.string.settings_error_wrong_recovery_kit,
        isDuressSession = _state.value.isDuressSession,
    ) {
        // Refused inside `setMasterPasswordWithRecoveryKit` in a decoy session, and reported here
        // with the ordinary wrong-words message — which is true, because the container’s one
        // recovery block wraps the real key. See its KDoc.
        MasterPasswordChanger.setPasswordWithRecoveryKit(lockManager, mnemonic, newPassword)
    }

    /**
     * Everything the two password-change routes share: the length floor, the strength floor, the
     * biometric read, the dispatcher, and the sentence the user is told afterwards.
     *
     * Shared rather than duplicated because the halves that are easy to get subtly different are
     * the ones that matter — a second route that skipped the zxcvbn floor, or forgot that rotating
     * the key retires the device-wrapped copy, would be a quieter defect than either is worth.
     * [change] must consume and wipe [newPassword]; both manager entry points do.
     */
    private fun runPasswordChange(
        newPassword: CharArray,
        alsoWipeOnRefusal: CharArray?,
        @StringRes refusedMessage: Int,
        isDuressSession: Boolean,
        change: suspend () -> ChangeResult,
    ) {
        fun refuse() {
            alsoWipeOnRefusal?.fill('\u0000')
            newPassword.fill('\u0000')
        }
        if (_state.value.isWorking) {
            refuse()
            return
        }
        if (newPassword.size < MIN_PASSWORD_LENGTH) {
            refuse()
            setError(
                SettingsMessage.WithArgs(
                    R.string.settings_error_password_too_short,
                    listOf(MIN_PASSWORD_LENGTH),
                ),
            )
            return
        }
        runOperation {
            // The same floor onboarding applies, for the same reason. Creating a master password was
            // gated on a zxcvbn score and *changing* it was gated on length alone, so the one screen
            // where the vault's only protection can be replaced was the one screen that would
            // accept "Password12345". The vault file is attackable offline; this score is what
            // stands in front of it. Deliberately no "accept anyway" escape hatch: onboarding offers
            // one because a user with no vault yet is choosing, while this is a live vault being
            // downgraded.
            //
            // Inside `runOperation` and on Dispatchers.Default, for the same reason the derivations
            // below are. zxcvbn is not a lookup: it runs every matcher it has across the candidate
            // and then a dynamic-programming search for the cheapest guess sequence, so its cost
            // scales with the password and lands in tens of milliseconds, not microseconds. Run
            // inline in this function it put exactly that on the main thread, in the one place that
            // goes to lengths to keep Argon2 off it. Here the spinner also covers it, which it did
            // not before: the gate now reads as work rather than as a dropped frame.
            val tooWeak = withContext(Dispatchers.Default) {
                StrengthAnalyzer.score(String(newPassword)) < StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE
            }
            if (tooWeak) {
                // Both arrays are wiped on every exit from this function, and a rejected password is
                // one — nothing downstream runs, so nothing downstream will do it.
                refuse()
                throw OperationFailure(SettingsMessage.Simple(R.string.settings_error_password_too_weak))
            }
            // Read before the change, because the change is what deletes it: rotating the data key
            // retires the device-wrapped copy with it, so this row would otherwise go on claiming
            // biometric unlock is on until Settings was next opened.
            //
            // Skipped entirely in a decoy session, and that is a security property rather than an
            // optimisation. `DeviceKeyStore` is device-global: it holds the *real* vault’s wrapped
            // key, the decoy has none, and `changeDuressPassword` rotates nothing, so reading it
            // here would both pull real-vault state into a decoy screen and append a sentence about
            // a credential this session cannot see and has not disturbed.
            val hadBiometric = !isDuressSession && DeviceKeyStore.load(context) != null
            // Off the main thread, explicitly. This path runs *three* Argon2 derivations — the
            // credential check, the new slot, and the duress-collision check against slot 1 — and
            // `runOperation` launches in `viewModelScope`, which is Main. `VaultInitializer`
            // already does this for the same reason; this call site did not, so the whole UI froze
            // for the length of the derivations with no way to draw a progress state.
            val result = withContext(Dispatchers.Default) { change() }
            if (!result.success) {
                throw OperationFailure(SettingsMessage.Simple(refusedMessage))
            }
            if (hadBiometric) _state.update { it.copy(biometricEnabled = false) }
            // Same reasoning as the biometric row above, for the other credential the rotation
            // retires. The kit is revoked on disk — a fresh noise block is written over it — so the
            // flag whose stated meaning is "a kit exists" stops being true here, and nothing else
            // in the app ever wrote it false. The notice below says so once and dies with this
            // ViewModel; the Settings row is the part that has to keep saying it, and it was
            // instead offering the neutral summary over 24 words that open nothing.
            //
            // After `result.success` deliberately: a refused change leaves the old key and the old
            // kit both live, and clearing there would warn about a kit that still works.
            if (result.oldRecoveryKitVoided) {
                LocalKeepSettings.forgetRecoveryKitSaved(context)
                _state.update { it.copy(recoveryKitSaved = false) }
            }
            // The two counted clauses below cover every copy on disk, not just `vault.enc`, and
            // that used to be false: the rolling snapshots and the `.bak` still opened under the
            // old password and the old kit. They are re-keyed with the vault now, and the two
            // cases where that could not be done are stated rather than glossed — a user whose
            // local backups were destroyed to close the hole has to know they are gone, and one
            // where a file could not be reached has to know the old password still opens it.
            SettingsMessage.PasswordChanged(
                recoveryKitVoided = result.oldRecoveryKitVoided,
                biometricDisabled = hadBiometric,
                reKeyedCount = result.staleContainersReKeyed,
                destroyedCount = result.staleContainersDestroyed,
                leftBehindCount = result.staleContainersLeftBehind,
                blobsPendingCount = result.blobsLeftBehind,
            )
        }
    }

    /**
     * [vaultPassword] proves the user is present, and is consumed by [requireVaultPassword]. Arming
     * a coercion vault took no re-authentication at all until 2026-09-20 — anyone holding the
     * unlocked phone for a minute could set a decoy password of their own, which is the same act as
     * locking the owner out of an answer they may later need. The Recovery Kit gate and the
     * invisible-items gate both ask; the two most security-critical rows in Settings did not.
     */
    fun setupDuress(vaultPassword: CharArray, password: CharArray) {
        fun refuse() {
            vaultPassword.fill('\u0000')
            password.fill('\u0000')
        }
        // Defense in depth (14b, §G): duress/decoy is Pro-gated. The ProGate wrapper hides the UI,
        // but re-check here so a stale/replayed call from any path can't configure it for free —
        // matching ItemEditViewModel.createFolder/addTag/addCustomField.
        //
        // Silently refused in the decoy session too, with no message: configuring a duress password
        // for the decoy is meaningless, and an error naming the feature would confirm to whoever is
        // holding the phone that the vault they were shown is not the real one. The UI withholds
        // this row entirely during duress; this is the path that survives a stale recomposition.
        if (_state.value.isDuressSession) {
            refuse()
            return
        }
        if (!_state.value.isPro) {
            refuse()
            return
        }
        if (_state.value.isWorking) {
            refuse()
            return
        }
        if (password.size < MIN_PASSWORD_LENGTH) {
            refuse()
            setError(
                SettingsMessage.WithArgs(
                    R.string.settings_error_duress_password_too_short,
                    listOf(MIN_PASSWORD_LENGTH),
                ),
            )
            return
        }
        runOperation {
            // Before anything is swept or derived: a refused gate must leave the vault exactly as
            // it was, and the sweep below is not reversible.
            try {
                requireVaultPassword(vaultPassword)
            } catch (refused: Throwable) {
                password.fill('\u0000')
                throw refused
            }
            val now = Instant.now()
            // Last safe moment to reclaim, and it must stay ahead of the call below.
            // `BlobGarbageCollector.sweep` declines outright once a decoy exists — a session holds
            // the key for exactly one payload region, so its referenced set is that region's
            // denominator alone and sweeping would delete the other region's media irrecoverably.
            // The decline is correct, but the only other sweep trigger is the on-unlock one, so
            // after this line the vault's orphaned blobs are unreachable until duress is removed:
            // their ciphertext stays on disk indefinitely, unbounded, for items the vault list has
            // already called permanently deleted. Opportunistic — a failure here must not fail the
            // duress setup, and blobs younger than the store's grace window are skipped as always.
            runCatching { withContext(Dispatchers.IO) { blobGarbageCollector.sweep() } }
            try {
                // Off the main thread, explicitly, for the reason `runPasswordChange` gives and
                // `removeDuress` repeats: `runOperation` launches in `viewModelScope`, which is
                // Main, so without this the Argon2 derivation for the duress slot and the re-seal
                // of every vault container run on the thread that draws the UI.
                withContext(Dispatchers.Default) {
                    DuressUnlocker.setupDuress(
                        lockManager = lockManager,
                        duressPassword = password,
                        // Seeded, not empty: a decoy that opens onto nothing tells a coercer a real
                        // vault is being withheld (UI/UX spec §10.3). Items stay editable like any
                        // other.
                        decoyVault = DecoyVaultSeed.build(now, context),
                    )
                }
            } catch (collision: DuressPasswordCollisionException) {
                // Named, not generic. The constraint is real and the user cannot guess it: they had
                // been told only "That didn't go through. Try again.", which followed literally
                // reproduces the same failure. Nothing leaks — this runs only in an authenticated
                // real session, and the duress rows do not exist in a decoy one.
                throw OperationFailure(
                    SettingsMessage.Simple(R.string.settings_error_duress_same_as_master),
                )
            }
            SettingsMessage.Simple(R.string.settings_message_duress_configured)
        }
    }

    /**
     * Turns duress off: the decoy is overwritten with random filler and the duress password stops
     * opening anything.
     *
     * `removeDuress` has existed and been tested since the feature landed, with no caller — the same
     * shape `panicLock()` had before it was wired, and the reason this codebase has a "wire it or delete it"
     * rule for unreachable code. Until now the only exit from duress was to leave it configured forever.
     *
     * Refused in the decoy session for the same reason [setupDuress] is, and silently: an error
     * naming the feature would tell whoever is holding the phone that the vault they were shown is
     * not the real one.
     */
    fun removeDuress(vaultPassword: CharArray) {
        if (_state.value.isDuressSession || _state.value.isWorking) {
            vaultPassword.fill('\u0000')
            return
        }
        runOperation {
            requireVaultPassword(vaultPassword)
            // Off the main thread, explicitly. `runOperation` launches in `viewModelScope`, which
            // is `Dispatchers.Main.immediate`, so this ran from the click handler itself:
            // `StaleContainerSweeper.sweepRemovedDecoy` AEAD-authenticates and re-seals both
            // regions of `vault.enc`, the `.bak` and all five rolling snapshots, and the app ANRed
            // for minutes (device, 2026-09-20). The sweep on the next line already did this; the
            // call that does the work did not.
            withContext(Dispatchers.Default) { DuressUnlocker.removeDuress(lockManager) }
            // The other end of the pair in [setupDuress]: with the decoy gone the sweep is safe
            // again, and everything it had to leave behind for the life of the configuration is
            // reclaimable in one pass. Waiting for the next unlock would get there eventually,
            // which is why this is opportunistic and its failure is swallowed — the duress change
            // itself has already succeeded and must be reported that way.
            runCatching { withContext(Dispatchers.IO) { blobGarbageCollector.sweep() } }
            SettingsMessage.Simple(R.string.settings_message_duress_removed)
        }
    }

    fun enableBiometric(activity: FragmentActivity) {
        if (!_state.value.biometricAvailable) {
            setError(SettingsMessage.Simple(R.string.settings_error_biometric_unavailable))
            return
        }
        runOperation {
            // 2026-10-02 (S5-03): the Keystore key is minted invalidated-by-enrolment, so after a
            // fingerprint change `Cipher.init` throws on it forever. The only code that ever deleted
            // it was the unlock screen's, which runs only while a wrap file exists — so once
            // biometrics had been turned off, Enable answered "try again" on every attempt until
            // app data was cleared. A dead key opens nothing and guards nothing; it, and any wrap
            // sealed with it, is dropped and a fresh one minted, which is what a first enable does.
            val cipher = try {
                biometricUnlock.encryptCipher()
            } catch (invalidated: KeyPermanentlyInvalidatedException) {
                // Both stores are device-global and hold the real vault's enrolment; a decoy
                // session never touches them (see disableBiometric).
                if (_state.value.isDuressSession) throw invalidated
                DeviceKeyStore.delete(context)
                keystoreManager.deleteKey(KeystoreManager.BIO_DEK_ALIAS)
                _state.update { it.copy(biometricEnabled = false) }
                biometricUnlock.encryptCipher()
            }
            val authenticated = biometricUnlock.authenticate(
                activity = activity,
                cipher = cipher,
                // The system BiometricPrompt is an imperative Android API, not a composable, so it
                // is resolved with Context.getString rather than stringResource — same exception
                // UnlockViewModel's biometric prompt strings use.
                title = context.getString(R.string.settings_biometric_enable_button),
                subtitle = context.getString(R.string.settings_biometric_prompt_subtitle),
                negativeButtonText = context.getString(
                    com.localkeep.core.ui.R.string.core_ui_biometric_negative_button,
                ),
            )
            if (lockManager.enableBiometricUnlock(authenticated)) {
                _state.update { it.copy(biometricEnabled = true) }
                SettingsMessage.Simple(R.string.settings_message_biometric_enabled)
            } else {
                null
            }
        }
    }

    /**
     * Turns biometric unlock off by destroying the device-wrapped copy of the DEK.
     *
     * There was no way to do this at all: the row rendered its Enable button only when biometrics
     * were *off*, so once on it showed "On for this device" and nothing else. A user who enrolled
     * on a shared or work phone had no way back short of clearing app data — for a control the app
     * itself describes as an optional convenience.
     *
     * No re-authentication is required to turn it off, deliberately: this only ever *removes* an
     * unlock route, and the vault is already open. Demanding a fingerprint to stop trusting the
     * fingerprint is the wrong way round.
     */
    fun disableBiometric() {
        // Refused in a decoy session, the same way `removeDuress` is. DeviceKeyStore is
        // device-global rather than vault-scoped, so this deletes the *real* vault's device-wrapped
        // DEK — and `biometricEnabled` is read from that same store, so the decoy's row read "On"
        // because the real vault was enrolled. The row is now withheld in a decoy session too; this
        // is the half that stops the damage rather than the tell, and it returns silently because
        // there is no caller left to explain anything to.
        if (_state.value.isDuressSession) return
        runOperation {
            DeviceKeyStore.delete(context)
            // The key goes with the wrap (2026-10-02, S5-03): left behind, a later fingerprint
            // change invalidates it and the next Enable starts from a dead key.
            keystoreManager.deleteKey(KeystoreManager.BIO_DEK_ALIAS)
            _state.update { it.copy(biometricEnabled = false) }
            SettingsMessage.Simple(R.string.settings_message_biometric_disabled)
        }
    }

    /**
     * The re-authentication both duress changes are behind: the *current* vault password, checked
     * the way the invisible-items gate checks it.
     *
     * `reauthenticateForCurrentVault` consumes and wipes [password], selects the session's own slot
     * (so a decoy screen could never solicit the real secret), and shares the unlock screen's
     * throttle — [runOperation]'s `VaultLockedException` branch already reports the countdown. Run
     * on `Dispatchers.Default` because it is an Argon2id derivation, for the same reason the
     * password change and the duress calls themselves are.
     */
    private suspend fun requireVaultPassword(password: CharArray) {
        val proved = withContext(Dispatchers.Default) {
            lockManager.reauthenticateForCurrentVault(password)
        }
        if (!proved) {
            throw OperationFailure(
                SettingsMessage.Simple(R.string.settings_error_wrong_current_password),
            )
        }
    }

    /**
     * A failure a [runOperation] block can describe for itself.
     *
     * `settings_error_generic` is the right thing to say when nothing is known about what went
     * wrong, and the wrong thing to say when something is. The message here reaches the user, so
     * it must never carry an exception's own text — see [setError]'s callers.
     */
    private class OperationFailure(val uiMessage: SettingsMessage) : Exception()

    private fun runOperation(block: suspend () -> SettingsMessage?) {
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true, message = null, error = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { message ->
                    if (message == null) {
                        _state.update {
                            it.copy(isWorking = false, error = GENERIC_FAILURE)
                        }
                    } else {
                        // `error` is cleared here too: without it, an error left by an unrelated
                        // setter while this operation was in flight (restorePurchases, before its
                        // own guard above, was one such setter) would sit on screen next to this
                        // success message instead of being superseded by it.
                        _state.update { it.copy(isWorking = false, message = message, error = null) }
                    }
                }
                .onFailure { cause ->
                    if (cause is CancellationException) throw cause
                    // Backing out of the biometric prompt is not a failure, and must not be
                    // reported as one. `BiometricUnlock.authenticate` resumes with a
                    // `BiometricAuthException` for every non-success code, so tapping the prompt's
                    // "Use master password" button — or dismissing it — while turning biometric
                    // unlock on fell through to `else` below and answered a routine, offered choice
                    // with the coral "That didn't go through. Try again." `feature/unlock` has
                    // treated the same two codes as a choice since 2026-08-30; this is that
                    // decision, from the one shared classifier, on the enable side.
                    //
                    // Nothing is enrolled either way — `lockManager.enableBiometricUnlock` is never
                    // reached — so the row stays "off" and says nothing, which is what a cancelled
                    // action should do. Every other biometric error code still reports.
                    if (cause is BiometricAuthException && cause.isUserChoice()) {
                        _state.update { it.copy(isWorking = false, error = null, message = null) }
                        return@onFailure
                    }
                    val message = when (cause) {
                        is OperationFailure -> cause.uiMessage
                        // A sensor lockout during enrolment, which reached the catch-all and was
                        // answered with "That didn't go through. Try again." — advice that
                        // reproduces the failure, because retrying is exactly what the lockout
                        // refuses (device, 2026-09-20). The user-choice codes are already handled
                        // above; every other biometric code still falls through to generic,
                        // because nothing is known about them that helps.
                        is BiometricAuthException -> when (cause.code) {
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                            ->
                                SettingsMessage.Simple(R.string.settings_error_biometric_lockout)
                            else -> GENERIC_FAILURE
                        }
                        // An in-app Argon2id run that did not fit the heap (duress setup, a
                        // master-password change). Nothing was written; the generic "try again"
                        // would have the user reproduce it (device, 2026-09-14).
                        is KdfNeedsMoreMemoryException ->
                            SettingsMessage.Simple(R.string.settings_error_kdf_needs_more_memory)
                        // The throttle these operations share with unlock. It reports the one thing
                        // that helps — how long — and the generic text reports none of it.
                        is VaultLockedException ->
                            SettingsMessage.RetryAfter(
                                R.plurals.settings_error_retry_after,
                                ((cause.retryAfterMs + 999) / 1_000).toInt(),
                            )
                        // The vault auto-locked part-way through. Nothing was written — every
                        // operation checks the session before it writes — so the honest thing to
                        // say is "nothing changed, unlock and retry", not the generic failure,
                        // which reads like the vault broke. Reachable in ordinary use: changing the
                        // master password runs three key derivations, and the Aggressive preset's
                        // background grace is five seconds.
                        is VaultSessionEndedException ->
                            vaultOperationRes(cause.operation)
                                ?.let { operationRes ->
                                    SettingsMessage.SessionEnded(
                                        R.string.settings_error_vault_session_ended,
                                        operationRes,
                                    )
                                }
                                ?: SettingsMessage.Simple(
                                    R.string.settings_error_vault_session_ended_generic,
                                )
                        else -> GENERIC_FAILURE
                    }
                    // `message` is cleared here too, for the same reason the success branch above
                    // now clears `error`: a stale success/status message must not survive to sit
                    // next to this failure.
                    _state.update { it.copy(isWorking = false, error = message, message = null) }
                }
        }
    }

    private fun setError(message: SettingsMessage) {
        _state.update { it.copy(error = message, message = null) }
    }


    private companion object {
        const val MIN_PASSWORD_LENGTH = MIN_MASTER_PASSWORD_LENGTH

        /** Only for a failure nothing knows anything about. Anything else says what it was. */
        val GENERIC_FAILURE = SettingsMessage.Simple(R.string.settings_error_generic)

        private fun readAppVersion(context: Context): String = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }
}

/**
 * The floor both password routes enforce, and the one the form has to disable its submit on.
 *
 * Top level rather than inside the ViewModel's private companion because the screen needs it too:
 * the length refusal was raised only after `changePassword` had been called, by which point the
 * composable had already cleared all three wells, so a twelve-character rule cost the user
 * everything they had typed (device, 2026-09-20).
 */
internal const val MIN_MASTER_PASSWORD_LENGTH = 12

/**
 * Maps [VaultSessionEndedException.operation] — a deliberately-English literal from
 * VaultLockManager, which never reaches the UI as-is — to its localised phrase. Returns null for
 * a literal this module does not know, so the caller falls back to the generic sentence instead
 * of splicing English into a translated one (the defect this replaced: "Le coffre s'est
 * verrouillé en essayant de save your changes."). A new operation in VaultLockManager needs a
 * row here and in every settings strings.xml — VaultOperationResTest pins the known set.
 */
@StringRes
internal fun vaultOperationRes(operation: String): Int? = when (operation) {
    "prepare the Recovery Kit" -> R.string.settings_op_prepare_recovery_kit
    "save the Recovery Kit" -> R.string.settings_op_save_recovery_kit
    "save your changes" -> R.string.settings_op_save_changes
    "set up duress" -> R.string.settings_op_set_up_duress
    "remove duress" -> R.string.settings_op_remove_duress
    "inspect duress" -> R.string.settings_op_inspect_duress
    "update the encryption settings" -> R.string.settings_op_update_encryption_settings
    "change the master password" -> R.string.settings_op_change_master_password
    "rotate the encryption key" -> R.string.settings_op_rotate_encryption_key
    "enable biometric unlock" -> R.string.settings_op_enable_biometric_unlock
    else -> null
}

/**
 * Why BIOMETRIC_STRONG is refused, as a string resource, or null when it is not refused.
 *
 * Only [BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED] is something the user can fix, and it is
 * also the most common of these on a phone that has just been set up - which is exactly the case
 * the single "Unavailable on this device" line got wrong, by telling someone their hardware cannot
 * do a thing it can do. The remaining codes are mapped rather than collapsed because a sensor that
 * is busy this second and a phone with no sensor at all deserve different sentences, and neither
 * deserves an offer to go and enrol something.
 *
 * `BIOMETRIC_STATUS_UNKNOWN` and the rest fall to the generic line on purpose: the platform is
 * saying it does not know, and inventing a reason on its behalf is the failure this function exists
 * to undo.
 */
@StringRes
internal fun biometricUnavailableReason(status: Int): Int? = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> null
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.settings_biometric_summary_not_enrolled
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> R.string.settings_biometric_summary_no_hardware
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> R.string.settings_biometric_summary_sensor_busy
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
        R.string.settings_biometric_summary_needs_update
    else -> R.string.settings_biometric_summary_unavailable
}

/**
 * Whether LocalKeep is the device's current autofill service.
 *
 * False on a device with no AutofillManager at all, which is the honest answer there: the row
 * describes something that cannot be turned on, and claiming otherwise would be worse than saying
 * off.
 */
internal fun autofillServiceEnabled(context: Context): Boolean =
    context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
