package com.localkeep.feature.importwizard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.VersionedHeader
import com.localkeep.core.import.AegisJsonImporter
import com.localkeep.core.import.BitwardenJsonImporter
import com.localkeep.core.import.CsvColumnMapper
import com.localkeep.core.import.CsvImporter
import com.localkeep.core.import.DashlaneImporter
import com.localkeep.core.import.EnpassImporter
import com.localkeep.core.import.ImportDefaultTitles
import com.localkeep.core.import.ImportFailureReason
import com.localkeep.core.import.ImportMerger
import com.localkeep.core.import.ImportRejection
import com.localkeep.core.import.ImportedFile
import com.localkeep.core.import.Importer
import com.localkeep.core.import.KdbxImporter
import com.localkeep.core.import.KeeperImporter
import com.localkeep.core.import.OnePuxImporter
import com.localkeep.core.import.PasswordProtectedImporter
import com.localkeep.core.import.ProtonPassJsonImporter
import com.localkeep.core.import.SkipReason
import com.localkeep.core.import.SkippedEntry
import com.localkeep.core.import.SourceFormat
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.File
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Identity
import com.localkeep.core.model.live
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.VaultBucketExhaustedException
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultMutator
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultTooLargeForDeviceException
import com.localkeep.feature.files.FileBlobStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One imported item as shown in the preview, with the user's keep/skip choice. */
data class PreviewRow(
    val id: UUID,
    val title: String,
    val subtitle: String,
    val disposition: ImportMerger.Disposition,
    val collidesWith: String?,
    val selected: Boolean,
    internal val item: VaultItem,
    /**
     * See [ImportMerger.PlannedItem.collidesWithVault] — vault collision, or same-file duplicate.
     *
     * Defaulted, and deliberately **last**: it is a `Boolean` and so is [selected], so a positional
     * construction with this one in the middle would bind `selected` to it silently and compile
     * clean. (The same shape cost a build earlier today, when a parameter added after a trailing
     * lambda captured the lambda instead.) Both current call sites use named arguments; putting it
     * here means a future one cannot get it wrong.
     */
    val collidesWithVault: Boolean = false,
)

/**
 * A resolved error to show on the import wizard. Never a raw String owned by this class — the
 * ViewModel exposes a resource id (or, for [FromImporter], a typed reason) and the composable
 * resolves it with `stringResource`, per `GeneratorViewModel.error` / `UnlockViewModel.UnlockError`.
 *
 * [FromImporter] used to carry an [com.localkeep.core.common.AppError]'s raw `message` on the
 * (false) assumption that it was already UI-safe text from a lower layer — it was not: an
 * `AppError.message` is internal-only by convention (never translated, never shown), and this was the one
 * place that echoed it to the user regardless, forever, in English (findings F2, F22). It now
 * carries the [ImportFailureReason] `core/import` actually authored the failure as.
 */
sealed interface ImportError {
    data class Simple(@param:StringRes val messageRes: Int) : ImportError
    data class FromImporter(val reason: ImportFailureReason) : ImportError

    /**
     * The picked file is LocalKeep's own `.vault` backup. Its own variant rather than a [Simple],
     * because the screen answers it with a way there — "Open Backup & export" — and not only with
     * a sentence. See [ImportWizardViewModel.isLocalKeepBackup].
     */
    data object LocalKeepBackup : ImportError
}

data class ImportUiState(
    val step: Step = Step.PICK_SOURCE,
    val source: ImportSource? = null,
    val fileName: String? = null,
    /**
     * The KDBX master password as typed. A `String` because `LkPasswordField` is a controlled
     * Compose text field bound by `value`/`onValueChange`, and Compose has no CharArray-native
     * equivalent — see [submitPassword] for the CharArray it is converted to at the point of use,
     * cleared here in the same call rather than left to linger for the length of the parse.
     */
    val password: String = "",
    /**
     * The display name of the KeePass key file the user added, or null when they added none.
     *
     * Only the *name* — the bytes are secret material and live off the state, beside the export's
     * own, so nothing about them can reach a `toString`, a crash report or the preview.
     */
    val keyFileName: String? = null,
    /**
     * What the password step is asking for: a KeePass *database* credential (true), or the *export*
     * password someone chose when they encrypted a Bitwarden export (false).
     *
     * It drives the step's three nouns and whether "Add key file" is offered at all — a key file is
     * a KeePass idea, and offering one on an export would send the user hunting for a second file
     * that has never existed. Decided from the picked file rather than from the source tile:
     * Bitwarden writes the same JSON encrypted or not, so only the bytes say which this is.
     */
    val passwordIsForDatabase: Boolean = true,
    val rows: List<PreviewRow> = emptyList(),
    val mode: ImportMerger.Mode = ImportMerger.Mode.MERGE,
    val isWorking: Boolean = false,
    val error: ImportError? = null,
    /**
     * Non-null only in MERGE mode, when some imported items were merged into entries the VAULT
     * already had. The composable builds the "N imported; M merged" sentence from this plus
     * [importedCount] with two `<plurals>` resources — see `import_done_notice_*`. Rows that
     * merged into an earlier row of the same file are not in here; see [repeatedInFileCount].
     */
    val mergedIntoExistingCount: Int? = null,
    /**
     * Non-null only in MERGE mode, when some selected rows merged into an earlier row of the same
     * file rather than into anything the vault held. Kept apart from [mergedIntoExistingCount]
     * because "merged into entries you already had" is a claim about the vault, and a file that
     * lists the same login twice was reporting it against an empty one.
     */
    val repeatedInFileCount: Int? = null,
    val importedCount: Int = 0,
    val sourceDeleted: Boolean = false,
    /** True while the Done step's delete-confirmation dialog is open. See [deleteSourceFile]. */
    val confirmDelete: Boolean = false,
    /** True while the Preview step's discard-confirmation dialog is open. See [back]. */
    val confirmDiscard: Boolean = false,
    /**
     * True when the file that was just parsed had to be decrypted with a password the user typed —
     * a `.kdbx`, or a Bitwarden export written as "Password protected".
     *
     * The Done step's cleanup warning is chosen on this, not on the source tile. It used to read
     * `source == KEEPASS`, so a password-protected Bitwarden export — the one shape that reaches
     * that step still encrypted on disk — was described to the user as "a plain, unencrypted copy of
     * your passwords sitting in your storage" (V3-04, 2026-09-17). That is a tier-A claim, it was
     * false as shown, and a security warning caught exaggerating stops being read.
     *
     * Asked of the importer and the bytes rather than of the tile, for the reason
     * [passwordIsForDatabase] is: a Bitwarden export is the same JSON either way.
     */
    val sourceEncryptedWithPassword: Boolean = false,
    val detectedFormat: SourceFormat? = null,
    /**
     * Entries the *parser* could not carry — a 1Password Bank Account, an Aegis HOTP code, a
     * Bitwarden type this build predates.
     *
     * These are the only skips left. A `proSkippedCount` used to sit beside them, covering the
     * Cards and Identities this wizard withheld from a free vault; that filter is gone (see the
     * note at the plan step) and the tier no longer decides what crosses over. The user still needs
     * to know about these before the next step offers to delete their export file.
     */
    val parserSkipped: List<SkippedEntry> = emptyList(),
    /**
     * Source columns the parser had no field for and carried across as custom fields — see
     * [com.localkeep.core.import.ParsedImport.carriedColumns].
     *
     * Reported beside [parserSkipped] and for the same reason: an import that quietly reshaped part
     * of the file read exactly like one that carried it whole, and the next step offers to delete
     * the export.
     */
    val carriedColumns: List<String> = emptyList(),
    /**
     * Two-factor seeds that crossed over as a hidden field rather than as a live code — see
     * [com.localkeep.core.import.ParsedImport.unusableTotpCount].
     */
    val unusableTotpCount: Int = 0,
    /**
     * Items that kept a KeePass field reference as literal text because nothing answered it — see
     * [com.localkeep.core.import.ParsedImport.unresolvedFieldReferenceCount].
     */
    val unresolvedFieldReferenceCount: Int = 0,
    /**
     * Logins whose only credential was a passkey, which no format here can carry — see
     * [com.localkeep.core.import.ParsedImport.passkeyOnlyLoginCount].
     */
    val passkeyOnlyLoginCount: Int = 0,
    /**
     * Folders the source filed its entries into, held from parse until apply.
     *
     * Not shown in the preview: the rows are what the user is deciding about, and a folder list
     * beside them would be a second thing to read for a decision they are not being asked to make.
     * It is carried so `applyImport` can create only the folders the finally-selected items need.
     */
    val parsedFolders: List<Folder> = emptyList(),
    /**
     * [com.localkeep.core.import.ParsedImport.timestampsKnown] for the file being previewed.
     *
     * Held from parse until apply because both halves of the merge have to agree about it — `plan`
     * decides what the preview says and `apply` decides what is written, and they would contradict
     * each other if only one were told. It also changes the wording of an ALREADY_CURRENT row: with
     * no dates in the file, "you already have a newer copy" is a claim nothing supports.
     */
    val timestampsKnown: Boolean = true,
) {
    /**
     * Redacted, like every secret-bearing type in `core/model`. This one holds the import source's master password, and the
     * compiler-generated `toString()` would print all of it — into a crash report, a `Log.d` added
     * during a debugging session, or an exception message that interpolates the state. Nothing
     * interpolates it today; this is the guard for the next person who does (V27, 2026-09-12).
     */
    override fun toString(): String = "ImportUiState(redacted)"

    enum class Step { PICK_SOURCE, INSTRUCTIONS, PASSWORD, PREVIEW, DONE }

    /** Skipped entries grouped by reason, most numerous first — how the preview lists them. */
    val skippedByReason: List<Pair<SkipReason, Int>>
        get() = parserSkipped.groupingBy { it.reason }.eachCount()
            .toList()
            .sortedByDescending { it.second }

    val selectedCount: Int get() = rows.count { it.selected }
    /**
     * Rows that match something already in the vault.
     *
     * Split from [fileDuplicateCount] because the preview used to add the two together and describe
     * the total as "N already looks like an entry you have" — which is a claim about the vault, and
     * was false for an export that simply listed the same login twice.
     */
    val vaultDuplicateCount: Int
        get() = rows.count { it.disposition != ImportMerger.Disposition.NEW && it.collidesWithVault }

    /** Rows that match an earlier row of the same export rather than anything in the vault. */
    val fileDuplicateCount: Int
        get() = rows.count { it.disposition != ImportMerger.Disposition.NEW && !it.collidesWithVault }

    /** Everything the merge would collapse, from either side — what "Skip duplicates" acts on. */
    val duplicateCount: Int
        get() = rows.count { it.disposition != ImportMerger.Disposition.NEW }
}

