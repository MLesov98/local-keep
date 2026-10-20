package com.localkeep.feature.itemedit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CardBrands
import com.localkeep.core.model.CardNumberValidator
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.announce
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.theme.LkCardDigitGroups
import com.localkeep.core.ui.theme.LkCardFacet
import com.localkeep.core.ui.theme.LkCardPreview
import com.localkeep.core.ui.theme.LkCardStrip
import com.localkeep.core.ui.theme.LkExpiryTransformation
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFolderField
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkStepRail
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField
import com.localkeep.core.ui.theme.LocalLkChromeUnpinned
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkCardNumberSlots
import com.localkeep.core.ui.theme.lkEnter

/**
 * Adding a card, one fact per screen.
 *
 * The flat form asks for nine things at once, and a card is the type where that is worst: the user
 * is reading digits off a piece of plastic, and every field they are not currently filling is
 * something to lose their place in. Stepping the flow means the number gets a whole screen, which
 * is also what lets it be grouped and brand-marked as it is typed — feedback the masked well in the
 * flat form could never give.
 *
 * **New cards only.** Editing an existing card opens the ordinary contained form: someone fixing a
 * typo in a note must not be walked through four unrelated screens to reach it. The flow is
 * therefore a way *in*, never a way back through.
 *
 * State lives entirely in the ViewModel the flat form already uses, so "Enter everything at once"
 * can hand over mid-flow with nothing to migrate, and so this file adds no persistence of its own.
 * Backing out of step one still loses the draft, exactly as backing out of the flat form does.
 */
