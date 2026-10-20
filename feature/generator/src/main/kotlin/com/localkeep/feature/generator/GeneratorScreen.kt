package com.localkeep.feature.generator

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localkeep.core.ui.ClipboardHelper
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.minTouchTarget
import com.localkeep.core.ui.rememberHaptics
import com.localkeep.core.ui.shellOverlayInset
import com.localkeep.core.ui.theme.LkBar
import com.localkeep.core.ui.theme.LkCard
import com.localkeep.core.ui.theme.LkCheckRow
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkGroup
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkInlineError
import com.localkeep.core.ui.theme.LkLiftedCard
import com.localkeep.core.ui.theme.LkLockButton
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkScreenHeader
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkSlider
import com.localkeep.core.ui.theme.LkStrength
import com.localkeep.core.ui.theme.LkStrengthMeter
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalLkReduceMotion
import com.localkeep.core.ui.theme.lkBloom
import com.localkeep.core.ui.theme.lkEnter
import com.localkeep.core.ui.theme.lkFocusRing
import com.localkeep.core.ui.theme.lkPressScale
import com.localkeep.core.ui.theme.lkRegenerateSpinDegrees
import com.localkeep.core.ui.theme.lkSurface
import com.localkeep.core.ui.theme.lkTween
import kotlinx.coroutines.delay

/**
 * The generator.
 *
 * Structured around one claim: **the output is the screen**. The generated value sits in the only
 * lifted card, at 21sp monospace, with its entropy stated in bits and named in words directly
 * beneath — so the effect of every control below is visible without scrolling back up. The controls
 * regenerate live rather than waiting for a "Generate" button, which is what makes the entropy bar
 * a feedback loop instead of a report.
 */