/**
 * **This ViewModel deliberately has no `SavedStateHandle`, and adding one would be a regression.**
 *
 * Recorded here because it has now been filed twice as a process-death defect, and read as an
 * oversight both times. It is not: a wizard that survives process death is exactly what this state
 * must not do.
 *
 * [ImportUiState] holds the source export's master password while the password step is open, and
 * from the parse onwards it holds every parsed row — usernames, passwords, TOTP secrets, notes — in
 * plaintext, because that is what the preview is showing the user. `SavedStateHandle` is written to
 * disk by the system, outside the vault and outside its encryption. So the fix for "the wizard
 * forgets where it was" would be to write the user's entire imported credential set, and the
 * password to their export file, into a file that no part of this app's threat model covers.
 *
 * What losing the state actually costs is one restart of a flow the user is standing in front of,
 * with their export file still on the device. That is the cheaper of the two failures by a distance
 * nothing about ergonomics closes.
 */
@HiltViewModel
class ImportWizardViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val lockManager: VaultLockManager,
    /**
     * The same encrypted blob store the item editor writes attachments through, for the files a
     * `.1pux` archive carries. `feature/import` has no store or key of its own, and inventing a
     * second write path for imported files is how two encodings of the same thing start to drift.
     */
    private val blobStore: FileBlobStore,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Raw source bytes, held only between file-pick and parse. Cleared as soon as parsing ends. */
    private var pendingBytes: ByteArray? = null
    private var pendingUri: Uri? = null

    /**
     * The KeePass key file's raw bytes, for a database whose credential is a password *and* a file.
     *
     * Held here rather than on [ImportUiState] for the same reason the export's bytes are: this is
     * the other half of a credential, and the state object is what the preview, a crash report and
     * `SavedStateHandle` would each reach. Wiped by [discardPending] like everything else.
     */
    private var pendingKeyFile: ByteArray? = null

    /**
     * Plaintext files the parsed export carried, keyed by the imported item they belong to.
     *
     * Off [ImportUiState] for the same reason the export's own bytes are: these are the user's
     * documents, and the state object is what the preview, a crash report and `SavedStateHandle`
     * would each reach. Sealed into the blob store in [applyImport] and wiped by [discardPending]
     * whether or not the import went ahead — backing out of the preview must not leave a passport
     * scan sitting in the heap.
     */
    private var pendingPayloads: Map<UUID, List<ImportedFile>> = emptyMap()

    /**
     * The in-flight read-or-parse coroutine, held so [back] can cancel it.
     *
     * Nothing retained it. Leaving the wizard did not stop
     * the parse — it only wiped `pendingBytes` out from under it — and the surviving coroutine went
     * on to write state to a screen the user had already left. Two ways that surfaced, and the
     * second needs no race at all:
     *
     *  - on failure, an error message landed on the *instructions* step the user had backed out to;
     *  - on success, `step = PREVIEW` yanked them forward into a preview of the import they had
     *    just cancelled.
     *
     * The blast radius is a KDBX parse, which the code below already notes runs Argon2 "for the
     * better part of a minute". Both the top-bar Back button and the system back gesture reach
     * [back], so gating the button alone would have fixed neither path.
     */
    private var parseJob: Job? = null

    /**
     * Brackets the system document picker so the auto-lock grace timer doesn't fire while the user
     * is browsing for their export. The picker is another process, so the app really is
     * backgrounded — but the user is still mid-task inside LocalKeep.
     */
    fun onPickerOpened() = lockManager.beginExternalPicker()

    fun onPickerClosed() = lockManager.endExternalPicker()

    /**
     * Launches [intent] to open the source app directly, from the Instructions step.
     *
     * Previously a bare `runCatching { }` at the call site with no failure branch: when no app
     * resolved the intent, the tap did nothing at all — no error, no fallback, no change of
     * state, so the user couldn't tell whether the app was broken, the tap missed, or their
     * device simply had nowhere to send the intent (F9). There is no fallback for "open this
     * other app" — nothing else to hand the user — so a failure becomes the same persistent
     * inline error every other failed system-intent launch in this app uses; see
     * `SettingsViewModel.openSystemSettings`.
     */
    fun openSourceApp(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            _state.update { it.copy(error = ImportError.Simple(R.string.import_error_open_app_failed)) }
        }
    }

    /**
     * Launches [url] in a browser, from the Instructions step's "Open the export page" button.
     * Reports whether it worked, unlike [openSourceApp], because this one has a real fallback:
     * the caller can copy the URL to the clipboard instead of showing an error with nothing
     * actionable in it — the wizard already has this exact pattern for "Copy these steps". Same
     * F9 gap as [openSourceApp]: previously a bare `runCatching { }` with the result discarded.
     */
    fun tryOpenUrl(url: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: Exception) {
        false
    }

    fun selectSource(source: ImportSource) {
        _state.update {
            it.copy(step = ImportUiState.Step.INSTRUCTIONS, source = source, error = null)
        }
    }

    fun back() = back(discardConfirmed = false)

    /**
     * @param discardConfirmed the user has already answered the discard dialog with "Discard", so
     *   the guard below must not raise it again. Without this the confirm path routed through the
     *   ordinary [back], which re-derives the guard from the step and the rows — neither of which
     *   confirming changes — and re-raised the very dialog it was answering. The button whose only
     *   job is to abandon the preview did nothing, every time, and a populated preview had no
     *   working backward exit at all. Found on a device by the pre-iOS sweep 2026-09-11; the
     *   source-text test that was meant to cover it asserted the flag was cleared before `back()`
     *   ran, which was true and which never mattered, because `back()` does not read the flag.
     */
    private fun back(discardConfirmed: Boolean) {
        // The applyImport *write* is deliberately NOT cancellable: cancel() lands mid-commit and the
        // runCatching inside applyImport would catch the CancellationException as a Throwable and
        // write an error onto whatever step is showing. So a write in flight still refuses Back,
        // keeping the write's completion state on the screen that asked for it.
        //
        // An in-flight *parse* is the opposite — cancelling it is the entire reason parseJob exists
        // (see cancelParseThenDiscard's KDoc: a KDBX Argon2 parse runs for the better part of a
        // minute and the user must be able to back out of it). A blanket `isWorking` refusal here
        // regressed that: because a parse holds isWorking = true for its whole life, it made the
        // cancel path below unreachable during the one scenario it was built for. So refuse Back only
        // for a non-parse `isWorking` (the write); let an active parse through to the cancel below.
        val current = _state.value
        val parsing = parseJob?.isActive == true
        when (val outcome = backOutcome(current, parsing, discardConfirmed)) {
            BackOutcome.Refuse -> return
            // `update` rather than a plain assignment: the lambda may be retried, and re-raising a
            // dialog is idempotent where the wipe below is not.
            BackOutcome.AskToDiscard -> _state.update { it.copy(confirmDiscard = true) }
            is BackOutcome.Leave -> {
                // Wiping the buffered export is a side effect, so it stays outside the state update.
                if (outcome.cancelParse) cancelParseThenDiscard()
                _state.value = outcome.next
            }
        }
    }

    /**
     * Stops an in-flight parse and only then wipes the buffer it was reading.
     *
     * **The ordering is the whole fix, and `cancel()` alone is not enough.** Kotlin cancellation is
     * cooperative: `parse` does its work inside `withContext(Dispatchers.Default)`, and the body of
     * that block is a *blocking* call into KeePassJava2. Cancelling does not interrupt Argon2
     * mid-derivation — it takes effect when the block returns. So wiping immediately after
     * `cancel()` would still zero the array while the parser is reading it, which is exactly the
     * bug, just with a shorter window.
     *
     * What `cancel()` does fix, deterministically and with no race involved, is the state writes:
     * once cancelled, `withContext` throws on resume and neither the failure branch nor the success
     * branch runs. Before this, backing out of a KDBX parse either dropped an error onto the
     * instructions screen the user had returned to, or set `step = PREVIEW` and pulled them into a
     * preview of the import they had just cancelled.
     *
     * So the wipe is deferred behind `join()`. The secret-hygiene guarantee is unchanged — the raw
     * export still gets zeroed — it simply happens once the reader has actually stopped.
     * [onCleared] still wipes unconditionally, which covers the case where the ViewModel dies before
     * the join completes.
     */
    private fun cancelParseThenDiscard() {
        val job = parseJob
        parseJob = null
        if (job == null || !job.isActive) {
            discardPending()
            return
        }
        job.cancel()
        viewModelScope.launch {
            job.join()
            discardPending()
        }
    }

    fun onPasswordChanged(value: String) = _state.update { it.copy(password = value) }

    fun setMode(mode: ImportMerger.Mode) = _state.update { it.copy(mode = mode) }

    fun toggleRow(id: UUID) {
        _state.update { current ->
            current.copy(
                rows = current.rows.map {
                    if (it.id == id) it.copy(selected = !it.selected) else it
                },
            )
        }
    }

    /** Bulk controls, because deselecting 300 duplicates one at a time is not a real option. */
    fun setAllSelected(selected: Boolean) {
        _state.update { current -> current.copy(rows = current.rows.map { it.copy(selected = selected) }) }
    }

    fun deselectDuplicates() {
        _state.update { current ->
            current.copy(
                rows = current.rows.map {
                    if (it.disposition == ImportMerger.Disposition.NEW) it else it.copy(selected = false)
                },
            )
        }
    }

    /**
     * Reads the picked file and either parses it straight away or, for an encrypted source, moves
     * to the password step.
     */
    fun onFilePicked(uri: Uri) {
        // The Instructions step's "Choose file" button had no `isWorking` guard, unlike its
        // siblings on Password/Preview/Done — so a second tap during the ~minute KDBX parse
        // re-entered here, overwrote `pendingBytes`/`pendingUri` and reassigned `parseJob`,
        // orphaning the first coroutine. Same guard as applyImport() and back().
        if (_state.value.isWorking) return
        val source = _state.value.source ?: return
        _state.update { it.copy(isWorking = true, error = null, mergedIntoExistingCount = null, repeatedInFileCount = null) }
        parseJob = viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // Capped WHILE reading, not after. `readBytes()` buffers the whole file
                        // first, so the size check only ever ran once the allocation it was meant
                        // to prevent had already happened — a user who picks a multi-gigabyte file
                        // pays for it before being told no. Same shape as the BreachFilter finding
                        // the fuzzer produced, and self-inflicted rather than attacker-supplied.
                        input.readAtMost(MAX_IMPORT_BYTES) ?: throw ImportFileTooLargeException()
                    } ?: error("couldn't open that file")
                }
            }
            val bytes = read.getOrElse { failure ->
                _state.update {
                    it.copy(
                        isWorking = false,
                        // `failure` is a raw Throwable from ContentResolver.openInputStream on a
                        // SAF Uri, and on several providers its message embeds the full content://
                        // Uri — which then renders on screen and is announced by TalkBack. The
                        // repo already treats this as a known bug class (see MediaErrors.kt and
                        // AttachmentPicker), and VaultImporter.readVault handles the identical
                        // call with a fixed string. Never interpolate this exception.
                        //
                        // The size cap is told apart (2026-10-02): it is the one read failure that is
                        // permanent and has an answer, and "Couldn't read that file" invited a retry
                        // that fails the same way. The importer's own too-large sentence says it.
                        error = if (failure is ImportFileTooLargeException) {
                            ImportError.FromImporter(
                                ImportFailureReason.ExportTooLarge(MAX_IMPORT_BYTES / (1024L * 1024L)),
                            )
                        } else {
                            ImportError.Simple(R.string.import_error_read_failed)
                        },
                    )
                }
                return@launch
            }
            pendingBytes = bytes
            pendingUri = uri
            _state.update { it.copy(fileName = displayName(uri)) }

            // Asked of the file, not only of the tile. `source.needsPassword` is a property of the
            // format — a `.kdbx` is always encrypted — but a Bitwarden export is the same JSON
            // whether or not the user gave it an export password, so nothing but the bytes can say
            // whether there is a password to ask for. Sending every Bitwarden import to the
            // password step would have asked the overwhelming majority of users for one that does
            // not exist; never asking is what left a password-protected export unopenable.
            //
            // Off the main thread (2026-10-02). Every claimant's `detect` runs here — a full CSV
            // parse, a full JSON decode, a zip walk — over up to 64 MB, and this coroutine resumes
            // on Main after the read above. Back still cancels it at the boundary.
            val (encrypted, needsPassword) = withContext(Dispatchers.Default) {
                val encrypted = importerFor(source, bytes) as? PasswordProtectedImporter
                // The tile's own say-so counts only for a file that has a database in it: a KDBX
                // opens with a four-byte signature, so under the KeePass tile a JPEG or a
                // `keepass.xml` has no password to ask for. It used to reach this step regardless,
                // where "This database is encrypted" described something that is not a database and
                // the real cause waited behind a typed password and a key derivation. Without the
                // step it goes to `parse`, which refuses it with the tile's own sentence.
                encrypted to (
                    (source.needsPassword && KdbxImporter.detect(bytes)) ||
                        runCatching { encrypted?.needsPassword(bytes) }.getOrDefault(false) == true
                    )
            }
            if (needsPassword) {
                _state.update {
                    it.copy(
                        isWorking = false,
                        step = ImportUiState.Step.PASSWORD,
                        // KDBX is the only format here whose credential can also be a file. The
                        // source is consulted as well so that picking the KeePass tile and then a
                        // file that detects as something else still offers the key file the user
                        // came for, rather than silently withdrawing it.
                        passwordIsForDatabase = encrypted is KdbxImporter ||
                            source == ImportSource.KEEPASS,
                    )
                }
            } else {
                parse(bytes, password = null)
            }
        }
    }

    /**
     * Adds the key file for a KeePass database whose credential is a password *and* a file.
     *
     * A second SAF pick rather than a guess: nothing in a `.kdbx` header says a key file is
     * involved, so the app cannot find it and the user has to point at it. Read into memory, wiped
     * by [discardPending] with everything else, and never written anywhere.
     */
    fun onKeyFilePicked(uri: Uri) {
        if (_state.value.isWorking) return
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // Capped like the export itself. A key file is normally 32 to a few hundred
                        // bytes, but KeePass lets *any* file be one, so the cap is the same.
                        input.readAtMost(MAX_IMPORT_BYTES)
                            ?: error("that file is larger than ${MAX_IMPORT_BYTES / 1024 / 1024} MB")
                    } ?: error("couldn't open that file")
                }
            }
            val bytes = read.getOrElse {
                // Never interpolated — a SAF provider's message embeds the content:// Uri. Same
                // rule and same fixed string as the export read above.
                _state.update { it.copy(error = ImportError.Simple(R.string.import_error_read_failed)) }
                return@launch
            }
            pendingKeyFile?.fill(0)
            pendingKeyFile = bytes
            _state.update {
                it.copy(
                    keyFileName = displayName(uri) ?: context.getString(R.string.import_password_key_file_unnamed),
                    error = null,
                )
            }
        }
    }

    fun submitPassword() {
        // 2026-10-02: the button is gated on `isWorking`, the keyboard's Done key is not. With a key
        // file attached the guard below passes on an empty well, so a second Done started a second
        // derivation, orphaned the first from `parseJob` — and so from Back — and let whichever
        // finished last decide the step. Same guard as onFilePicked and applyImport.
        if (_state.value.isWorking) return
        val bytes = pendingBytes ?: return
        val password = _state.value.password
        // Not `password.isEmpty()` any more: a KeePass database can be protected by a key file
        // alone, and refusing to submit without typed characters made those unopenable through the
        // wizard however correct the key file was.
        if (password.isEmpty() && pendingKeyFile == null) return
        // Converted and cleared from state in this same, synchronous call — not in a `finally`
        // after `parse()` returns. A KDBX key derivation runs for the better part of a
        // minute, and clearing there (the old code) left the plaintext password sitting in
        // ImportUiState, reachable off the ViewModel, for the whole of that window. Compose has
        // no CharArray-native text field, so `LkPasswordField` still needs a `String` to bind to
        // while the user is typing — this only narrows how long the *finished* password stays
        // reachable once it is no longer needed, matching how UnlockViewModel.unlockWithPassword
        // clears `password` before launching its own (much longer) derivation rather than after.
        //
        // This said "KDBX's Argon2id derivation" until 2026-09-13.
        // The pinned KeePassJava2 2.2.4 registers Argon2**d** and AES-KDF and nothing
        // else, so the one variant it named is the one it never runs — and an Argon2id database is
        // refused outright, see KdbxImporter.describeFailure. The timing point is unaffected.
        val passwordChars = password.toCharArray()
        _state.update { it.copy(password = "", isWorking = true, error = null) }
        parseJob = viewModelScope.launch {
            parse(bytes, passwordChars, pendingKeyFile)
        }
    }

    private suspend fun parse(bytes: ByteArray, password: CharArray?, keyFile: ByteArray? = null) {
        val source = _state.value.source
        // The wipe is in a `finally`, and the reason is narrower than it looks. When the password
        // does reach KdbxImporter, that importer zeroes it in its own `finally`
        // (KdbxImporter.kt:51) — at the point of use, which is the stronger place for it — so
        // cancelling a KDBX parse was never leaving a plaintext password behind. Two windows were
        // genuinely uncovered, and this closes both at zero cost, idempotently:
        //
        //   - cancellation delivered before the withContext body is dispatched, so the importer is
        //     never entered;
        //   - the `importer == null` and non-password-protected branches below, where the password
        //     is never handed to KdbxImporter at all — reachable when the user picks the KeePass
        //     source and the picked file detects as some other format.
        //
        // Found on a path that the cancel-on-back change made
        // routinely reachable rather than exotic.
        // Resolved once via the injected Context rather than exposed as a resource id for the
        // composable to resolve later — these titles are persisted into the vault at import time,
        // not read back on render. See ImportDefaultTitles's kdoc for why this is a deliberate,
        // narrow exception to "ViewModels never hold display strings".
        val titles = defaultTitles()
        // N-02: a failed attempt used to leave this well empty no matter what, so "Add key
        // file" — the error's own suggested repair — sent the user back to retype a password
        // they had already typed once. `recoveredPassword` is only ever materialised for that
        // one outcome, and only after the derivation above has already finished, so the success
        // and unrecognised-format paths never pay for a String copy they don't need.
        //
        // It is read from `retained`, never from `password`, and the copy is taken before the
        // hand-over (2026-10-04, device report D1 finding 1). Every PasswordProtectedImporter
        // zeroes the array it is given — KdbxImporter and BitwardenJsonImporter both, in their own
        // `finally` — so reading the failure's copy back out of `password` restored a well of NULs:
        // eight dots on screen, sent as the password on the retry, and refused as wrong however
        // right the key file added in between. A CharArray, wiped below with the original.
        val retained = password?.copyOf()
        val (outcome, recoveredPassword) = try {
            val result = withContext(Dispatchers.Default) {
                val importer = importerFor(source, bytes)
                when {
                    importer == null -> null
                    importer is PasswordProtectedImporter && password != null ->
                        importer to importer.parse(bytes, password, titles, keyFile)
                    else -> importer to importer.parse(bytes, titles)
                }
            }
            result to if (result?.second is Outcome.Failure) retained?.concatToString() else null
        } finally {
            password?.fill('\u0000')
            retained?.fill('\u0000')
        }

        if (outcome == null) {
            _state.update { it.copy(isWorking = false, error = refusalFor(source, bytes)) }
            return
        }
        val (importer, parsed) = outcome
        when (parsed) {
            is Outcome.Failure -> _state.update {
                it.copy(
                    isWorking = false,
                    // A KDBX failure is almost always a wrong password, so stay on that step.
                    // The importer authored this failure as a typed ImportFailureReason, carried on
                    // AppError.cause rather than AppError.message — see ImportRejection's kdoc for
                    // why: a raw AppError.message is internal-only by convention and must never be
                    // shown (findings F2, F22 were exactly this field reaching the UI unresolved).
                    error = ImportError.FromImporter(
                        (parsed.error.cause as? ImportRejection)?.reason ?: ImportFailureReason.Unexpected,
                    ),
                    // N-02: restored, not re-cleared — see the comment above `recoveredPassword`.
                    password = recoveredPassword ?: "",
                )
            }

            is Outcome.Success -> {
                // Not `getOrDefault(emptyList())`. An empty vault and an unreadable one produce the
                // same preview — every item marked NEW — and only one of those is true. The screen
                // it feeds is a set of counts the user decides on ("42 new, 3 updated"), and a read
                // that failed silently turns those into a claim the app cannot support: duplicate
                // detection did not find nothing, it did not run. Worse, the import proceeds, the
                // save reads the vault again and fails there, and the message names the save.
                // Through the projection, not `getAllItems()`. The preview is a screen: "3 updated"
                // beside a title the user recognises is a statement that the vault already holds that
                // entry, and a concealed entry that answered here would be disclosed by its own
                // collision. So a concealed record contributes nothing to what is shown, and its
                // imported twin previews as NEW.
                //
                // The *apply* path deliberately does not follow this — it merges against
                // `mutator.items`, the complete raw list — so the dedup still happens; it simply
                // happens without saying so. The one thing the two must agree on is the number the
                // DONE screen reports, which is why that number is counted from the preview rather
                // than from the size delta the merge produced. See `applyImport`.
                val existing = try {
                    repository.visibilitySnapshot().liveItems
                } catch (_: Exception) {
                    _state.update {
                        it.copy(
                            isWorking = false,
                            error = ImportError.Simple(R.string.import_error_vault_read_failed),
                        )
                    }
                    return
                }
                // Every parsed item, whatever its type. This wizard used to drop Cards and
                // Identities for a free vault, on the reasoning that they are Pro types and nothing
                // downstream re-checks the tier. Both halves of that turned out to be wrong.
                //
                // Something downstream does re-check: `ItemEditViewModel.save()` refuses to write a
                // Card or an Identity past the free cap, and reading one is allowed there deliberately —
                // "it is the user's own data, and holding it hostage would be a worse answer than
                // declining to write more of it." The gate this filter was doubling already exists,
                // and it is the one that gets the balance right. (That gate was `!isPro` until
                // 2026-09-03 and is a *quota* now — `ProTypeQuota.FREE_LIMIT`, one card and one
                // identity free, creation-path only. The conclusion here is unchanged: this
                // wizard still must not filter, and import still never charges against a cap.)
                //
                // And the filter made this wizard the only door that behaved that way. Backup
                // restore has no entitlement check at all, and the vault list does not hide Pro
                // types — so the same card crossed into the same free vault through one route and
                // not the other. The case that settles it: `foss` sets `isPro = false` permanently
                // (spec §N2), so someone who *bought* Pro on Play and moved to F-Droid would have
                // been refused an import of their own cards, forever.
                //
                // Tiers doc, "The invariant that has to ship with the caps": limits apply to
                // creation, never to import, and never to display.
                pendingPayloads = parsed.value.attachmentPayloads
                val planned = ImportMerger.plan(existing, parsed.value.items, parsed.value.timestampsKnown)
                _state.update {
                    it.copy(
                        isWorking = false,
                        step = ImportUiState.Step.PREVIEW,
                        // Only a file this importer says is password-protected counts, and only when
                        // a password was actually handed over: picking the KeePass tile and then a
                        // plain CSV routes through the password step too, and that file is in the
                        // clear whatever the user typed.
                        sourceEncryptedWithPassword = password != null &&
                            importer is PasswordProtectedImporter &&
                            runCatching { importer.needsPassword(bytes) }.getOrDefault(false),
                        detectedFormat = importer.sourceFormat,
                        password = "",
                        parserSkipped = parsed.value.skipped,
                        carriedColumns = parsed.value.carriedColumns,
                        unusableTotpCount = parsed.value.unusableTotpCount,
                        unresolvedFieldReferenceCount = parsed.value.unresolvedFieldReferenceCount,
                        passkeyOnlyLoginCount = parsed.value.passkeyOnlyLoginCount,
                        parsedFolders = parsed.value.folders,
                        timestampsKnown = parsed.value.timestampsKnown,
                        rows = planned.map { row ->
                            PreviewRow(
                                id = row.item.id,
                                title = row.item.title.ifBlank { "Untitled" },
                                subtitle = row.item.previewSubtitle(),
                                disposition = row.disposition,
                                collidesWith = row.collidesWith,
                                collidesWithVault = row.collidesWithVault,
                                // Pre-deselect items the vault already has a newer copy of: the
                                // default should be the one that can't lose data.
                                selected = row.disposition != ImportMerger.Disposition.ALREADY_CURRENT,
                                item = row.item,
                            )
                        },
                        error = if (parsed.value.items.isEmpty()) {
                            ImportError.Simple(R.string.import_error_empty_file)
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    /**
     * The titles an importer falls back to for a source entry the file itself left blank.
     *
     * `core/import` cannot resolve a `@StringRes` itself (it is a pure-Kotlin/JVM leaf module — see
     * [ImportDefaultTitles]'s kdoc), and these values are persisted into the vault at import time
     * rather than read back on a later render, so there is no composable left to resolve them at.
     * Resolving via the already-injected [context] here is a narrow, deliberate exception to
     * "ViewModels never hold display strings" for that reason.
     */
    private fun defaultTitles(): ImportDefaultTitles = ImportDefaultTitles(
        untitledItem = context.getString(R.string.core_import_default_untitled_item),
        importedItem = context.getString(R.string.core_import_default_imported_item),
        untitledCode = context.getString(R.string.core_import_default_untitled_code),
        importedCode = context.getString(R.string.core_import_default_imported_code),
        untitledRow = context.getString(R.string.core_import_default_untitled_row),
        untitledFolder = context.getString(R.string.core_import_default_untitled_folder),
        rescuedTotpFieldName = context.getString(R.string.core_import_default_rescued_totp_field_name),
        identityNotesFieldName = context.getString(R.string.core_import_default_identity_notes_field_name),
    )



    /**
     * Creates the folders the imported items need, and repoints those items at the vault's own ids.
     *
     * Three things this has to get right, all of them user-visible:
     *
     *  - **Only folders the user is actually importing into.** [selected] is what survived the
     *    preview's checkboxes, so a folder whose every item was deselected must not be created —
     *    otherwise deselecting rows still reorganises the vault.
     *  - **Match an existing folder by name *and place* rather than adding a second one.** The
     *    importer mints fresh UUIDs, so without this a user who already has "Finance", or who
     *    imports the same export twice, ends up with two folders of that name and their items split
     *    across both. Compared case-insensitively for the same reason the picker sorts that way:
     *    two folders a user cannot tell apart are one folder as far as they are concerned. Scoped
     *    to siblings, now that folders nest — matching "Bank" globally would pull a KeePass
     *    "Work/Bank" into an unrelated "Personal/Bank" the vault already had.
     *  - **Never leave an item pointing at a folder that does not exist.** Anything unresolved is
     *    nulled, which is the state the item would have had before this feature — filed nowhere,
     *    rather than filed at a dangling reference.
     */
    private fun reconcileFolders(
        mutator: VaultMutator,
        selected: List<VaultItem>,
        parsed: List<Folder>,
    ): List<VaultItem> {
        val (created, refiled) = reconcileImportedFolders(mutator.folders, selected, parsed)
        mutator.folders.addAll(created)
        return refiled
    }

    /**
     * Seals each imported file into the blob store and returns [selected] with the resulting
     * `blobRef` filled in.
     *
     * Runs before the merge, and that ordering is the point: `ItemMerge.preserveStructure` gives a
     * merged item the *vault's* id, so a payload keyed on the parsed item's id has nowhere to
     * attach once the merge has run. The ids here are still the ones [OnePuxImporter] minted.
     *
     * The session DEK is a private copy ([VaultLockManager.sessionSnapshotOrNull]) rather than the
     * manager's live array: sealing a 50 MB attachment can outlive the auto-lock grace timer, and
     * `lock()` zeroes the manager's own array underneath a write in flight — sealing the blob under
     * an all-zero key. Same reason the item editor takes a snapshot.
     *
     * The plaintext bytes are left for [discardPending], which [applyImport] runs once the vault
     * write has succeeded (2026-10-02). They used to be wiped as each file was sealed, and a failed
     * apply then discarded the rest — while the preview stayed up with Import enabled. The retry
     * found no payloads, sealed nothing, and saved every Document as a File item pointing at no
     * blob, reported as a success beside an offer to delete the export. A retry needs the bytes;
     * backing out or leaving the wizard still wipes them.
     */
    private suspend fun sealPayloads(selected: List<VaultItem>): List<VaultItem> {
        if (pendingPayloads.isEmpty()) return selected
        val session = lockManager.sessionSnapshotOrNull() ?: error("vault locked before the import was applied")
        return try {
            withContext(Dispatchers.IO) {
                selected.map { item ->
                    val payloads = pendingPayloads[item.id] ?: return@map item
                    var content = item.content
                    val attachments = item.attachments.toMutableList()
                    payloads.forEach { payload ->
                        val ref = blobStore.encryptToBlob(
                            java.io.ByteArrayInputStream(payload.bytes),
                            session.dek,
                        )
                        val size = payload.bytes.size.toLong()
                        val body = content
                        // A standalone File item *is* its file, so the first payload fills the item
                        // itself; anything further hangs off it as an ordinary attachment, which is
                        // also where every payload on a Login, Card or Note goes.
                        if (body is File && body.blobRef.isBlank()) {
                            content = body.copy(
                                filename = payload.filename,
                                mime = payload.mime,
                                sizeBytes = size,
                                blobRef = ref,
                            )
                        } else {
                            attachments += Attachment(
                                id = UUID.randomUUID(),
                                filename = payload.filename,
                                mime = payload.mime,
                                sizeBytes = size,
                                blobRef = ref,
                            )
                        }
                    }
                    item.copy(content = content, attachments = attachments)
                }
            }
        } finally {
            session.dek.fill(0)
        }
    }

    /**
     * Refuses a File item that is not backed by a sealed blob. A File item *is* its file, and every
     * importer that makes one also hands over its bytes, so one with no blob here is this wizard
     * having lost them — saving it would report success for a document that is not in the vault.
     */
    private fun requireSealedFiles(items: List<VaultItem>) {
        check(items.none { (it.content as? File)?.blobRef?.isBlank() == true }) {
            "an imported file was never sealed"
        }
    }

    fun applyImport() {
        val current = _state.value
        // The button is disabled while this runs, but `enabled` is only true of the *composition*:
        // two taps inside one frame are both dispatched to the node as it was drawn, and the second
        // one re-ran the whole merge against a vault that already held the first one's results —
        // reported back as a second "N items imported". Same guard as ItemEditViewModel.save.
        if (current.isWorking) return
        val selectedRows = current.rows.filter(PreviewRow::selected)
        val selected = selectedRows.map(PreviewRow::item)
        if (selected.isEmpty()) {
            _state.update { it.copy(error = ImportError.Simple(R.string.import_error_nothing_selected)) }
            return
        }
        _state.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                // Before the vault write, and inside the same runCatching: a file that cannot be
                // sealed must fail the import rather than land as an item pointing at a blob that
                // does not exist. Blobs written before a failure are unreferenced and the next
                // `BlobGarbageCollector` sweep removes them.
                val sealed = sealPayloads(selected).also(::requireSealedFiles)
                var added = 0
                // One encrypted write for the whole import rather than one per item.
                repository.mutate { mutator ->
                    val all = mutator.items.toList()
                    // Split trashed items out and put them back untouched. `getAllItems()` — and
                    // therefore the merge — only ever sees live items, so replacing the item list
                    // with the merge result alone would silently empty the trash and destroy
                    // anything the user hadn't got round to restoring.
                    val (trashed, live) = all.partition(VaultItem::isDeleted)
                    val filed = reconcileFolders(mutator, sealed, current.parsedFolders)
                    val merged = ImportMerger.apply(live, filed, current.mode, current.timestampsKnown)
                    added = importedCount(current.mode, selectedRows)
                    mutator.items.clear()
                    mutator.items.addAll(merged)
                    mutator.items.addAll(trashed)
                }
                added
            }
            // Only once the write has landed (2026-10-02): a failed apply leaves the preview up with
            // Import enabled, and the retry needs the files' bytes to seal. See [sealPayloads].
            if (result.isSuccess) discardPending()
            _state.update {
                result.fold(
                    onSuccess = { added ->
                        it.copy(
                            isWorking = false,
                            step = ImportUiState.Step.DONE,
                            // What actually landed as a new entry, not how many rows were
                            // selected. It was `selected.size`, so a re-import whose rows all
                            // merged reported "Imported 3 items" over a vault that had gained
                            // none — and the sentence below it then said "3 items imported; 3
                            // merged into entries you already had", naming the same three twice.
                            // Found by the F10 QA persona, 2026-08-30.
                            importedCount = added,
                            mergedIntoExistingCount = mergedIntoVaultCount(current.mode, selectedRows),
                            repeatedInFileCount = repeatedInFileCount(current.mode, selectedRows),
                        )
                    },
                    onFailure = { failure ->
                        it.copy(isWorking = false, error = ImportError.Simple(saveFailureMessage(failure)))
                    },
                )
            }
        }
    }

    /** Opens the Done step's confirmation dialog. See [deleteSourceFile] for the delete itself. */
    /** Keeps the parsed rows and closes the discard dialog. */
    fun cancelDiscard() = _state.update { it.copy(confirmDiscard = false) }

    /**
     * Discards the preview the user just confirmed they are done with, and drops back a step.
     *
     * Clears the flag *and* tells [back] the question has been answered. Clearing alone is not
     * enough and was the bug: `back()` re-derives the guard from `step` and `rows`, so it set the
     * flag straight back and the dialog re-raised itself forever.
     */
    fun confirmDiscard() {
        _state.update { it.copy(confirmDiscard = false) }
        back(discardConfirmed = true)
    }

    fun requestDeleteSourceFile() = _state.update { it.copy(confirmDelete = true) }

    /** Dismisses the confirmation dialog without deleting anything. */
    fun cancelDeleteSourceFile() = _state.update { it.copy(confirmDelete = false) }

    /**
     * Deletes the export file the user picked (spec §6 cleanup).
     *
     * This is an ordinary delete through the Storage Access Framework, **not** a multi-pass
     * overwrite — Android gives us a document handle, never raw block access to someone else's
     * file, so overwriting in place isn't possible. The UI says so rather than implying a
     * guarantee we can't make.
     *
     * Only reachable from the Done step's confirmation dialog (`confirmDelete = true`) — this is a
     * single, permanent, un-undoable delete, and used to fire off one tap on the button alone.
     */
    fun deleteSourceFile() {
        val uri = pendingUri ?: lastImportedUri ?: return
        // The one async action in this class that ran with no pending state: a provider that takes
        // its time answering left the button looking untapped, on the last step of a wizard the
        // user is trying to finish. Guarded as well as flagged, matching applyImport.
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true, confirmDelete = false, error = null) }
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                }.getOrDefault(false)
            }
            _state.update {
                if (deleted) {
                    it.copy(isWorking = false, sourceDeleted = true, error = null)
                } else {
                    it.copy(
                        isWorking = false,
                        error = ImportError.Simple(R.string.import_error_delete_failed),
                    )
                }
            }
        }
    }

    /** Kept so cleanup still works after [applyImport] clears the in-flight state. */
    private var lastImportedUri: Uri? = null

    private fun discardPending() {
        pendingBytes?.fill(0)
        pendingBytes = null
        pendingKeyFile?.fill(0)
        pendingKeyFile = null
        pendingPayloads.values.flatten().forEach { it.bytes.fill(0) }
        pendingPayloads = emptyMap()
        lastImportedUri = pendingUri ?: lastImportedUri
        pendingUri = null
    }

    override fun onCleared() {
        discardPending()
        super.onCleared()
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    /**
     * Second line for a preview row. Never renders a password: the point of the preview is to let
     * the user check *which* entries are coming across, not to put every secret on one screen.
     */
    private fun VaultItem.previewSubtitle(): String = when (val body = content) {
        is Login -> body.username.ifBlank { "No username" }
        is Totp -> listOf(body.issuer, body.account)
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .ifBlank { "2FA code" }
        is Card -> "Card"
        is Identity -> "Identity"
        is Note -> "Secure note"
        // Named rather than left to the fallback below. A newly supported type that drops into
        // `else` renders as "Item" in the preview — indistinguishable from a content type this
        // build genuinely does not understand, on the one screen whose job is to let the user check
        // what is about to be written into their vault.
        //
        // No importer produces one today: no third-party format this app reads carries coordinates,
        // and none will be invented for them. This branch is for a native `.vault` merge, where the
        // item came from LocalKeep in the first place.
        is Coordinate -> "Saved location"
        // A 1Password Document, whose file is read out of the `.1pux` archive. Named for the same
        // reason Coordinate is: "Item" on the one screen whose job is to say what is about to be
        // written reads as a type this build does not understand.
        is File -> body.filename.ifBlank { "File" }
        else -> "Item"
    }

    internal companion object {
        /** Exports are text; anything this large is a mistake, and reading it would OOM. */
        const val MAX_IMPORT_BYTES = 64 * 1024 * 1024

        /**
         * Order is load-bearing in exactly one place: [CsvImporter] stays last.
         *
         * It is the only importer here that recognises a file by the *shape* of its columns rather
         * than by anything naming a product, so anything before it gets first refusal on a CSV that
         * really is a Keeper, Enpass or Dashlane export. Among the rest order does not matter — a
         * sweep of every file in `src/test/resources/realworld/` through all nine found each one
         * claimed by exactly one importer (WP-B2, 2026-09-17).
         *
         * Every entry here meets an attacker-supplied file chosen from anywhere on the device, so
         * `checkFuzzTargetsCovered` reads this list and fails the build for any importer with no
         * `@FuzzTest` in `ImporterFuzzTest` — see that gate's kdoc in the root build file.
         */
        val ALL_IMPORTERS: List<Importer> = listOf(
            KdbxImporter,
            BitwardenJsonImporter,
            OnePuxImporter,
            AegisJsonImporter,
            KeeperImporter,
            EnpassImporter,
            ProtonPassJsonImporter,
            DashlaneImporter,
            CsvImporter,
        )

        /**
         * Why no importer would take this file, in the terms of the tile the user picked.
         *
         * Every refusal used to be one string — *"That file isn't in a format LocalKeep recognizes.
         * Check you exported the unencrypted format, and that the file didn't get truncated."* — shown
         * for a 0-byte file, a binary file, a header the mapper could not bind, a semicolon-delimited
         * CSV and a non-KDBX file under the KeePass tile alike. For most of those neither cause named
         * is the cause, and `core_import_error_not_kdbx_file` — the one accurate sentence for the last
         * — was unreachable, because `importerFor` returns null before any importer authors a typed
         * reason (1B-F3 / 1A-F5, 2026-09-20).
         *
         * The order is by how specific the answer is, not by how likely: an empty file has exactly one
         * explanation, the KeePass tile has one accurate sentence of its own, and a file that reads as
         * delimited text is one whose header is the thing to talk about. Only the generic tile, where
         * the user has said they do not know what the file is, keeps a generic answer.
         */
        fun refusalFor(source: ImportSource?, bytes: ByteArray): ImportError {
            val format = source?.sourceFormat
            return when {
                bytes.isEmpty() -> ImportError.Simple(R.string.import_error_source_file_empty)
                // Ahead of every tile's own sentence: for this file the useful answer is what it is
                // and where it goes, not which format it is not. See [isLocalKeepBackup].
                isLocalKeepBackup(bytes) -> ImportError.LocalKeepBackup
                // The tile's own sentence, finally reachable. True of a `keepass.xml`, a truncated
                // `.kdbx` and a picked-the-wrong-file alike, and none of them is "export it unencrypted".
                source == ImportSource.KEEPASS -> ImportError.FromImporter(ImportFailureReason.NotAKdbxFile)
                // Keeper's CSV has no header row, so the header sentence below ("export again with
                // the column headings included") is advice no Keeper user can take (D1 finding 2,
                // 2026-10-04). The tile's own caveat already names the way out: export JSON.
                source == ImportSource.KEEPER ->
                    ImportError.FromImporter(ImportFailureReason.MalformedExport(SourceFormat.KEEPER))
                looksLikeDelimitedText(bytes) ->
                    ImportError.Simple(R.string.import_error_csv_header_unrecognised)
                format != null -> ImportError.FromImporter(ImportFailureReason.MalformedExport(format))
                else -> ImportError.Simple(R.string.import_error_unrecognised_format)
            }
        }

        /**
         * Whether these bytes read as a delimited text file at all — the question that separates "your
         * header row has nothing I recognise" from "this is not a text file".
         *
         * Only the first 64 KB: one header row is all this has to answer, and the file may be 64 MB of
         * something else. A NUL byte in that window means binary, which is the one shape that must not
         * be described to the user as a header problem.
         *
         * Nor may JSON be. A minified export is one long line full of commas, so splitting it on the
         * comma yields dozens of "columns" and a truncated `truncated.json` picked from the Bitwarden
         * tile was answered *"this file's header has no column it recognizes as a password. Export
         * again with the column headings included"* — advice about a header, for a file that has no
         * header row and never will (1A-F3 re-verify, 2026-09-20). One opening brace or bracket is
         * the whole test, the same rule `KeeperCsv.detect` already uses to stop reading JSON as a
         * positional CSV; the tile's own "that isn't a valid Bitwarden export" is the true answer and
         * `refusalFor` reaches it as soon as this returns false.
         *
         * Both delimiters, and the wider reading wins — not `parseDelimited`, which falls back to the
         * comma when neither binds a password column, and a semicolon file read that way is one cell
         * wide and would answer "not a text file" about a text file.
         */
        private fun looksLikeDelimitedText(bytes: ByteArray): Boolean = runCatching {
            val window = bytes.copyOf(minOf(bytes.size, 64 * 1024))
            if (window.any { it == 0.toByte() }) return@runCatching false
            // Leading whitespace and a UTF-8 BOM are skipped: both precede the first real glyph, and
            // a BOM would otherwise hide the brace behind it.
            val firstGlyph = window.asSequence()
                .map { it.toInt() and 0xFF }
                .firstOrNull { it > 0x20 && it != 0xEF && it != 0xBB && it != 0xBF }
            if (firstGlyph == '{'.code || firstGlyph == '['.code) return@runCatching false
            val header = listOf(',', ';')
                .map { CsvColumnMapper.parseRows(window, it).firstOrNull().orEmpty() }
                .maxBy { it.size }
            header.size >= 2 && header.any { it.isNotBlank() }
        }.getOrDefault(false)

        /**
         * The importer to use for [bytes], given the tile the user picked.
         *
         * **A chosen tile pins the claimants.** The old rule preferred the tile's importer and then
         * fell back to `ALL_IMPORTERS.firstOrNull { detect }`, on the reasoning that exports get
         * renamed and grabbed from the wrong app. What that actually did was hand a file the chosen
         * source had *refused* to whichever other format claimed it first — and
         * `KeeperImporter.detect` reads a header-less positional CSV, which any comma-bearing text
         * satisfies. A truncated Bitwarden JSON and a legacy `.1pif` both previewed as
         * `1 item found · Keeper`, with a raw JSON fragment offered as a vault item under a brand
         * the user did not choose and no hint the file was damaged (1A-F3, 2026-09-20).
         *
         * So each tile lists its own importer(s) and [CsvImporter], and nothing else. The CSV entry
         * is not a loophole: it recognises a file by the shape of its header row and every vendor
         * here also exports a plain CSV, which is how picking Dashlane and handing it a
         * `credentials.csv` has always worked. Sniffing every format survives only under the
         * **Other / CSV** tile, where the user has said they do not know what the file is.
         *
         * A file no claimant takes is refused, and the wizard names the source that refused it —
         * see `refusalFor`.
         */
        fun importerFor(source: ImportSource?, bytes: ByteArray): Importer? =
            if (isLocalKeepBackup(bytes)) {
                // Never offered to a claimant. A container is header plus ciphertext, and a
                // positional CSV detector that happened to bind it would preview noise as items.
                null
            } else {
                claimantsFor(source).firstOrNull { runCatching { it.detect(bytes) }.getOrDefault(false) }
            }

        /**
         * Whether [bytes] are LocalKeep's own `.vault` backup — the `LKBUNDLE` wrapper Backup &
         * export writes, or the bare `VLT1` container a pre-bundle export and a rolling snapshot are.
         *
         * Device report C3 finding 1 (2026-10-04): a user moving to a new phone took onboarding's
         * "Import my passwords", picked Other / CSV and then the `.vault` from the old phone, and
         * was told it was not in a format LocalKeep recognises. It is the app's own format; the
         * restore simply lives under Backup & export, which needs no wizard and is where the answer
         * now points ([ImportError.LocalKeepBackup]).
         *
         * By the leading magic alone, the same test `VaultImporter.readContainer` makes. Both are
         * frozen by the v1 vault format: `VLT1` is [VersionedHeader.MAGIC],
         * and `LKBUNDLE` is `VaultBundle.MAGIC`, internal to `:feature:backup` and so restated here.
         * Nothing is decrypted — the wizard never asks for the backup's password, because it is not
         * the place that restores one.
         */
        internal fun isLocalKeepBackup(bytes: ByteArray): Boolean =
            bytes.startsWith(BUNDLE_MAGIC) || bytes.startsWith(VersionedHeader.MAGIC)

        private val BUNDLE_MAGIC = "LKBUNDLE".toByteArray(Charsets.US_ASCII)

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

        /** The importers a tile allows, most specific first. See [importerFor]. */
        fun claimantsFor(source: ImportSource?): List<Importer> = when (source) {
            ImportSource.KEEPASS -> listOf(KdbxImporter, CsvImporter)
            ImportSource.BITWARDEN -> listOf(BitwardenJsonImporter, CsvImporter)
            ImportSource.ONEPASSWORD -> listOf(OnePuxImporter, CsvImporter)
            ImportSource.AEGIS -> listOf(AegisJsonImporter, CsvImporter)
            ImportSource.KEEPER -> listOf(KeeperImporter, CsvImporter)
            ImportSource.ENPASS -> listOf(EnpassImporter, CsvImporter)
            ImportSource.PROTONPASS -> listOf(ProtonPassJsonImporter, CsvImporter)
            // `DashlaneImporter` reads the four non-credential exports only. A `credentials.csv` is
            // an ordinary header-bound CSV it refuses, which is what the second entry is for.
            ImportSource.DASHLANE -> listOf(DashlaneImporter, CsvImporter)
            ImportSource.CHROME, ImportSource.APPLE -> listOf(CsvImporter)
            // The generic tile, and the no-tile case that only tests reach: here, and only here,
            // "I do not know what this file is" is what the user said.
            ImportSource.CSV, null -> ALL_IMPORTERS
        }
    }
}

