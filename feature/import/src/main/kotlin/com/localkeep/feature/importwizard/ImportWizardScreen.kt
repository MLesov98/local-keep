package com.localkeep.feature.importwizard

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import com.localkeep.core.import.ImportFailureReason
import com.localkeep.core.import.ImportMerger
import com.localkeep.core.import.SkipReason
import com.localkeep.core.import.SourceFormat
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkCard
import com.localkeep.core.ui.theme.LkChip
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFootnote
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkPasswordField
import com.localkeep.core.ui.theme.LocalLkChromeUnpinned
import com.localkeep.core.ui.theme.rememberLkKeyboardUnpinsChrome
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSectionLabel
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkBrandedText
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkFocusRing
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkScaledMaxLines
import com.localkeep.core.ui.theme.lkStaggerDelay
import com.localkeep.core.ui.theme.lkSurface

/**
 * The migration wizard (UI/UX spec §6).
 *
 * The importers have worked and been tested since Phase 12; there was simply no way to reach them
 * — Settings' import entry was a literal no-op. This is that missing path: pick a source, follow
 * its export steps, choose the file, then review exactly what will land in the vault before
 * anything is written.
 */
@Composable
fun ImportWizardScreen(
    viewModel: ImportWizardViewModel,
    onBack: () -> Unit,
    onFinished: () -> Unit,
    /** Leaves the wizard for Backup & export, where a picked `.vault` is actually restored. */
    onOpenBackup: () -> Unit,
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // Ends the excursion on cancel too, or the vault would sit on the longer timer forever.
        viewModel.onPickerClosed()
        uri?.let(viewModel::onFilePicked)
    }

    // The key file is a second SAF pick: KeePass can protect a database with a password, a key
    // file, or both, and nothing in the container says which — so it cannot be found, only asked
    // for. `*/*` because KeePass lets any file at all be a key file.
    val pickKeyFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        viewModel.onPickerClosed()
        uri?.let(viewModel::onKeyFilePicked)
    }

    // Back is the header arrow, step for step. Without this the gesture went straight past the
    // wizard to the nav host and popped the whole destination: three steps in, one swipe dropped
    // the file, the password and the preview at once — and `onCleared` discards the buffered bytes,
    // so re-entering starts from the picker.
    //
    // DONE is the exception: the flow has finished, so Back leaves the wizard rather than
    // resetting it to a fresh PICK_SOURCE (the old behaviour threw a user who just imported
    // something back into the source grid, and re-tapping a source restarted a flow that had
    // already landed).
    BackHandler(enabled = state.step != ImportUiState.Step.PICK_SOURCE) {
        if (state.step == ImportUiState.Step.DONE) onBack() else viewModel.back()
    }

    LkScreen(accent = c.jade, auraAlpha = 0.07f, auraBias = 0.4f) {
        WizardFrame(step = state.step) {
            LkScreenHeader(
                title = stringResource(R.string.import_screen_title),
                subtitle = state.progressLabel(),
                modifier = Modifier.lkEnter(),
                trailing = {
                    LkIconButton(
                        icon = if (state.step == ImportUiState.Step.PICK_SOURCE) {
                            LkIcons.Close
                        } else {
                            LkIcons.ChevronLeft
                        },
                        label = if (state.step == ImportUiState.Step.PICK_SOURCE) {
                            stringResource(R.string.import_nav_close)
                        } else {
                            stringResource(R.string.import_nav_back)
                        },
                        onClick = {
                            if (state.step == ImportUiState.Step.PICK_SOURCE || state.step == ImportUiState.Step.DONE) {
                                onBack()
                            } else {
                                viewModel.back()
                            }
                        },
                        plateAlignment = Alignment.CenterEnd,
                    )
                },
            )

            if (state.isWorking) {
                // Derived from the step rather than hardcoded. Both phases raise the same
                // `isWorking`, so committing the selection to the vault — which is a write, and the
                // slower of the two — announced "Reading the file…", describing work that had
                // already finished.
                val working = if (state.step == ImportUiState.Step.PREVIEW) {
                    stringResource(R.string.import_working_saving)
                } else {
                    stringResource(R.string.import_working_reading)
                }
                LkInlineNote(
                    working,
                    accent = c.textMuted,
                    icon = LkIcons.Refresh,
                    modifier = Modifier.announce(working),
                )
            }
            resolveImportError(state.error)?.let { LkInlineError(it, Modifier.announce(it)) }
            // A LocalKeep backup is restored under Backup & export, not here — so the answer is the
            // way there, not just its name. Device report C3 finding 1: the sentence alone still
            // left a user moving to a new phone to find a screen they had never seen.
            if (state.error == ImportError.LocalKeepBackup) {
                LkSecondaryButton(
                    text = stringResource(R.string.import_open_backup_button),
                    icon = LkIcons.Import,
                    onClick = onOpenBackup,
                    modifier = Modifier.fillMaxWidth().lkTag("import-open-backup"),
                )
            }

            when (state.step) {
                ImportUiState.Step.PICK_SOURCE -> SourceGrid(viewModel::selectSource)
                ImportUiState.Step.INSTRUCTIONS -> Instructions(
                    state = state,
                    viewModel = viewModel,
                    onChooseFile = {
                        state.source?.let {
                            viewModel.onPickerOpened()
                            pickFile.launch(it.mimeTypes)
                        }
                    },
                )
                ImportUiState.Step.PASSWORD -> PasswordStep(
                    state = state,
                    onPasswordChanged = viewModel::onPasswordChanged,
                    onSubmit = viewModel::submitPassword,
                    onAddKeyFile = {
                        viewModel.onPickerOpened()
                        pickKeyFile.launch(arrayOf("*/*"))
                    },
                )
                ImportUiState.Step.PREVIEW -> Preview(state, viewModel)
                ImportUiState.Step.DONE -> Done(state, viewModel, onFinished)
            }
        }
    }

    // Outside the step `when`, because the answer is what decides which step is showing.
    if (state.confirmDiscard) {
        LkDialog(
            onDismiss = viewModel::cancelDiscard,
            title = stringResource(R.string.import_preview_discard_confirm_title),
            body = stringResource(R.string.import_preview_discard_confirm_body),
            icon = LkIcons.Trash,
            // Amber, not coral: nothing is wrong and nothing of the user's is at risk — the file on
            // disk is untouched and no item has been written. What is lost is the reading of it.
            accent = c.amber,
            confirm = LkDialogAction(
                label = stringResource(R.string.import_preview_discard_confirm_button),
                onClick = viewModel::confirmDiscard,
                emphasis = LkActionEmphasis.Destructive,
            ),
        )
    }
}