@Composable
internal fun CardWizard(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    onLeave: () -> Unit,
    onEnterFullForm: () -> Unit,
    onChangeFolder: () -> Unit,
    /**
     * Which step is showing, owned by [ItemEditScreen].
     *
     * Hoisted there rather than remembered here because choosing a folder replaces this whole
     * screen: held locally, the wizard came back at a step the user had already walked past.
     */
    stepState: MutableIntState,
) {
    val c = LkTheme.colors
    var step by stepState
    val brand = CardBrands.detect(state.cardNumber)
    val digits = state.cardNumber.filter(Char::isDigit)
    val cvvLength = CardNumberValidator.cvvLength(brand)
    // Concatenated exactly as stored, with no padding on the way out. Zero-padding here would
    // rewrite the field under the user mid-word: a lone "1" became "01", so the *next* keystroke
    // landed in the year and 11/29 was stored as 01/21. Padding belongs on the way in, and only
    // where it is unambiguous — see the handler in [ExpiryStep].
    val expiryDigits = state.cardExpMonth + state.cardExpYear.takeLast(2)

    val stepCount = STEP_COUNT
    val advance = { if (step < stepCount - 1) step++ }

    // The keyboard-up ("typing") layout — the card folding to a strip, Continue standing alone — is
    // driven by the keyboard's own progress, so it is one motion with the keyboard. See
    // CardWizardKeyboard.kt.
    val kb = rememberCardWizardKeyboard(step, STEP_REVIEW)
    val typing by kb.rememberTyping()
    val scroll = rememberScrollState()

    // The system Back gesture has to walk the steps, exactly as the on-screen button does. Without
    // this the only handler in scope is ItemEditScreen's unconditional one, so a single back gesture
    // from step 4 offered to discard the whole card — four screens of typed digits, one swipe from
    // gone. Registering here means this callback sits above the editor's and consumes the gesture
    // first while it is enabled; at step 0 it disables itself and the editor's discard prompt is
    // correctly what Back reaches. Same layering as the folder picker (ItemEditScreen) and the
    // import wizard (ImportWizardScreen), which is the pattern this flow never got.
    BackHandler(enabled = step > 0) { step-- }

    // Auto-advance is a convenience, never a trap — and until 2026-08-11 it was both.
    //
    // These effects are keyed on `step`, so *arriving* at a step re-runs them, and arriving is
    // exactly what Back does. With four valid expiry digits already entered, Back from the CVV
    // reached the expiry step, this effect fired on the still-valid field, and the wizard threw
    // the user straight back to the CVV. Back therefore appeared to do nothing at all, and the
    // number step became unreachable the moment sixteen digits were in it. Reported from hands-on
    // use: "same bug I cannot go back."
    //
    // The guard is a high-water mark, not a re-key. Simply dropping `step` from the keys would
    // stop the bounce and still leave a worse version of the trap: a user who went back to fix
    // the last digit of a field would be thrown forward again the instant they retyped it. A step
    // may move the user on by itself once per card, and after that the button is the only thing
    // that advances.
    var autoAdvancedThrough by rememberSaveable(state.itemId) { mutableIntStateOf(-1) }
    val autoAdvance = {
        if (step > autoAdvancedThrough) {
            autoAdvancedThrough = step
            advance()
        }
    }
    // The length comes from the same table as the hint below — driving them from two different
    // numbers is what let the step jump forward while the hint still said the number was short.
    LaunchedEffect(digits.length, step) {
        if (step == STEP_NUMBER && digits.length == CardNumberValidator.autoAdvanceLength(brand)) {
            autoAdvance()
        }
    }
    LaunchedEffect(expiryDigits.length, step) {
        if (step == STEP_EXPIRY && expiryDigits.length == 4 &&
            !state.cardExpMonthInvalid && !state.cardExpYearInvalid
        ) {
            autoAdvance()
        }
    }
    LaunchedEffect(state.cardCvv.length, step) {
        if (step == STEP_CVV && state.cardCvv.length == cvvLength) autoAdvance()
    }

    // The number step's one challenge. Continue is never *disabled* — [CardNumberValidator] says
    // why, and a loyalty or gift number that follows no network scheme has to stay storable — but
    // at a length no card is printed at, the first tap now stops and says so instead of advancing
    // in silence. Reported from hands-on use: "entered 6 digits, proceed → still lets me proceed
    // instead of telling me invalid card."
    //
    // Recorded as the digit count it was raised against, so editing the number withdraws the
    // challenge and a second bad length earns a second one. Tapping again with the number
    // unchanged is the deliberate second action that lets it through.
    val numberImplausible = CardNumberValidator.isImplausible(brand, digits.length)
    var numberChallengedAt by rememberSaveable(state.itemId) { mutableIntStateOf(-1) }
    val numberChallenged = numberImplausible && numberChallengedAt == digits.length

    // The same naming step every other new item now ends on. The wizard used to ask for the title
    // inline on Review, which was already title-last for cards alone; it is asked here instead so a
    // card and a login are named by the same control. Filing is on Review rather than in the sheet,
    // for the same reason it is on the flat form: the chooser is a screen, and a modal sheet cannot
    // open one without tearing itself down.
    // Whichever field this step is asking for, focused on arrival.
    //
    // Auto-advance without it is a trap of the flow's own making: sixteen digits move the wizard on
    // by itself with the keyboard still up, and every keystroke after that goes nowhere, because
    // the field that had focus left with the step. Measured on the emulator 2026-08-20 — typing a
    // card number and an expiry straight through left the expiry empty and the wizard on step 2.
    // The comp assumes the same thing its own walkthrough does, which is that the caret is already
    // where the question is.
    val stepFocus = remember { FocusRequester() }
    // The same field, asked to be shown rather than focused: [WizardFrame] brings it into view when
    // a step arrives, so a short window does not open on a heading with the question below the fold.
    val stepReveal = remember { BringIntoViewRequester() }
    // Two rules, and the first one is the arrival.
    //
    // **The wizard does not take focus when it opens.** It used to, one frame after composition, so
    // the keyboard rose while the screen was still animating in and covered half of it before the
    // user had looked at anything. Reported 2026-08-20: "when I click on Card (to add one) focus is
    // immediately on and I have to type with keyboard hiding half of the screen — I assume user
    // would tap only then keyboard would show". Arriving at a form is not the same act as answering
    // it; the first field is one tap away and that tap is the user saying they are ready.
    //
    // **Advancing still focuses, and it does so immediately.** That focus is not a convenience, it
    // is load-bearing: sixteen digits auto-advance the wizard with the IME still up, and without
    // moving the caret every keystroke after that goes nowhere, because the field that had focus
    // left with the step (measured on the emulator 2026-08-20 — typing a number and an expiry
    // straight through left the expiry empty).
    //
    // It used to wait `LkMotion.Screen` first, to keep the IME from rising *through* the entrance
    // animation. That reasoning was sound and the remedy was not: waiting does not stop the two
    // motions competing, it separates them far enough apart to read as two events instead of one.
    // The `when (step)` below has no `AnimatedContent`, so the departing field is disposed in the
    // same frame the step changes — focus dies with it, the IME slides out, and `imePadding()`
    // collapses. The delay then held the screen there before asking for focus back.
    //
    // Measured on emulator-5554 (API 36, 1080x2400) from a screen recording of the number→expiry
    // advance, keyboard-band luminance per rendered frame: keyboard up to t=1.08 s, fully gone by
    // t=1.51 s, fully back by t=2.13 s. Two IME transitions, two 704 px layout shifts, ~620 ms of
    // dead screen between them — "double jump animation", reported 2026-09-04.
    //
    // Asking on the next frames instead means focus is regained before the IME has finished
    // leaving, so the keyboard never lands and there is one motion rather than two. The frame waits
    // stay: the field for the step being *entered* has to be attached before it can be asked for
    // focus, or the request lands on the departing step's requester and throws. Two of them rather
    // than one, because a single frame is the composition pass — the requester is attached on the
    // one after it, which is what the old `delay` was incidentally paying for.
    var advanced by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        if (!advanced) {
            // First composition. Nothing has been advanced yet, so this is the arrival.
            advanced = true
            return@LaunchedEffect
        }
        if (step == STEP_REVIEW) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        runCatching { stepFocus.requestFocus() }
    }

    var showSaveSheet by rememberSaveable { mutableStateOf(false) }
    if (showSaveSheet) {
        SaveSheet(
            state = state,
            onDismiss = { showSaveSheet = false },
            onSave = viewModel::saveWithTitle,
        )
    }

    LkScreen(accent = c.jade, auraAlpha = 0.09f, auraBias = 0.5f) {
        // The keyboard-aware root, the rail, the scroll region and the pinned actions live in
        // [WizardFrame], shared with the identity wizard. The notes that used to sit on the root
        // Column moved there with it: `imePadding()` is what keeps Continue from landing on the
        // keyboard's "5" key (measured on device 2026-08-10, API 36), and it is applied after the
        // consumed-insets reader so the keyboard's progress is measured on the inset it really pads.
        WizardFrame(
            keyboard = kb,
            scroll = scroll,
            step = step,
            railGap = 0.dp,
            rail = {
                LkStepRail(
                    stepIndex = step,
                    stepCount = stepCount,
                    progressText = stringResource(R.string.itemedit_card_wizard_progress, step + 1, stepCount),
                    progressDescription = stringResource(
                        R.string.itemedit_card_wizard_progress_a11y,
                        step + 1,
                        stepCount,
                    ),
                )
            },
            arrival = stepReveal.takeIf { step != STEP_REVIEW },
            actions = {
                CardWizardActions(
                    step = step,
                    canAdvance = when (step) {
                        STEP_NUMBER -> digits.isNotEmpty()
                        STEP_EXPIRY -> expiryDigits.length == 4 && !state.cardExpMonthInvalid && !state.cardExpYearInvalid
                        STEP_CVV -> true // A card with no CVV recorded is a legitimate thing to store.
                        STEP_WHO -> true
                        // The title is asked for after this button, in [SaveSheet], so there is
                        // nothing left on Review for it to wait on.
                        else -> true
                    },
                    isSaving = state.isSaving,
                    onAdvance = {
                        when {
                            // Stops once, then gets out of the way. The check is on the *challenge*
                            // rather than on the length, so the second tap advances without needing
                            // the number to change.
                            step == STEP_NUMBER && numberImplausible && !numberChallenged ->
                                numberChallengedAt = digits.length
                            step == STEP_REVIEW ->
                                if (viewModel.readyToName()) showSaveSheet = true
                            else -> advance()
                        }
                    },
                    onBack = { if (step == 0) onLeave() else step-- },
                    onEnterFullForm = onEnterFullForm.takeIf { step == STEP_NUMBER },
                    continueDisabledReasonRes = cardWizardContinueDisabledReason(step, digits),
                    keyboard = kb,
                    typing = typing,
                )
            },
        ) {
            // The card face, at the top of every step and never leaving it.
            //
            // `Card Flow.dc.html` promotes it out of the review screen: *"Today LkCardPreview only
            // appears on the review step, so four of the five screens are a heading over an empty
            // well."* Filling in an object you can see is a different act from answering four
            // questions in a row — and it is the same composable at the same 1.586 ratio, so the
            // only thing it costs is where it is drawn.
            //
            // Inside the scroll column, not pinned above it.
            //
            // The comp draws the face fixed under the rail, which is right on its own 872dp
            // device and wrong on a shorter one: the emulator here is 698dp tall and the
            // principal's phone 780dp, and with the face pinned the card-number well — the one
            // thing step 1 asks for — was clipped by the action column on arrival, with no way
            // to scroll it into view because the only scrolling region was the part already
            // showing. Scrolling with the form costs nothing on a device the comp's height,
            // where none of this moves.
            CardWizardFace(state, brand, step, cvvLength, kb, scroll)
            CardWizardHeading(step, brand)
            // Mirrors CardWizardActions' own onAdvance decision for these two steps, so the
            // keyboard's Next key can never do something Continue would not: a challenged number
            // still stops on the warning, and an incomplete or invalid expiry still does not
            // advance, exactly as the disabled Continue button would refuse to.
            val onNumberNext = {
                if (digits.isNotEmpty()) {
                    if (numberImplausible && !numberChallenged) {
                        numberChallengedAt = digits.length
                    } else {
                        advance()
                    }
                }
            }
            val onExpiryNext = {
                if (expiryDigits.length == 4 && !state.cardExpMonthInvalid && !state.cardExpYearInvalid) {
                    advance()
                }
            }
            when (step) {
                STEP_NUMBER -> NumberStep(state, viewModel, brand, numberChallenged, stepFocus, stepReveal, onNumberNext)
                STEP_EXPIRY -> ExpiryStep(state, viewModel, expiryDigits, stepFocus, stepReveal, onExpiryNext)
                STEP_CVV -> CvvStep(state, viewModel, brand, cvvLength, stepFocus, stepReveal)
                STEP_WHO -> WhoStep(state, viewModel, stepFocus, stepReveal)
                else -> ReviewStep(state, onChangeFolder)
            }
            // Step 1's two quiet exits ride here, not in the pinned stack, while the keyboard is
            // up: pinned they cost 116dp of the little room there is.
            if (step == STEP_NUMBER && typing) {
                CardWizardTypingExits(
                    keyboard = kb,
                    scroll = scroll,
                    enabled = !state.isSaving,
                    onDiscard = onLeave,
                    onEnterFullForm = onEnterFullForm,
                )
            }
        }
    }
}