/**
 * What a failed apply says. 2026-10-02: every failure said "Couldn't save the imported items." beside
 * an enabled Import button — including two that pressing it again cannot fix, a vault grown past what
 * this device can save and a full disk. The item editor names both (`saveFailureMessage` there); so
 * does this. The exception is only classified, never shown.
 */
@StringRes
internal fun saveFailureMessage(failure: Throwable): Int = when {
    failure is VaultTooLargeForDeviceException || failure is VaultBucketExhaustedException ->
        R.string.import_error_save_vault_too_large
    failure is java.io.IOException && failure.message?.contains("No space left", ignoreCase = true) == true ->
        R.string.import_error_save_out_of_space
    else -> R.string.import_error_save_failed
}

/** The picked export is over [ImportWizardViewModel.MAX_IMPORT_BYTES]. Carries no path or Uri. */
private class ImportFileTooLargeException : java.io.IOException("import file over the size cap")

/**
 * Reads at most [limit] bytes, or returns null if the stream holds more.
 *
 * Deliberately reads one byte past the limit rather than trusting a declared size: the point is to
 * stop before the allocation, so nothing here may depend on being told how big the file is.
 */
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
    val chunk = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val read = read(chunk)
        if (read == -1) return out.toByteArray()
        total += read
        if (total > limit) return null
        out.write(chunk, 0, read)
    }
}