/**
 * The wizard's column: the screen's gutters, and the room above the keyboard.
 *
 * Split out of [ImportWizardScreen] so the password step can be composed under a keyboard at a
 * chosen window size — the screen itself needs a Hilt-injected ViewModel with a vault behind it.
 *
 * **The password step is the only one with a field**, and on a window the keyboard leaves too
 * little room (see [rememberLkKeyboardUnpinsChrome]) the pinned header, the step's own scroll and
 * the pinned "Open database" were three claims on 298dp at 548dp tall: the scroll got 100dp and the
 * well shared it with the explanation above. So the whole column scrolls as one then, and
 * [PasswordStep] reads [LocalLkChromeUnpinned] to drop its own scroll (a scroll inside a scroll
 * throws) and let its button ride at the end. Every other step keeps its arrangement.
 */
@Composable
internal fun WizardFrame(step: ImportUiState.Step, content: @Composable ColumnScope.() -> Unit) {
    val unpinned = step == ImportUiState.Step.PASSWORD && rememberLkKeyboardUnpinsChrome()
    CompositionLocalProvider(LocalLkChromeUnpinned provides unpinned) {
        Column(
            Modifier
                .fillMaxSize()
                // The activity goes edge-to-edge and deliberately does NOT pad for the IME
                // globally — each screen pads the element that has to stay reachable. This one
                // never did, so on the password step the keyboard opened over "Open database" and
                // the column, being `fillMaxSize` with no scroll, had nowhere to move it to: the
                // only way on was the keyboard's own Done key, which is not discoverable and is
                // absent on hardware keyboards.
                .imePadding()
                .then(if (unpinned) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
internal fun SourceGrid(onSelect: (ImportSource) -> Unit) {
    val c = LkTheme.colors
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.import_source_title), style = LkText.cardTitle, color = c.textStrong)
            Text(
                // Through the brand helper like every other sentence that names the product, so
                // the wordmark's two tones read the same on this screen as on the rest.
                lkBrandedText(stringResource(R.string.import_source_body)),
                style = LkText.bodyQuiet,
                color = c.textMuted,
            )
        }
        // Two per row: a real grid of seven tiles wastes vertical space on a phone, and these
        // entries need enough width for names like "Apple Passwords" not to truncate.
        //
        // One per row when two would break a name inside a word. At 308dp and font 1.5 a tile's
        // text column was ~100dp and the names came out as "Bitwarde / n", "1Passwo / rd" and a
        // clipped "Apple Passwor" (final sweep, 2026-10-28). Decided by measuring the widest word
        // across every source's name against the column a half-width tile leaves, so the two-up
        // grid the comps draw stays wherever it fits.
        val labels = ImportSource.entries.map { stringResource(it.labelRes) }
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val widestWord = remember(labels, measurer, density) {
            val px = labels.flatMap { it.split(' ') }.filter { it.isNotEmpty() }
                .maxOfOrNull { measurer.measure(it, LkText.rowTitle, softWrap = false, maxLines = 1).size.width } ?: 0
            with(density) { px.toDp() }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = if ((maxWidth - SourceGridGap) / 2 - SourceTilePadding * 2 < widestWord) 1 else 2
            Column(verticalArrangement = Arrangement.spacedBy(SourceGridGap)) {
                ImportSource.entries.chunked(perRow).forEachIndexed { rowIndex, pair ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SourceGridGap),
                    ) {
                        pair.forEachIndexed { column, source ->
                            SourceTile(
                                source = source,
                                modifier = Modifier
                                    .weight(1f)
                                    .lkEnter(delayMillis = lkStaggerDelay(rowIndex * perRow + column)),
                                onClick = { onSelect(source) },
                            )
                        }
                        // Keeps the last odd tile at half width instead of stretching it across the row.
                        if (pair.size < perRow) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The gap between source tiles, across and down. */
private val SourceGridGap = 12.dp

/** A source tile's inner padding, each side — what the name's column loses to the tile's frame. */
private val SourceTilePadding = 16.dp

@Composable
private fun SourceTile(
    source: ImportSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val blocked = source.unsupportedReason != null
    val accent = if (blocked) c.textMuted else c.jade
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(source.labelRes)
    val blockedDescription = stringResource(R.string.import_source_tile_blocked_description, label)

    Column(
        modifier = modifier
            .lkPressScale(interaction, pressedScale = 0.985f)
            .lkSurface(LkShape.xxl, c.surface, c.ring)
            .lkFocusRing(LkShape.xxl)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .minTouchTarget()
            .padding(SourceTilePadding)
            .semantics(mergeDescendants = true) {
                contentDescription = if (blocked) {
                    blockedDescription
                } else {
                    label
                }
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .lkSurface(LkShape.md, accent.copy(alpha = 0.08f), accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(source.icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = LkText.rowTitle, color = c.textPrimary, maxLines = 2)
            if (blocked) {
                Text(stringResource(R.string.import_source_tile_unavailable), style = LkText.caption, color = c.textFaint)
            }
        }
    }
}

@Composable
private fun Instructions(state: ImportUiState, viewModel: ImportWizardViewModel, onChooseFile: () -> Unit) {
    val c = LkTheme.colors
    val source = state.source ?: return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // Resolved once per source rather than on every recomposition: this is a PackageManager call,
    // and it is the only place the app looks at what else is installed.
    val launchable = remember(source) { source.installedLaunchIntent(context) }
    val label = stringResource(source.labelRes)
    // Resolved at composition, not inside the "Copy these steps" click handler below — a handler
    // is not a composable context, so `stringResource` cannot be called there. See ImportSource.kt.
    val stepTexts = source.steps.map { stringResource(it.textRes) }
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LkSectionLabel(stringResource(R.string.import_instructions_export_from, label))

        if (source.showsWhyManualNote()) WhyManualNote(source)

        // The two things we can actually do for the user. Everything below this point is them
        // driving another app, because nothing else is possible — step 1 says why.
        if (launchable != null) {
            LkSecondaryButton(
                text = stringResource(R.string.import_instructions_open_source_app, label),
                icon = LkIcons.OpenExternal,
                onClick = { viewModel.openSourceApp(launchable) },
            )
        }
        source.webExportUrl?.let { url ->
            LkSecondaryButton(
                text = stringResource(R.string.import_instructions_open_export_page),
                icon = LkIcons.Globe,
                onClick = {
                    // No inline error here — unlike openSourceApp, this has a real fallback: the
                    // URL itself. Same "Copy these steps" pattern used a few lines below.
                    if (!viewModel.tryOpenUrl(url)) {
                        clipboard.setText(AnnotatedString(url))
                        LkNotices.post(R.string.import_instructions_export_page_copied)
                    }
                },
            )
        }

        LkCard {
            source.steps.forEachIndexed { index, step ->
                if (index > 0) {
                    Spacer(Modifier.height(10.dp))
                    // A rail joining one plate to the next, aligned to their centres. Without it the
                    // illustrations read as a column of unrelated badges rather than as an order.
                    Box(
                        Modifier
                            .padding(start = 22.dp)
                            .size(width = 2.dp, height = 12.dp)
                            .lkSurface(LkShape.xs, c.jade.copy(alpha = 0.16f), Color.Transparent),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                StepRow(
                    index = index,
                    total = source.steps.size,
                    stepText = stepTexts[index],
                    art = step.art,
                    modifier = Modifier.lkEnter(delayMillis = lkStaggerDelay(index)),
                )
            }
        }

        // The steps happen in another app, often on another device — copying them out is the
        // difference between following them and guessing.
        LkSecondaryButton(
            text = stringResource(R.string.import_instructions_copy_steps),
            icon = LkIcons.Copy,
            onClick = {
                clipboard.setText(
                    AnnotatedString(
                        stepTexts.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"),
                    ),
                )
                // A copy with no confirmation is indistinguishable from a dead button — and this
                // one walks the user through leaving the app, where they cannot check the screen.
                // (QA 2026-08-06, A2.) These are public setup instructions, not a secret, so the
                // sensitive/auto-clear clipboard path is not needed here.
                LkNotices.post(R.string.import_instructions_steps_copied)
            },
        )

        if (source.caveats.isNotEmpty()) {
            LkSectionLabel(stringResource(R.string.import_instructions_worth_knowing))
            source.caveats.forEach { LkFootnote(stringResource(it)) }
        }

        // No Spacer here: this column is `spacedBy`, so a Spacer is itself a spaced child and
        // costs its own height plus a gap on each side (V23, 2026-09-12). Same trap as G11.
        val blocked = source.unsupportedReason
        if (blocked != null) {
            LkInlineError(blocked, Modifier.announce(blocked))
        } else {
            LkPrimaryButton(
                text = stringResource(R.string.import_instructions_choose_file),
                onClick = onChooseFile,
                // Its Password/Preview/Done siblings all gate on isWorking; this one didn't, so a
                // second tap during the ~minute KDBX parse re-entered onFilePicked mid-read. See the
                // guard added there.
                enabled = !state.isWorking,
            )
        }
    }
}

/**
 * Whether the instructions step opens with the amber panel.
 *
 * Only for a source that can be exported from a computer alone. The panel used to open every
 * instructions step with Android's app-sandbox explanation, which step 1 has already given in its
 * own words, five lines further down the same wizard. What step 1 cannot know is which source the
 * user picks, so "this export needs a computer" is the part that is still news.
 */
internal fun ImportSource.showsWhyManualNote(): Boolean = requiresComputer

/**
 * States, once and up front, that this source's export can only happen on a computer.
 *
 * Without it the user reads four steps before discovering step one needs a Mac. The other half of
 * what this panel used to say — that LocalKeep cannot perform the export itself because Android
 * sandboxes every app's storage — is step 1's job and is no longer repeated here; see
 * [showsWhyManualNote].
 *
 * **Amber and static, not red and pulsing.** Red is the app's error colour and this is not an
 * error — nothing has gone wrong, and a screen that opens by flashing red at you teaches people to
 * ignore red. Pulsing would also be motion with no meaning, which the app suppresses under the
 * system "remove animations" setting, so the emphasis would simply vanish for the users most
 * likely to need the explanation.
 */
@Composable
private fun WhyManualNote(source: ImportSource) {
    val c = LkTheme.colors
    val label = stringResource(source.labelRes)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lkSurface(LkShape.lg, c.amber.copy(alpha = 0.09f), c.amber.copy(alpha = 0.24f))
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            LkIcons.Warning,
            contentDescription = null,
            tint = c.amber,
            modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                stringResource(R.string.import_why_manual_title_computer),
                style = LkText.buttonSmall,
                color = c.amber,
            )
            Text(
                stringResource(R.string.import_why_manual_body_computer, label),
                style = LkText.caption,
                color = c.textMuted,
            )
        }
    }
}

/**
 * One export step: its illustration, its number, and the sentence.
 *
 * Merged into a single accessibility node reading "Step 2 of 4. …". The plate is decorative and the
 * number is a bare numeral, so read separately a screen reader would announce "2" as its own stop
 * before the sentence it belongs to — which is the same mistake the import preview rows avoid.
 */
@Composable
private fun StepRow(index: Int, total: Int, stepText: String, art: StepArt, modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    val description = stringResource(R.string.import_step_row_description, index + 1, total, stepText)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepIllustration(art)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.import_step_row_number, index + 1), style = LkText.fieldLabel, color = c.textFaint)
            Text(stepText, style = LkText.body, color = c.textSecondary)
        }
    }
}