/**
 * The four digits behind an `MM/YY` well, as the user types them.
 *
 * The one transformation applied while typing is the unambiguous one: no month starts with 2–9, so
 * a lone such digit can only mean `0X`, and padding it immediately is what lets someone type
 * "7", "2", "5" and get 07/25 instead of a month of 72. A leading "0" or "1" is left alone — it may
 * still be going to become 01 or 12, and guessing would rewrite the field under the user's caret,
 * which is exactly the bug this replaced.
 */
internal fun expiryDigitsFrom(typed: String): String {
    val d = typed.filter(Char::isDigit).take(4)
    return if (d.length == 1 && d[0] > '1') "0$d" else d
}

/**
 * Why a dimmed Continue cannot be advanced right now, or null when it can (or when the reason is
 * `isSaving`, which the deriving overlay already states — see [CardWizardActions]).
 *
 * A pure function of the same two inputs [CardWizardActions.canAdvance] uses for [STEP_NUMBER], so
 * the announcement and the gate cannot disagree, and a JVM test can reach it (F49, 2026-09-13).
 * Keyed on the field being empty, never on `enabled`, which also folds in `isSaving`: hanging the
 * reason on `enabled` would announce "enter a card number" over a number the user already typed,
 * while the final save is running — the same false-cause failure mode
 * [core_ui_naming_confirm_disabled_a11y] (V21) exists to avoid.
 */