/**
 * How many selected rows actually land in the vault, for the Done step's headline count.
 *
 * Extracted from [ImportWizardViewModel.applyImport] so the two modes' arithmetic can be pinned
 * without a vault behind it — the same reasoning as [reconcileImportedFolders].
 *
 * Counted from the preview's own verdict, not from a post-merge size delta: the vault's live-item
 * list includes concealed records, so a size delta would leak whether a colliding entry existed at
 * all. [ImportMerger.Mode.ADD_ALL] bypasses dedup entirely (see [ImportMerger.apply]) — every
 * selected row lands regardless of disposition, so counting only [ImportMerger.Disposition.NEW]
 * rows there understated the actual gain whenever a selected row collided with something that
 * already existed. [ImportMerger.Mode.MERGE] only adds rows the preview called NEW; anything else
 * updates the existing item or is discarded as already-current, contributing no new item.
 */
internal fun importedCount(mode: ImportMerger.Mode, selectedRows: List<PreviewRow>): Int = when (mode) {
    ImportMerger.Mode.ADD_ALL -> selectedRows.size
    ImportMerger.Mode.MERGE -> selectedRows.count { it.disposition == ImportMerger.Disposition.NEW }
}

/**
 * Selected rows a MERGE import folded into an entry the vault already held, or null when there
 * were none (and always in [ImportMerger.Mode.ADD_ALL], which folds nothing).
 *
 * Half of what the Done step used to report as one number, `selected.size - added`. The other
 * half is [repeatedInFileCount]; [PreviewRow.collidesWithVault] is what tells them apart.
 */
