package com.localkeep.feature.settings

import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.model.HiddenLease
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.theme.LkEmptyState
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkGroupRow
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkInlineNote
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSheet
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkEnter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The stable handle the locale sweep and the device pass navigate by. Never localised. */
const val HIDDEN_ITEMS_TEST_TAG = "hidden-items"

/**
 * The screen root, tagged separately from [HIDDEN_ITEMS_TEST_TAG] — that one names the Settings
 * row that leads here, and reusing it for the destination would leave a Maestro flow unable to
 * tell "the row exists" from "the screen opened". This tag is on [LkScreen] itself, above the
 * `authorized` branch, so it is visible whether the auth gate or the entry list is what composed
 * under it — the recovery flow only needs proof that navigation reached this screen, not that it
 * got past the gate.
 */
const val HIDDEN_ITEMS_SCREEN_TEST_TAG = "hidden-items-screen"

/**
 * Settings → Privacy → Hidden items: the authenticated recovery route for anything concealed.
 *
 * The row that leads here is always present, so this screen has to be reachable when nothing is
 * hidden and when everything is. That is the reason it exists in this shape: a user who has
 * forgotten the reveal phrase, or whose lease is indefinite, has no other way back to their own
 * data, and a route that appeared only while something was hidden would both leak that fact and
 * vanish exactly when it was needed.
 *
 * **No entry content is composed until [HiddenItemsUiState.authorized] is true.** Not hidden behind
 * an overlay, not rendered blurred — simply not built, so a Back press out of the prompt leaves a
 * screen that never held a name. Auto-lock takes the authorization with it.
 */
@Composable
fun HiddenItemsScreen(
    viewModel: HiddenItemsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? FragmentActivity
    val c = LkTheme.colors

    // The single app-wide nearest-expiry timer is held only while this screen is STARTED, so a lease
    // that ends while the user is looking updates the rows, and a back-stacked copy of this screen
    // keeps nothing running. The rows themselves refresh from the shared presentation flow, which is
    // also what a save, a reveal and a conceal come through — there is no timer local to this screen.
    LifecycleStartEffect(viewModel) {
        viewModel.onForegrounded()
        onStopOrDispose { viewModel.onBackgrounded() }
    }

    var pendingDuration by remember { mutableStateOf<HiddenEntryRow?>(null) }
    var changingPhrase by remember { mutableStateOf(false) }
    // The view-only peek. Plain `remember`, like the gate's password: the row holds vault data and
    // must not reach saved instance state, so a rotation simply closes the sheet.
    var peeking by remember { mutableStateOf<HiddenEntryRow?>(null) }

    LkScreen(
        modifier = Modifier.lkTag(HIDDEN_ITEMS_SCREEN_TEST_TAG),
        accent = c.jade,
        auraAlpha = 0.07f,
        auraBias = 0.5f,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LkFrame.screenHorizontal)
                .padding(top = LkFrame.screenTop, bottom = LkFrame.gutterPlain),
            verticalArrangement = Arrangement.spacedBy(LkSpace.md),
        ) {
            LkScreenHeader(
                title = stringResource(R.string.settings_hidden_title),
                modifier = Modifier.lkEnter(),
                // Trailing, matching the eight other LkScreenHeader back chevrons (Trash,
                // Settings, Backup, Import, Recovery Kit, item edit, file edit, attachments).
                // This screen put it in the leading slot, so Back physically swapped sides
                // depending on which screen you were leaving. Visual sweep, 2026-09-01.
                trailing = {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = stringResource(R.string.settings_hidden_back),
                        onClick = onBack,
                        plateAlignment = Alignment.CenterEnd,
                    )
                },
            )

            state.error?.let {
                val message = hiddenMessage(it)
                LkInlineError(message, Modifier.announce(message))
            }
            state.message?.let {
                val message = hiddenMessage(it)
                LkInlineNote(message, Modifier.announce(message), accent = c.amber)
            }

            if (!state.authorized) {
                AuthGate(
                    biometricAvailable = state.biometricAvailable && activity != null,
                    working = state.isWorking,
                    onBiometric = { activity?.let(viewModel::authorizeWithBiometric) },
                    onPassword = viewModel::authorizeWithPassword,
                )
            } else {
                HiddenEntries(
                    entries = state.entries,
                    working = state.isWorking,
                    onStopHiding = viewModel::stopHiding,
                    onChangeDuration = { pendingDuration = it },
                    onChangePhrase = { changingPhrase = true },
                    onPeek = { peeking = it },
                )
            }
        }
    }

    val release = pendingDuration
    if (release != null && state.authorized) {
        DurationSheet(
            name = release.name,
            onDismiss = { pendingDuration = null },
            onPick = { until ->
                pendingDuration = null
                viewModel.changeDuration(release, until)
            },
        )
    }

    peeking?.let { entry ->
        // Closed with the gate: `authorized` dropping recomposes this away with everything else.
        if (state.authorized) {
            PeekSheet(entry = entry, onDismiss = { peeking = null })
        }
    }

    if (changingPhrase && state.authorized) {
        PhraseSheet(
            onDismiss = { changingPhrase = false },
            onSubmit = { phrase ->
                changingPhrase = false
                viewModel.changeRevealPhrase(phrase)
            },
        )
    }
}

