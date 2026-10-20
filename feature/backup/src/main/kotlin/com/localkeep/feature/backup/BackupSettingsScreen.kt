package com.localkeep.feature.backup

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.rememberExternalPickerLauncher
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.crypto.KdfRecalibration
import com.localkeep.core.health.StrengthAnalyzer
import com.localkeep.core.ui.theme.LkCheckRow
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkDisclosure
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFootnote
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkStrength
import com.localkeep.core.ui.theme.LkStrengthMeter
import com.localkeep.core.ui.theme.LkSwitchRow
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LkTintButton
import com.localkeep.core.ui.theme.lkEnter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Backup, export and the rolling local snapshots.
 *
 * The screen's job is to make the difference between the three kinds of copy legible, because they
 * are easy to confuse and one path through them is destructive: Auto Backup is Google's, the
 * `.vault` export is a file the user carries, and a snapshot is a local rollback point. **Replace**
 * is the only control in the app that discards a vault, so it is the only one behind a confirmation.
 *
 * Five labelled sections render, not three. Import sits here because it is the other half of export
 * and a restore has to be reachable from wherever backups are explained; the KDF section sits here
 * because this is where restores happen, which is the argument made at its own definition. The
 * count in the sentence above was left at three when those two arrived, which is worth a note
 * rather than a restructure: an audit read the density as a defect and proposed splitting export,
 * import and recalibration into separate destinations. That would contradict the grouping the UI
 * spec asks for in §10.1, and it would push the three password fields below across a lifecycle
 * boundary they were deliberately written to stay inside — they use plain `remember`, not
 * `rememberSaveable`, so a master password never reaches the instance-state Bundle.
 *
 * Export, Import and Encryption fold (`LkDisclosure`) and start folded, so the page opens as a list
 * of rows with summaries rather than three forms. The wells above stay hoisted in this composable
 * for the same reason as before, which is why folding a section clears what was typed into it.
 */
/**
 * How long after arriving on this screen the [BackupFocus] reveal keeps re-applying its target.
 *
 * Long enough to outlast the several frames of settling a fresh arrival takes — the scroller's own
 * extent was still 866px of an eventual ~2600 at the first layout — and short enough that it is over
 * before anyone has finished reading the first line. It bounds a loop that would otherwise run for
 * the life of the screen and keep pulling the view back whenever the layout changed.
 */
private const val SNAPSHOTS_REVEAL_SETTLE_MS = 700L

/**
 * Where the scroller has to be for the rolling-snapshots section to sit as high as it can.
 *
 * [sectionTopInViewport] is the section's top in the scroller's own coordinates, so adding
 * [scrollValue] converts it to an offset into the content — a figure that does not move as the
 * screen fills in, because this section is the last one and everything that arrives late arrives
 * below it.
 *
 * The clamp against [maxValue] is the load-bearing half, and leaving it to `scrollTo`'s own
 * coercion is what made the fix look like it was working while it was not. An uncoerced target is
 * *constant*, so `snapshotFlow` emits it once, the scroller cuts it down to whatever extent that
 * first frame happened to have, and no later emission ever arrives however far the content grows.
 * Measured on device 2026-09-11: the screen landed 852px down both with an empty snapshot list and
 * with four snapshots, two layouts 560px apart. Clamping here instead makes the emitted value move
 * with the extent, so each measurement pass lands the section a little higher until it has settled.
 *
 * Returns a content offset, never a distance: the caller passes it straight to `scrollTo`.
 */
internal fun snapshotsRevealTarget(sectionTopInViewport: Float, scrollValue: Int, maxValue: Int): Int =
    minOf((sectionTopInViewport + scrollValue).roundToInt(), maxValue)

/**
 * The date in the name the export's save dialog proposes, `LocalKeep-2026-10-05.vault` — the
 * pattern itself is `backup_export_filename`, untranslated, like the Recovery Kit PDF's.
 *
 * It was always `vault-export.vault`, so the second backup in a folder became `vault-export (1)
 * .vault` and a folder of them could be told apart only by opening each (device report C3 finding
 * 6). ISO rather than the device locale's date: it sorts by name, and a localized date would put
 * slashes or non-ASCII digits into a file name. Nothing else goes in: the file is the copy that
 * leaves the device, so the name carries no vault, account or device detail.
 */
internal fun exportFileDate(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)


/**
 * [recoveryKitVoidedNotice] is `settings_password_changed_recovery_kit_voided`, passed in by `:app`
 * because this module cannot see `:feature:settings`' resources — see [BackupMessage.ImportComplete].
 */