internal fun cardWizardContinueDisabledReason(step: Int, digits: String): Int? =
    if (step == STEP_NUMBER && digits.isEmpty()) {
        R.string.itemedit_card_wizard_continue_disabled_number_a11y
    } else {
        null
    }

private const val STEP_NUMBER = 0
private const val STEP_EXPIRY = 1
private const val STEP_CVV = 2
private const val STEP_WHO = 3
private const val STEP_REVIEW = 4
private const val STEP_COUNT = 5

/**
 * Digits left unmasked on the review step's card face.
 *
 * Four — the same tail the save sheet puts in a card's default title, and the same tail a receipt
 * or a bank statement prints. Enough to tell two cards apart and to catch a transposed last block;
 * not enough to be a card number.
 */
private const val REVIEW_VISIBLE_DIGITS = 4

@Composable
internal fun CardWizardHeading(step: Int, brand: CardBrand) {
    val c = LkTheme.colors
    val title = when (step) {
        STEP_NUMBER -> stringResource(R.string.itemedit_card_wizard_number_title)
        STEP_EXPIRY -> stringResource(R.string.itemedit_card_wizard_expiry_title)
        STEP_CVV -> stringResource(R.string.itemedit_card_wizard_cvv_title)
        STEP_WHO -> stringResource(R.string.itemedit_card_wizard_who_title)
        else -> stringResource(R.string.itemedit_card_wizard_review_title)
    }
    val body = when (step) {
        STEP_NUMBER -> stringResource(R.string.itemedit_card_wizard_number_body)
        STEP_EXPIRY -> stringResource(R.string.itemedit_card_wizard_expiry_body)
        // The CVV's length depends on the network, so saying which one is expected is the whole
        // point of asking for it on its own screen.
        STEP_CVV -> if (brand == CardBrand.AMEX) {
            stringResource(R.string.itemedit_card_wizard_cvv_body_amex)
        } else {
            stringResource(R.string.itemedit_card_wizard_cvv_body)
        }
        STEP_WHO -> stringResource(R.string.itemedit_card_wizard_who_body)
        else -> stringResource(R.string.itemedit_card_wizard_review_body)
    }
    Column(Modifier.fillMaxWidth().lkEnter(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        Text(
            title,
            // CardWizard builds its own step titles instead of using LkScreenHeader, which supplies
            // heading() to every other screen -- so TalkBack's navigate-by-heading gesture had no
            // stop across any of the five steps (Card number/Expiry/CVV/Who/Review). Same defect and
            // fix as the unlock screen (W9) and item detail screen (V20) (fix-I, 21-1, 2026-09-13).
            modifier = Modifier.semantics { heading() },
            style = LkText.display,
            color = c.textStrong,
        )
        Text(body, style = LkText.bodyQuiet, color = c.textMuted)
    }
}

@Composable
private fun NumberStep(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    brand: CardBrand,
    challenged: Boolean,
    focus: FocusRequester,
    reveal: BringIntoViewRequester,
    onNext: () -> Unit,
) {
    val c = LkTheme.colors
    val groups = if (brand == CardBrand.AMEX) LkCardDigitGroups.Amex else LkCardDigitGroups.Default
    // No container. The comp draws a labelled well straight on the ground, and the card that used
    // to sit around these was the principal's complaint on 2026-08-19 — first about Login, then
    // "keep doing this for all items from the design". A box around a single labelled field states
    // a grouping that is not there: one field is not a group.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        run {
            // The label and the well are what a step arrives showing ([WizardFrame]'s `arrival`).
            Column(
                Modifier.fillMaxWidth().bringIntoViewRequester(reveal),
                verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
            ) {
                LkFieldLabel(stringResource(R.string.itemedit_card_number_field_label))
                CardNumberTextField(
                    value = state.cardNumber,
                    onValueChange = { viewModel.onCardNumberChanged(it.filter(Char::isDigit)) },
                    label = stringResource(R.string.itemedit_card_number_field_label),
                    groups = groups,
                    modifier = Modifier.focusRequester(focus),
                    keyboardActions = KeyboardActions(onNext = { onNext() }),
                )
            }
            // Validate on submit, not while typing. The length hint used to fire from the first digit
            // (14 of 16 keystrokes on a Visa) and, with the keyboard up, sat under the pinned
            // buttons where nobody could read it. While the user types, only the brand is named; the
            // first Continue tap on an implausible length shows the hint and the instruction in
            // its place — one status at a time, never two colours at once.
            //
            // The group asks to be scrolled into view when the challenge appears: bring-into-view
            // only ever follows the focused *field*, so a caption below it stayed below the fold.
            val statusReveal = rememberOpenReveal(challenged)
            Column(
                Modifier.fillMaxWidth().bringIntoViewRequester(statusReveal),
                verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
            ) {
                if (challenged) {
                    CardNumberLengthHint(brand, state.cardNumber.count(Char::isDigit), challenged = true)
                } else if (brand != CardBrand.UNKNOWN) {
                    // Non-interactive on purpose: a brand mark that reads as a button gets tapped by
                    // people expecting a network picker.
                    Text(brand.displayName(), style = LkText.caption, color = c.jade)
                }
            }
        }
    }
}