/**
 * The gate. Strong biometric, or the **current** vault password — the decoy's in a duress session,
 * which is why the label says "vault password" and never "master password": the same words have to
 * be true in both, and a decoy screen that asked for the real secret would be the whole game.
 *
 * Wrapped in its own Column because it emits several children into a `spacedBy` parent, where a bare
 * `Spacer` would be spaced as well and read as a double gap.
 */
@Composable
internal fun AuthGate(
    biometricAvailable: Boolean,
    working: Boolean,
    onBiometric: () -> Unit,
    onPassword: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    // Plain `remember`, never `rememberSaveable`: saved state is written to disk by the system, and
    // a half-typed vault password does not belong there. Cleared on dispose so it does not outlive
    // the screen even in memory (the String itself cannot be wiped, and this does not pretend to).
    DisposableEffect(Unit) { onDispose { password = "" } }

    Column(
        modifier = Modifier.lkEnter(delayMillis = 40),
        verticalArrangement = Arrangement.spacedBy(LkSpace.md),
    ) {
        LkEmptyState(
            icon = LkIcons.Lock,
            title = stringResource(R.string.settings_hidden_gate_title),
            // The password-only sentence whenever the biometric button is withheld — after a panic
            // lock (`isBiometricSuspendedByPanic`) or on a phone with no strong sensor — because
            // "biometric confirmation or your vault password" invited the one route not on offer.
            body = stringResource(
                if (biometricAvailable) {
                    R.string.settings_hidden_gate_body
                } else {
                    R.string.settings_hidden_gate_body_password_only
                },
            ),
        )
        if (biometricAvailable) {
            LkSecondaryButton(
                text = stringResource(R.string.settings_hidden_gate_biometric),
                icon = LkIcons.Fingerprint,
                onClick = onBiometric,
                enabled = !working,
            )
        }
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.settings_hidden_gate_password_label),
            revealed = revealed,
            onToggleReveal = { revealed = !revealed },
            onImeAction = {
                if (password.isNotBlank()) {
                    val chars = password.toCharArray()
                    password = ""
                    onPassword(chars)
                }
            },
        )
        // The label carries the working state, because nothing else on this gate does. Deriving the
        // vault key is an Argon2id run measured in seconds, and a disabled primary button renders
        // hollow — identical to the button before a password has been typed.
        LkPrimaryButton(
            text = stringResource(
                if (working) {
                    R.string.settings_working
                } else {
                    R.string.settings_hidden_gate_password_submit
                },
            ),
            enabled = !working && password.isNotBlank(),
            onClick = {
                // Copied out and the field cleared before the call: the lock manager consumes and
                // wipes the array, and leaving the String behind would keep the secret on screen.
                val chars = password.toCharArray()
                password = ""
                onPassword(chars)
            },
        )
    }
}

/**
 * The authenticated body: what concealment costs, then a row per explicitly-leased record.
 *
 * The two explanations are not decoration. Health counts genuinely drop while something is hidden,
 * so a user watching their score move needs to be told why here rather than guessing at the
 * dashboard; and concealment is a projection over a decrypted vault, so promising more than that
 * anywhere in the app would be a claim the design cannot keep.
 */