@Composable
fun BackupSettingsScreen(
    viewModel: BackupSettingsViewModel,
    onBack: () -> Unit,
    @StringRes recoveryKitVoidedNotice: Int,
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The three password fields stay plain `remember` **on purpose**, and must not be "fixed" to
    // rememberSaveable: saved state goes into the instance-state Bundle, which the system may write
    // to disk to survive process death. That would put the master password in plaintext outside the
    // vault, which is the one thing this app exists not to do. Losing what was typed on rotation is
    // the correct trade.
    var masterPassword by remember { mutableStateOf("") }
    var exportPassphrase by remember { mutableStateOf("") }
    // `remember`, not `rememberSaveable`: a revealed secret must not survive into a saved instance
    // state, and coming back from the background with the well re-masked is the safe direction. The
    // unlock screen's own reveal is held the same way.
    //
    // The export passphrase is a secret the user is CREATING, typed once, with no confirm field and
    // no way to check it afterwards — a typo produces a `.vault` nobody can open, and it was typed
    // blind (release sweep 2026-09-20, observation 1C-O10). The master-password well above it gets
    // the same control: the unlock screen already offers one for that exact secret, and two
    // adjacent wells where only one has an eye reads as a defect of the other.
    var exportPassphraseRevealed by remember { mutableStateOf(false) }
    var masterPasswordRevealed by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    // Held like the two export flags above, for the same reasons: `remember`, never saveable, and
    // dropped when the section folds. Without the eye the file's password could not be checked
    // before a read that spends an Argon2id derivation and a picker excursion.
    var importPasswordRevealed by remember { mutableStateOf(false) }

    // These three carry no secret — a toggle and two "are you sure" flags — and a rotation used to
    // silently drop the confirmation for the only action in the app that discards a vault.
    var useIndependentPassphrase by rememberSaveable { mutableStateOf(false) }
    var confirmReplace by rememberSaveable { mutableStateOf(false) }
    var confirmRestorePath by rememberSaveable { mutableStateOf<String?>(null) }
    val confirmRestore = confirmRestorePath?.let(::File)
    // The vault password the restore confirmation now asks for. Plain `remember`, not saveable,
    // for the same reason the three master-password wells above it are: a half-typed master
    // password must not reach the instance-state Bundle the system writes to disk. The flag beside
    // it stays saveable — losing the dialog on rotation is the bug this pairing avoids, and the
    // well simply starts empty again, which is the safe direction for a gate.
    var restorePassword by remember { mutableStateOf("") }
    // "Are you sure you want to leave?", raised by Back and by the header chevron when a decrypted
    // `.vault` is staged and waiting on Merge or Replace. Saveable like its two siblings above, and
    // gated on `state.asksBeforeExit` where it is rendered for the same reason `confirmReplace` is
    // gated on the payload: the flag outlives the staged vault, which does not survive process
    // death, and a dialog restored over nothing would offer to discard something already gone.
    var confirmDiscardImport by rememberSaveable { mutableStateOf(false) }

    // The export passphrase is scored by the same analyser, against the same floor, that gates
    // creating a master password (OnboardingViewModel) and changing one (SettingsViewModel). It was
    // gated on being non-empty alone, which made the one credential on the one artifact designed to
    // leave this device the only one in the app with no strength floor at all — and an export is a
    // single-slot container (VaultExporter passes `revokedRecoveryBlock()`), so this passphrase is
    // the only way back into that file.
    //
    // Scored here rather than in the ViewModel on purpose: the three password fields on this screen
    // stay in plain `remember` so no secret reaches the instance-state Bundle, and the ViewModel is
    // handed the passphrase only at export. `produceState` keeps zxcvbn off the frame — its matching
    // is superlinear in length — and cancels the previous run on each keystroke, holding the last
    // computed score meanwhile. Nought until a score arrives, so the gate below fails closed.
    val exportPassphraseScore by produceState(0, exportPassphrase, useIndependentPassphrase) {
        value = if (!useIndependentPassphrase || exportPassphrase.isEmpty()) {
            0
        } else {
            withContext(Dispatchers.Default) { StrengthAnalyzer.score(exportPassphrase) }
        }
    }

    // Hoisted out of the Export button so the password wells above it can fire the same action from
    // the keyboard's Done key. The terms are unchanged; only where they are written moved.
    //
    // The passphrase half is the same idiom Import below is gated on, and it closes a worse hole
    // than an empty field usually is. The launcher below sends `exportPassphrase.toCharArray()`
    // whenever the checkbox is on, so an untouched well arrives as an empty — not null — array,
    // which `export` reads as "no passphrase" and answers by writing a master-password export,
    // reported as an ordinary success. The user asked for a file only their chosen passphrase opens
    // and got one tied to their master password *as it stood at export time*: rotate it afterwards,
    // forget the old one, and the backup opens for nobody. The strength term is the same floor
    // onboarding and Settings apply to the master password, for the same reason: the file this
    // passphrase wraps is the one copy meant to be carried off, and it carries no Recovery Kit, so
    // an offline guess at it has nothing but this in front of it. Emptiness stays as its own term —
    // it is what the comment above is about, and a zero score would otherwise be the only thing
    // standing between an untouched well and a master-password export reported as a success.
    val exportEnabled = masterPassword.isNotEmpty() &&
        (
            !useIndependentPassphrase ||
                (
                    exportPassphrase.isNotEmpty() &&
                        exportPassphraseScore >= StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE
                    )
            ) &&
        !state.isWorking

    // Both launchers are paired with the lock manager. Export and import are the two longest
    // picker excursions in the app — naming a file, then browsing for one — and a relock partway
    // meant the callback returned to a locked vault and the operation vanished.
    val exportLauncher = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri ->
        if (uri != null) {
            viewModel.export(
                uri,
                if (useIndependentPassphrase) exportPassphrase.toCharArray() else null,
            )
            masterPassword = ""
            exportPassphrase = ""
        }
    }
    val importLauncher = rememberExternalPickerLauncher(
        contract = ActivityResultContracts.OpenDocument(),
        onPickerOpened = viewModel::onPickerOpened,
        onPickerClosed = viewModel::onPickerClosed,
    ) { uri ->
        if (uri != null) {
            viewModel.stageImport(uri, importPassword.toCharArray())
            importPassword = ""
            importPasswordRevealed = false
        }
    }

    // The destination picker opens only once the master password has checked out. Launching it
    // first is what made a wrong password cost a folder browse, a filename, and a 0-byte `.vault`
    // left behind in the user's chosen folder before the refusal arrived (F-39). The request is
    // consumed before the launch, so a recomposition cannot open a second picker on the same
    // approval.
    // Resolved at composition, per the LocalContextGetResourceValueCall rule. The request flipping is
    // itself a recomposition, so the date is read in the same pass that launches the picker.
    val exportFileName = stringResource(R.string.backup_export_filename, exportFileDate(LocalDate.now()))
    LaunchedEffect(state.exportDestinationRequested) {
        if (state.exportDestinationRequested) {
            viewModel.consumeExportDestinationRequest()
            exportLauncher(exportFileName)
        }
    }

    // Leaving while a picker is still open means its answer has nowhere to go — see
    // [BackupSettingsViewModel.onScreenDisposed]. `isChangingConfigurations` is the signal that tells
    // a rotation apart from the lock that replaced this screen.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) {
        onDispose { viewModel.onScreenDisposed(activity?.isChangingConfigurations == true) }
    }

    val scrollState = rememberScrollState()
    // The drawer's "Restore a snapshot" row asks this screen to open on its last section. See
    // [BackupFocus]; arriving any other way opens at the top as before.
    //
    // Taken here, once, in a `remember` — not from inside the effect that acts on it. `take()` is
    // destructive, so whichever pass calls it owns the request, and an effect that restarts spends
    // it on a pass that is then cancelled. That is precisely what the earlier version did: measured
    // on device 2026-09-11, the effect ran, took `snapshots`, began the scroll, restarted 26 ms
    // later when its key settled, and the second run found `take()` empty. The screen stayed at
    // offset 0 and nothing anywhere said a request had been made and dropped.
    //
    // The import wizard's "Open Backup & export" asks for the import section the same way (device
    // report C3 finding 1): it sends someone who picked a `.vault` there, and the section sits three
    // below the top. One take, then one of two sections — the measuring below is the same for both.
    val focusSection = remember { BackupFocus.take() }
    val revealSnapshots = focusSection == BackupFocus.SNAPSHOTS
    val revealImport = focusSection == BackupFocus.IMPORT
    var scrollerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var snapshotsCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var importCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    // Three of the page's five sections fold. Plain booleans, so `rememberSaveable` is fine here -
    // unlike the password wells above, nothing secret hangs off them; what folding does to those
    // wells is in each section's `onOpenChange`.
    //
    // Export and Encryption start folded. Import starts open only for the visitor the import wizard
    // sent here, who came for exactly that section and would otherwise land on a closed row.
    var exportOpen by rememberSaveable { mutableStateOf(false) }
    var kdfOpen by rememberSaveable { mutableStateOf(false) }
    var importOpen by rememberSaveable { mutableStateOf(revealImport) }
    // A decrypted file waiting on Merge or Replace pins Import open - see the section itself. Written
    // into the flag as well as OR-ed in, so cancelling the staged file leaves the section where it
    // was rather than snapping it shut under the user's finger.
    val importStaged = state.pendingImportVault != null
    LaunchedEffect(importStaged) { if (importStaged) importOpen = true }

    // The single error/status slot renders at the top of this scroll, but the operations that set
    // it — KDF recalibrate, wrong-password export/restore, the in-flight "Exporting…"/"Restoring…"
    // notes themselves — live in sections the user reaches only by scrolling down. Without this the
    // refusal was written off-screen from where the button was pressed, reachable only by the
    // live-region announcement. Bring it into view on any status *or* error change. (QA 2026-08-06:
    // the effect used to key only on error, so the in-flight and completion notes a multi-second
    // export produces stayed off-screen and the only near-button feedback was the button dimming.)
    LaunchedEffect(state.status, state.error) {
        if (state.status != null || state.error != null) {
            // A staged file's read lands on the section holding Merge and Replace instead: the
            // strip that used to be up here named two buttons a screen below it, and the user had
            // to scroll down to find them. [importCoords] and [scrollerCoords] are the same
            // measurements the arrival scroll uses.
            if (fileReadPromptReplacesStatus(state.status, state.pendingImportVault != null)) {
                withTimeoutOrNull(SNAPSHOTS_REVEAL_SETTLE_MS) {
                    snapshotFlow {
                        val scroller = scrollerCoords
                        val section = importCoords
                        if (scroller == null || section == null || !scroller.isAttached || !section.isAttached) {
                            null
                        } else {
                            snapshotsRevealTarget(
                                sectionTopInViewport = scroller.localPositionOf(section, Offset.Zero).y,
                                scrollValue = scrollState.value,
                                maxValue = scrollState.maxValue,
                            )
                        }
                    }.filterNotNull().collect { scrollState.animateScrollTo(it) }
                }
            } else {
                scrollState.animateScrollTo(0)
            }
        }
    }

    val kdfCheckedGood = state.kdfVerdict == KdfRecalibration.Verdict.KEEP &&
        state.kdfMeasuredMillis <= KdfRecalibration.UNUSABLY_SLOW_MS
    val kdfSummary = stringResource(
        when (state.kdfVerdict) {
            null -> R.string.backup_kdf_summary_unchecked
            KdfRecalibration.Verdict.KEEP ->
                if (kdfCheckedGood) R.string.backup_kdf_summary_ok else R.string.backup_kdf_summary_slow
            else -> R.string.backup_kdf_summary_offer
        },
    )
    val exportSummary = state.lastExportEpochMillis
        ?.let { stringResource(R.string.backup_export_last_export, it.asLocalTimestamp()) }
        ?: stringResource(R.string.backup_export_summary_never)
    LaunchedEffect(focusSection) {
        if (!revealSnapshots && !revealImport) return@LaunchedEffect
        // Re-applied while the screen settles rather than computed once. At the first layout after
        // arrival this scroller's extent was 866px against a content that finishes near 2600 (same
        // measurement), so an offset read from that frame lands more than a screen short — which is
        // the under-scroll this whole block exists to fix. `withTimeoutOrNull` bounds the re-applying
        // to the arrival itself; after that the screen belongs to whoever is scrolling it.
        //
        // The emitted target is scroll-invariant by construction: `localPositionOf` moves by exactly
        // what `scrollState.value` moves by, in the opposite direction, so the sum only changes when
        // the *layout* does. A scroll of the user's own therefore emits nothing and is not undone.
        withTimeoutOrNull(SNAPSHOTS_REVEAL_SETTLE_MS) {
            snapshotFlow {
                val scroller = scrollerCoords
                val section = if (revealImport) importCoords else snapshotsCoords
                if (scroller == null || section == null || !scroller.isAttached || !section.isAttached) {
                    null
                } else {
                    snapshotsRevealTarget(
                        sectionTopInViewport = scroller.localPositionOf(section, Offset.Zero).y,
                        scrollValue = scrollState.value,
                        maxValue = scrollState.maxValue,
                    )
                }
            }.filterNotNull().collect { scrollState.scrollTo(it) }
        }
    }
    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.5f) {
        Column(Modifier.fillMaxSize()) {
            // Every action button on this screen is already gated on `isWorking`; the way out was
            // not, and there was no `BackHandler` at all — so the one moment the screen most needs
            // the user to stay put, a restore or a vault-replacing import, was the one moment they
            // could leave without being told anything.
            //
            // `AtomicVaultWriter` writes temp-then-rename, so leaving cannot corrupt the vault. What
            // it does produce is an unanswered question: the destructive replace either finished or
            // was cancelled with the screen, and nothing afterwards says which.
            //
            // `blocksExit`, not `isWorking`: an export is the one operation here that never touches
            // the local vault, and it is the one that can hang for as long as a cloud provider
            // wants — the SAF copy is a blocking stdlib stream loop with no timeout. Blocking Back
            // for it guarded nothing and made a stalled destination a dead end: no drawer (Backup
            // is outside `shellRoutes`, so this screen composes no shell chrome), no bottom bar,
            // the header chevron dimmed by the same flag, and Home/Recents returning to the same
            // stuck screen. The only way out was force-stopping the app. See
            // [BackupSettingsUiState.isExporting].
            //
            // The second clause is a different answer to a different question, and the two are
            // deliberately not merged. `blocksExit` refuses; `asksBeforeExit` asks. A staged
            // `.vault` restore — picked, unlocked with its own password, decrypted, waiting on
            // Merge or Replace — was dropped by Back and by the chevron without a word, and the
            // cost of redoing it is a SAF pick plus an Argon2id derivation over a file that may be
            // large. It cannot simply be kept: the staged vault
            // is another vault's decrypted contents and must never reach saved instance state.
            BackHandler(enabled = state.blocksExit || state.asksBeforeExit) {
                // Nothing at all while an operation owns the screen — that is the refusal above.
                if (!state.blocksExit) confirmDiscardImport = true
            }
            // Pinned, outside the scroller. It used to be the scroller's first child, and the
            // drawer's "Restore a snapshot" scrolls this screen ~1630px to reach the snapshots
            // section — which took the title with it, so that row landed the user on an unlabelled
            // wall of controls with nothing on screen saying where they were. Measured 2026-09-06.
            //
            // Two drawer rows deliberately share this one route, and [AppShell] calls that "the same
            // trick" as Duress / decoy -> Settings. Settings keeps its title fixed while it filters,
            // so only one of the two actually behaved that way; this is what makes them agree.
            LkScreenHeader(
                title = stringResource(R.string.backup_screen_title),
                subtitle = stringResource(R.string.backup_screen_subtitle),
                modifier = Modifier
                    .padding(horizontal = LkFrame.screenHorizontal)
                    .padding(top = LkFrame.screenTop)
                    .lkEnter(),
                trailing = {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = stringResource(R.string.backup_screen_back),
                        // The other half of the `BackHandler` above, and it answers both of its
                        // questions the same way: refused while an operation owns the screen, and
                        // routed through the discard confirmation while a restore is staged. A
                        // chevron that leaves silently is the same defect as a Back that does.
                        onClick = { if (state.asksBeforeExit) confirmDiscardImport = true else onBack() },
                        enabled = !state.blocksExit,
                        plateAlignment = Alignment.CenterEnd,
                    )
                },
            )
            Column(
                Modifier
                    // weight, not fillMaxSize: in a Column, fillMaxSize takes the *parent's* height
                    // rather than what is left after the header, which squeezes the header. Same
                    // reasoning, and the same wording, as [GeneratorScreen], where it was found.
                    .weight(1f)
                    .fillMaxWidth()
                    // The shell keeps the keyboard out of its own padding, so this is where the
                    // viewport shrinks for it. Without it the export passphrase and import password
                    // wells were typed into from under the keyboard: `LkTextField`'s bring-into-view
                    // had nothing to scroll because the viewport never got shorter. Ahead of the
                    // measurement below, so [scrollerCoords] is the shortened viewport and the
                    // snapshots section is still measured against what is actually on screen.
                    .imePadding()
                    // Before `verticalScroll`, so these are the viewport's coordinates rather than
                    // the scrolled content's — the frame the snapshots section is measured against.
                    .onGloballyPositioned { scrollerCoords = it }
                    // The gap the header used to give this content by being the arrangement's
                    // previous child. Padding rather than a Spacer: a Spacer inside a `spacedBy`
                    // Column is itself spaced, so it would render 32dp.
                    //
                    // BEFORE `verticalScroll`, so it is the viewport's own gutter rather than the
                    // content's first 16dp. Inside the scroll it scrolled away with everything
                    // else, and the first section label then ran flush into the pinned header's
                    // subtitle the moment the screen moved — `IMPORT A .VAULT FILE` drawn touching
                    // `Encrypted copies, local snapshots, and cloud backup`, with no gap at all
                    // (fixed 2026-09-15). The bottom gutter stays inside: that one is
                    // content, and is meant to scroll.
                    .padding(top = LkSpace.md)
                    .verticalScroll(scrollState)
                    .padding(horizontal = LkFrame.screenHorizontal)
                    .padding(bottom = LkFrame.gutterPlain),
                verticalArrangement = Arrangement.spacedBy(LkSpace.md),
            ) {

                // Not for a staged file's "File read" line: Merge and Replace are below and the
                // section carries the prompt beside them (see [fileReadPromptReplacesStatus]).
                if (!fileReadPromptReplacesStatus(state.status, importStaged)) {
                    state.status?.let { resolveBackupMessage(it, recoveryKitVoidedNotice) }
                        ?.let {
                            // Work still running gets the neutral spinner, the way the in-flight
                            // note below does; the jade tick is for "done".
                            if (isProgressStatus(state.status)) {
                                LkInlineNote(
                                    it,
                                    accent = c.textMuted,
                                    icon = LkIcons.Refresh,
                                    modifier = Modifier.announce(it),
                                )
                            } else {
                                LkInlineNote(it, Modifier.announce(it))
                            }
                        }
                }
                state.error?.let { resolveBackupMessage(it, recoveryKitVoidedNotice) }
                    ?.let { LkInlineError(it, Modifier.announce(it)) }
                // The in-flight note for the one operation that writes no status of its own.
                //
                // Export, import, merge, replace and restore each set their own "Exporting…" /
                // "Restoring…" line above, and that line carries the announcement. `checkKdfCalibration`
                // sets only `isWorking`, so the sole feedback for a real Argon2id derivation was the
                // button dimming — which a screen reader is not told about, leaving a blind user with a
                // tap that appeared to do nothing for several seconds. Conditioned on `status == null`
                // so it is that operation's missing note rather than a second one announced over
                // everybody else's. Same shape, same accent and same glyph as SettingsScreen's.
                if (state.isWorking && state.status == null) {
                    val working = stringResource(R.string.backup_working)
                    LkInlineNote(
                        working,
                        accent = c.textMuted,
                        icon = LkIcons.Refresh,
                        modifier = Modifier.announce(working),
                    )
                }

                // The label and the container it heads are one child of the outer `spacedBy(LkSpace.md)`
                // arrangement, not two: [LkSectionLabel] owns the gap below it (LkSectionLabelGap),
                // and the arrangement would be added to that padding rather than supplying it.
                Column {
                    LkSectionLabel(stringResource(R.string.backup_cloud_section))
                    LkGroup {
                        LkSwitchRow(
                            // Presented positively since 2026-09-09: ON means the vault IS included.
                            // The label used to read "Disable cloud backup" with the switch ON meaning
                            // backup was off, so the user had to negate twice to learn what was
                            // happening — on the single control governing whether vault ciphertext can
                            // leave the device. The stored preference keeps its name and its polarity;
                            // only what the row shows is flipped, so no migration is involved.
                            label = stringResource(R.string.backup_cloud_include_label),
                            // The hints already describe reality rather than the switch, so their
                            // condition is unchanged by the inversion above.
                            hint = if (state.disableCloudBackup) {
                                stringResource(R.string.backup_cloud_disable_hint_on)
                            } else {
                                stringResource(R.string.backup_cloud_disable_hint_off)
                            },
                            checked = !state.disableCloudBackup,
                            onCheckedChange = { included -> viewModel.setDisableCloudBackup(!included) },
                            // The last control on this screen without the gate every other one has.
                            // `setDisableCloudBackup` writes `status` unconditionally, so a toggle
                            // during an export overwrote "Exporting…" with its own confirmation, and
                            // the export's completion overwrote that back moments later: the toggle was
                            // never acknowledged and the in-flight note vanished for the gap. The
                            // preference write itself was always correct — this is the status line, not
                            // the setting.
                            enabled = !state.isWorking,
                            first = true,
                        )
                    }
                }
                // One line, not two clauses. What the user needs here is the fact — Google holds
                // ciphertext — not a second sentence reassuring them about it.
                //
                // Shown only while the backup is actually on. Unconditional, it told a user who had
                // just switched the backup OFF what Google holds, which reads as though something
                // is still being sent and undercuts the setting they just chose. The sentence is
                // correct and unchanged; it simply has no subject when nothing is being backed up.
                // The OFF state is not silent either, and hiding the timing line there was what made
                // it look like one. Turning the backup off does not reach back into Google's copy:
                // `LocalKeepBackupAgent` omits the vault from the *next* pass, and Android decides
                // when that is. The ON state's "when" line says the same thing about a change that
                // is not uploaded yet; this says it about a copy that is not withdrawn yet. Without
                // it the screen answered the one question this setting raises — "is the copy gone?"
                // — with nothing at all (fixed 2026-09-15).
                if (!state.disableCloudBackup) {
                    LkFootnote(stringResource(R.string.backup_cloud_footnote))
                    // A second line, against the "one line, not two clauses" note above, because
                    // this is a different fact rather than a second reassurance about the same one.
                    // The line above answers *what* Google holds; this one answers *when* — and
                    // "when" is the half a user acts on. Auto Backup uploads on Android's schedule,
                    // not on save, so someone who turns this on and immediately changes a password
                    // has a backup that does not contain it, and someone restoring after a lost
                    // phone gets whatever the last upload held. Added 2026-09-13: the screen
                    // disclosed the 25 MB cap and the ciphertext, and a reconciliation sweep found
                    // it silent on the transport conditions while the release notes called the screen
                    // "honest about it".
                    LkFootnote(stringResource(R.string.backup_cloud_schedule_footnote))
                    // A third line, and a different fact again: what Auto Backup does NOT take.
                    // `full_backup_content.xml` excludes `blobs/` deliberately — attachment bytes
                    // would blow the ~25 MB payload cap on their own — and nothing here or in
                    // FEATURES.md said so, so a user restoring onto a new phone got every item back
                    // with every File and photo item empty and no warning that this would happen
                    // (release sweep 2026-09-20, finding 1C-F4). Shown in the ON branch only, like
                    // the two above: with the backup off there is no payload to describe.
                    LkFootnote(stringResource(R.string.backup_cloud_attachments_footnote))
                } else {
                    LkFootnote(stringResource(R.string.backup_cloud_schedule_footnote_off))
                }

                // Folded by default. A check is an occasional chore for someone who has just restored
                // onto different hardware, and the screen is mostly visited for the other two halves
                // of this page. The summary says what is inside without opening it.
                LkDisclosure(
                    title = stringResource(R.string.backup_kdf_section),
                    summary = kdfSummary,
                    summaryIcon = if (kdfCheckedGood) LkIcons.Check else null,
                    summaryAccent = if (kdfCheckedGood) c.jade else null,
                    open = kdfOpen,
                    onOpenChange = { kdfOpen = it },
                ) {
                    // design.md \u00a711.1: the Argon2id cost is chosen once, at vault creation, on
                    // whatever phone the user had then \u2014 and then travels with the vault through
                    // every export, restore and new device. On a slower phone that can make
                    // unlocking take seconds; on a faster one it hands an offline attacker the same
                    // speed-up the user got, invisibly. The check is on demand because it costs a
                    // real Argon2 derivation, and this screen \u2014 where restores happen \u2014 is where
                    // it is worth spending.
                    // Trimmed 2026-07-29. The full three-sentence version explained where the
                    // setting came from and why it travels — true, and the reason it exists is
                    // still in the comment above for whoever maintains this. On screen it was
                    // background for a decision the button below makes in one tap.
                    Text(
                        stringResource(R.string.backup_kdf_intro),
                        style = LkText.bodyQuiet,
                        color = c.textMuted,
                    )
                    when (state.kdfVerdict) {
                        null -> Unit

                        // The same two situations the status line distinguishes: nothing to change
                        // because the settings fit, and nothing to change because the vault is
                        // already at the floor. Only the first is good news.
                        //
                        // The good one is [LkInlineNote]: jade with a tick, which `LkColors` reserves for
                        // "safe, done". It was a grey [LkFootnote] - the same plate as fine print - so a
                        // check that came back clean looked exactly like the boilerplate above it. The slow
                        // one stays neutral on purpose: it is not good news and must not borrow the tick.
                        KdfRecalibration.Verdict.KEEP -> if (state.kdfMeasuredMillis > KdfRecalibration.UNUSABLY_SLOW_MS) {
                            LkFootnote(stringResource(R.string.backup_kdf_keep_slow, state.kdfMeasuredMillis))
                        } else {
                            // Nothing drawn: the disclosure's own summary row, a tick and "Checked ·
                            // suits this device", sits directly above this panel and says the same
                            // thing, and a jade note repeating it put the verdict twice in one
                            // viewport. The slow result above has no summary tick, so it keeps its note.
                        }

                        KdfRecalibration.Verdict.OFFER_FASTER -> RecalibrateOffer(
                            state = state,
                            viewModel = viewModel,
                            headline = stringResource(
                                R.string.backup_kdf_faster_headline,
                                state.kdfMeasuredMillis,
                            ),
                            // Said plainly rather than buried: this direction trades away real
                            // protection, and a user who is not told that cannot consent to it.
                            consequence = stringResource(R.string.backup_kdf_faster_consequence),
                        )

                        // Says nothing about this device's speed, because this verdict is not about
                        // this device. It fires where the vault opens fine — which is the only
                        // place the fix can be applied, since re-wrapping needs the vault open.
                        KdfRecalibration.Verdict.OFFER_PORTABLE -> RecalibrateOffer(
                            state = state,
                            viewModel = viewModel,
                            headline = stringResource(R.string.backup_kdf_portable_headline),
                            consequence = stringResource(R.string.backup_kdf_portable_consequence),
                        )

                        KdfRecalibration.Verdict.OFFER_STRONGER -> RecalibrateOffer(
                            state = state,
                            viewModel = viewModel,
                            headline = stringResource(
                                R.string.backup_kdf_stronger_headline,
                                state.kdfMeasuredMillis,
                            ),
                            consequence = stringResource(R.string.backup_kdf_stronger_consequence),
                        )
                    }

                    // Under every result, not only before the first one. It used to vanish for good once a
                    // verdict existed, so a user who restored a different vault, or who wanted to see the
                    // answer change, had no way to ask again short of leaving the screen.
                    LkSecondaryButton(
                        text = stringResource(R.string.backup_kdf_check_button),
                        enabled = !state.isWorking,
                        onClick = viewModel::checkKdfCalibration,
                    )
                }


                // Folded by default, and the summary is the last export's date: the one fact a user
                // opening this section is usually after, and the one that says whether a backup file
                // exists at all. It used to be a faint caption under the Export button.
                LkDisclosure(
                    title = stringResource(R.string.backup_export_section),
                    summary = exportSummary,
                    open = exportOpen,
                    onOpenChange = { open ->
                        exportOpen = open
                        // Folding the form drops what was typed into it. The wells are hoisted above
                        // this call so the keyboard's Done key and the picker callback can reach them,
                        // which also means folding does not dispose them - and a master password left
                        // in a section nobody can see is the kind of thing this app does not do.
                        if (!open) {
                            masterPassword = ""
                            exportPassphrase = ""
                            masterPasswordRevealed = false
                            exportPassphraseRevealed = false
                        }
                    },
                ) {
                    // The label and its well are one thing, so the pair carries the label-to-well gap
                    // itself rather than inheriting the card's field-to-field rhythm. LkFormField owns
                    // that relationship everywhere else; it wraps LkTextField, not LkPasswordField, so
                    // a password pairing has to state the same value instead of inheriting it.
                    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                        LkFieldLabel(stringResource(R.string.backup_master_password_label))
                        LkPasswordField(
                            value = masterPassword,
                            onValueChange = { masterPassword = it },
                            label = stringResource(R.string.backup_export_master_password_field_label),
                            placeholder = stringResource(R.string.backup_password_placeholder),
                            revealed = masterPasswordRevealed,
                            onToggleReveal = { masterPasswordRevealed = !masterPasswordRevealed },
                            showCount = true,
                            // Done submits, the way it does on every other
                            // one-password-then-one-button form in the app (unlock, the hide
                            // phrase, the import wizard, onboarding). Without it the keyboard's
                            // Done only closed the keyboard, so a keyboard, DPAD or
                            // switch-access user had a separate navigate-and-activate step on
                            // every export. Guarded by the same term as the button — the
                            // `HideFlow` idiom — so Done cannot start an export the button
                            // itself refuses.
                            onImeAction = {
                                if (exportEnabled) {
                                    viewModel.requestExportDestination(masterPassword.toCharArray())
                                }
                            },
                        )
                    }
                    LkGroup {
                        LkCheckRow(
                            label = stringResource(R.string.backup_export_separate_passphrase_label),
                            hint = null,
                            checked = useIndependentPassphrase,
                            onToggle = { useIndependentPassphrase = !useIndependentPassphrase },
                            first = true,
                        )
                    }
                    if (useIndependentPassphrase) {
                        // The label and its well are one thing, so the pair carries the label-to-well gap
                        // itself rather than inheriting the card's field-to-field rhythm. LkFormField owns
                        // that relationship everywhere else; it wraps LkTextField, not LkPasswordField, so
                        // a password pairing has to state the same value instead of inheriting it.
                        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                            LkFieldLabel(stringResource(R.string.backup_export_passphrase_label))
                            LkPasswordField(
                                value = exportPassphrase,
                                onValueChange = { exportPassphrase = it },
                                label = stringResource(R.string.backup_export_passphrase_label),
                                placeholder = stringResource(R.string.backup_password_placeholder),
                                revealed = exportPassphraseRevealed,
                                onToggleReveal = { exportPassphraseRevealed = !exportPassphraseRevealed },
                                showCount = true,
                                // The last well in the export form, so Done submits it. Same
                                // gate as the button, which here also means Done stays inert
                                // while the passphrase is below the strength floor.
                                onImeAction = {
                                    if (exportEnabled) {
                                        viewModel.requestExportDestination(masterPassword.toCharArray())
                                    }
                                },
                            )
                        }
                        // The meter and its refusal are emitted straight into this
                        // `spacedBy(LkSpace.md)` Column, not from a helper composable: a composable
                        // that emits several children into an arranged Column has to repeat
                        // that arrangement itself, and two conditional lines are not worth the
                        // wrapper. `LkPasswordField` takes no strength parameter, so a sibling
                        // is where the meter goes.
                        if (exportPassphrase.isNotEmpty()) {
                            LkStrengthMeter(LkStrength.ofScore(exportPassphraseScore))
                            if (exportPassphraseScore < StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE) {
                                // A refusal that blocks Export, so the failure treatment and a live
                                // region — not the grey footnote plate below it, which is fine print
                                // and was indistinguishable from this at a glance.
                                val tooWeak = stringResource(R.string.backup_export_passphrase_too_weak)
                                LkInlineError(tooWeak, Modifier.announce(tooWeak))
                            }
                        }
                        LkFootnote(stringResource(R.string.backup_export_passphrase_footnote))
                    }
                    LkPrimaryButton(
                        text = stringResource(R.string.backup_export_button),
                        icon = LkIcons.Export,
                        // Terms and their reasoning are at `exportEnabled`, above the launchers:
                        // the two password wells fire this same action from the Done key and
                        // have to agree with the button about when it is allowed.
                        enabled = exportEnabled,
                        onClick = { viewModel.requestExportDestination(masterPassword.toCharArray()) },
                    )
                }


                // Forced open while a decrypted file is waiting on Merge or Replace: the three buttons
                // that answer it live in here, and a user who folded the section would be left holding
                // a staged vault with nothing on screen to finish it. `locked` takes the header's
                // action away rather than leaving a tap that does nothing. The tag and the position
                // callback sit on the wrapper, outside the body, so the arrival scroll can measure the
                // section whether or not it is showing.
                LkDisclosure(
                    title = stringResource(R.string.backup_import_section),
                    summary = null,
                    open = importOpen || importStaged,
                    locked = importStaged,
                    onOpenChange = { open ->
                        importOpen = open
                        if (!open) {
                            importPassword = ""
                            importPasswordRevealed = false
                        }
                    },
                    modifier = Modifier
                        .lkTag("backup-import-section")
                        // The whole section, label and body, as the snapshots section is measured.
                        .onGloballyPositioned { importCoords = it },
                ) {
                    // The label and its well are one thing, so the pair carries the label-to-well gap
                    // itself rather than inheriting the card's field-to-field rhythm. LkFormField owns
                    // that relationship everywhere else; it wraps LkTextField, not LkPasswordField, so
                    // a password pairing has to state the same value instead of inheriting it.
                    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                        LkFieldLabel(stringResource(R.string.backup_import_vault_password_label))
                        LkPasswordField(
                            value = importPassword,
                            onValueChange = { importPassword = it },
                            label = stringResource(R.string.backup_import_password_field_label),
                            placeholder = stringResource(R.string.backup_password_placeholder),
                            revealed = importPasswordRevealed,
                            onToggleReveal = { importPasswordRevealed = !importPasswordRevealed },
                            showCount = true,
                            // Done opens the file picker, which is what the button below it
                            // does — the password is typed first here, and the pick is the
                            // submit. Same gate as that button.
                            onImeAction = {
                                if (importPassword.isNotEmpty() && !state.isWorking) {
                                    importLauncher(arrayOf("*/*"))
                                }
                            },
                        )
                    }
                    LkSecondaryButton(
                        text = stringResource(R.string.backup_import_choose_button),
                        icon = LkIcons.Import,
                        // Gated on a non-empty password like Export above and Recalibrate below.
                        // Import was the outlier: an empty field still spent a whole file pick and
                        // an Argon2id derivation before failing with "Wrong password for this
                        // file. Enter it again to retry." — which is both untrue (no password was
                        // entered) and an offer the state cannot honour, because the failure path
                        // caches neither the vault nor the picked Uri, so "retry" means walking
                        // the whole SAF picker again.
                        enabled = importPassword.isNotEmpty() && !state.isWorking,
                        onClick = { importLauncher(arrayOf("*/*")) },
                    )

                    if (state.pendingImportVault != null) {
                        // Carries the announcement the top strip would have: the strip is withheld for a
                        // staged file, so this is the one place the screen says what to do next.
                        val decrypted = stringResource(R.string.backup_import_decrypted_note)
                        LkInlineNote(decrypted, Modifier.announce(decrypted))
                        LkSecondaryButton(
                            text = stringResource(R.string.backup_import_merge_button),
                            icon = LkIcons.Layers,
                            enabled = !state.isWorking,
                            onClick = { viewModel.confirmImport(ImportMode.MERGE) },
                        )
                        // Replace is the one control here that destroys data, so it is coral and it
                        // asks. It used to sit beside Merge as an identical filled button.
                        LkTintButton(
                            text = stringResource(R.string.backup_import_replace_button),
                            accent = c.coral,
                            icon = LkIcons.Warning,
                            height = 54.dp,
                            // The screen's own comment above says every action button here is
                            // gated on `isWorking`. This one was not — it was the only tinted
                            // button in the app and `LkTintButton` had no `enabled` until it
                            // was given one — so the app's only vault-destroying confirmation
                            // opened during an in-flight operation, and the confirmed tap then
                            // died at `confirmImport`'s bare `isWorking` return, which writes
                            // neither a status nor an error.
                            enabled = !state.isWorking,
                            onClick = { confirmReplace = true },
                        )
                        LkSecondaryButton(
                            text = stringResource(R.string.backup_import_cancel_button),
                            enabled = !state.isWorking,
                            onClick = viewModel::cancelImport,
                        )
                    }
                }


                // Reports where this section sits so the arrival scroll above can land on it.
                //
                // The whole section is measured, not just its heading. On the heading alone the
                // minimum reveal put the label on the last 35px of the screen and left the snapshot
                // list and its Restore buttons below the fold, so the drawer row named "Restore a
                // snapshot" opened on the export and import forms and the section it named still had
                // to be found by hand. Measured on a 1080x1920 device 2026-07-30, identically in
                // en-US and en-XA: `ROLLING LOCAL SNAPSHOTS` at y=1822 of a 1857px content area.
                //
                // No arrangement on the wrapper: it holds exactly the heading and the block that
                // heading names, and [LkSectionLabel] owns the gap between them (LkSectionLabelGap).
                // A `spacedBy` here would be added to the label's own padding rather than being it.
                Column(modifier = Modifier.onGloballyPositioned { snapshotsCoords = it }) {
                    LkSectionLabel(stringResource(R.string.backup_snapshots_section))
                    if (state.snapshots.isEmpty()) {
                        LkEmptyState(
                            icon = RollingSnapshotIcon,
                            title = stringResource(R.string.backup_snapshots_empty_title),
                            body = pluralStringResource(
                                R.plurals.backup_snapshots_empty_body,
                                RollingBackupManager.MAX_SNAPSHOTS,
                                RollingBackupManager.MAX_SNAPSHOTS,
                            ),
                        )
                    } else {
                        LkGroup {
                            state.snapshots.forEachIndexed { index, file ->
                                LkGroupRow(first = index == 0) {
                                    val facts = snapshotRowFacts(file)
                                    Column(Modifier.weight(1f)) {
                                        // Lead with the human, local-zone timestamp. The row used to
                                        // lead with the raw `vault-<UTCstamp>-<n>.enc` filename — an
                                        // internal storage id — above this same timestamp, so one
                                        // snapshot showed two times in two zones (the filename is UTC,
                                        // this is local mtime) and could read as two different moments.
                                        Text(
                                            facts.takenAtMillis.asSnapshotTimestamp(),
                                            style = LkText.body,
                                            color = c.textSecondary,
                                        )
                                        // How big the snapshot is, under the moment it was taken.
                                        // Choosing between five snapshots of a 175-item vault, the
                                        // rows said nothing about which one was fuller, directly
                                        // above a warning that restoring one discards everything
                                        // since (release sweep 2026-09-20, finding 3B-F7).
                                        // `formatShortFileSize` rather than a hand-rolled divide:
                                        // it is locale-aware and needs no translated "MB".
                                        Text(
                                            Formatter.formatShortFileSize(
                                                LocalContext.current,
                                                facts.sizeBytes,
                                            ),
                                            style = LkText.caption,
                                            color = c.textMuted,
                                        )
                                    }
                                    LkSecondaryButton(
                                        text = stringResource(R.string.backup_snapshot_restore_button),
                                        // As wide as its label, not a third of the row: pinned to a
                                        // fraction, German's "Wiederherstellen" broke mid-word on
                                        // every row (QA 2026-10-03). The timestamp beside it wraps
                                        // instead, which is the column that can.
                                        modifier = Modifier.width(IntrinsicSize.Max),
                                        height = 40.dp,
                                        enabled = !state.isWorking,
                                        onClick = { confirmRestorePath = file.path },
                                    )
                                }
                            }
                        }
                        LkFootnote(stringResource(R.string.backup_snapshots_footnote))
                    }
                }
            }
        }
    }

    // `!state.isWorking` as well as the flag: `confirmReplace` is `rememberSaveable`, so a
    // configuration change or a process-death restore can bring the dialog back on top of an
    // operation that started after it was raised. The button above cannot cover that, because the
    // flag survives the composition the button lives in.
    //
    // And `pendingImportVault != null`, because the flag outlives its payload. The staged vault
    // lives in the ViewModel and is NOT `SavedStateHandle`-backed, so it survives a rotation and
    // does not survive process death — while `confirmReplace` survives both. Restored from a
    // process death, the dialog came back over nothing: `confirmImport` opens with
    // `pendingImportVault ?: return`, so Confirm dismissed a dialog about destroying the vault and
    // silently did nothing at all - no work, no status, no error. A destructive confirmation that
    // no-ops is worse than one that fails, because the user has no reason to doubt it worked.
    //
    // Gated on the payload rather than making the flag non-saveable: the payload does survive
    // rotation, so a rotation should keep the dialog, and only the case where the payload is
    // genuinely gone should drop it.
    if (confirmReplace && !state.isWorking && state.pendingImportVault != null) {
        LkDialog(
            onDismiss = { confirmReplace = false },
            title = stringResource(R.string.backup_replace_dialog_title),
            // Spells out both directions, because "including its files and photos" read as a
            // promise about the incoming file to at least one reader. What is certain is what is
            // destroyed. What arrives depends on whether the export came from this vault: an
            // export of a *different* vault carries media this vault has no key for, and that
            // media is skipped rather than written as bytes that would fail to authenticate.
            body = stringResource(R.string.backup_replace_dialog_body),
            icon = LkIcons.Warning,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.backup_replace_dialog_confirm),
                onClick = {
                    confirmReplace = false
                    viewModel.confirmImport(ImportMode.REPLACE)
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
        )
    }

    // Raised by Back and by the chevron while a decrypted `.vault` waits on Merge or Replace.
    //
    // Amber, not coral, and the confirm carries no destructive emphasis: `LkColors` puts the test
    // plainly — coral is "the user's data is less safe than they believe", and nothing in the vault
    // is at risk here. What is lost is the staged copy in memory and the work of producing it; the
    // file the user picked is untouched on disk and can be picked again. Painting this the same
    // colour as Replace-vault would spend the alarm the app needs for the control that really does
    // discard a vault, two dialogs up.
    //
    // Gated on `asksBeforeExit` as well as the flag, for the reason the Replace dialog documents:
    // the flag is `rememberSaveable` and the staged vault is not, so a process-death restore would
    // otherwise bring this back over nothing and "Discard" would dismiss a dialog about work that
    // no longer exists.
    if (confirmDiscardImport && state.asksBeforeExit) {
        LkDialog(
            onDismiss = { confirmDiscardImport = false },
            title = stringResource(R.string.backup_discard_import_dialog_title),
            body = stringResource(R.string.backup_discard_import_dialog_body),
            icon = LkIcons.Warning,
            accent = c.amber,
            confirm = LkDialogAction(
                label = stringResource(R.string.backup_discard_import_dialog_confirm),
                // No `confirmDiscardImport = false` here, and it must not be added back — it was,
                // and `checkNoDismissThenNavigate` caught it. A pop takes the screen's dialogs down
                // as part of taking the screen down, so closing one first plays two window
                // teardowns for one intent: that task measured seven frames of the undimmed form,
                // redrawn complete, between the dialog going and the list arriving. The departure
                // here is unconditional, so there is no case that needs the flag cleared; the
                // saveable flag dies with the back-stack entry, and even a restored `true` renders
                // nothing because the `state.asksBeforeExit` gate above is false once the staged
                // vault is gone.
                //
                // `cancelImport()` stays and is not a dismissal — it drops another vault's
                // decrypted contents out of memory, which is the one thing here that must happen
                // whether or not the navigation does.
                onClick = {
                    viewModel.cancelImport()
                    onBack()
                },
            ),
        )
    }

    confirmRestore?.let { file ->
        LkDialog(
            onDismiss = {
                confirmRestorePath = null
                restorePassword = ""
            },
            title = stringResource(R.string.backup_restore_dialog_title),
            // Snapshots hold the vault only, not the media — five generations of every video would
            // be a storage problem of its own. Restoring one therefore drops files added since,
            // which is consistent with dropping the items that referenced them, but it is a
            // sharper loss than "anything saved since then" conveys on its own.
            // Says *why* the media goes, not just that it does: a snapshot holds no files at all,
            // so restoring one leaves nothing pointing at anything added since. Phrased as
            // "including any files or photos added since" it read as though the snapshot had them
            // and was choosing not to restore them.
            body = stringResource(
                R.string.backup_restore_dialog_body,
                snapshotLabelEpochMillis(file).asSnapshotTimestamp(),
            ),
            // Coral, and the warning glyph, like the Replace-vault dialog in this same file. The
            // action already carries `Destructive` emphasis, and `LkColors` states the test plainly:
            // amber is "something is worth looking at but nothing is wrong", and coral's test "is
            // not 'did the app fail', it is 'is the user's data less safe than they believe'".
            // Restoring a snapshot discards everything saved since it and detaches every file added
            // since — the same class of loss the sibling dialog paints coral for. A refresh icon in
            // the reassuring colour made the most destructive control on this screen the calmest.
            // Found by the F11 UX persona, 2026-08-30.
            icon = LkIcons.Warning,
            accent = c.coral,
            confirm = LkDialogAction(
                label = stringResource(R.string.backup_restore_dialog_confirm),
                enabled = restorePassword.isNotEmpty() && !state.isWorking,
                onClick = {
                    confirmRestorePath = null
                    val password = restorePassword.toCharArray()
                    restorePassword = ""
                    viewModel.restoreSnapshot(file, password)
                },
                emphasis = LkActionEmphasis.Destructive,
            ),
        ) {
            // The gate. Restoring discards everything saved since the snapshot and was the one
            // irreversible control in the app that asked for nothing but a tap (release sweep
            // 2026-09-20, 1C-O4); export, the KDF change and both duress changes all ask for this
            // same password. Inside the dialog rather than as a step before it, so the sentence
            // saying what is about to be lost is still on screen while it is typed.
            //
            // The label and its well are one thing, so the pair carries the label-to-well gap
            // itself rather than inheriting the dialog’s rhythm — the idiom the export and KDF
            // forms above use, and for the reason stated there: LkFormField wraps LkTextField, not
            // LkPasswordField.
            Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                LkFieldLabel(stringResource(R.string.backup_master_password_label))
                LkPasswordField(
                    value = restorePassword,
                    onValueChange = { restorePassword = it },
                    label = stringResource(R.string.backup_restore_master_password_field_label),
                    placeholder = stringResource(R.string.backup_password_placeholder),
                    showCount = true,
                    // Deliberately no Done-submits shortcut, unlike the export and KDF wells: this
                    // is the one form whose submit cannot be undone, so it costs a deliberate tap
                    // on a control that says `Restore` rather than a keystroke on a keyboard.
                )
            }
        }
    }
}