@Composable
internal fun ColumnScope.PasswordStep(
    state: ImportUiState,
    onPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onAddKeyFile: () -> Unit,
) {
    val c = LkTheme.colors
    // Remembered here, not in the ViewModel and not saveable: whether a secret is on screen is a
    // property of this composition, and a rotation or a return to the step starts masked again.
    var revealed by remember { mutableStateOf(false) }
    // The explanatory text and the field scroll; "Open database" does not.
    //
    // The screen-level `imePadding()` above was itself a fix for "the keyboard covered Open
    // database", and it is the anti-pattern `LkActionScaffold`'s KDoc warns about: padding the
    // whole screen by the IME SHRINKS the content box rather than moving anything, so an unscrolled
    // child is crushed instead of scrolled. Every sibling step already works around it —
    // `SourceGrid` has a `verticalScroll`, `Preview` a `weight(1f)` — and this one, the only step
    // that pairs a text field with a primary action, had neither. At a large font scale or on a
    // short screen the button went off the bottom with no way to reach it.
    //
    // `fill = false` so the step keeps its natural height when there is room; the weight only caps
    // it at what is left, which is what gives the button somewhere guaranteed to sit.
    //
    // While the keyboard leaves too little room, [WizardFrame] scrolls the whole column instead and
    // this one neither weights nor scrolls: the header and the button ride along with it.
    Column(
        modifier = if (LocalLkChromeUnpinned.current) {
            Modifier
        } else {
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                state.fileName ?: stringResource(
                    if (state.passwordIsForDatabase) {
                        R.string.import_password_default_filename
                    } else {
                        R.string.import_password_default_filename_export
                    },
                ),
                style = LkText.monoField,
                color = c.textStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (state.passwordIsForDatabase) {
                        R.string.import_password_body
                    } else {
                        R.string.import_password_body_export
                    },
                ),
                style = LkText.bodyQuiet,
                color = c.textMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            val passwordLabel = stringResource(
                if (state.passwordIsForDatabase) {
                    R.string.import_password_field_label
                } else {
                    R.string.import_password_field_label_export
                },
            )
            LkFieldLabel(passwordLabel)
            LkPasswordField(
                value = state.password,
                onValueChange = onPasswordChanged,
                label = passwordLabel,
                placeholder = stringResource(R.string.import_password_field_placeholder),
                showCount = true,
                revealed = revealed,
                onToggleReveal = { revealed = !revealed },
                onImeAction = onSubmit,
            )
        }
        // KeePass only. A key file is a KeePass credential; an export password is the whole of
        // what an encrypted export asks for, so offering the pick there would invite the user to
        // hunt for a second file that has never existed.
        if (state.passwordIsForDatabase) Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
            LkSecondaryButton(
                text = stringResource(R.string.import_password_key_file_add),
                onClick = onAddKeyFile,
                // Key, not Lock: LkIcons reserves the padlock for "this field is behind re-auth",
                // and a key file is a credential, which is what Key names.
                icon = LkIcons.Key,
                enabled = !state.isWorking,
            )
            state.keyFileName?.let { name ->
                Text(
                    stringResource(R.string.import_password_key_file_chosen, name),
                    style = LkText.bodyQuiet,
                    color = c.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    LkPrimaryButton(
        text = stringResource(
            if (state.passwordIsForDatabase) {
                R.string.import_password_submit
            } else {
                R.string.import_password_submit_export
            },
        ),
        onClick = onSubmit,
        // A key file on its own is a complete credential, so an empty password is not an empty
        // form. Requiring typed characters made a key-file-only database unopenable here.
        enabled = (state.password.isNotEmpty() || state.keyFileName != null) && !state.isWorking,
    )
}

/**
 * Step 3: what will land in the vault, and the chance to deselect any of it before it does.
 *
 * Everything above the list scrolls *with* the list, as one `LazyColumn` header item, rather than
 * sitting in fixed rows above a `weight(1f)` box. In landscape — or any short window — the fixed
 * rows consumed the whole height, the box was laid out at zero and the step rendered **no item
 * rows at all** while still offering "Import N items": the one gate before data enters the vault,
 * invisible, with nothing to scroll (1A-F4 / 1B-F8, 2026-09-20). The button stays outside so it is
 * reachable without scrolling to the end of a long export.
 */
@Composable
private fun Preview(state: ImportUiState, viewModel: ImportWizardViewModel) = PreviewContent(
    state = state,
    onSetMode = viewModel::setMode,
    onSetAllSelected = viewModel::setAllSelected,
    onDeselectDuplicates = viewModel::deselectDuplicates,
    onToggleRow = viewModel::toggleRow,
    onApply = viewModel::applyImport,
)

/**
 * [Preview]'s layout, with the ViewModel replaced by the five calls it makes.
 *
 * Split out so the step can be composed in a unit test at a chosen window size — `ImportWizardScreen`
 * itself needs a Hilt-injected ViewModel with a vault behind it, which is why this step's landscape
 * defect survived every unit test the module had.
 */
@Composable
internal fun PreviewContent(
    state: ImportUiState,
    onSetMode: (ImportMerger.Mode) -> Unit,
    onSetAllSelected: (Boolean) -> Unit,
    onDeselectDuplicates: () -> Unit,
    onToggleRow: (UUID) -> Unit,
    onApply: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // A wide, short window puts the header *beside* the rows instead of above them.
            //
            // Scrolling them together fixed the zero-height list (1A-F4 / 1B-F8) and did not fix
            // what the user sees: on the device in landscape the step got ~253dp, the header —
            // counts, a carried-columns footnote, two chip rows and the mode explainer — is taller
            // than that on its own, so the first view held no item row and no Select all / None
            // while "Import 3 items" stayed enabled. The rows were one swipe away, which is one
            // swipe more than a review gate may ask for (1B-F3 re-verify, 2026-09-20).
            //
            // Landscape has the width to spare: 731dp on the emulator that showed the defect.
            val sideBySide = maxHeight < SHORT_WINDOW && maxWidth > maxHeight
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(LkSpace.md)) {
                    Column(
                        Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        PreviewHeader(
                            state = state,
                            onSetMode = onSetMode,
                            onSetAllSelected = onSetAllSelected,
                            onDeselectDuplicates = onDeselectDuplicates,
                            // They go above the rows on the other side instead — see SelectionChips.
                            showSelection = false,
                        )
                    }
                    Column(
                        Modifier.weight(0.58f),
                        verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
                    ) {
                        SelectionChips(state, onSetAllSelected, onDeselectDuplicates)
                        PreviewRows(state = state, onToggleRow = onToggleRow, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                PreviewRows(state = state, onToggleRow = onToggleRow) {
                    PreviewHeader(
                        state = state,
                        onSetMode = onSetMode,
                        onSetAllSelected = onSetAllSelected,
                        onDeselectDuplicates = onDeselectDuplicates,
                        modifier = Modifier.padding(bottom = LkSpace.sm),
                    )
                }
            }
        }

        LkPrimaryButton(
            text = if (state.selectedCount > 0) {
                pluralStringResource(R.plurals.import_preview_import_button, state.selectedCount, state.selectedCount)
            } else {
                stringResource(R.string.import_preview_nothing_selected)
            },
            onClick = onApply,
            enabled = state.selectedCount > 0 && !state.isWorking,
        )
    }
}

/**
 * Below this the step is too short to hold the header and a row, so the two go side by side.
 *
 * Measured, not chosen: the landscape step on emulator-5556 (1080x1920 at 420dpi) is 253dp tall and
 * the header alone is taller. Portrait on the same device is 573dp.
 */
private val SHORT_WINDOW = 380.dp

/** The item rows, with [header] — when there is one — scrolling above them as the list's own item. */
@Composable
private fun PreviewRows(
    state: ImportUiState,
    onToggleRow: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        if (header != null) item(key = "preview-header") { header() }
        itemsIndexed(state.rows, key = { _, row -> row.id.toString() }) { index, row ->
            PreviewRowItem(
                row = row,
                timestampsKnown = state.timestampsKnown,
                modifier = Modifier.lkEnter(delayMillis = lkStaggerDelay(index)),
                onToggle = { onToggleRow(row.id) },
            )
        }
    }
}