@Composable
private fun HiddenEntries(
    entries: List<HiddenEntryRow>,
    working: Boolean,
    onStopHiding: (HiddenEntryRow) -> Unit,
    onChangeDuration: (HiddenEntryRow) -> Unit,
    onChangePhrase: () -> Unit,
    onPeek: (HiddenEntryRow) -> Unit,
) {
    val c = LkTheme.colors
    Column(
        modifier = Modifier.lkEnter(delayMillis = 40),
        verticalArrangement = Arrangement.spacedBy(LkSpace.md),
    ) {
        // Both claims, one box. They shipped as two identical amber blocks stacked on top of each
        // other, which put eight lines of near-identical-looking notice above the first entry on a
        // screen whose whole content is that list — the other half of the 2026-08-21 "look how
        // stacked they are" report. Two boxes also read as two *warnings*; they are one
        // explanation with two halves.
        //
        // The strings are joined verbatim and never edited: both carry `SECURITY-COPY: tier G`, so
        // rewriting or condensing either one needs the security-copy review protocol rather than a
        // layout pass. A blank line between two complete sentences composes no grammar, so this is
        // safe in all five catalogues.
        LkInlineNote(
            stringResource(R.string.settings_hidden_explainer_health) +
                "\n\n" +
                stringResource(R.string.settings_hidden_explainer_storage),
            accent = c.amber,
            icon = LkIcons.Info,
        )

        if (entries.isEmpty()) {
            // The same state whether the feature was never used or every lease has expired. A
            // "nothing hidden any more" variant would answer a question this screen must not answer.
            LkEmptyState(
                icon = LkIcons.EyeOff,
                title = stringResource(R.string.settings_hidden_empty_title),
                body = stringResource(R.string.settings_hidden_empty_body),
                modifier = Modifier.padding(top = LkSpace.lg),
            )
        } else {
            entries.forEach { entry ->
                // Keyed by target id so a row keeps its identity — and its entrance — as leases
                // expire and the list reorders underneath it.
                key(entry.target.id) {
                    HiddenEntryCard(
                        entry = entry,
                        working = working,
                        onStopHiding = { onStopHiding(entry) },
                        onChangeDuration = { onChangeDuration(entry) },
                        onPeek = { onPeek(entry) },
                    )
                }
            }
        }

        LkSecondaryButton(
            text = stringResource(R.string.settings_hidden_change_phrase),
            icon = LkIcons.Key,
            onClick = onChangePhrase,
            enabled = !working,
        )
    }
}

/** The glyph a hidden record draws — the same vocabulary the add sheet and the drawer use. */
private fun HiddenEntryKind.icon() = when (this) {
    HiddenEntryKind.FOLDER -> LkIcons.Folder
    HiddenEntryKind.LOGIN -> LkIcons.Key
    HiddenEntryKind.TOTP -> LkIcons.Clock
    HiddenEntryKind.NOTE -> LkIcons.Note
    HiddenEntryKind.CARD -> LkIcons.Card
    HiddenEntryKind.IDENTITY -> LkIcons.Identity
    HiddenEntryKind.FILE -> LkIcons.Attachment
    HiddenEntryKind.COORDINATE -> LkIcons.Pin
    HiddenEntryKind.SEEDPHRASE -> LkIcons.SeedPhrase
}