/**
 * Says what is odd about the typed length, and what to do about it — **only once the user has tried
 * to walk past it** ([challenged]). Until then nothing is said about the length at all: a number being
 * typed is short by definition, and an amber "Visa cards have 13, 16, 19 digits." under every
 * keystroke is premature validation (and was hidden behind the pinned buttons anyway).
 *
 * The status is coral because it is now a response to an action. A length on its own is not a
 * failure: the user may be typing a loyalty or gift number that follows no network scheme at all,
 * and [CardNumberValidator] exists precisely so nothing is rejected on the strength of a length.
 * Continue is never disabled here.
 *
 * What changed on 2026-08-11 is that the hint had two holes, and a six-digit number fell through
 * both. With no network detected there was no hint at all — [CardNumberValidator.isUnexpectedLength]
 * is false for every unrecognised number by construction — and even where a hint did show, one tap
 * advanced past it in silence. So an obviously incomplete number reached the review screen with
 * nothing having questioned it: "entered 6 digits, proceed → still lets me proceed instead of
 * telling me invalid card."
 *
 * Now [CardNumberValidator.isImplausible] covers the unrecognised case too, and the wizard spends
 * the first tap on saying so. The second tap still goes through — being told is the fix, not being
 * stopped.
 */
@Composable
private fun CardNumberLengthHint(brand: CardBrand, digitCount: Int, challenged: Boolean) {
    if (!challenged || !CardNumberValidator.isImplausible(brand, digitCount)) return
    val lengths = CardNumberValidator.acceptedLengths(brand).sorted()
    // Empty for UNKNOWN, which is the case that has no name and no length set to print. It gets the
    // one sentence that is true without either.
    val message = if (lengths.isEmpty()) {
        stringResource(R.string.itemedit_card_number_unrecognised_hint)
    } else {
        val spelled = if (lengths.size > 2 && lengths.last() - lengths.first() == lengths.size - 1) {
            stringResource(R.string.itemedit_card_length_range, lengths.first(), lengths.last())
        } else {
            lengths.joinToString(stringResource(R.string.itemedit_card_length_separator))
        }
        stringResource(R.string.itemedit_card_number_length_hint, brand.displayName(), spelled)
    }
    Text(
        message,
        style = LkText.caption,
        color = LkTheme.colors.coral,
        modifier = Modifier.announce(message),
    )
    val instruction = stringResource(R.string.itemedit_card_number_challenge)
    Text(
        instruction,
        style = LkText.caption,
        color = LkTheme.colors.textMuted,
        modifier = Modifier.announce(instruction),
    )
}

@Composable
private fun ExpiryStep(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    expiryDigits: String,
    focus: FocusRequester,
    reveal: BringIntoViewRequester,
    onNext: () -> Unit,
) {
    val invalid = state.cardExpMonthInvalid || state.cardExpYearInvalid
    // No container. The comp draws a labelled well straight on the ground, and the card that used
    // to sit around these was the principal's complaint on 2026-08-19 — first about Login, then
    // "keep doing this for all items from the design". A box around a single labelled field states
    // a grouping that is not there: one field is not a group.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        run {
            Column(
                Modifier.fillMaxWidth().bringIntoViewRequester(reveal),
                verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
            ) {
                LkFieldLabel(stringResource(R.string.itemedit_card_wizard_expiry_label))
                LkTextField(
                    value = expiryDigits,
                    // One well, split back into the two fields the item actually stores. A month typed
                    // as "7" is stored as "07": the card prints two digits, and a validator that only
                    // accepts what the card shows is not being strict, it is being consistent.
                    onValueChange = { typed ->
                        val d = expiryDigitsFrom(typed)
                        viewModel.onCardExpMonthChanged(d.take(2))
                        viewModel.onCardExpYearChanged(d.drop(2))
                    },
                    label = stringResource(R.string.itemedit_card_wizard_expiry_label),
                    placeholder = stringResource(R.string.itemedit_card_wizard_expiry_placeholder),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = LkText.monoValue,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { onNext() }),
                    visualTransformation = LkExpiryTransformation(),
                )
            }
            if (invalid) {
                // Three cases, not two. In this well the year is two digits by construction, so
                // "the four digits printed on the card" — which is the only thing an invalid year
                // used to say — was advice the user could not follow, and following it anyway
                // produced the same rejection.
                val message = when {
                    state.cardExpMonthInvalid -> stringResource(R.string.itemedit_card_exp_month_error)
                    state.cardExpYearTooFarAhead ->
                        stringResource(R.string.itemedit_card_exp_year_far_error)
                    else -> stringResource(R.string.itemedit_card_exp_year_error)
                }
                Text(
                    message,
                    style = LkText.caption,
                    color = LkTheme.colors.coral,
                    modifier = Modifier.announce(message),
                )
            }
        }
    }
}