internal fun mergedIntoVaultCount(mode: ImportMerger.Mode, selectedRows: List<PreviewRow>): Int? =
    foldedRows(mode, selectedRows).count { it.collidesWithVault }.takeIf { it > 0 }

/**
 * Selected rows a MERGE import folded into an EARLIER ROW OF THE SAME FILE, or null when there
 * were none. A KeePass database holding one login twice, imported into a vault with no match,
 * used to say "2 merged into entries you already had" — the vault held neither.
 */
internal fun repeatedInFileCount(mode: ImportMerger.Mode, selectedRows: List<PreviewRow>): Int? =
    foldedRows(mode, selectedRows).count { !it.collidesWithVault }.takeIf { it > 0 }

private fun foldedRows(mode: ImportMerger.Mode, selectedRows: List<PreviewRow>): List<PreviewRow> = when (mode) {
    ImportMerger.Mode.ADD_ALL -> emptyList()
    ImportMerger.Mode.MERGE -> selectedRows.filter { it.disposition != ImportMerger.Disposition.NEW }
}

/**
 * The folder half of an import, as a pure function: which folders have to be created, and where the
 * selected items end up pointing.
 *
 * Extracted from the ViewModel so it can be tested without a vault behind it. Everything about it
 * is a decision worth pinning — matching is sibling-scoped, ordering must not depend on the order
 * the importer happened to emit, and an unresolved folder has to become null rather than a dangling
 * id — and none of that was reachable from a test while it lived inside a `mutate` block.
 *
 * @param existing the folders the vault already has.
 * @return the folders to add, and the items with their `folderId` remapped.
 */