/** The counts, the notices and the mode/selection chips — the preview's scrolling header. */
@Composable
private fun PreviewHeader(
    state: ImportUiState,
    onSetMode: (ImportMerger.Mode) -> Unit,
    onSetAllSelected: (Boolean) -> Unit,
    onDeselectDuplicates: () -> Unit,
    modifier: Modifier = Modifier,
    showSelection: Boolean = true,
) {
    val c = LkTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val itemsFound = pluralStringResource(R.plurals.import_preview_items_found, state.rows.size, state.rows.size)
            Text(
                state.detectedFormat?.let {
                    stringResource(R.string.import_preview_items_found_with_format, itemsFound, it.displayName())
                } ?: itemsFound,
                style = LkText.cardTitle,
                color = c.textStrong,
            )
            // Two sentences, because they are two different facts. One count covering both used to
            // say "N already looks like an entry you have" about rows that matched nothing in the
            // vault and only repeated each other inside the export.
            if (state.vaultDuplicateCount > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.import_preview_duplicate_count,
                        state.vaultDuplicateCount,
                        state.vaultDuplicateCount,
                    ),
                    style = LkText.subtitle,
                    color = c.amber,
                )
            }
            if (state.fileDuplicateCount > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.import_preview_file_duplicate_count,
                        state.fileDuplicateCount,
                        state.fileDuplicateCount,
                    ),
                    style = LkText.subtitle,
                    color = c.amber,
                )
            }
        }

        // The Pro-skip footnote that used to sit here is gone with the filter it explained. Cards
        // and identities now import like anything else; what a free tier may not do is create or
        // edit more of them, which is enforced where the writing happens rather than at the door.

        // Without this an import that left a third of the file behind read exactly like a complete
        // one — and the last step then offers to delete the export. These are parser skips: unlike
        // the Pro skips that once shared this space, they will never import at all, so they are
        // named by reason rather than totalled.
        SkippedByParser(state)
        CarriedColumns(state)
        UnusableTotp(state)
        UnresolvedFieldReferences(state)
        PasskeyOnlyLogins(state)

        // Every control below acts on rows, so with no rows there is nothing for any of them to do
        // — and a 0-item preview offered "Merge duplicates", "Add everything", "Select all" and
        // "None" regardless, four controls that could not change anything (1B-F10, 2026-09-20).
        if (state.rows.isEmpty()) return@Column

        // FlowRow, not Row: a Row measures its later children with whatever width is left, so at
        // `font_scale 2.0` these chips were squeezed and ellipsized to "Add eve…" and "Skip dupli…"
        // — two controls that no longer said what they do (1A-F11 / 1B-F9, 2026-09-20). Wrapping
        // costs a second line at large text and nothing at all at the default size.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
        ) {
            ImportMerger.Mode.entries.forEach { mode ->
                LkChip(
                    text = mode.label(),
                    selected = state.mode == mode,
                    onClick = { onSetMode(mode) },
                )
            }
        }
        Text(
            when (state.mode) {
                ImportMerger.Mode.MERGE -> stringResource(R.string.import_mode_merge_description)
                ImportMerger.Mode.ADD_ALL -> stringResource(R.string.import_mode_add_all_description)
            },
            style = LkText.bodyQuiet,
            color = c.textMuted,
        )

        if (showSelection) SelectionChips(state, onSetAllSelected, onDeselectDuplicates)
    }
}