@Composable
private fun CvvStep(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    brand: CardBrand,
    cvvLength: Int,
    focus: FocusRequester,
    reveal: BringIntoViewRequester,
) {
    // No container. The comp draws a labelled well straight on the ground, and the card that used
    // to sit around these was the principal's complaint on 2026-08-19 — first about Login, then
    // "keep doing this for all items from the design". A box around a single labelled field states
    // a grouping that is not there: one field is not a group.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        SecretField(
            state.cardCvv,
            { viewModel.onCardCvvChanged(it.filter(Char::isDigit).take(cvvLength)) },
            stringResource(R.string.itemedit_card_cvv_label),
            "cardCvv",
            state,
            viewModel,
            keyboardType = KeyboardType.NumberPassword,
            modifier = Modifier.focusRequester(focus).bringIntoViewRequester(reveal),
        )
        // Only ever fires while a code is half-typed — the field is capped at the brand's length, so
        // it cannot be too long, and a card stored with no code at all stays perfectly valid.
        if (CardNumberValidator.isUnexpectedCvvLength(brand, state.cardCvv.count(Char::isDigit))) {
            val message = stringResource(
                R.string.itemedit_card_cvv_length_hint,
                brand.displayName(),
                cvvLength,
            )
            Text(
                message,
                style = LkText.caption,
                color = LkTheme.colors.amber,
                modifier = Modifier.announce(message),
            )
        }
    }
}

@Composable
private fun WhoStep(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    focus: FocusRequester,
    reveal: BringIntoViewRequester,
) {
    // PIN and Notes ride along here rather than each taking a screen of their own: most stored
    // cards have neither, and a step that is usually skipped is a step that teaches people to skip
    // steps.
    // No container. The comp draws a labelled well straight on the ground, and the card that used
    // to sit around these was the principal's complaint on 2026-08-19 — first about Login, then
    // "keep doing this for all items from the design". A box around a single labelled field states
    // a grouping that is not there: one field is not a group.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        // The cardholder's Next key goes straight to the PIN well. Left to Compose's own focus
        // search it lands on the PIN's Show-PIN eye - the next focusable down - which is a button,
        // not a field, so Next put focus on a control instead of in the well the label promises.
        val pinFocus = remember { FocusRequester() }
        BoxedField(
            value = state.cardHolder,
            onValueChanged = viewModel::onCardHolderChanged,
            label = stringResource(R.string.itemedit_card_holder_field_label),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).bringIntoViewRequester(reveal),
            imeAction = ImeAction.Next,
            onImeAction = { runCatching { pinFocus.requestFocus() } },
        )
        SecretField(
            state.cardPin,
            viewModel::onCardPinChanged,
            stringResource(R.string.itemedit_card_pin_label),
            "cardPin",
            state,
            viewModel,
            keyboardType = KeyboardType.NumberPassword,
            modifier = Modifier.focusRequester(pinFocus),
        )
        BoxedField(
            value = state.cardNotes,
            onValueChanged = viewModel::onCardNotesChanged,
            label = stringResource(R.string.itemedit_notes_label),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            // See the note on `NoteFields` in ItemEditScreen: a notes well is typed with the IME's
            // autocorrect and learning off, whichever route reaches it.
            keyboardType = KeyboardType.Password,
            minHeight = 86.dp,
            maxHeight = 220.dp,
        )
    }
}

/**
 * The face, wired to whatever has been typed so far.
 *
 * Three things change as the flow walks: the digits land one at a time on the number step, the fact
 * being asked for lights jade, and arriving at the security code turns the whole card over — except
 * for Amex, whose code is printed on the front and which therefore has nothing to turn to.
 *
 * The number is masked on Review and nowhere else. While the user is typing, the digits are the
 * thing they are checking against the plastic in their hand; on Review the face is showing them how
 * the card will be *stored*.
 *
 * Masked there means all but the last four, matching the comp. It meant *everything* until
 * 2026-09-11, on the reasoning that the read screen it becomes masks the lot and a review showing
 * more than the item ever will again is a preview of something that does not exist — which read
 * well and cost the user the one chance to check the field most likely to carry a typo. The last
 * four are not a new disclosure: the naming sheet that follows pre-fills the title as
 * "Visa •••• 1111" from the same digits. What the face draws and what it announces are decided
 * together by [cardNumberAnnouncement], because they drifted apart once already.
 */