@Composable
private fun HiddenEntryCard(
    entry: HiddenEntryRow,
    working: Boolean,
    onStopHiding: () -> Unit,
    onChangeDuration: () -> Unit,
    onPeek: () -> Unit,
) {
    val c = LkTheme.colors
    // Folders open nothing: a folder's only content is the rows it conceals, and listing those here
    // would show names this screen's own rule keeps to explicitly-leased records.
    val peekable = !entry.isFolder
    LkGroup {
        LkGroupRow(
            first = true,
            onClick = if (peekable) onPeek else null,
            onClickLabel = if (peekable) stringResource(R.string.settings_hidden_view_entry) else null,
            // This is a *leading-icon* row, the shape LkGroupRow did not previously have a gap for
            // — see its own `horizontalArrangement` docs. Without it the plate touched the title
            // and the two-line block ran on into the chevron, which is the whole of the 2026-08-21
            // report: "where's the spacing? Look how stacked they are".
            horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
        ) {
            // The record's own type glyph on its own plate, in the amber this screen already
            // speaks: hidden is a state the user chose. Every item drew LkIcons.Note here before,
            // which called a login a note on the one screen where the name may be too vague to
            // tell you what the record is.
            //
            // 40dp, not the add sheet's 32: that cell is one of eight in a grid where the label
            // sits beside a single line, and this is a list row with a two-line block next to it.
            // Borrowed from the vault list's own 42dp monogram tile — the row this one is read as
            // a sibling of — rather than from the grid, because a 32dp plate against two lines of
            // text is what made the glyph read as an afterthought stuck to the corner.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .lkSurface(LkShape.md, c.amber.copy(alpha = 0.07f), c.amber.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    entry.kind.icon(),
                    // Named for folders because "folder" is the one thing the row's text does not
                    // say; an item's kind is carried by the peek and needs no announcement here.
                    contentDescription = if (entry.isFolder) {
                        stringResource(R.string.settings_hidden_folder_label)
                    } else {
                        null
                    },
                    tint = c.amber,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
            ) {
                Text(entry.name, style = LkText.rowTitle, color = c.textStrong)
                Text(
                    leaseText(entry),
                    style = LkText.subtitle,
                    // Amber, never coral: hidden is a state the user chose, not a failure.
                    color = c.amber,
                )
            }
            if (peekable) {
                Icon(
                    LkIcons.ChevronRight,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        // 4dp, not the row default's 15: [LkTextButton] already carries the 48dp touch floor itself,
        // so the default padding was stacked *on top of* a control that was already tall enough and
        // gave a 48dp pair of actions a 78dp band to sit in. Measured on the emulator 2026-08-21 —
        // 196px at 420dpi — and it is most of why the card read as mostly empty space below a line.
        LkGroupRow(first = false, verticalPadding = LkSpace.xs) {
            LkTextButton(
                text = stringResource(R.string.settings_hidden_stop),
                onClick = onStopHiding,
                // Disabled, not merely dimmed: this is the one action here that goes through
                // `mutate`, which returns immediately while `isWorking` and has already cleared the
                // banner — so a tap taken in that window produces nothing at all. "Change duration"
                // beside it stays live because it only raises the duration sheet; one dimmed action
                // next to one live one is the honest reading of that difference.
                enabled = !working,
                color = c.amber,
                modifier = Modifier.weight(1f),
            )
            LkTextButton(
                text = stringResource(R.string.settings_hidden_change_duration),
                onClick = onChangeDuration,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The view-only peek: what this record is, without un-hiding it.
 *
 * Renders only what [HiddenEntryRow.peekLines] carries — identity-grade fields picked in the
 * ViewModel, never the high-value ones — so this sheet cannot grow into a second detail screen by
 * accident. A reprompt-guarded record shows a sentence instead of its content: the gate into this
 * screen re-authenticated the screen, not that record's own per-open ask.
 */
@Composable
private fun PeekSheet(
    entry: HiddenEntryRow,
    onDismiss: () -> Unit,
) {
    val c = LkTheme.colors
    LkSheet(
        onDismiss = onDismiss,
        title = entry.name,
        subtitle = leaseText(entry),
    ) {
        // 14dp is [LkSheet]'s own title inset, not a spacing decision made here: the sheet pads
        // its content 10dp and its title a further 14, so body text needs the same 14 or it hangs
        // left of the title it sits under.
        Column(Modifier.padding(horizontal = 14.dp)) {
            when {
                entry.repromptGuarded -> Text(
                    stringResource(R.string.settings_hidden_peek_reprompt),
                    style = LkText.bodyQuiet,
                    color = c.textMuted,
                )
                entry.peekLines.isEmpty() && entry.peekInstant == null -> Text(
                    stringResource(R.string.settings_hidden_peek_empty),
                    style = LkText.bodyQuiet,
                    color = c.textFaint,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                    entry.peekLines.forEach { line ->
                        Text(
                            line,
                            style = LkText.body,
                            color = c.textPrimary,
                            // A note body can be arbitrarily long; a peek is not the place to read
                            // a novel, and an unbounded sheet would put its own subtitle out of
                            // reach.
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    entry.peekInstant?.let { taken ->
                        Text(
                            stringResource(R.string.settings_hidden_peek_taken, instantText(taken)),
                            style = LkText.subtitle,
                            color = c.textMuted,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(LkSpace.md))
    }
}

/**
 * "Hidden until <date>", or the indefinite wording.
 *
 * `getBestDateTimePattern` rather than a fixed pattern: day-then-month and the 12/24-hour choice are
 * locale and device settings, and this line is the one thing on the row a user has to be able to
 * act on.
 */
@Composable
private fun leaseText(entry: HiddenEntryRow): String {
    if (entry.indefinite) return stringResource(R.string.settings_hidden_indefinite)
    return stringResource(R.string.settings_hidden_until, instantText(entry.until))
}

/** One locale-and-device-honest date-time, shared by the lease line and the peek's capture line. */
@Composable
private fun instantText(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter
            .ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMdjm"), locale)
            .withZone(ZoneId.systemDefault())
    }
    return formatter.format(instant)
}

/**
 * Re-leasing a record from the recovery screen: the presets and "Indefinitely".
 *
 * Deliberately no custom-duration builder here. This screen exists to get data *back*, and the full
 * amount-plus-unit form belongs where hiding starts; duplicating it would put a second copy of the
 * same validation and six more strings on the one screen that must stay easy to read under stress.
 */
@Composable
private fun DurationSheet(
    name: String,
    onDismiss: () -> Unit,
    onPick: (Instant) -> Unit,
) {
    LkSheet(onDismiss = onDismiss, title = stringResource(R.string.settings_hidden_duration_title, name)) {
        // Instant.now() plus the preset: the repository re-validates against its own clock, and both
        // are wall-clock, so the millisecond skew between them is immaterial.
        DurationRow(stringResource(R.string.settings_hidden_preset_5min)) {
            onPick(Instant.now().plus(Duration.ofMinutes(5)))
        }
        DurationRow(stringResource(R.string.settings_hidden_preset_30min)) {
            onPick(Instant.now().plus(Duration.ofMinutes(30)))
        }
        DurationRow(stringResource(R.string.settings_hidden_preset_1hour)) {
            onPick(Instant.now().plus(Duration.ofHours(1)))
        }
        DurationRow(stringResource(R.string.settings_hidden_preset_1day)) {
            onPick(Instant.now().plus(Duration.ofDays(1)))
        }
        DurationRow(stringResource(R.string.settings_hidden_preset_indefinite)) {
            onPick(HiddenLease.INDEFINITE)
        }
        Spacer(Modifier.height(LkSpace.md))
    }
}

@Composable
private fun DurationRow(text: String, onClick: () -> Unit) {
    LkTextButton(text = text, onClick = onClick, color = LkTheme.colors.textPrimary)
}

/**
 * Phrase rotation. One field, one commit — the repository writes the new verifier and touches no
 * lease, because hidden state is not keyed by the phrase.
 */
@Composable
private fun PhraseSheet(
    onDismiss: () -> Unit,
    onSubmit: (CharArray) -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { phrase = "" } }

    LkSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_hidden_change_phrase),
        subtitle = stringResource(R.string.settings_hidden_phrase_body),
    ) {
        // 14dp is [LkSheet]'s own title inset (see PeekSheet): the sheet pads its content 10dp and
        // its title a further 14, so a well left unwrapped hangs 14dp left of the title above it.
        Column(Modifier.padding(horizontal = 14.dp)) {
            PasswordField(
                value = phrase,
                onValueChange = { phrase = it },
                label = stringResource(R.string.settings_hidden_phrase_label),
                revealed = revealed,
                onToggleReveal = { revealed = !revealed },
                onImeAction = {
                    if (phrase.isNotBlank()) {
                        val chars = phrase.toCharArray()
                        phrase = ""
                        onSubmit(chars)
                    }
                },
            )
            Spacer(Modifier.height(LkSpace.md))
            LkPrimaryButton(
                text = stringResource(R.string.settings_hidden_phrase_confirm),
                enabled = phrase.isNotBlank(),
                onClick = {
                    val chars = phrase.toCharArray()
                    phrase = ""
                    onSubmit(chars)
                },
            )
            Spacer(Modifier.height(LkSpace.md))
        }
    }
}

/**
 * Resolves this screen's [SettingsMessage]s. The ViewModel holds resource ids and arguments only;
 * every string this screen shows is resolved here, at composition.
 */
@Composable
private fun hiddenMessage(message: SettingsMessage): String = when (message) {
    is SettingsMessage.Simple -> stringResource(message.textRes)
    is SettingsMessage.WithArgs -> stringResource(message.textRes, *message.args.toTypedArray())
    is SettingsMessage.RetryAfter ->
        pluralStringResource(message.pluralsRes, message.seconds, message.seconds)
    // Shapes this screen never produces; the generic wording beats crashing on an unreachable branch.
    else -> stringResource(R.string.settings_error_generic)
}