/**
 * Select all / None / Skip duplicates — the controls that act on the rows.
 *
 * Their own composable because in a short window they travel with the rows instead of staying at
 * the end of the header: the header there is a scrolling side column, and these three were below
 * its fold, so the rows were visible and the only way to deselect one was not
 * (1B-F3 re-verify, 2026-09-20). Above the list they are where what they act on is.
 *
 * Hidden with no rows, like everything else that acts on rows (1B-F10).
 */
@Composable
private fun SelectionChips(
    state: ImportUiState,
    onSetAllSelected: (Boolean) -> Unit,
    onDeselectDuplicates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.rows.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
    ) {
        LkChip(
            stringResource(R.string.import_preview_select_all),
            selected = false,
            onClick = { onSetAllSelected(true) },
        )
        LkChip(
            stringResource(R.string.import_preview_select_none),
            selected = false,
            onClick = { onSetAllSelected(false) },
        )
        if (state.duplicateCount > 0) {
            LkChip(
                text = stringResource(R.string.import_preview_skip_duplicates),
                selected = false,
                accent = LkTheme.colors.amber,
                onClick = onDeselectDuplicates,
            )
        }
    }
}

/**
 * What the parser could not carry, grouped by reason.
 *
 * Shown on the preview *and* again before the clean-up step, because that step offers to delete the
 * source file: the one moment the user can still decide to keep their export is the moment they are
 * being asked to destroy it.
 */
@Composable
private fun SkippedByParser(state: ImportUiState) {
    val grouped = state.skippedByReason
    if (grouped.isEmpty()) return
    val total = state.parserSkipped.size
    // The reasons list itself (e.g. "Bank Account (2); HOTP code (1)") is built from a typed
    // SkipReason per group, resolved through SkipReason.label() and composed with
    // core_import_skip_reason_count — no longer the raw importer-authored strings this comment used
    // to describe as untranslated (Task 10 closed that gap).
    val reasons = grouped.map { (reason, count) ->
        stringResource(R.string.core_import_skip_reason_count, reason.label(), count)
    }.joinToString("; ")
    LkFootnote(
        text = pluralStringResource(R.plurals.import_skipped_summary, total, total, reasons),
        accent = LkTheme.colors.amber,
    )
}

/**
 * Two-factor seeds that crossed over as a hidden field instead of as a live code.
 *
 * The rescue is old and the sentence is not: a seed `TotpFieldParser` refuses was preserved on the
 * item and reported nowhere, so an import that quietly downgraded a working second factor read as a
 * complete success (1B-F5, 2026-09-20). Amber, not coral: nothing failed and nothing was lost — the
 * user has one thing left to do by hand.
 */
@Composable
private fun UnusableTotp(state: ImportUiState) {
    val count = state.unusableTotpCount
    if (count == 0) return
    LkFootnote(
        text = pluralStringResource(R.plurals.import_unusable_totp_summary, count, count),
        accent = LkTheme.colors.amber,
    )
}

/**
 * KeePass entries that borrow a field from an entry this file does not contain.
 *
 * [com.localkeep.core.import.KdbxImporter] substitutes every reference it can follow; what is left
 * is a `{REF:U@I:...}` token where a username or a password should be. The item looked entirely
 * ordinary in the preview until this was said out loud (1A-F7, 2026-09-20). Amber, like the TOTP
 * note above: nothing failed, and there is one thing left for the user to fill in by hand.
 */
@Composable
private fun UnresolvedFieldReferences(state: ImportUiState) {
    val count = state.unresolvedFieldReferenceCount
    if (count == 0) return
    LkFootnote(
        text = pluralStringResource(R.plurals.import_unresolved_reference_summary, count, count),
        accent = LkTheme.colors.amber,
    )
}

/**
 * Logins whose only credential was a passkey.
 *
 * The caveat on the source step already said passkeys do not come across, and that was the whole of
 * it: the item itself imports — name, username, address — so the preview offered what looks like a
 * working login with an empty password, and the step after this one offers to delete the export
 * (1A-F8, 2026-09-20). Amber for the same reason as its neighbours: nothing broke, and the user
 * keeps signing in where the passkey already lives.
 */
@Composable
private fun PasskeyOnlyLogins(state: ImportUiState) {
    val count = state.passkeyOnlyLoginCount
    if (count == 0) return
    LkFootnote(
        text = pluralStringResource(R.plurals.import_passkey_only_summary, count, count),
        accent = LkTheme.colors.amber,
    )
}

/**
 * Columns the file carried that no field of ours matches, kept as custom fields.
 *
 * Shown wherever [SkippedByParser] is, and for the same reason: the two together are what make
 * "N items found" an honest headline. A reshaped column used to be invisible — the wizard reported
 * full success and then offered to delete the export (1B-F1). The column names come out of the
 * user's own file, so they are inserted rather than translated.
 */