internal fun reconcileImportedFolders(
    existing: List<Folder>,
    selected: List<VaultItem>,
    parsed: List<Folder>,
): Pair<List<Folder>, List<VaultItem>> {
    if (parsed.isEmpty()) return emptyList<Folder>() to selected
    val needed = selected.mapNotNull(VaultItem::folderId).toSet()
    if (needed.isEmpty()) return emptyList<Folder>() to selected

    // Every folder on the path to something being imported, not just the ones items sit in
    // directly: creating "Bank" without its parent "Work" would leave it dangling, and the whole
    // point of matching parent-aware is that the two names together identify the place.
    val parsedById = parsed.associateBy { it.id }
    val required = mutableSetOf<UUID>()
    needed.forEach { id ->
        var current = parsedById[id]
        while (current != null && required.add(current.id)) {
            current = current.parentId?.let(parsedById::get)
        }
    }

    // Live folders only, and the name says so. A trashed folder must never be matched: reusing one
    // would file the whole import into a folder the user threw away, invisible in the vault list and
    // absent from Trash's flat item list — data imported into nowhere. On a name collision with a
    // trashed folder this creates a live one beside it instead, which is the lesser failure: two
    // same-name siblings if the trashed one is ever restored, against an import that vanishes.
    val live = existing.live.toMutableList()
    val created = mutableListOf<Folder>()
    val remap = mutableMapOf<UUID, UUID>()
    // Parents before children — a child can only be matched once its parent's fate is known, so
    // the order `parsed` arrives in must not decide the outcome. Depth-sorted rather than assumed.
    val ordered = parsed.filter { it.id in required }
        .sortedBy { folder -> FolderTree.depthOf(parsed, folder.id) ?: Int.MAX_VALUE }

    for (folder in ordered) {
        // Where this folder's parent ended up in *this* vault: a folder it matched, or one just
        // created. Null means the top level.
        val targetParent = folder.parentId?.let(remap::get)
        val match = live.firstOrNull {
            it.parentId == targetParent && it.name.equals(folder.name, ignoreCase = true)
        }
        if (match != null) {
            // Matched rather than duplicated: the importer mints fresh UUIDs, so without this a
            // user who already has "Finance", or who imports the same export twice, ends up with
            // two of it and their items split across both.
            remap[folder.id] = match.id
        } else {
            val now = Instant.now()
            val fresh = folder.copy(parentId = targetParent, updatedAt = now, parentUpdatedAt = now)
            live += fresh
            created += fresh
            remap[folder.id] = fresh.id
        }
    }
    val refiled = selected.map { item ->
        val target = item.folderId?.let(remap::get)
        if (target == item.folderId) item else item.copy(folderId = target)
    }
    return created to refiled
}