/** A box: the same glyph the Recovery Kit uses, because a snapshot is the same kind of object. */
private val RollingSnapshotIcon get() = LkIcons.RecoveryKit

/**
 * When to say a snapshot was taken: its own filename first, the file's mtime only as a fallback.
 *
 * `File.lastModified()` was the label, and a master-password change resets it on every snapshot at
 * once — `StaleContainerSweeper` re-keys each one by renaming a sibling over it — so five distinct
 * restore points collapsed into five rows all reading the moment of the rotation (F-43, sweep
 * `visual-20260915`). See [RollingBackupManager.snapshotTakenAt] for why the name survives where the
 * mtime does not. The mtime fallback is for a file that is not a snapshot at all, which the list
 * cannot currently hold: it is there so a future name change degrades to the old label rather than
 * to no label.
 */
internal fun snapshotLabelEpochMillis(file: File): Long =
    RollingBackupManager.snapshotTakenAt(file)?.toEpochMilli() ?: file.lastModified()

/** The two facts a snapshot row states before the user commits to an irreversible restore. */
internal data class SnapshotRowFacts(val takenAtMillis: Long, val sizeBytes: Long)

/**
 * When a snapshot was taken, and how big it is.
 *
 * The size is the half that was missing. Choosing between five snapshots of a 175-item vault, the
 * rows carried a timestamp and nothing else, so nothing said which one was fuller — directly above
 * a line warning that restoring one replaces the current vault and anything saved since is gone
 * (release sweep 2026-09-20, finding 3B-F7). It is the encrypted on-disk size, which is the only
 * size available without decrypting the snapshot, and it tracks the vault's contents closely enough
 * to tell a full generation from a nearly empty one.
 */
