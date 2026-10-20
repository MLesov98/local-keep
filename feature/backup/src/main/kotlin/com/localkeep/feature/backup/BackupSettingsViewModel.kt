package com.localkeep.feature.backup

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.common.map
import com.localkeep.core.crypto.Argon2Calibrator
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KdfRecalibration
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Vault
import com.localkeep.core.ui.LkUnlockNotices
import com.localkeep.core.vault.MasterPasswordChanger
import com.localkeep.core.vault.RewrapOutcome
import com.localkeep.core.vault.VaultKdfInspector
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.KdfNeedsMoreMemoryException
import com.localkeep.core.vault.VaultSessionEndedException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportMode { MERGE, REPLACE }

/**
 * What [BackupSettingsUiState.status] and [BackupSettingsUiState.error] actually are.
 *
 * [Res] and [Plural] are this ViewModel's own copy — kept as resource references, never
 * resolved strings, per the "ViewModels never hold display strings" rule; the composable
 * resolves them via `stringResource`/`pluralStringResource`. There used to be a fourth variant,
 * `Raw`, carrying an `AppError.message` straight through from VaultExporter/VaultImporter/
 * RollingBackupManager on every export/import/restore failure — rendered verbatim, unlocalized,
 * contradicting the rule that "AppError messages never reach the UI by design". Removed:
 * every failure path now goes through [AppError.toBackupMessage], which maps each known failure
 * literal to a real [Res] and falls back to [R.string.backup_error_generic] for anything else —
 * so nothing derived from an `AppError` reaches the screen as raw text again.
 */
/**
 * What the screen says once an import has finished, when no referenced file was missing.
 *
 * Merge has three outcomes per imported item and used to report none of them: "Import complete" was
 * the entire result copy on a path with no preview, no item list and no per-item toggles, so a user
 * merging a backup could not learn whether it had brought anything in at all (release sweep
 * 2026-09-20, finding 1C-F1).
 *
 * Replace has no per-item outcomes — it discards the vault and writes the file's records whole — and
 * kept the two words for that reason. But what it leaves behind is countable, and it is the one
 * thing a user restoring onto a new phone wants confirmed: device report C3 finding 4 (2026-10-04)
 * had Merge answer with counts and Replace, on the same file, with "Import complete". [replaced] is
 * what the vault list will show afterwards. The two words remain only for a Replace whose result
 * could not be read back.
 */
internal fun importCompleteMessage(counts: MergeCounts?, replaced: VaultHolds? = null): BackupMessage =
    when {
        counts != null -> BackupMessage.Res(
            R.string.backup_status_merge_complete,
            listOf(counts.added, counts.updated, counts.unchanged),
        )
        replaced != null -> BackupMessage.Res(
            R.string.backup_status_replace_complete,
            listOf(replaced.items, replaced.folders),
        )
        else -> BackupMessage.Res(R.string.backup_status_import_complete)
    }

/**
 * How many items and folders the vault holds, counted the way the vault list's header counts them.
 *
 * Through the visibility projection rather than the imported file: a concealed entry is not on the
 * list, and a number larger than the list would be how its existence got disclosed — the same rule
 * the import wizard's preview follows. Trash is not counted either; the list does not show it.
 */
internal data class VaultHolds(val items: Int, val folders: Int)

sealed interface BackupMessage {
    data class Res(@StringRes val res: Int, val args: List<Any> = emptyList()) : BackupMessage
    data class Plural(@PluralsRes val res: Int, val count: Long) : BackupMessage

    /**
     * A byte count to be rendered as a human file size ("4.5 MB") rather than a raw integer.
     *
     * Its own variant rather than a pre-formatted [Res] arg because sizing needs a Context, and a
     * ViewModel that resolves display text is the thing this hierarchy exists to avoid. The
     * composable does the formatting; this only carries the number and which string it goes in.
     */
    data class Size(@StringRes val res: Int, val bytes: Long) : BackupMessage

    /**
     * A finished `.vault` import: its [result] line, then — when [recoveryKitVoided] — the sentence
     * telling the user the Recovery Kit they hold for the file's vault no longer covers them.
     *
     * That sentence is `settings_password_changed_recovery_kit_voided`, reused as-is: it is Tier B
     * security copy and already verified in five languages, and it lives in `:feature:settings`,
     * which this module cannot depend on (it would drag Play Billing into an air-gapped module). So
     * the screen is handed its resource id by `:app`, and this carries only the fact. See
     * [VaultImporter.ImportResult.voidsRecoveryKit] for when the fact is true.
     */
    data class ImportComplete(val result: BackupMessage, val recoveryKitVoided: Boolean) : BackupMessage
}

/**
 * Maps a known [AppError.message] literal from VaultExporter/VaultImporter/RollingBackupManager
 * to the [BackupMessage.Res] that explains it, falling back to [R.string.backup_error_generic]
 * for anything not in the table below.
 *
 * Matching is on exact text rather than a prefix so a message that happens to embed a filename
 * cannot leak it into the UI through a partial match. That is why every restore failure used to
 * land in the fallback: RollingBackupManager interpolated the snapshot's filename into all of
 * them, so none could match, and three separate permanent conditions — an unreadable file, a file
 * that is not a vault, and a corrupt one — were all reported as "Something went wrong. Please try
 * again.", offering a retry that changes none of them. Those three now raise filename-free
 * literals and have entries below, exactly as every export and import branch already does. The one
 * restore failure still carrying a name is the unknown-exception catch, which belongs in the
 * fallback because nothing is known about it to say.
 */