/** What [ImportWizardViewModel.back] should do, decided without touching any state. */
internal sealed interface BackOutcome {
    /** A vault write is in flight. Back does nothing at all — see [ImportWizardViewModel.back]. */
    data object Refuse : BackOutcome

    /** A finished preview holds work worth a question before it is thrown away. */
    data object AskToDiscard : BackOutcome

    /**
     * Leave the current step for [next].
     *
     * [cancelParse] covers an active parse launched from any step (onFilePicked parses at
     * INSTRUCTIONS, submitPassword at PASSWORD), not just the PASSWORD/PREVIEW steps, so no parse
     * coroutine is left orphaned.
     */
    data class Leave(val next: ImportUiState, val cancelParse: Boolean) : BackOutcome
}

/**
 * The whole of Back's decision, as a pure function of the state.
 *
 * Pulled out of the ViewModel so it can be driven by a test with real [ImportUiState] values: the
 * ViewModel itself needs a Context, a VaultRepository and a VaultLockManager to construct and this
 * module ships no Robolectric, which is why the tests that used to guard this behaviour asserted on
 * the *source text* of `back()` instead — and why they passed for weeks over a Discard button that
 * did nothing on a device.
 *
 * @param parsing a read-or-parse coroutine is still running.
 * @param discardConfirmed the user has already said "Discard" to the confirmation dialog.
 */