internal fun snapshotRowFacts(file: File): SnapshotRowFacts =
    SnapshotRowFacts(takenAtMillis = snapshotLabelEpochMillis(file), sizeBytes = file.length())

/**
 * A timestamp a person can read, in their own zone.
 *
 * `Instant.toString()` was being rendered directly, which produced `2026-07-25T18:41:36.284Z` — a
 * UTC machine timestamp with milliseconds, shown to someone trying to answer "is this today's?".
 *
 * `getBestDateTimePattern` rather than a fixed pattern, resolved from the composition's locale
 * (not the JVM default): day-then-month and the 12/24-hour choice are locale and device settings.
 * Same approach as `HiddenItemsScreen.instantText`.
 */
@Composable
private fun Long.asLocalTimestamp(): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { localTimestampFormatter(locale) }
    return formatter.format(Instant.ofEpochMilli(this))
}

/** Split out from [asLocalTimestamp] so the locale-driven pattern is unit-testable without a
 * Compose environment. */
internal fun localTimestampFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter
        .ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMdjm"), locale)
        .withZone(ZoneId.systemDefault())

/**
 * A snapshot's timestamp, to the second.
 *
 * The rolling save fires on a change, so several snapshots routinely land inside one minute: five
 * edits in a minute produced `vault-20260920-092355/092406/092416/092427/092437.enc` and four
 * consecutive rows all reading `Sep 20, 2026, 12:24 PM` (release sweep 2026-09-20, finding 1C-F3).
 * "Restore to just before I broke it" is the only thing this list is for, and the restore is
 * irreversible, so the rows have to be distinguishable.
 *
 * Its own formatter rather than a wider skeleton on [localTimestampFormatter]: that one also labels
 * the last export, where a seconds field is noise rather than a distinction.
 */