private fun AppError.toBackupMessage(): BackupMessage.Res = when (message) {
    "vault file missing or structurally corrupt; backup recovery failed" ->
        BackupMessage.Res(R.string.backup_error_vault_file_missing)
    "failed to write export to the chosen location" ->
        BackupMessage.Res(R.string.backup_error_export_write_failed)
    // Distinct from the line above on purpose: that one sends the user to pick a different
    // folder, and this one sends them to free up space on the phone. Staging happens in
    // internal storage whatever destination was chosen, so reporting a full device as a
    // problem with "the location you chose" pointed at the one place that had room.
    "not enough free space on this device to stage the export" ->
        BackupMessage.Res(R.string.backup_error_export_device_full)
    "failed to build independent-passphrase export" ->
        BackupMessage.Res(R.string.backup_error_export_build_failed)
    "failed to read import file" ->
        BackupMessage.Res(R.string.backup_error_import_read_failed)
    "that file is too large to open on this device" ->
        BackupMessage.Res(R.string.backup_error_import_too_large)
    "could not open the file you chose" ->
        BackupMessage.Res(R.string.backup_error_import_open_failed)
    "not a valid .vault file" ->
        BackupMessage.Res(R.string.backup_error_import_invalid_file)
    "wrong password" ->
        BackupMessage.Res(R.string.backup_error_import_wrong_password)
    "failed to decrypt import file" ->
        BackupMessage.Res(R.string.backup_error_import_decrypt_failed)
    // Sits next to the line above because it is the case that line used to swallow. The password
    // is proven correct and the file is intact by the time this can be raised — see
    // [decryptThenRestore] — so it must not land in the generic string, which would leave the
    // user re-typing a password that was right.
    "this backup was written by a newer version of LocalKeep" ->
        BackupMessage.Res(R.string.backup_error_import_newer_version)
    // Not an attachment failure: the vault locked while the picker was open. Naming the real cause
    // matters because the remedy is different — unlock and pick again, not "your backup is broken".
    VAULT_LOCKED_DURING_IMPORT ->
        BackupMessage.Res(R.string.backup_error_import_vault_locked)
    // The picked file's own Argon2 cost does not fit this heap (2026-10-02). The same named message
    // every other in-app derivation gives for this, because "try again" reproduces it.
    IMPORT_KDF_NEEDS_MORE_MEMORY ->
        BackupMessage.Res(R.string.backup_error_kdf_needs_more_memory)
    "failed to restore attachments from import file" ->
        BackupMessage.Res(R.string.backup_error_import_attachments_failed)
    "merge failed" ->
        BackupMessage.Res(R.string.backup_error_merge_failed)
    "replace failed" ->
        BackupMessage.Res(R.string.backup_error_replace_failed)
    // The three restore failures. Each is permanent for the snapshot the user picked and each has
    // a different answer, which is what the generic "try again" took away from all of them: an
    // unreadable file, a file that is not a vault, and a corrupt one. The vault is untouched in
    // all three — `restoreVaultFile` validates before it overwrites — so the useful thing to say
    // is "this one, not you", and to point at the other snapshots on the same screen.
    "failed to read the chosen snapshot" ->
        BackupMessage.Res(R.string.backup_error_restore_read_failed)
    "that snapshot is not a valid vault file" ->
        BackupMessage.Res(R.string.backup_error_restore_invalid_file)
    "that snapshot is corrupted and was not restored" ->
        BackupMessage.Res(R.string.backup_error_restore_corrupt)
    // A well-formed snapshot from a newer LocalKeep — the container's own header decoded fine,
    // or (duress splice only) its payload schema did not. Neither CORRUPT nor INVALID_FILE would
    // be true here, and both send the user toward exactly the wrong next step (delete this one,
    // try an older one) instead of the one that actually opens it: update the app.
    "that snapshot was written by a newer version of LocalKeep" ->
        BackupMessage.Res(R.string.backup_error_restore_newer_version)
    else -> BackupMessage.Res(R.string.backup_error_generic)
}