@Composable
internal fun CardWizardFace(
    state: ItemEditUiState,
    brand: CardBrand,
    step: Int,
    cvvLength: Int,
    keyboard: CardWizardKeyboard,
    scroll: ScrollState,
) {
    val digits = state.cardNumber.filter(Char::isDigit)
    val groups = if (brand == CardBrand.AMEX) LkCardDigitGroups.Amex else LkCardDigitGroups.Default
    // The review step masks everything but the last four. It masked *everything* until 2026-09-11,
    // which made the one screen that asks "Check it over" the one screen on which the card number
    // could not be checked — and the number is what step 1 was entirely about, and the field most
    // likely to carry a typo. Last-four is not a new disclosure: the very next sheet pre-fills this
    // item's title as "Visa •••• 1111" from the same digits.
    val masked = step == STEP_REVIEW
    val maskedBefore = if (masked) (digits.length - REVIEW_VISIBLE_DIGITS).coerceAtLeast(0) else 0
    val expiry = cardExpiryFace(state)
    val brandName = brand.takeIf { it != CardBrand.UNKNOWN }?.displayName()
    // What is spoken must be what is drawn, and it is spoken once: the region carries the one
    // semantics node and clears it on both faces, so a screen reader never meets the sentence twice
    // while the full face and the strip are cross-fading.
    val faceSentence = run {
        // What is spoken must be what is drawn. The review step draws the last four (see
        // `masked`/`maskedBefore` above), and this said "number hidden" there until
        // 2026-09-11 — so the one step whose whole job is "Check it over" was the one step on
        // which a screen-reader user could not check the number, while a sighted user could.
        // The earlier note here argued the face is decorative and the number belongs to the
        // detail screen's NUMBER row; that holds for a *saved* item, and the review step is
        // precisely the moment before there is one. `takeLast` rather than a fixed four so the
        // announcement still matches the face for a part-typed number.
        //
        // Not a new disclosure: the naming sheet that follows pre-fills this item's title as
        // "Visa •••• 1111" from the same digits.
        val numberSpoken = when (val announcement = cardNumberAnnouncement(digits, masked)) {
            CardNumberAnnouncement.Hidden ->
                stringResource(R.string.itemedit_detail_card_number_hidden_a11y)
            is CardNumberAnnouncement.Ending -> stringResource(
                R.string.itemedit_card_review_number_ending_a11y,
                announcement.digits,
            )
            CardNumberAnnouncement.Full -> cardNumberFace(state.cardNumber, brand, revealed = true)
        }
        // A brand-less card gets the sentence that does not take a brand. Feeding the generic
        // word "Card" into the "%1$s card, …" pattern made a screen reader say "Card card".
        if (brandName == null) {
            stringResource(R.string.itemedit_detail_card_face_a11y_no_brand, numberSpoken, expiry)
        } else {
            stringResource(R.string.itemedit_detail_card_face_a11y, brandName, numberSpoken, expiry)
        }
    }
    CardWizardFaceRegion(
        keyboard = keyboard,
        scroll = scroll,
        contentDescription = faceSentence,
        // The strip rides in the scroll with the rail once the keyboard-up rule has unpinned them.
        pinned = !LocalLkChromeUnpinned.current,
        full = { faceModifier ->
            LkCardPreview(
                modifier = faceModifier,
                numberSlots = lkCardNumberSlots(
                    digits = digits,
                    groups = groups,
                    maskedBefore = maskedBefore,
                    // Only while the number step is open: arriving back here from Back must not re-land a
                    // digit the user typed two screens ago.
                    landingIndex = if (step == STEP_NUMBER) digits.lastIndex else -1,
                ),
                expiryDisplay = expiry,
                holder = state.cardHolder,
                holderPlaceholder = stringResource(R.string.itemedit_card_face_holder_placeholder),
                brandLabel = brandName,
                expiryCaption = stringResource(R.string.itemedit_detail_card_expires_label),
                active = when (step) {
                    STEP_NUMBER -> LkCardFacet.Number
                    STEP_EXPIRY -> LkCardFacet.Expiry
                    STEP_CVV -> LkCardFacet.Cvv
                    STEP_WHO -> LkCardFacet.Holder
                    else -> LkCardFacet.None
                },
                showBack = step == STEP_CVV && brand != CardBrand.AMEX,
                // Masked on the face as it is in the field: the box on a real card is where the digits are
                // printed, but a second copy of the code on a surface the user is not typing into is one
                // more place it can be read over a shoulder.
                cvvDisplay = "•".repeat(state.cardCvv.length),
                cvvSlots = cvvLength,
                cvvHint = stringResource(
                    if (brand == CardBrand.AMEX) {
                        R.string.itemedit_card_face_cvv_hint_amex
                    } else {
                        R.string.itemedit_card_face_cvv_hint
                    },
                ),
                contentDescription = faceSentence,
            )
        },
        strip = { faceModifier ->
            // The same number, never landing: the strip is composed alongside the full face for the
            // whole wizard, which is the one that animates the newest digit.
            LkCardStrip(
                modifier = faceModifier,
                numberSlots = lkCardNumberSlots(
                    digits = digits,
                    groups = groups,
                    maskedBefore = maskedBefore,
                    landingIndex = -1,
                ),
                contentDescription = faceSentence,
                brandLabel = brandName,
                showBack = step == STEP_CVV && brand != CardBrand.AMEX,
                cvvDisplay = "•".repeat(state.cardCvv.length),
                cvvSlots = cvvLength,
                cvvActive = step == STEP_CVV,
            )
        },
    )
}

/**
 * "09 / 29", or the em-dash placeholder the comp draws while the field is empty.
 *
 * Internal, and shared with the item detail screen's card face, because those two used to hold
 * separate copies of this expression and the copies disagreed the moment a card was saved. A real
 * card prints `01`, and the wizard showed `01 / 20` while the typed string was still in hand — but
 * the month round-trips through an `Int` (`ItemContent.expMonth`), so reopening the item rendered
 * `1 / 20` for the rest of that card's life. `ItemDetailScreen`'s own comment on the card face says
 * the face must "look the same the moment after it is saved as it did the moment before"; padding
 * here, in one place both callers use, is what makes that true.
 */
@Composable
internal fun cardExpiryFace(state: ItemEditUiState): String =
    listOf(state.cardExpMonth.padMonth(), state.cardExpYear)
        .filter(String::isNotBlank)
        .joinToString(" / ")
        .ifBlank { stringResource(R.string.itemedit_detail_card_expiry_unset) }

/** `"1"` -> `"01"`; anything that is not a lone digit is left exactly as the user typed it. */
private fun String.padMonth(): String = if (length == 1 && single().isDigit()) "0$this" else this