@Composable
private fun Long.asSnapshotTimestamp(): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { snapshotTimestampFormatter(locale) }
    return formatter.format(Instant.ofEpochMilli(this))
}

/** Split out from [asSnapshotTimestamp] for the same reason [localTimestampFormatter] is. */
internal fun snapshotTimestampFormatter(locale: Locale): DateTimeFormatter =
    DateTimeFormatter
        .ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMdjms"), locale)
        .withZone(ZoneId.systemDefault())

/**
 * Resolves a [BackupMessage] to displayable text at composition time — never inside a handler,
 * per the `LocalContextGetResourceValueCall` gate. Every variant now carries a real `@StringRes`;
 * there used to be a `Raw` variant echoing `AppError.message` verbatim, unlocalized (see
 * [AppError.toBackupMessage]'s doc comment for why it was removed).
 */
@Composable
private fun resolveBackupMessage(message: BackupMessage, @StringRes recoveryKitVoidedNotice: Int): String = when (message) {
    is BackupMessage.Res -> stringResource(message.res, *message.args.toTypedArray())
    is BackupMessage.Plural -> pluralStringResource(message.res, message.count.toInt(), message.count)
    // formatShortFileSize, not a hand-rolled divide: it is locale-aware (a French build says
    // "4,5 Mo") and it already exists, so no string needs a translated "MB".
    is BackupMessage.Size -> stringResource(
        message.res,
        Formatter.formatShortFileSize(LocalContext.current, message.bytes),
    )
    // On its own line under the result, so neither sentence has to end the other — the result
    // lines carry no closing full stop.
    is BackupMessage.ImportComplete -> buildString {
        append(resolveBackupMessage(message.result, recoveryKitVoidedNotice))
        if (message.recoveryKitVoided) {
            append('\n')
            append(stringResource(recoveryKitVoidedNotice))
        }
    }
}