data class BackupSettingsUiState(
    val disableCloudBackup: Boolean = false,
    val lastExportEpochMillis: Long? = null,
    val snapshots: List<File> = emptyList(),
    val pendingImportVault: Vault? = null,
    /**
     * How many distinct files [pendingImportVault]'s items reference that the staged backup's
     * media section did not contain. Set alongside [pendingImportVault] by [stageImport] and read
     * once by [confirmImport] to pick the completion message — never shown on its own, and never
     * hints at *why* a file might be missing (truncated, corrupted, or a decoy-session export all
     * look identical here, which is the point; see `VaultImporter.ImportResult`).
     */
    val pendingImportMissingBlobRefCount: Int = 0,
    val pendingImportVoidsRecoveryKit: Boolean = false,
    val status: BackupMessage? = null,
    val error: BackupMessage? = null,
    /**
     * True while an export, import, merge, replace or restore is in flight.
     *
     * Drives the pending indicator, but it earns its keep by disabling the triggers: every one of
     * these operations is a multi-second encrypt-and-write with no feedback of its own, so the
     * screen looked idle and a second tap was the natural thing to do. A double-tapped Replace or
     * Restore is not a wasted click — it runs the whole vault swap twice.
     */
    val isWorking: Boolean = false,
    /**
     * True while the in-flight operation is an export.
     *
     * Export is the one operation on this screen that never touches the local vault: it reads
     * `vault.enc`, stages a sealed copy in app-private storage, and streams that to the SAF
     * destination the user picked. It is also the one that can take arbitrarily long — the copy
     * "can be an upload to a cloud provider and takes as long as the network does", as
     * [VaultExporter.sweepStaleExportStages]'s own KDoc puts it — and that copy is a blocking
     * stdlib stream loop with no timeout and nothing a cancellation could land on.
     *
     * Those two facts together are why this flag exists. The screen blocks Back while [isWorking]
     * so a vault-replacing import or a restore cannot be walked out of half-done; applied to an
     * export it protected nothing and made a stalled provider a dead end whose only exit was
     * force-stopping the app. See [blocksExit].
     */
    val isExporting: Boolean = false,
    /**
     * True while [BackupSettingsViewModel.stageImport] is reading and decrypting a picked `.vault`.
     *
     * 2026-10-02: the same two facts as [isExporting]. Staging never touches the local vault — it
     * decrypts into memory and writes media blobs, which the blob sweep reclaims if nothing ever
     * references them — and its read is a blocking stream loop over whatever SAF provider the file
     * came from, with no timeout. Blocking Back for it made a stalled cloud provider a dead end. Leaving
     * clears the ViewModel, which cancels the stage and discards whatever it would have produced.
     */
    val isStagingImport: Boolean = false,
    /**
     * The master password checked out, so the screen may open the destination picker.
     *
     * A one-shot request, cleared by [BackupSettingsViewModel.consumeExportDestinationRequest] the
     * moment the screen acts on it. It exists because `ACTION_CREATE_DOCUMENT` creates the file as
     * soon as the user taps SAVE, so checking the password after the pick meant a refusal arrived
     * only once a 0-byte `.vault` had already been made in the folder they chose (F-39, sweep
     * `visual-20260915`). The check now runs first and the intent is launched on its result.
     */
    val exportDestinationRequested: Boolean = false,
    /**
     * The re-benchmark design.md §11.1 asks for after a restore or import onto different hardware.
     *
     * Null until a check has actually run — the screen must not imply an answer it does not have.
     * [KdfRecalibration.Verdict.KEEP] means checked and fine, which is a different statement from
     * "not checked" and is why this is not just a Boolean.
     */
    val kdfVerdict: KdfRecalibration.Verdict? = null,
    /** What the calibrator picked for this device, offered as the replacement. */
    val kdfSuggestion: KdfParams? = null,
    /** How long the *stored* parameters actually took to derive here. Shown, not guessed at. */
    val kdfMeasuredMillis: Long = 0L,
) {
    /**
     * Whether the operation in flight must not be abandoned part-way.
     *
     * A merge, a replace, a snapshot restore and a KDF re-wrap all rewrite the local vault, and the
     * screen holds the user there until they finish. An export does not — see [isExporting] — so it
     * is deliberately not one of them.
     */
    val blocksExit: Boolean get() = isWorking && !isExporting && !isStagingImport

    /**
     * Whether leaving must ask first, because leaving would throw work away.
     *
     * Distinct from [blocksExit], which is about an operation already *running* and refuses to let
     * go at all. This one is about an operation the user has half-finished: a `.vault` they picked,
     * whose password they typed, which has been read and decrypted and is sitting in memory waiting
     * for Merge or Replace. Back and the header chevron both dropped it without a word — and the
     * cost is not a lost keystroke, it is a SAF pick plus an Argon2id derivation over a file that
     * may be large.
     *
     * **Keyed on the staged vault, never on [isWorking].** A screen that asks on the way out while
     * an export is in flight would be asking about an operation that will finish on its own and
     * touches nothing local; a screen that asks whenever "something is happening" teaches people to
     * dismiss the dialog, which is how a confirmation stops being one. There is exactly one thing
     * on this screen whose loss is silent and unrecoverable-by-waiting, and this names it.
     *
     * The staged vault must stay unsaveable, which is why the answer is a dialog rather than a
     * "keep it for later": it lives in plain `remember`-shaped ViewModel state deliberately, so the
     * decrypted contents of somebody's backup never reach the instance-state Bundle.
     */
    val asksBeforeExit: Boolean get() = pendingImportVault != null
}

/**
 * The state after a completed "Check unlock speed" reading.
 *
 * Split out of [BackupSettingsViewModel.checkKdfCalibration] so what it leaves behind is testable
 * without an Argon2id derivation.
 */
internal fun BackupSettingsUiState.withKdfReading(
    verdict: KdfRecalibration.Verdict,
    suggestion: KdfParams,
    measuredMillis: Long,
): BackupSettingsUiState = copy(
    isWorking = false,
    kdfVerdict = verdict,
    kdfSuggestion = suggestion,
    kdfMeasuredMillis = measuredMillis,
    status = if (verdict == KdfRecalibration.Verdict.KEEP && measuredMillis > KdfRecalibration.UNUSABLY_SLOW_MS) {
        // KEEP covers two different situations and they must not read the same.
        // Found on device: an emulator measured 2830 ms and was told its settings
        // "suit this device" — true only in the sense that nothing weaker exists to
        // move to, which is not what that sentence says. A vault already at the
        // Argon2id floor cannot be eased further, and saying so is the honest
        // answer; pretending the wait is fine is not.
        BackupMessage.Res(R.string.backup_status_kdf_check_slow, listOf(measuredMillis))
    } else {
        // No banner for the good result: the Encryption section now answers in place, with a tick,
        // and a status line scrolls the screen to the top — away from the section it reports on.
        // The offers carry their own explanation and never had one.
        null
    },
)

/**
 * The state after [BackupSettingsViewModel.applyKdfRecalibration] has an outcome from the re-wrap.
 *
 * **[BackupSettingsUiState.kdfVerdict] goes back to null on the two paths that end the offer.** It used to be forced to KEEP, which kept the *old* measurement
 * beside the new parameters — after an OFFER_FASTER update the screen could say "slow here, already
 * at the lowest setting" about settings that had just been replaced — and, on the blocked path, put
 * "suits this device" directly under an error saying the change was refused. Null means "not
 * checked", which is the true statement in both cases and brings the check button back.
 */