internal fun backOutcome(
    current: ImportUiState,
    parsing: Boolean,
    discardConfirmed: Boolean,
): BackOutcome {
    if (current.isWorking && !parsing) return BackOutcome.Refuse
    // A finished preview is work the user did: a KDBX parse runs for the better part of a minute,
    // and on top of that they have gone through the rows deciding what to keep. Every other
    // work-losing exit in this app asks first — the item editor's `leave()`, the file editor's, the
    // backup screen's back handler — and this one silently emptied `rows`.
    //
    // Scoped deliberately to a preview that is *not* still parsing. Backing out is the only way to
    // cancel a running parse, and that is exactly what `cancelParseThenDiscard` was written for; a
    // confirmation in front of it would put a dialog between the user and the escape hatch for a
    // minute-long operation they already decided to abandon. Nothing is lost by cancelling a parse
    // that has produced nothing yet.
    //
    // `discardConfirmed` is the answer coming back. Without it the confirm path re-entered this
    // guard, which reads only the step and the rows — neither of which answering changes — and
    // re-raised the dialog it was answering, forever.
    if (!discardConfirmed &&
        !parsing &&
        current.step == ImportUiState.Step.PREVIEW &&
        current.rows.isNotEmpty()
    ) {
        return BackOutcome.AskToDiscard
    }
    val cancelParse = parsing ||
        current.step == ImportUiState.Step.PASSWORD ||
        current.step == ImportUiState.Step.PREVIEW
    val next = when (current.step) {
        ImportUiState.Step.PICK_SOURCE -> current
        ImportUiState.Step.INSTRUCTIONS, ImportUiState.Step.DONE -> ImportUiState()
        ImportUiState.Step.PASSWORD, ImportUiState.Step.PREVIEW -> current.copy(
            step = ImportUiState.Step.INSTRUCTIONS,
            rows = emptyList(),
            password = "",
            // `cancelParse` wipes the key file's bytes on the way out; the name has to go with
            // them, or the next database picked would show the previous one's key file as attached.
            keyFileName = null,
            error = null,
        )
    }
    return BackOutcome.Leave(next, cancelParse)
}