@Composable
private fun ReviewStep(state: ItemEditUiState, onChangeFolder: () -> Unit) {
    if (state.cardCvv.isNotBlank()) {
        Text(
            stringResource(R.string.itemedit_card_wizard_cvv_set),
            style = LkText.caption,
            color = LkTheme.colors.jade,
        )
    }
    // The wizard's only filing control. Without it a card was the one item type that could not be
    // put in a folder at the moment it was created — the flat form has it under "more", and the
    // wizard never reaches the flat form.
    LkFolderField(
        selectedName = state.folders.firstOrNull { it.id == state.folderId }?.name,
        onChange = onChangeFolder,
    )
}

@Composable
internal fun CardWizardActions(
    step: Int,
    canAdvance: Boolean,
    isSaving: Boolean,
    onAdvance: () -> Unit,
    onBack: () -> Unit,
    onEnterFullForm: (() -> Unit)?,
    /** See [cardWizardContinueDisabledReason]. Null while saving on purpose — that state already
     * has "Encrypting…" as the button's own text. */
    continueDisabledReasonRes: Int? = null,
    keyboard: CardWizardKeyboard,
    typing: Boolean,
) {
    // At rest this is the stack it always was. While the keyboard is up the quieter buttons fold
    // away as it rises and Continue stands alone (with a chevron for Back on steps 2-5), because
    // three pinned buttons were 168dp of the room a keyboard leaves — see CardWizardActionsFrame.
    CardWizardActionsFrame(
        keyboard = keyboard,
        typing = typing,
        withChevron = step > 0 && step != STEP_REVIEW,
        onBack = onBack,
        backEnabled = !isSaving,
        primary = {
            LkPrimaryButton(
                text = when {
                    isSaving -> stringResource(R.string.itemedit_edit_encrypting)
                    step == STEP_REVIEW -> stringResource(R.string.itemedit_edit_save)
                    else -> stringResource(R.string.itemedit_card_wizard_continue)
                },
                onClick = onAdvance,
                enabled = canAdvance && !isSaving,
                haptic = if (step == STEP_REVIEW) Haptic.SUCCESS else Haptic.SELECT,
                modifier = if (continueDisabledReasonRes != null && !isSaving) {
                    Modifier.fillMaxWidth().labelled(stringResource(continueDisabledReasonRes))
                } else {
                    Modifier.fillMaxWidth()
                },
            )
        },
        secondaries = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
                // **Discard is a text button; Back is a secondary.** Same slot, same handler, two different
                // weights, because on step 1 the second control is the one that throws the work away.
                //
                // Drawn as a secondary it was the brightest thing on the screen: `LkPrimaryButton` disabled
                // is a hollow plate — ring and faint label, no fill, which is how it says "no primary action
                // is available yet" — and a fresh wizard step has nothing to continue with, so the only
                // fully-drawn plate on it was Discard (F-22, 2026-09-15 visual pass, `shots/072`). The
                // hierarchy the button family promises is at most one bright element per screen; it does not
                // promise the dim ones sort themselves, and here the dimmer of the two has to be the one
                // that loses data.
                //
                // `Back` keeps the secondary weight it had. It is not destructive — the wizard holds every
                // field it has collected — and on steps 2-5 the primary above it is live and brighter, so
                // nothing about that row was ever wrong.
                if (step == 0) {
                    LkTextButton(
                        text = stringResource(R.string.itemedit_edit_discard_confirm),
                        onClick = onBack,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LkSecondaryButton(
                        text = stringResource(R.string.itemedit_card_wizard_back),
                        onClick = onBack,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Pinned with the actions rather than left in the content above: the reassurance that there
                // is another way through is only worth anything at the moment the user is deciding, and
                // that moment happens at the buttons.
                if (onEnterFullForm != null) {
                    LkSecondaryButton(
                        text = stringResource(R.string.itemedit_card_wizard_manual),
                        onClick = onEnterFullForm,
                        enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}

/** How the card face's number should be announced. Resolved to strings by the composable. */
internal sealed interface CardNumberAnnouncement {
    /** Nothing to read out — no digits typed yet. */
    data object Hidden : CardNumberAnnouncement

    /** The digits actually drawn on a masked face. */
    data class Ending(val digits: String) : CardNumberAnnouncement

    /** The whole number, as drawn while the user is typing it. */
    data object Full : CardNumberAnnouncement
}

/**
 * Decides what a screen reader is told about the card number, from the same two inputs that decide
 * what is drawn.
 *
 * Pulled out of [CardWizardFace] because the two drifted apart: the review step started drawing the
 * last four on 2026-09-11 and went on announcing "number hidden", so the one step whose whole job is
 * "Check it over" was the one step a screen-reader user could not check the number on. Keeping the
 * decision in one pure function is what lets a test hold them together — the composable itself needs
 * a device to observe.
 *
 * [Ending] carries `takeLast` rather than a fixed four so a part-typed number still announces
 * exactly what the face shows: below four digits nothing is masked, and claiming otherwise would be
 * a different lie from the one this replaced.
 */
internal fun cardNumberAnnouncement(digits: String, masked: Boolean): CardNumberAnnouncement = when {
    digits.isEmpty() -> CardNumberAnnouncement.Hidden
    masked -> CardNumberAnnouncement.Ending(digits.takeLast(REVIEW_VISIBLE_DIGITS))
    else -> CardNumberAnnouncement.Full
}