@Composable
private fun CarriedColumns(state: ImportUiState) {
    val columns = state.carriedColumns
    if (columns.isEmpty()) return
    LkFootnote(
        text = pluralStringResource(
            R.plurals.import_carried_columns_summary,
            columns.size,
            columns.size,
            columns.joinToString(", "),
        ),
        accent = LkTheme.colors.textMuted,
    )
}

@Composable
private fun PreviewRowItem(
    row: PreviewRow,
    timestampsKnown: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    // Resolved at composition, not inside the `.semantics { }` lambda below — that lambda is not a
    // composable context, so `stringResource` cannot be called there. See LkPasswordField's trap.
    val disposition = resolveDispositionLabel(row, timestampsKnown)
    val selectedWord = stringResource(
        if (row.selected) R.string.import_preview_row_selected else R.string.import_preview_row_not_selected,
    )
    val description = stringResource(R.string.import_preview_row_description, row.title, selectedWord, disposition)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lkPressScale(interaction, pressedScale = 0.99f)
            .lkSurface(LkShape.lg, if (row.selected) c.surface else c.wash, c.ring)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .minTouchTarget()
            .padding(horizontal = 14.dp, vertical = 11.dp)
            // One node per row: as a checkbox plus two labels, a screen reader reads the tick state
            // adrift from the title it belongs to, forty times in a row.
            .semantics(mergeDescendants = true) {
                contentDescription = description
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .lkSurface(
                    LkShape.xs,
                    if (row.selected) c.jade else androidx.compose.ui.graphics.Color.Transparent,
                    if (row.selected) c.jade else c.controlIdle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (row.selected) {
                Icon(LkIcons.Check, contentDescription = null, tint = c.onJade, modifier = Modifier.size(12.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.title,
                style = LkText.rowTitle,
                color = if (row.selected) c.textPrimary else c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                disposition,
                style = LkText.subtitle,
                color = c.textFaint,
                // This line is not a subtitle in the usual sense — for an UPDATES_EXISTING row it is
                // the sentence naming *which* existing vault item this import will overwrite, and
                // those rows stay selected by default. Held at one line, the largest font size cut
                // it before the substituted title appeared, so the warning stopped naming the thing
                // it warns about. Two lines at the default size, because even there a long title or
                // address ended the sentence in an ellipsis before the part that says what happens
                // to the user's entry; the budget scales from there.
                maxLines = lkScaledMaxLines(base = 2),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Done(
    state: ImportUiState,
    viewModel: ImportWizardViewModel,
    onFinished: () -> Unit,
) {
    val c = LkTheme.colors
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Announced: the wizard auto-advances PREVIEW -> DONE with no action from the user, so
        // without a live region the one sentence that says the import worked is silent to a screen
        // reader until they go looking for it.
        val importedSummary =
            // "Imported 0 items" is true and useless. When every selected row merged into the
            // vault, the thing that happened was an update, and the headline says so; the sentence
            // below still gives both numbers. Rows that only repeated an earlier row of the file
            // updated nothing the vault had, so they keep "Imported" and stay out of this count.
            if (state.importedCount == 0 && (state.mergedIntoExistingCount ?: 0) > 0) {
                pluralStringResource(
                    R.plurals.import_done_updated_summary,
                    state.mergedIntoExistingCount ?: 0,
                    state.mergedIntoExistingCount ?: 0,
                )
            } else {
                pluralStringResource(R.plurals.import_done_imported_summary, state.importedCount, state.importedCount)
            }
        LkInlineNote(importedSummary, Modifier.announce(importedSummary))
        resolveDoneNotice(state.importedCount, state.mergedIntoExistingCount)?.let {
            Text(it, style = LkText.bodyQuiet, color = c.textMuted)
        }
        state.repeatedInFileCount?.let { repeated ->
            Text(
                pluralStringResource(R.plurals.import_done_notice_repeated, repeated, repeated),
                style = LkText.bodyQuiet,
                color = c.textMuted,
            )
        }

        SkippedByParser(state)
        CarriedColumns(state)
        UnusableTotp(state)
        UnresolvedFieldReferences(state)
        PasskeyOnlyLogins(state)

        LkSectionLabel(stringResource(R.string.import_done_cleanup_title))
        if (state.sourceDeleted) {
            LkInlineNote(stringResource(R.string.import_done_file_deleted))
        } else {
            // Whether the *file* is still encrypted, not which tile was picked. A .kdbx is, and so
            // is a Bitwarden export written as "Password protected" — which this step told its user
            // was "a plain, unencrypted copy of your passwords" (V3-04). Saying that of a file
            // sealed under a password is simply false, and being caught in a needless exaggeration
            // is how a security warning stops being believed.
            LkInlineError(
                if (state.sourceEncryptedWithPassword) {
                    stringResource(R.string.import_done_delete_warning_encrypted)
                } else {
                    stringResource(R.string.import_done_delete_warning_plain)
                },
            )
            // Deliberately not overstated: through the Storage Access Framework we get a document
            // handle, not raw block access, so this is an ordinary delete and remnants can survive
            // on flash storage. Claiming a secure wipe here would be a lie.
            LkFootnote(stringResource(R.string.import_done_delete_footnote))
            // The label names the file. Everywhere else on this step a KeePass source is called a
            // database, not an export — a button that then says "export file" reads as being about
            // some other file, on the one screen where deleting the wrong thing matters. It stays
            // on the source, not on the warning's own condition: the warning now asks whether the
            // file is encrypted, and a Bitwarden export is an export either way.
            val deleteLabel = when {
                state.isWorking -> stringResource(R.string.import_done_delete_button_working)
                state.source == ImportSource.KEEPASS -> stringResource(R.string.import_done_delete_button_keepass)
                else -> stringResource(R.string.import_done_delete_button_generic)
            }
            LkSecondaryButton(
                text = deleteLabel,
                icon = LkIcons.Trash,
                // A single tap used to call deleteSourceFile() directly — the file is gone for good,
                // through the SAF, with no confirmation. Now it only opens the dialog; the dialog's
                // Destructive action is the one call site left that actually deletes, matching
                // TrashScreen's confirm-then-destroy pattern.
                onClick = viewModel::requestDeleteSourceFile,
                enabled = !state.isWorking,
            )
            if (state.confirmDelete) {
                LkDialog(
                    onDismiss = viewModel::cancelDeleteSourceFile,
                    title = stringResource(R.string.import_done_delete_confirm_title),
                    body = stringResource(R.string.import_done_delete_confirm_body),
                    icon = LkIcons.Trash,
                    accent = c.coral,
                    confirm = LkDialogAction(
                        label = deleteLabel,
                        onClick = viewModel::deleteSourceFile,
                        emphasis = LkActionEmphasis.Destructive,
                    ),
                )
            }
        }

        LkFootnote(stringResource(R.string.import_done_keep_app_footnote))
        // No Spacer here: this column is `spacedBy`, so a Spacer is itself a spaced child and
        // costs its own height plus a gap on each side (V23, 2026-09-12). Same trap as G11.
        LkPrimaryButton(text = stringResource(R.string.import_done_finish_button), onClick = onFinished)
    }
}

/**
 * The wizard's own icons.
 *
 * Deliberately generic — a key, a globe, a stopwatch — rather than each vendor's brand mark. Bundling
 * seven logos would mean shipping seven trademarks we have no licence to redistribute, and fetching
 * them is a network call this app does not make. What the tile has to answer is "which of my apps is
 * this", and the label already does that; the glyph is there to make the grid scannable.
 */
private val ImportSource.icon: ImageVector
    get() = when (this) {
        ImportSource.CHROME -> LkIcons.Globe
        ImportSource.APPLE -> LkIcons.Key
        ImportSource.BITWARDEN -> LkIcons.Shield
        // Was `Lock` until V3-07 (2026-09-17). The argument for keeping it — "a tile is not a
        // field, so nothing is being over-promised" — survived V26 because the alternatives were
        // all borrowed: `Shield` is this product's own mark and would brand a competitor as
        // LocalKeep, `ShieldCheck` means verified, `Key` is already Apple's. What settled it was
        // that the comment three lines below states the rule this line broke, and a grid that
        // spends a load-bearing glyph on one tile cannot claim the vocabulary is strict. `VaultDoor`
        // was drawn for this: neutral, owned by nothing else, and not on the reserved list.
        ImportSource.ONEPASSWORD -> LkIcons.VaultDoor
        // Layers, not RecoveryKit: that sealed-box glyph means *this vault's own* Recovery Kit
        // everywhere else in the app (unlock screen, drawer, Recovery phrases), so spending it on a
        // competitor's tile borrowed a meaning the grid does not want. Generic, per the note above.
        ImportSource.KEEPASS -> LkIcons.Layers
        ImportSource.AEGIS -> LkIcons.Clock
        // The four added 2026-09-17, chosen the same way and under the same rule the note above
        // states: generic, and none of them a glyph LkIcons' kdoc marks load-bearing (Eye/EyeOff,
        // Camera, Lock, Fingerprint). `Table` goes to Dashlane because it is the one source here
        // that is CSV-only, and `Note` — the obvious choice for that — is already "Other / CSV"
        // sitting two tiles away.
        ImportSource.KEEPER -> LkIcons.Pin
        ImportSource.ENPASS -> LkIcons.Tag
        ImportSource.PROTONPASS -> LkIcons.Folder
        ImportSource.DASHLANE -> LkIcons.Table
        ImportSource.CSV -> LkIcons.Note
    }

/**
 * "Step 2 of 5" in words — the wizard has no progress rail, so the subtitle carries it.
 *
 * `@Composable` rather than a plain function: it resolves resource ids with `stringResource`, per
 * `GeneratorViewModel.error`'s pattern of resolving display text only where a composer is available.
 */
@Composable
private fun ImportUiState.progressLabel(): String = when (step) {
    ImportUiState.Step.PICK_SOURCE -> stringResource(R.string.import_step_progress_pick_source)
    ImportUiState.Step.INSTRUCTIONS -> stringResource(R.string.import_step_progress_instructions)
    // "the database" is a KeePass noun. A Bitwarden export got it too, so the subtitle said
    // "unlock the database" on a step whose own body says "This export is encrypted…" and whose
    // field is labelled EXPORT PASSWORD (1A-F9, 2026-09-20). Decided on the same flag the rest of
    // this step's nouns are — the file, not the tile.
    ImportUiState.Step.PASSWORD -> stringResource(
        if (passwordIsForDatabase) {
            R.string.import_step_progress_password
        } else {
            R.string.import_step_progress_export_password
        },
    )
    ImportUiState.Step.PREVIEW -> stringResource(R.string.import_step_progress_preview)
    ImportUiState.Step.DONE -> stringResource(R.string.import_step_progress_done)
}

@Composable
private fun ImportMerger.Mode.label(): String = when (this) {
    ImportMerger.Mode.MERGE -> stringResource(R.string.import_mode_merge)
    ImportMerger.Mode.ADD_ALL -> stringResource(R.string.import_mode_add_all)
}

/**
 * The second line of a preview row.
 *
 * Both non-NEW dispositions come in two flavours, and [PreviewRow.collidesWithVault] is what tells
 * them apart: a row can collide with something already in the vault, or with an earlier row of the
 * same export. Only the first is "you already have" — the second is about a file the user is
 * looking at for the first time. The headline count was split for this reason (F19,
 * `import_preview_file_duplicate_count`); the per-row reason kept borrowing the vault phrasing
 * underneath it until the pre-iOS sweep 2026-09-11 caught the mismatch on a device.
 */
@Composable
private fun resolveDispositionLabel(row: PreviewRow, timestampsKnown: Boolean): String {
    val reason = rowReason(row.disposition, row.collidesWithVault, timestampsKnown) ?: return row.subtitle
    return if (reason.namesCollision) {
        stringResource(reason.res, row.subtitle, row.collidesWith.orEmpty())
    } else {
        stringResource(reason.res, row.subtitle)
    }
}

/**
 * Which string a preview row's second line uses, and whether it names the entry it hit.
 *
 * [namesCollision] is the only reason the composable still branches at all: `updates_existing` is
 * the one of the four that takes a second format argument.
 */
internal data class RowReason(@get:StringRes val res: Int, val namesCollision: Boolean)

/**
 * Pure so a test can hold the cases together — see `ImportRowReasonTest`.
 *
 * [timestampsKnown] splits the vault-collision ALREADY_CURRENT row in two, because with an undated
 * export — a CSV, an Aegis JSON — "you already have a newer copy" is a comparison nothing in the
 * file supports. What is true, and what the user needs to know before they press Import, is that
 * their own entry is the one being kept (S1, 2026-09-15).
 */
internal fun rowReason(
    disposition: ImportMerger.Disposition,
    collidesWithVault: Boolean,
    timestampsKnown: Boolean = true,
): RowReason? = when (disposition) {
    ImportMerger.Disposition.NEW -> null
    ImportMerger.Disposition.UPDATES_EXISTING -> if (collidesWithVault) {
        RowReason(R.string.import_disposition_updates_existing, namesCollision = true)
    } else {
        RowReason(R.string.import_disposition_updates_earlier_row, namesCollision = false)
    }
    ImportMerger.Disposition.ALREADY_CURRENT -> when {
        !collidesWithVault -> RowReason(R.string.import_disposition_already_in_file, namesCollision = false)
        timestampsKnown -> RowReason(R.string.import_disposition_already_current, namesCollision = false)
        else -> RowReason(R.string.import_disposition_undated_keeps_yours, namesCollision = false)
    }
}

/** Resolves an [ImportError] to display text, per `UnlockScreen.resolveUnlockError`. */
@Composable
private fun resolveImportError(error: ImportError?): String? = when (error) {
    null -> null
    is ImportError.Simple -> stringResource(error.messageRes)
    is ImportError.FromImporter -> error.reason.resolve()
    ImportError.LocalKeepBackup -> stringResource(R.string.import_error_localkeep_backup)
}

/**
 * Resolves an [ImportFailureReason] to display text. `core/import` cannot author this text itself
 * — see that type's kdoc — so this is the one place it becomes a sentence.
 */
@Composable
private fun ImportFailureReason.resolve(): String = when (this) {
    is ImportFailureReason.MalformedExport ->
        stringResource(R.string.core_import_error_malformed_export, source.displayName())
    is ImportFailureReason.ExportNeedsMoreMemory ->
        stringResource(R.string.core_import_error_export_needs_more_memory, source.displayName())
    is ImportFailureReason.ContainerEncrypted ->
        stringResource(R.string.core_import_error_container_encrypted, source.displayName())
    is ImportFailureReason.AccountEncrypted ->
        stringResource(R.string.core_import_error_account_encrypted, source.displayName())
    is ImportFailureReason.WrongPassword -> stringResource(
        if (keyFileMayBeNeeded) {
            R.string.core_import_error_wrong_password_or_key_file
        } else {
            R.string.core_import_error_wrong_password
        },
    )
    is ImportFailureReason.WrongExportPassword ->
        stringResource(R.string.core_import_error_wrong_export_password, source.displayName())
    ImportFailureReason.UnreadableKeyFile -> stringResource(R.string.core_import_error_unreadable_key_file)
    ImportFailureReason.NotAKdbxFile -> stringResource(R.string.core_import_error_not_kdbx_file)
    ImportFailureReason.LegacyKeePass1File ->
        stringResource(R.string.core_import_error_legacy_keepass1_file)
    ImportFailureReason.DamagedKdbxFile -> stringResource(R.string.core_import_error_damaged_kdbx_file)
    ImportFailureReason.KdbxKdfTooLarge -> stringResource(R.string.core_import_error_kdbx_kdf_too_large)
    ImportFailureReason.KdbxSupportBroken -> stringResource(R.string.core_import_error_kdbx_support_broken)
    ImportFailureReason.OnePuxMissingExportData ->
        stringResource(R.string.core_import_error_onepux_missing_export_data)
    is ImportFailureReason.ExportTooLarge -> stringResource(R.string.core_import_error_export_too_large, limitMb)
    ImportFailureReason.Unexpected -> stringResource(R.string.core_import_error_unexpected)
}

/**
 * The brand-name display for a [SourceFormat] — reuses `ImportSource.labelRes`, which already
 * carries these as `translatable="false"` (brand names are never translated), rather
 * than duplicating the same five strings under a second key.
 */
@Composable
private fun SourceFormat.displayName(): String = stringResource(
    when (this) {
        SourceFormat.BITWARDEN -> R.string.import_source_label_bitwarden
        SourceFormat.KDBX -> R.string.import_source_label_keepass
        SourceFormat.AEGIS -> R.string.import_source_label_aegis
        SourceFormat.ONEPUX -> R.string.import_source_label_onepassword
        // Not the source card's "Other / CSV": Chrome and Apple exports are CSV too.
        SourceFormat.CSV -> R.string.import_format_label_csv
        SourceFormat.KEEPER -> R.string.import_source_label_keeper
        SourceFormat.ENPASS -> R.string.import_source_label_enpass
        SourceFormat.PROTONPASS -> R.string.import_source_label_protonpass
        SourceFormat.DASHLANE -> R.string.import_source_label_dashlane
    },
)

/** Resolves a [SkipReason] to the label the preview groups skipped entries by. */
@Composable
private fun SkipReason.label(): String = when (this) {
    SkipReason.IncompleteEntry -> stringResource(R.string.core_import_skip_incomplete_entry)
    SkipReason.SshKey -> stringResource(R.string.core_import_skip_ssh_key)
    is SkipReason.UnsupportedBitwardenType ->
        stringResource(R.string.core_import_skip_unsupported_bitwarden_type, type)
    is SkipReason.UnsupportedAegisType ->
        stringResource(R.string.core_import_skip_unsupported_aegis_type, codeType)
    is SkipReason.UnsupportedTotpSettings ->
        stringResource(R.string.core_import_skip_unsupported_totp_settings, algorithm, digits, periodSec)
    SkipReason.OnePasswordArchived -> stringResource(R.string.core_import_skip_onepassword_archived)
    SkipReason.SourceRecycleBin -> stringResource(R.string.core_import_skip_source_recycle_bin)
    is SkipReason.UnsupportedOnePasswordCategory -> stringResource(onePasswordCategoryRes(categoryUuid))
    SkipReason.NoLoginFields -> stringResource(R.string.core_import_skip_no_login_fields)
}

/**
 * 1Password's own category ids, so the wizard can name what it left behind rather than reporting a
 * smaller count than the file contained. Moved here from `core/import`'s `OnePuxImporter` — that
 * module cannot hold string resources; only the uuid -> category mapping lived there, never the
 * English text.
 */
private fun onePasswordCategoryRes(uuid: String): Int = when (uuid) {
    "005" -> R.string.core_import_onepassword_category_password
    "006" -> R.string.core_import_onepassword_category_document
    "100" -> R.string.core_import_onepassword_category_software_license
    "101" -> R.string.core_import_onepassword_category_bank_account
    "102" -> R.string.core_import_onepassword_category_database
    "103" -> R.string.core_import_onepassword_category_driver_license
    "104" -> R.string.core_import_onepassword_category_outdoor_license
    "105" -> R.string.core_import_onepassword_category_membership
    "106" -> R.string.core_import_onepassword_category_passport
    "107" -> R.string.core_import_onepassword_category_reward_program
    "108" -> R.string.core_import_onepassword_category_ssn
    "109" -> R.string.core_import_onepassword_category_wireless_router
    "110" -> R.string.core_import_onepassword_category_server
    "111" -> R.string.core_import_onepassword_category_email_account
    "112" -> R.string.core_import_onepassword_category_api_credential
    "113" -> R.string.core_import_onepassword_category_medical_record
    "114" -> R.string.core_import_onepassword_category_ssh_key
    else -> R.string.core_import_skip_onepassword_category_unsupported
}

/**
 * "N items imported; M merged into entries you already had." — built from two independently
 * pluralised fragments rather than one string, since the two counts can each be singular or plural
 * on their own. Per `UnlockScreen.resolveKdfSummary`.
 */
@Composable
private fun resolveDoneNotice(importedCount: Int, mergedIntoExistingCount: Int?): String? {
    if (mergedIntoExistingCount == null) return null
    val imported = pluralStringResource(R.plurals.import_done_notice_imported, importedCount, importedCount)
    val merged = pluralStringResource(
        R.plurals.import_done_notice_merged,
        mergedIntoExistingCount,
        mergedIntoExistingCount,
    )
    return stringResource(R.string.import_done_notice_combined, imported, merged)
}