/**
 * The re-calibration offer, in both directions.
 *
 * One composable rather than two because the *shape* is identical \u2014 a headline, the consequence,
 * the master password, one button \u2014 and only the words differ. Splitting it would let the two
 * directions drift into looking like different kinds of decision, when they are the same decision
 * with opposite signs. The password is required because this rewrites the vault header; it is
 * wiped by the ViewModel on every path.
 */
@Composable
private fun RecalibrateOffer(
    state: BackupSettingsUiState,
    viewModel: BackupSettingsViewModel,
    headline: String,
    consequence: String,
) {
    val c = LkTheme.colors
    var password by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Text(headline, style = LkText.bodyQuiet, color = c.textPrimary)
        LkFootnote(consequence, accent = c.amber)
        // The label and its well are one thing, so the pair carries the label-to-well gap
        // itself rather than inheriting the card's field-to-field rhythm. LkFormField owns
        // that relationship everywhere else; it wraps LkTextField, not LkPasswordField, so
        // a password pairing has to state the same value instead of inheriting it.
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            LkFieldLabel(stringResource(R.string.backup_master_password_label))
            LkPasswordField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.backup_kdf_master_password_field_label),
                placeholder = stringResource(R.string.backup_password_placeholder),
                showCount = true,
                // The only well in this offer, and Update is the only button, so Done is Update.
                // Same gate, and the same clear afterwards — the ViewModel takes ownership of the
                // array either way, so the field must not keep the string behind it.
                onImeAction = {
                    if (password.isNotEmpty() && !state.isWorking) {
                        viewModel.applyKdfRecalibration(password.toCharArray())
                        password = ""
                    }
                },
            )
        }
        LkPrimaryButton(
            text = stringResource(R.string.backup_kdf_update_button),
            enabled = password.isNotEmpty() && !state.isWorking,
            onClick = {
                viewModel.applyKdfRecalibration(password.toCharArray())
                password = ""
            },
        )
        // Neither direction is forced. A user who reads both sentences and prefers what they have
        // is making a legitimate choice, and the screen should not keep asking.
        LkFootnote(stringResource(R.string.backup_kdf_leaving_fine_footnote))
    }
}