@Composable
fun GeneratorScreen(
    viewModel: GeneratorViewModel,
    /**
     * Hands the current value, and the entropy of the draw that produced it, to a new login.
     * Defaults to a no-op so the screen still stands alone in a preview or a test, where there is no
     * vault to save into.
     *
     * **The bit count travels with the string.** This screen names the value from its entropy; the
     * item form scores a *typed* password with zxcvbn. Handing over the string alone meant the
     * editor re-derived a verdict from the characters and disagreed with the one the user had just
     * read here — "Reasonable · 77 bits" became "Excellent" one tap later (F-29, 2026-09-15 visual
     * pass). The figure cannot be recovered downstream, because a string does not say which pool it
     * was drawn from, so it has to be carried.
     */
    onSaveToVault: (String, Double) -> Unit = { _, _ -> },
    /**
     * Raises the command drawer. Defaults to a no-op so the screen still stands alone in a preview
     * or a test, exactly as [onSaveToVault] does.
     */
    onOpenDrawer: () -> Unit = {},
) {
    val c = LkTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val random = state.mode == GeneratorUiState.Mode.RANDOM

    // Brass rather than jade: this screen is about *making* a secret, not about a vault being
    // safe — reusing jade would flatten the distinction the palette is carrying.
    LkScreen(accent = c.brass, auraAlpha = 0.08f, auraBias = 0.76f) {
        Column(Modifier.fillMaxSize()) {
            // Outside the scroller — see HealthDashboardScreen: Lock has to stay on screen.
            LkScreenHeader(
                title = stringResource(R.string.generator_title),
                // "CSPRNG" was the whole reassurance, spelled in an acronym the people who need
                // reassuring cannot read. The guarantee is that the value is made here and made
                // unguessably, so say that.
                subtitle = stringResource(R.string.generator_subtitle),
                modifier = Modifier
                    .lkEnter()
                    .padding(horizontal = LkFrame.screenHorizontal)
                    .padding(top = LkFrame.screenTop),
                // Every top-level tab reaches the drawer from its own header. Only the vault
                // list carried this button, so from Generator and Health the drawer existed but
                // had no visible way in - an edge swipe is not an affordance, it is a secret.
                leading = {
                    LkIconButton(
                        icon = LkIcons.Menu,
                        label = stringResource(com.localkeep.core.ui.R.string.core_ui_menu_open),
                        onClick = onOpenDrawer,
                        modifier = Modifier.lkTag("nav-open-drawer"),
                        plateAlignment = Alignment.CenterStart,
                    )
                },
                trailing = {
                    // Lock, not a back chevron. This is a bottom-bar tab with no back stack
                    // to pop, so the chevron pointed at nothing — and the one control that
                    // belongs on every screen of a password manager was missing here.
                    LkLockButton()
                },
            )

            // The lift this screen needs is not the one a bottom-anchored control needs. The vault
            // list's FAB is *positioned* by its padding, so padding alone moved it clear of the
            // clipboard countdown. A scrolling list is not: extra bottom padding lengthens the
            // scroll range but does not move a row that is already laid out, so the Lowercase
            // toggle stayed exactly where the card covers it — measured on the device 2026-09-06
            // with the padding already in place, `content-desc="Lowercase, enabled"` still at
            // `bounds="[53,1897][1027,2034]"` under the card. So the padding buys the room and this
            // spends it: when the overlay appears, scroll the list up by however far its content
            // reaches into the overlay's band — see [overlayLiftScrollPx] for why that is not simply
            // the card's height, and why a list running past the viewport is not scrolled at all. Only
            // ever downward-compensating — when the card leaves, the list stays where the user last put it
            // rather than jumping back under them.
            val optionsScroll = rememberScrollState()
            val density = LocalDensity.current
            val overlayInsetPx = with(density) { shellOverlayInset().roundToPx() }
            // Root-space edges, written by the layout below and read when the overlay arrives.
            var viewportBottomPx by remember { mutableFloatStateOf(0f) }
            var contentBottomPx by remember { mutableFloatStateOf(0f) }
            LaunchedEffect(overlayInsetPx) {
                if (overlayInsetPx == 0) return@LaunchedEffect
                // The shell leaves 12dp between the tab bar and the overlays (AppShell's
                // `padding(bottom = overlayFloor + 12.dp)`), which is part of what the card covers.
                val lift = overlayLiftScrollPx(
                    contentBottomPx = contentBottomPx,
                    viewportBottomPx = viewportBottomPx,
                    overlayPx = overlayInsetPx,
                    gapPx = with(density) { 12.dp.roundToPx() },
                )
                if (lift > 0) optionsScroll.animateScrollBy(lift.toFloat())
            }
            Column(
                Modifier
                    // weight, not fillMaxSize: in a Column, fillMaxSize takes the *parent's* height
                    // rather than what is left after the header, so the header got squeezed and its
                    // subtitle rendered 17px of a 40px line.
                    .weight(1f)
                    .fillMaxWidth()
                    // The shell keeps the keyboard out of its own padding, so this is where the
                    // viewport shrinks for it. Without it the Separator well, the last control
                    // here, was typed into from under the keyboard: `LkTextField`'s bring-into-view
                    // had nothing to scroll because the viewport never got shorter. Outside the
                    // scroll so it is the scroller's height that gives, not its content — padding
                    // inside `verticalScroll` would be dead scroll rather than clearance. The tab
                    // bar sits below this column and is already under the keyboard, so the gap that
                    // leaves above the keys is the bar's height; not worth a measurement to claw back.
                    .imePadding()
                    .onGloballyPositioned { viewportBottomPx = it.positionInRoot().y + it.size.height }
                    .verticalScroll(optionsScroll)
                    .padding(horizontal = LkFrame.screenHorizontal)
                    // Matching the arrangement below, and it has to stay matched: the header used
                    // to be this scroller's first child and took its top spacing from there.
                    //
                    // `lg`, from 20dp. The blocks this separates — the output card, the mode
                    // toggle, the options group, the save button — are sections, and `lg` is the
                    // rung for section to section. 20 is not a rung, and having it in two places
                    // that must agree made an off-scale number load-bearing.
                    .padding(top = LkSpace.lg, bottom = LkFrame.gutterFab)
                    // And it lifts over the shell's floating overlays, which is what this screen was
                    // missing. Copying a password mounts the clipboard countdown card at the bottom
                    // of the shell — a sibling of this screen's content, not a row in it — and this
                    // list ends near the bottom of the viewport, so the card landed squarely on the
                    // Lowercase toggle: hidden *and* untappable for the ~30 seconds the countdown
                    // runs. Measured 2026-09-06; the same defect the FAB had until 2026-08-10, and
                    // the same fix, from the mechanism built for it — see [LkShellOverlays], where
                    // the shell reports what it actually measured so no bottom-anchored control has
                    // to guess.
                    //
                    // Animated for the reason [VaultListScreen] gives at its own call site: the list
                    // has to arrive with the card it is avoiding, not before it.
                    .padding(
                        bottom = animateDpAsState(
                            shellOverlayInset(),
                            lkTween(LkMotion.Base),
                            label = "generator-overlay-lift",
                        ).value,
                    )
                    // After every padding, so this is the content's own box: the bottom it reports is
                    // where the last control ends, not where the scroll range does.
                    .onGloballyPositioned { contentBottomPx = it.positionInRoot().y + it.size.height },
                verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
            ) {
                // Resolved at composition: androidx.compose.ui's LocalContextGetResourceValueCall
                // lint (an error under this repo's config) forbids context.getString inside a
                // handler reached from LocalContext — and stringResource also recomposes correctly
                // on a locale change where a captured getString result would not.
                val clipboardLabel = stringResource(R.string.generator_clipboard_label)
                OutputCard(
                    value = state.generatedValue.orEmpty(),
                    entropyBits = state.entropyBits.toInt(),
                    onRegenerate = viewModel::generate,
                    onCopy = {
                        haptics.play(Haptic.COPY)
                        ClipboardHelper.copy(context, clipboardLabel, state.generatedValue.orEmpty())
                    },
                    modifier = Modifier.lkEnter(delayMillis = 40),
                )

                state.error?.let {
                    val message = stringResource(it)
                    LkInlineError(message, Modifier.announce(message))
                }

                // The generator could only put a password on the clipboard, which is the one place the
                // app spends the rest of its time trying to get secrets out of — it marks the copy
                // sensitive and auto-clears it. So the whole screen produced something the user then had
                // to shepherd into the vault by hand, through the clipboard, before the timer ran out.
                // Saving is the ordinary ending for this screen and is now one tap.
                if (!state.generatedValue.isNullOrEmpty()) {
                    LkSecondaryButton(
                        text = stringResource(R.string.generator_save_as_login),
                        icon = LkIcons.Plus,
                        // `state.entropyBits` and `state.generatedValue` are written in the same
                        // update (GeneratorViewModel.generate), so the figure handed over is always
                        // the one describing the string handed over — never the previous draw's.
                        onClick = { onSaveToVault(state.generatedValue.orEmpty(), state.entropyBits) },
                        modifier = Modifier.lkEnter(delayMillis = 60),
                    )
                }

                ModeToggle(
                    random = random,
                    onSelect = viewModel::setMode,
                    modifier = Modifier.lkEnter(delayMillis = 80),
                )

                LkCard(modifier = Modifier.lkEnter(delayMillis = 120)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            stringResource(if (random) R.string.generator_length else R.string.generator_words),
                            style = LkText.buttonSmall,
                            color = c.textSecondary,
                        )
                        Text(
                            (if (random) state.length else state.wordCount).toString(),
                            style = LkText.monoValue.copy(fontSize = LkText.heading.fontSize),
                            color = c.jade,
                        )
                    }
                    Spacer(Modifier.height(13.dp))
                    LkSlider(
                        value = if (random) state.length else state.wordCount,
                        range = if (random) 8..64 else 3..10,
                        onValueChange = {
                            if (random) viewModel.onLengthChanged(it) else viewModel.onWordCountChanged(it)
                        },
                        label = stringResource(
                            if (random) R.string.generator_length else R.string.generator_word_count,
                        ),
                    )
                }

                LkGroup(modifier = Modifier.lkEnter(delayMillis = 160)) {
                    if (random) {
                        LkCheckRow(
                            stringResource(R.string.generator_uppercase),
                            state.uppercase,
                            { viewModel.onUppercaseToggled(!state.uppercase) },
                            first = true,
                            hint = stringResource(R.string.generator_hint_uppercase),
                        )
                        LkCheckRow(
                            stringResource(R.string.generator_lowercase),
                            state.lowercase,
                            { viewModel.onLowercaseToggled(!state.lowercase) },
                            first = false,
                            hint = stringResource(R.string.generator_hint_lowercase),
                        )
                        LkCheckRow(
                            stringResource(R.string.generator_digits),
                            state.digits,
                            { viewModel.onDigitsToggled(!state.digits) },
                            first = false,
                            hint = stringResource(R.string.generator_hint_digits),
                        )
                        LkCheckRow(
                            stringResource(R.string.generator_symbols),
                            state.symbols,
                            { viewModel.onSymbolsToggled(!state.symbols) },
                            first = false,
                            hint = stringResource(R.string.generator_hint_symbols),
                        )
                        LkCheckRow(
                            stringResource(R.string.generator_avoid_ambiguous),
                            state.noAmbiguous,
                            { viewModel.onNoAmbiguousToggled(!state.noAmbiguous) },
                            first = false,
                            hint = stringResource(R.string.generator_hint_ambiguous),
                        )
                    } else {
                        LkCheckRow(
                            stringResource(R.string.generator_capitalize_words),
                            state.capitalize,
                            { viewModel.onCapitalizeToggled(!state.capitalize) },
                            first = true,
                            hint = stringResource(R.string.generator_hint_capitalize),
                        )
                        LkCheckRow(
                            stringResource(R.string.generator_include_number),
                            state.includeNumber,
                            { viewModel.onIncludeNumberToggled(!state.includeNumber) },
                            first = false,
                            // Not generator_hint_digits ("0–9"): a passphrase's number is not a
                            // single random digit, it is PasswordGenerator.generatePassphrase's
                            // random.nextInt(1_000).toString().padStart(3, '0') — a zero-padded
                            // three-digit string, 000–999 (F42, 2026-09-13). Verified against the
                            // generator source before writing this string, not assumed from the
                            // Random-mode hint beside it.
                            hint = stringResource(R.string.generator_hint_number),
                        )
                    }
                }

                if (!random) {
                    // No `LkCard` around this one. A card fills with `c.surface` (#0E1214) and the
                    // well fills with `c.field` (#101416) — a two-per-channel step, where the same
                    // well sitting on `c.ground` (#0A0C0E) gets six/eight/eight. `Item Flows.dc.html`
                    // never nests a free-text well inside a raised panel: Username, Password, a
                    // website row, the folder name, the note title and the note body all rest
                    // directly on the device's own #0A0C0E. The only thing it does nest is a
                    // *summary* row you tap to go elsewhere. This is a well, so it comes out.
                    Column(
                        modifier = Modifier.fillMaxWidth().lkEnter(delayMillis = 200),
                        verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
                    ) {
                        Text(stringResource(R.string.generator_separator), style = LkText.buttonSmall, color = c.textSecondary)
                        LkTextField(
                            value = state.separator,
                            onValueChange = viewModel::onSeparatorChanged,
                            placeholder = stringResource(R.string.generator_separator_placeholder),
                            // Without this the field takes its name from the placeholder,
                            // which here is the separator character itself — the field
                            // announced as "-", naming its value rather than its purpose.
                            label = stringResource(R.string.generator_separator),
                            shape = LkShape.lg,
                            textStyle = LkText.monoField,
                            // A one-character field with no Done key left the keyboard sitting over
                            // the passphrase the user came here to read.
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The output card.
 *
 * A new value is announced *once per explicit Regenerate tap* — a "New password, N characters"
 * message through a live region. It must not announce on every regeneration: the length slider
 * regenerates on every drag step, so a single 8-to-64 drag used to queue dozens of announcements
 * on top of TalkBack's own slider announcements. And the value itself is deliberately NOT pinned
 * in the semantics tree — a generated password spelled out character by character was read aloud
 * into the room and stayed in the tree for the node's lifetime. The value is on screen for anyone
 * who can see it, and reachable by navigating to the field for anyone who cannot (the `.announce`
 * that used to shadow it with a `contentDescription` is what made that navigation silent).
 */
@Composable
internal fun OutputCard(
    value: String,
    entropyBits: Int,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val strength = LkStrength.ofBits(entropyBits)
    // Turning off Uppercase, Lowercase, Digits and Symbols together leaves the generator with no
    // alphabet, so it produces nothing and the screen says "Choose at least one valid option".
    // `Save as a new login` already withdrew in that state; Copy and Regenerate stayed lit over an
    // empty value, offering to put nothing on the clipboard and to re-roll a configuration that
    // cannot produce a result. Both now dim with it.
    val hasValue = value.isNotEmpty()
    val copyInteraction = remember { MutableInteractionSource() }
    val reduce = LocalLkReduceMotion.current
    // Counts taps rather than latching a flag, so two copies in a row both bloom. See `lkBloom`.
    var copyCount by remember { mutableIntStateOf(0) }

    // One-shot "a password arrived" announcement, fired only on an explicit Regenerate press.
    // A counter rather than a boolean so two consecutive regenerations both announce, and the
    // live-region node below it carries the changing message only when a press happened.
    var regenerateCount by remember { mutableIntStateOf(0) }
    val generatedMessage = pluralStringResource(
        R.plurals.generator_announce_generated,
        value.length,
        value.length,
    )

    // A half-turn on every regenerate, driven by the press count. See lkRegenerateSpinDegrees for
    // why it is not derived from the value — it used to be, and it almost never moved.
    val spin by animateFloatAsState(
        targetValue = lkRegenerateSpinDegrees(regenerateCount),
        animationSpec = lkTween(LkMotion.Screen, easing = LkMotion.Emphasized),
        label = "regen-spin",
    )

    LkLiftedCard(modifier = modifier) {
        // A fixed three-line stage for the value, with the text scaled to fit inside it.
        //
        // Previously this was a plain wrapping Text with only a *minimum* height, so every extra
        // line it wrapped onto made the card taller and pushed the mode toggle, the length card and
        // every checkbox down the screen. Dragging the length slider from 8 to 64 did that
        // continuously, and the controls the user was aiming at kept sliding out from under their
        // thumb. Reserving the space instead means the layout below the card never moves.
        //
        // The reserver is an invisible three-line string in the same style rather than a dp
        // constant, so the stage grows correctly with the system font scale. Scaling the value down
        // rather than letting it wrap further is what keeps it inside — truncating a generated
        // password would be worse than any amount of movement.
        // The roll: six ticks of 55ms, four characters resolving left to right per tick, the rest
        // drawn from the value's own alphabet. `Item Flows.dc.html`: *"Long enough to read as
        // generation, short enough not to delay the answer."*
        //
        // Keyed on the press count as well as the value, for the same reason the announcement and
        // the spin are: the length slider regenerates on every drag step, and a roll on each of
        // those would be a stutter rather than an animation. It also means two presses that happen
        // to produce a similar string still both roll.
        var rolling by remember { mutableStateOf(value) }
        // The press count this composable has already rolled for.
        //
        // Keying the effect on `regenerateCount` alone is not enough: the effect must also see a
        // new `value` (the slider regenerates on every drag step, and those must land instantly),
        // and once it is keyed on both, a slider drag after any Regenerate press re-enters the
        // effect with a still-elevated count and rolls on every step of the drag — a stutter under
        // the user's thumb, on the control most likely to be dragged. Comparing against the count
        // last rolled for separates "the user asked for a new value" from "the value changed".
        var rolledFor by remember { mutableIntStateOf(0) }
        LaunchedEffect(value, regenerateCount) {
            val pressed = regenerateCount > rolledFor
            rolledFor = regenerateCount
            if (!pressed || reduce || value.isEmpty()) {
                rolling = value
                return@LaunchedEffect
            }
            val alphabet = value.toSet().toList()
            repeat(ROLL_TICKS - 1) { tick ->
                val settled = (tick + 1) * ROLL_CHARS_PER_TICK
                rolling = buildString(value.length) {
                    value.forEachIndexed { i, ch -> append(if (i < settled) ch else alphabet.random()) }
                }
                delay(ROLL_TICK_MILLIS)
            }
            rolling = value
        }
        // Letters strong, digits jade-lit, everything else amber. Reading a 20-character password
        // off the screen to type it somewhere else is the actual task this screen exists for, and
        // one flat colour makes that harder than it needs to be — a run of `l1O0` in a single tone
        // is where transcription goes wrong. Deliberately not applied to a passphrase's words,
        // where the separators are the only non-letters and tinting them adds nothing.
        val tinted = remember(rolling, c) {
            buildAnnotatedString {
                rolling.forEach { ch ->
                    val tone = when {
                        ch.isDigit() -> c.jadeLit
                        ch.isLetter() -> c.textStrong
                        else -> c.amber
                    }
                    withStyle(SpanStyle(color = tone)) { append(ch) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().lkBloom(copyCount)) {
            Text(
                "M\nM\nM",
                style = LkText.monoValue,
                color = Color.Transparent,
                modifier = Modifier.decorative(),
            )
            BasicText(
                text = tinted,
                style = LkText.monoValue,
                maxLines = 3,
                autoSize = TextAutoSize.StepBased(
                    // The floor is in sp, so it scales with the system font — and at the largest
                    // accessibility scale it stopped shrinking while the value still needed a fourth
                    // line, which `BasicText` then clipped without a word. Measured: 64 random
                    // characters and a ten-word passphrase both overflowed the stage at fontScale
                    // 2.0. 8.sp is not small in absolute terms at that scale (16dp, larger than the
                    // old floor renders at fontScale 1.0) — it only lets the fit succeed where it
                    // used to give up and truncate.
                    minFontSize = 8.sp,
                    maxFontSize = LkText.monoValue.fontSize,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart),
            )
            // One-shot live region for the explicit Regenerate press only. The node only exists
            // after a press, and the counter's change re-fires the announce on consecutive presses;
            // a slider drag does not touch the counter, so it does not flood. Never carries the
            // value. (Two presses at the same length coalesce into one announcement — the price of
            // keeping the spoken text clean.)
            //
            // And only while there is a value: toggling every class off after a regenerate left the
            // node in place, so the changing count announced "generated, 0 characters" for nothing.
            if (regenerateCount > 0 && hasValue) {
                Box(
                    Modifier
                        .size(1.dp)
                        .announce(generatedMessage),
                )
            }
        }
        Spacer(Modifier.height(15.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Four segments and the word, the same meter the login form now shows under a typed
            // password — `Item Flows.dc.html`: *"the same four-band treatment as the login field,
            // so strength means one thing in both places."* This was a single continuous bar
            // (`fraction = bits / 140f`), which was a second way of saying the same thing and gave
            // the two screens nothing in common to compare.
            //
            // The bit count stays, in the trailing slot: "128 bits" means little on its own, and
            // "Excellent" asserted with nothing behind it means little either. Together the word is
            // explained rather than claimed.
            //
            // Only with a value to rate. With every character class off there is no password, and
            // the meter used to rate that nothing "Weak · 0 bits" beside a dice that still looked
            // live; the error line below the card is the whole answer in that state. The weight stays
            // so the dice and the copy button keep their place at the row's right-hand edge.
            if (hasValue) {
                Column(Modifier.weight(1f)) {
                    LkStrengthMeter(
                        strength = strength,
                        trailing = {
                            Text(
                                pluralStringResource(R.plurals.generator_entropy_bits, entropyBits, entropyBits),
                                style = LkText.monoCaption,
                                color = c.textFaint,
                            )
                        },
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            LkIconButton(
                // Dice, matching the password well in the item editor — see the note there.
                icon = LkIcons.Dice,
                label = stringResource(R.string.generator_regenerate),
                enabled = hasValue,
                onClick = {
                    regenerateCount++
                    onRegenerate()
                },
                container = c.wash,
                ring = c.ringStrong,
                iconSize = 24.dp,
                modifier = Modifier.rotate(spin),
            )
            Box(
                modifier = Modifier
                    .minTouchTarget()
                    .lkFocusRing(LkShape.md)
                    .clickable(
                        interactionSource = copyInteraction,
                        indication = null,
                        enabled = hasValue,
                    ) {
                        copyCount++
                        onCopy()
                    }
                    .labelled(stringResource(R.string.generator_copy)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .lkPressScale(copyInteraction, pressedScale = 0.92f)
                        // The same treatment `LkSecondaryButton` gives a disabled control — the
                        // fill goes flat and the ink goes faint — rather than an alpha, which this
                        // design system does not use for disablement anywhere else.
                        .then(
                            if (hasValue) {
                                Modifier.background(c.jadeFill, LkShape.md)
                            } else {
                                Modifier.background(Color.Transparent, LkShape.md)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        LkIcons.Copy,
                        contentDescription = null,
                        tint = if (hasValue) c.onJade else c.textFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** A two-position segmented control. Chips would imply the two modes could both be off. */
@Composable
private fun ModeToggle(
    random: Boolean,
    onSelect: (GeneratorUiState.Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(LkShape.md, c.field, c.ring)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeButton(
            stringResource(R.string.generator_mode_random),
            random,
            { onSelect(GeneratorUiState.Mode.RANDOM) },
            Modifier.weight(1f),
        )
        ModeButton(
            stringResource(R.string.generator_mode_passphrase),
            !random,
            { onSelect(GeneratorUiState.Mode.PASSPHRASE) },
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) c.jade.copy(alpha = 0.12f) else Color.Transparent,
        lkTween(LkMotion.Base),
        label = "mode-bg",
    )
    val content by animateColorAsState(
        if (selected) c.jade else c.textMuted,
        lkTween(LkMotion.Base),
        label = "mode-fg",
    )
    Box(
        modifier = modifier
            .minTouchTarget()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .labelled(if (selected) stringResource(R.string.generator_mode_selected, text) else text),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // A minimum, not a height. The outer Box's `minTouchTarget()` does not propagate a
                // min constraint into this one, so a pinned 40dp measured the pill against a loose
                // constraint and the label drew outside its own background: at the largest font
                // size, in the 308dp window the type scale documents as narrowest, "Passphrase" no
                // longer fits one line and wraps. The pill grows now instead, the same way
                // LkPrimaryButton and LkSecondaryButton already do.
                .heightIn(min = 40.dp)
                .background(container, LkShape.sm),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = LkText.buttonSmall, color = content)
        }
    }
}

/** The roll's shape, from the comp: six ticks of 55ms, four characters settling per tick. */
private const val ROLL_TICKS = 6
private const val ROLL_TICK_MILLIS = 55L
private const val ROLL_CHARS_PER_TICK = 4

// `entropyStrength` and `entropyLabel` lived here until 2026-08-19. Both moved to
// `LkStrength.ofBits` in core:ui, with their thresholds unchanged, so the login form's meter and
// this one cannot drift apart — see LkStrength's own note on why one vocabulary is the point.

/**
 * How far to scroll so the end of the options list is not under the shell's floating overlays.
 *
 * The overlays occupy the viewport's bottom [overlayPx] plus the [gapPx] the shell leaves beneath
 * them. The answer is how deep the *last row* reaches into that band, and only when that row is
 * on screen at all. A list that runs past the viewport has no last row in the band: what the card
 * covers there is the middle of the list, which the user is not using (they just pressed Copy at
 * the top), and the overlay-sized bottom padding already lets them scroll the end clear. Lifting
 * it anyway moved the page by the whole band — 237px, ~90dp, on a 1080x2400 device — and slid the
 * output card under the header for nothing. Zero as well when the list already ends above the band.
 *
 * All three positions are in the same coordinate space (the root), so the caller passes them as
 * measured.
 */
internal fun overlayLiftScrollPx(
    contentBottomPx: Float,
    viewportBottomPx: Float,
    overlayPx: Int,
    gapPx: Int,
): Int {
    if (contentBottomPx > viewportBottomPx) return 0
    val band = overlayPx + gapPx
    return (contentBottomPx - (viewportBottomPx - band)).toInt().coerceIn(0, band)
}