internal fun BackupSettingsUiState.afterRecalibration(outcome: RewrapOutcome): BackupSettingsUiState =
    when (outcome) {
        RewrapOutcome.OK -> copy(
            isWorking = false,
            kdfVerdict = null,
            kdfSuggestion = null,
            kdfMeasuredMillis = 0L,
            status = BackupMessage.Res(R.string.backup_status_kdf_recalibrated),
        )
        RewrapOutcome.WRONG_PASSWORD -> copy(
            isWorking = false,
            error = BackupMessage.Res(R.string.backup_error_kdf_wrong_password),
        )
        // Never name duress: the feature only works while an observer does not learn it
        // exists. The reason given is true as far as it goes — this vault's setup does not
        // allow the change — and says nothing more.
        RewrapOutcome.DURESS_CONFIGURED -> copy(
            isWorking = false,
            kdfVerdict = null,
            kdfSuggestion = null,
            kdfMeasuredMillis = 0L,
            error = BackupMessage.Res(R.string.backup_error_kdf_recalibrate_blocked),
        )
    }

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    // Hilt provides `Application` in the SingletonComponent for any `@HiltAndroidApp`
    // Application subclass with no extra @Provides needed — used here for
    // filesDir/SharedPreferences access, same as VaultLockManager/ApplicationContext elsewhere.
    private val application: Application,
    private val vaultExporter: VaultExporter,
    private val vaultImporter: VaultImporter,
    private val rollingBackupManager: RollingBackupManager,
    private val vaultLockManager: VaultLockManager,
    private val vaultRepository: VaultRepository,
    // Defaulted so the seven unit tests that build this ViewModel by hand keep compiling with the
    // real thing, which is never reached unless a toggle is driven. Hilt ignores Kotlin defaults
    // and injects [BackupDataChangeNotifier]'s own constructor binding.
    private val backupDataChangeNotifier: BackupDataChangeNotifier =
        BackupDataChangeNotifier(application),
) : ViewModel() {

    /**
     * The system picker is a task the user is in the middle of, not a departure. Without this the
     * background grace timer — thirty seconds on Aggressive — runs while they browse for a file,
     * and the result callback returns to a locked vault, which drops the operation silently.
     * See [com.localkeep.core.ui.rememberExternalPickerLauncher], which pairs these for the caller.
     */
    fun onPickerOpened() {
        pickerOutstanding = true
        vaultLockManager.beginExternalPicker()
    }

    fun onPickerClosed() {
        pickerOutstanding = false
        vaultLockManager.endExternalPicker()
    }

    /** True from a picker opening until it answers; read only by [onScreenDisposed]. */
    private var pickerOutstanding = false

    /**
     * The screen has left composition.
     *
     * A picker still outstanding at that moment will answer a screen that is not there, so the pick
     * is lost — which in practice means the vault locked while the user was choosing a file (the
     * lock replaces this screen). The notice is queued for the next unlock, which is the first time
     * anyone can read it; nothing was written, and the sentence says so. Not for an activity
     * recreation ([isChangingConfigurations]): the same ViewModel and launcher come back and the
     * pick is delivered, so a notice would be false.
     */
    fun onScreenDisposed(isChangingConfigurations: Boolean) {
        if (pickerOutstanding && !isChangingConfigurations) {
            LkUnlockNotices.queue(R.string.backup_notice_locked_while_choosing_file)
        }
    }


    private val _state = MutableStateFlow(
        BackupSettingsUiState(
            disableCloudBackup = BackupPreferences.isCloudBackupDisabled(application),
            lastExportEpochMillis = BackupPreferences.lastExportEpochMillis(application),
        ),
    )
    val state: StateFlow<BackupSettingsUiState> = _state.asStateFlow()

    init {
        // listSnapshots() lists a directory on disk; ViewModel construction runs on the main
        // thread (Hilt resolves it synchronously off viewModel()/hiltViewModel()), so that
        // listing used to run as disk IO on the thread that draws. Off Dispatchers.IO here;
        // the field starts at emptyList() above and this fills it in once the listing returns.
        viewModelScope.launch {
            val snapshots = withContext(Dispatchers.IO) { rollingBackupManager.listSnapshots() }
            _state.value = _state.value.copy(snapshots = snapshots)
        }
        // Mirrored out of the ViewModel because the reader is `MainActivity`'s lock collector,
        // which cannot reach a ViewModel scoped to a nav entry it is about to pop. Collected rather
        // than set at each of the five sites that assign `pendingImportVault`, so the mirror cannot
        // drift from the state it mirrors — the drift is the whole defect class here. See
        // [BackupStaging].
        viewModelScope.launch {
            state.collect { BackupStaging.setStaged(it.pendingImportVault != null) }
        }
    }

    /**
     * The staged vault dies with this ViewModel, so the flag has to as well.
     *
     * Without it a screen destroyed for some reason other than a lock — a process-death restore
     * that never replays the stage — would leave the mirror reading `true`, and the next lock would
     * owe a notice for work nobody was doing. `take()` on the reading side makes that at most one
     * spurious notice; this makes it none.
     */
    override fun onCleared() {
        BackupStaging.setStaged(false)
        super.onCleared()
    }

    fun setDisableCloudBackup(disabled: Boolean) {
        BackupPreferences.setCloudBackupDisabled(application, disabled)
        // In BOTH directions. The preference is only read by `LocalKeepBackupAgent.onFullBackup`,
        // which Android calls on its own schedule, so until a pass runs the setting the user just
        // chose is not the one in force: OFF leaves a vault already in Google's backup set there,
        // and ON leaves it out. This does not upload anything and promises no time — it marks the
        // app as due, which is the most this setting can do about the copy that already exists.
        backupDataChangeNotifier.dataChanged()
        _state.value = _state.value.copy(
            disableCloudBackup = disabled,
            // Same acknowledgement the Settings screen gives for this preference, so the two
            // surfaces onto one setting do not report it differently.
            status = if (disabled) {
                BackupMessage.Res(R.string.backup_status_cloud_disabled)
            } else {
                BackupMessage.Res(R.string.backup_status_cloud_enabled)
            },
            error = null,
        )
    }

    /**
     * Re-benchmarks the vault's stored Argon2id cost against this device (design.md §11.1).
     *
     * The cost is chosen once, at creation, on whatever phone the user had then, and then travels
     * with the vault for its whole life — through every export, restore and new device. On a slower
     * phone that can make unlocking take seconds; on a faster one it quietly hands an offline
     * attacker the same speed-up the user got.
     *
     * Runs off the main thread and deliberately only on demand: it costs a real Argon2 derivation,
     * so it belongs where the user just restored something, not on every screen open.
     */
    fun checkKdfCalibration() {
        if (_state.value.isWorking) return
        // `status = null` as well as `error`, like every other operation that starts here. This is
        // the one that writes no status of its own, so a completed export's "Exported 4.5 MB" used
        // to sit on screen for the whole Argon2id derivation and describe an operation that had
        // already finished — and it is what the screen's in-flight note keys off (see the
        // `isWorking && status == null` branch in BackupSettingsScreen).
        _state.value = _state.value.copy(isWorking = true, error = null, status = null)
        viewModelScope.launch {
            val reading = withContext(Dispatchers.Default) {
                runCatching {
                    val stored = VaultKdfInspector.storedParams(application) ?: return@runCatching null
                    val measured = VaultKdfInspector.timeDerivation(stored)
                    val calibrated = Argon2Calibrator.calibrate()
                    Triple(KdfRecalibration.assess(stored, calibrated, measured), calibrated, measured)
                }.getOrNull()
            }
            _state.value = if (reading == null) {
                _state.value.copy(
                    isWorking = false,
                    error = BackupMessage.Res(R.string.backup_error_kdf_check_failed),
                )
            } else {
                _state.value.withKdfReading(reading.first, reading.second, reading.third)
            }
        }
    }

    /** Applies the recalibrated parameters. Consumes and wipes [masterPassword]. */
    fun applyKdfRecalibration(masterPassword: CharArray) {
        val target = _state.value.kdfSuggestion
        if (target == null || _state.value.isWorking) {
            Zeroizer.wipe(masterPassword)
            return
        }
        _state.value = _state.value.copy(isWorking = true, error = null, status = null)
        viewModelScope.launch {
            // Two Argon2id derivations run inside recalibrateKdf; keep them off the main thread,
            // matching checkKdfCalibration above. viewModelScope defaults to Main.immediate, so
            // without this the derivations would freeze the UI (and can ANR) while Apply is pressed.
            val outcome = try {
                withContext(Dispatchers.Default) {
                    MasterPasswordChanger.recalibrateKdf(vaultLockManager, masterPassword, target)
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: IllegalStateException) {
                // Broader than export's two named catches because this path can raise a third
                // thing: `rewrapKdfParams` opens on `realSessionSnapshot`, which reports a locked
                // vault with a bare `error("vault is locked")`. All three are IllegalStateException
                // and none was caught, so each one left this launch uncaught — process death, the
                // same defect export had.
                _state.value = _state.value.copy(
                    isWorking = false,
                    error = if (cause is VaultSessionEndedException) {
                        BackupMessage.Res(R.string.backup_error_export_vault_locked)
                    } else if (cause is KdfNeedsMoreMemoryException) {
                        // The re-wrap derives twice beside the open UI and did not fit the heap;
                        // nothing was written (device, 2026-09-14).
                        BackupMessage.Res(R.string.backup_error_kdf_needs_more_memory)
                    } else {
                        // Covers the shared master-password backoff (VaultLockedException, whose
                        // countdown needs a plural this module does not have) and any other
                        // IllegalStateException, about which nothing specific is known.
                        BackupMessage.Res(R.string.backup_error_generic)
                    },
                    status = null,
                )
                return@launch
            } catch (_: IOException) {
                // The write itself, which the arm above cannot reach. `rewrapKdfParams` ends in
                // `AtomicVaultWriter.write`, whose `FileOutputStream` constructor, `fd.sync()` and
                // two `Files.move(ATOMIC_MOVE)` calls are all plain IOException — a full disk, a
                // filesystem that will not fsync, a move the platform refuses to make atomic. None
                // of those is an IllegalStateException, so each escaped this launch uncaught, and
                // there is no CoroutineExceptionHandler and no default uncaught-exception handler
                // anywhere in this app to catch it: the process died. Same defect as the three
                // above, one exception family later.
                //
                // Nothing is lost when it happens — the writer stages `vault.enc.tmp` and fsyncs it
                // before either rename, and `VaultLoader.loadCandidates` reads `vault.enc`, then
                // `.tmp`, then `.bak` — so the honest thing to say is the generic retryable
                // failure, not a data-loss warning. `isWorking = false` matters as much as the
                // message: without it the screen stays behind its own in-flight guard forever.
                _state.value = _state.value.copy(
                    isWorking = false,
                    error = BackupMessage.Res(R.string.backup_error_generic),
                    status = null,
                )
                return@launch
            }
            _state.value = _state.value.afterRecalibration(outcome)
        }
    }

    /**
     * Removes the document the file picker created, but only while it is still empty.
     *
     * `ACTION_CREATE_DOCUMENT` makes the file the moment the user taps SAVE, and the export's
     * remaining checks — the vault session, the passphrase's own derivation — run after that. The
     * master password no longer does; that one moved in front of the picker, which is the case this
     * cleanup no longer has to cover (F-39). A refusal therefore left
     * a 0-byte `.vault` sitting in whatever folder the user chose, named like a backup and never
     * mentioned on screen. For an app whose recovery story is "you have an export", a folder filling
     * up with plausible empty ones is its own hazard; two were produced in a single QA session on
     * 2026-09-11, and two more from the 2026-08-30 sweep were still there.
     *
     * The zero-length guard is what makes deleting safe rather than clever: whatever the provider
     * did with the name the user typed, a file of no bytes holds nothing, so removing it cannot cost
     * them anything that still exists. Anything non-empty is left strictly alone.
     *
     * Best-effort throughout. A provider that refuses the delete leaves the old behaviour, which is
     * the thing being improved on, not a regression.
     */
    private fun discardEmptyExport(destination: Uri) {
        runCatching {
            val resolver = application.contentResolver
            val length = resolver.openFileDescriptor(destination, "r")?.use { it.statSize } ?: return
            if (length != 0L) return
            DocumentsContract.deleteDocument(resolver, destination)
        }
    }

    /**
     * Re-verifies identity (§D), and only on success asks the screen to open the destination picker.
     *
     * The check itself is unchanged — it is the same [VaultLockManager.reauthenticate] call [export]
     * used to make — but it has moved in front of the intent. `ACTION_CREATE_DOCUMENT` creates the
     * document the moment the user taps SAVE, so running it afterwards meant a wrong password was
     * answered only once the user had browsed for a folder, named a file, and had a 0-byte `.vault`
     * made for them in it. [discardEmptyExport] cleaned that up and still does for the failures that
     * can only be found later; this removes the one that never needed a destination at all (fixed
     * 2026-09-15).
     *
     * Consumes and wipes [masterPassword] on every return path, including the refused one, for the
     * reason [export] documents about double taps.
     */
    fun requestExportDestination(masterPassword: CharArray) {
        if (_state.value.isWorking) {
            Zeroizer.wipe(masterPassword)
            return
        }
        // `isExporting` as well as `isWorking`, so this derivation does not block Back: it is part
        // of the one operation on this screen that never touches the local vault. `status = null`
        // leaves the screen's generic in-flight note to speak for it — nothing is being exported
        // yet, and saying "Exporting…" here would name an operation that may not happen.
        _state.value = _state.value.copy(
            isWorking = true,
            isExporting = true,
            status = null,
            error = null,
        )
        viewModelScope.launch {
            try {
                _state.value = if (vaultLockManager.reauthenticate(masterPassword)) {
                    _state.value.copy(exportDestinationRequested = true, error = null)
                } else {
                    // The same message the post-pick refusal used to carry, in the same slot. "Enter
                    // it again" still holds: the screen clears the field on the way into this call.
                    _state.value.copy(
                        error = BackupMessage.Res(R.string.backup_error_export_wrong_password),
                        status = null,
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (_: VaultLockedException) {
                // The shared master-password backoff, which this screen arms in ordinary use — the
                // form clears the field after every attempt, so a run of wrong entries is the
                // expected interaction. Generic rather than a countdown for the reason [export]
                // records: that rendering needs a plural this module does not have.
                _state.value = _state.value.copy(
                    error = BackupMessage.Res(R.string.backup_error_generic),
                    status = null,
                )
            } catch (_: VaultSessionEndedException) {
                _state.value = _state.value.copy(
                    error = BackupMessage.Res(R.string.backup_error_export_vault_locked),
                    status = null,
                )
            } catch (_: KdfNeedsMoreMemoryException) {
                // The re-authentication's Argon2id run did not fit beside the open UI. No
                // destination exists yet, so unlike [export] there is nothing to discard.
                _state.value = _state.value.copy(
                    error = BackupMessage.Res(R.string.backup_error_kdf_needs_more_memory),
                    status = null,
                )
            } finally {
                Zeroizer.wipe(masterPassword)
                _state.value = _state.value.copy(isWorking = false, isExporting = false)
            }
        }
    }

    /** One-shot: the screen has opened the picker [requestExportDestination] approved. */
    fun consumeExportDestinationRequest() {
        _state.value = _state.value.copy(exportDestinationRequested = false)
    }

    fun export(destination: Uri, exportPassphrase: CharArray?) {
        // A refused call still owns the arrays it was handed. Returning without wiping them would
        // make a double tap the one way to leave a passphrase sitting in the heap — the same
        // shape as SettingsViewModel.changePassword, which wipes on its rejected path too.
        if (_state.value.isWorking) {
            Zeroizer.wipe(exportPassphrase)
            return
        }
        // 2026-10-02: an empty array is not "no passphrase". Null is how the screen asks for a
        // master-password export; an empty one arrives when the separate-passphrase box is ticked and
        // the well came back blank — an activity recreated while the picker was open drops the
        // passphrase (plain `remember`) and keeps the tick (saveable). That used to fall through to a
        // byte copy of vault.enc reported as an ordinary success: the copy meant to be sealed under its
        // own passphrase, openable with the master password instead. Refused, nothing written.
        if (exportPassphrase != null && exportPassphrase.isEmpty()) {
            discardEmptyExport(destination)
            _state.value = _state.value.copy(
                error = BackupMessage.Res(R.string.backup_error_export_build_failed),
                status = null,
            )
            return
        }
        _state.value = _state.value.copy(
            isWorking = true,
            isExporting = true,
            status = BackupMessage.Res(R.string.backup_status_exporting),
            error = null,
        )
        viewModelScope.launch {
            try {
                // No re-authentication here: it ran in [requestExportDestination], which is what
                // approved this destination in the first place. The vault can still end mid-export
                // — the catches below are unchanged for exactly that.
                val result = if (exportPassphrase == null) {
                    vaultExporter.exportWithMasterPassword(destination)
                } else {
                    // A copy, not the live session array. The export calibrates Argon2id and
                    // derives its KEK before it first reads the DEK, and an auto-lock landing in
                    // that window zeroes the manager's array underneath it — see
                    // [VaultLockManager.sessionSnapshotOrNull] for what that wrote to disk.
                    val session = vaultLockManager.sessionSnapshotOrNull()
                    if (session == null) {
                        discardEmptyExport(destination)
                        _state.value = _state.value.copy(
                            error = BackupMessage.Res(R.string.backup_error_export_vault_locked),
                            status = null,
                        )
                        return@launch
                    }
                    try {
                        vaultExporter.exportWithPassphrase(
                            destination,
                            session.vault,
                            session.dek,
                            exportPassphrase,
                        )
                    } finally {
                        Zeroizer.wipe(session.dek)
                    }
                }
                _state.value = when (result) {
                    is Outcome.Success -> _state.value.copy(
                        // "Exported 4.5 MB", not "Exported 4728193 bytes". The raw count was the
                        // only place in the app that reported a size in bytes to a person — even
                        // feature/files already renders KB/MB/GB — and a seven-digit byte figure
                        // tells nobody whether their export worked.
                        status = BackupMessage.Size(
                            R.string.backup_status_exported,
                            result.value.sizeBytes,
                        ),
                        lastExportEpochMillis = BackupPreferences.lastExportEpochMillis(application),
                        error = null,
                    )
                    is Outcome.Failure -> {
                        discardEmptyExport(destination)
                        _state.value.copy(
                            error = result.error.toBackupMessage(),
                            status = null,
                        )
                    }
                }
            } catch (cause: CancellationException) {
                // First, and rethrown: a cancelled scope is not a failure to report, and swallowing
                // it here would leave the ViewModel claiming an error while it is being destroyed.
                // Same order SettingsViewModel.runOperation uses.
                throw cause
            } catch (_: VaultLockedException) {
                // The master-password backoff, which this screen arms in ordinary use. Its trigger
                // moved to [requestExportDestination] with the re-authentication; this stays as a
                // guard rather than being deleted with it, because a throwable escaping
                // viewModelScope has nothing to land on — the app installs no
                // CoroutineExceptionHandler and no default uncaught-exception handler — so it took
                // the process down when there was no catch here at all.
                //
                // Reported generically rather than as SettingsViewModel's "retry in N seconds":
                // that rendering needs a plural this module does not have, and a new string cannot
                // ship without its five translations.
                _state.value = _state.value.copy(
                    error = BackupMessage.Res(R.string.backup_error_generic),
                    status = null,
                )
            } catch (_: VaultSessionEndedException) {
                // The vault auto-locked part-way through — long enough Argon2 work sits inside an
                // export for the background grace to land in it. Nothing was written, so saying the
                // vault is locked is the whole truth.
                _state.value = _state.value.copy(
                    error = BackupMessage.Res(R.string.backup_error_export_vault_locked),
                    status = null,
                )
            } catch (_: KdfNeedsMoreMemoryException) {
                // An Argon2id run — the export passphrase's own KEK derivation — did not fit beside
                // the open UI. Until 2026-09-14 this was a bare OutOfMemoryError escaping
                // viewModelScope — the process died on the export button on a 48 MiB heap. The
                // picker has already created the destination, and nothing was written into it.
                discardEmptyExport(destination)
                _state.value = _state.value.copy(
                    error = BackupMessage.Res(R.string.backup_error_kdf_needs_more_memory),
                    status = null,
                )
            } finally {
                // Defensive: exportWithPassphrase already wipes this on the path that reaches it,
                // but the early "vault is locked" return above never calls it — wipe unconditionally
                // here too so no code path skips zeroing (§ no secret ever left unwiped). Re-wiping
                // an already-zeroed array is a harmless no-op.
                Zeroizer.wipe(exportPassphrase)
                _state.value = _state.value.copy(isWorking = false, isExporting = false)
            }
        }
    }

    /**
     * Decrypts the picked file; the caller then chooses Merge or Replace via [confirmImport].
     *
     * Takes ownership of [password] and wipes it on every return path. The caller hands over a
     * fresh `toCharArray()` and never touches it again, and neither this function nor
     * `VaultImporter.readVault` used to zero it — so an import passphrase outlived the import.
     */
    fun stageImport(source: Uri, password: CharArray) {
        if (_state.value.isWorking) {
            Zeroizer.wipe(password)
            return
        }
        _state.value = _state.value.copy(
            isWorking = true,
            isStagingImport = true,
            status = BackupMessage.Res(R.string.backup_status_reading_file),
            error = null,
        )
        viewModelScope.launch {
            try {
                _state.value = when (val result = vaultImporter.readVault(source, password)) {
                    is Outcome.Success -> _state.value.copy(
                        // The status was left at "Reading the file…" here, so the screen kept
                        // reporting an operation still running after it had finished — the user
                        // stares at "Reading…" while deciding Merge vs Replace. Resolve it to a
                        // completed-reading message.
                        pendingImportVault = result.value.vault,
                        pendingImportMissingBlobRefCount = result.value.missingBlobRefCount,
                        pendingImportVoidsRecoveryKit = result.value.voidsRecoveryKit,
                        status = BackupMessage.Res(R.string.backup_status_file_read),
                        error = null,
                    )
                    // Clears the staged vault as well as reporting the failure. It used to leave
                    // whatever a previous *successful* stage had put there, so a second pick that
                    // failed — wrong password for the new file, or an unreadable one — answered
                    // with an error while Merge and Replace stayed armed on the file staged
                    // before it. Confirming then applied the earlier file, which for Replace is
                    // the app's one vault-discarding act, performed on a vault the user believed
                    // they had just replaced the selection of. (Recoverable — the rolling
                    // snapshots on this same screen hold vault.enc, though not media — but not
                    // what was asked for.)
                    //
                    // [pendingImportMissingBlobRefCount] goes stale by the same route and is
                    // cleared with it: left behind, it would describe the discarded file and
                    // decide the *next* import's completion message.
                    is Outcome.Failure -> _state.value.copy(
                        pendingImportVault = null,
                        pendingImportMissingBlobRefCount = 0,
                        pendingImportVoidsRecoveryKit = false,
                        error = result.error.toBackupMessage(),
                        status = null,
                    )
                }
            } finally {
                Zeroizer.wipe(password)
                _state.value = _state.value.copy(isWorking = false, isStagingImport = false)
            }
        }
    }

    fun confirmImport(mode: ImportMode) {
        val pending = _state.value.pendingImportVault ?: return
        val missingBlobRefCount = _state.value.pendingImportMissingBlobRefCount
        val voidsRecoveryKit = _state.value.pendingImportVoidsRecoveryKit
        if (_state.value.isWorking) return
        _state.value = _state.value.copy(
            isWorking = true,
            // Named per mode: "Replacing this vault…" is the one the user most needs to see is
            // still running, because it is the one that destroys what was there.
            status = if (mode == ImportMode.MERGE) {
                BackupMessage.Res(R.string.backup_status_merging)
            } else {
                BackupMessage.Res(R.string.backup_status_replacing_vault)
            },
            error = null,
        )
        viewModelScope.launch {
            try {
                val result: Outcome<MergeCounts?> = when (mode) {
                    ImportMode.MERGE -> vaultImporter.mergeInto(vaultRepository, pending)
                    ImportMode.REPLACE -> vaultImporter.replaceWith(vaultRepository, pending).map { null }
                }
                // Read back after the write, not counted from the file: see [VaultHolds]. A read
                // that fails — the vault locked in the instant after the save — costs only the
                // numbers, never the completion line.
                val replaced = if (mode == ImportMode.REPLACE && result is Outcome.Success) {
                    runCatching {
                        vaultRepository.visibilitySnapshot().let { VaultHolds(it.liveItems.size, it.liveFolders.size) }
                    }.getOrNull()
                } else {
                    null
                }
                _state.value = when (result) {
                    is Outcome.Success -> _state.value.copy(
                        pendingImportVault = null,
                        pendingImportMissingBlobRefCount = 0,
                        pendingImportVoidsRecoveryKit = false,
                        // A partial restore is still a completed one — this never blocks the
                        // import, only tells the user afterward that some referenced files were
                        // not in the backup they picked. See [BackupSettingsUiState
                        // .pendingImportMissingBlobRefCount].
                        // The missing-file warning still outranks the counts: "some of your
                        // photos are not in this backup" is a fact the user has to act on, and the
                        // merge tally is not. The Recovery Kit clause follows whichever line won:
                        // it is the other thing the user has to act on, and it outranks neither.
                        status = BackupMessage.ImportComplete(
                            result = if (missingBlobRefCount > 0) {
                                BackupMessage.Plural(
                                    R.plurals.backup_status_import_complete_missing_files,
                                    missingBlobRefCount.toLong(),
                                )
                            } else {
                                importCompleteMessage(result.value, replaced)
                            },
                            recoveryKitVoided = voidsRecoveryKit,
                        ),
                        error = null,
                        snapshots = rollingBackupManager.listSnapshots(),
                    )
                    is Outcome.Failure -> _state.value.copy(
                        error = result.error.toBackupMessage(),
                        status = null,
                    )
                }
            } finally {
                _state.value = _state.value.copy(isWorking = false)
            }
        }
    }

    fun cancelImport() {
        _state.value = _state.value.copy(
            pendingImportVault = null,
            pendingImportMissingBlobRefCount = 0,
            pendingImportVoidsRecoveryKit = false,
        )
    }

    /**
     * Restores a snapshot over the live vault, once [masterPassword] has proved who is asking.
     *
     * The gate is the change. Restoring was the only irreversible control in the app that asked for
     * nothing but a tap on a confirmation dialog — anyone holding the unlocked phone for a few
     * seconds could roll the vault back and take everything saved since with it (release sweep
     * 2026-09-20, 1C-O4). Export, the KDF recalibration and both duress changes all ask; the one
     * that destroys data did not.
     *
     * [VaultLockManager.reauthenticate], the same call [requestExportDestination] makes, and for
     * the same two reasons: it verifies the slot belonging to the current session — so a decoy
     * session is answered about the decoy and never solicits the real password — and it shares the
     * master password’s backoff, so this is not a free oracle. Consumes and wipes [masterPassword]
     * on every return path, refused ones included.
     */
    fun restoreSnapshot(file: File, masterPassword: CharArray) {
        if (_state.value.isWorking) {
            Zeroizer.wipe(masterPassword)
            return
        }
        _state.value = _state.value.copy(
            isWorking = true,
            status = BackupMessage.Res(R.string.backup_status_restoring),
            error = null,
        )
        viewModelScope.launch {
            try {
                // Before the restore, not beside it: a refused password must leave the vault
                // exactly as it was, and `restoreSnapshot` below force-locks and replaces the file.
                val authenticated = try {
                    vaultLockManager.reauthenticate(masterPassword)
                } catch (cause: CancellationException) {
                    throw cause
                } catch (_: VaultLockedException) {
                    // The shared master-password backoff. Generic rather than a countdown for the
                    // reason [export] records: that rendering needs a plural this module lacks.
                    _state.value = _state.value.copy(
                        error = BackupMessage.Res(R.string.backup_error_generic),
                        status = null,
                    )
                    return@launch
                } catch (_: VaultSessionEndedException) {
                    _state.value = _state.value.copy(
                        error = BackupMessage.Res(R.string.backup_error_export_vault_locked),
                        status = null,
                    )
                    return@launch
                } catch (_: KdfNeedsMoreMemoryException) {
                    _state.value = _state.value.copy(
                        error = BackupMessage.Res(R.string.backup_error_kdf_needs_more_memory),
                        status = null,
                    )
                    return@launch
                }
                if (!authenticated) {
                    _state.value = _state.value.copy(
                        error = BackupMessage.Res(R.string.backup_error_restore_wrong_password),
                        status = null,
                    )
                    return@launch
                }
                _state.value = when (val result = rollingBackupManager.restoreSnapshot(file)) {
                    // A restored vault may well have come from another phone, which is exactly the
                    // case design.md §11.1 wants re-benchmarked. Checked after the next unlock
                    // rather than now: the measurement needs the vault open.
                    //
                    // Success says nothing here, and cannot: `restoreVaultFile` force-locks the
                    // vault before returning OK, MainActivity's lock collector pops the graph
                    // inclusive, and that destroys this ViewModel — in practice cancelling the scope
                    // before the line above even resumes. A status set on this branch was a message
                    // written to a screen that no longer exists; the confirmation is posted after the
                    // next successful unlock instead, from the flag RollingBackupManager.restoreSnapshot
                    // records while it is still alive to record it.
                    is Outcome.Success -> _state.value
                    is Outcome.Failure -> _state.value.copy(
                        error = result.error.toBackupMessage(),
                        status = null,
                    )
                }
            } finally {
                _state.value = _state.value.copy(isWorking = false)
            }
        }
    }
}
