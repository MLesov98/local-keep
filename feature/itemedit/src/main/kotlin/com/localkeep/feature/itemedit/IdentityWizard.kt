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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
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
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.theme.LkFieldLabel
import com.localkeep.core.ui.theme.LkFolderField
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkPrimaryButton
import com.localkeep.core.ui.theme.LkScreen
import com.localkeep.core.ui.theme.LkSecondaryButton
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkStepRail
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.lkEnter

/**
 * Typing an identity in by hand, one group per screen.
 *
 * The route used to fall through to the ordinary form: fourteen wells under four section labels, every
 * one optional, which is the part of that form nobody could take in at a glance — and the tile that
 * led to it advertised exactly that. The four groups the form already had become the four steps, so
 * there is no new way of cutting the same fields to learn, and a fifth screen checks the result.
 *
 * **Nothing here is required, so the primary says what it will do.** It reads "Skip" while the step
 * is empty and "Continue" once it holds something. A permanently-enabled "Continue" over an empty
 * step claims the user did something; a disabled one would stop them, and every field is optional.
 *
 * **New identities only**, like [CardWizard]: editing a stored identity opens the ordinary form, since
 * someone correcting a phone number must not be walked through four unrelated screens to reach it.
 *
 * **No "Enter everything at once".** The card has that exit because the flat form is its fallback;
 * here the flat form is what this replaces.
 *
 * State lives entirely in the ViewModel the flat form already uses, so nothing is migrated and this
 * file adds no persistence of its own. The keyboard choreography and the pinned action row are the
 * card wizard's, unchanged — see CardWizardKeyboard.kt.
 */
@Composable
internal fun IdentityWizard(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    onLeave: () -> Unit,
    onChangeFolder: () -> Unit,
    /**
     * Which step is showing, owned by [ItemEditScreen] for the reason [CardWizard.stepState] gives:
     * choosing a folder replaces this whole screen, and a step held locally would come back reset.
     */
    stepState: MutableIntState,
) {
    val c = LkTheme.colors
    var step by stepState
    val advance = { if (step < STEP_COUNT - 1) step++ }

    val kb = rememberCardWizardKeyboard(step, STEP_REVIEW)
    val typing by kb.rememberTyping()
    val scroll = rememberScrollState()

    // The system Back gesture walks the steps, as the on-screen button does. At step 0 this disables
    // itself and the editor's own discard prompt is what Back reaches.
    BackHandler(enabled = step > 0) { step-- }

    // Whichever field opens the step, focused when the user *advanced* with the keyboard already up.
    // Not on arrival (the keyboard must not rise over a screen nobody has looked at yet), and not
    // when Continue was tapped with the keyboard down: focusing then would raise it unasked. See the
    // long note on the same effect in [CardWizard] for why it waits two frames and no longer.
    val stepFocus = remember { FocusRequester() }
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        val keyboardWasUp = typing
        if (!arrived) {
            arrived = true
            return@LaunchedEffect
        }
        if (step == STEP_REVIEW || !keyboardWasUp) return@LaunchedEffect
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

    val hasDetail = identityHasAnyDetail(state)
    LkScreen(accent = c.jade, auraAlpha = 0.09f, auraBias = 0.5f) {
        WizardFrame(
            keyboard = kb,
            scroll = scroll,
            step = step,
            railGap = LkSpace.lg,
            rail = {
                LkStepRail(
                    stepIndex = step,
                    stepCount = STEP_COUNT,
                    progressText = stringResource(R.string.itemedit_card_wizard_progress, step + 1, STEP_COUNT),
                    progressDescription = stringResource(
                        R.string.itemedit_card_wizard_progress_a11y,
                        step + 1,
                        STEP_COUNT,
                    ),
                )
            },
            actions = {
                IdentityWizardActions(
                    step = step,
                    stepIsBlank = identityStepIsBlank(step, state),
                    hasDetail = hasDetail,
                    isSaving = state.isSaving,
                    onAdvance = {
                        if (step == STEP_REVIEW) {
                            if (viewModel.readyToName()) showSaveSheet = true
                        } else {
                            advance()
                        }
                    },
                    onBack = { if (step == 0) onLeave() else step-- },
                    keyboard = kb,
                    typing = typing,
                )
            },
        ) {
            IdentityWizardHeading(step, hasDetail)
            when (step) {
                STEP_NAME -> NameStep(state, viewModel, stepFocus, onNext = advance)
                STEP_IDS -> IdsStep(state, viewModel, stepFocus)
                STEP_CONTACT -> ContactStep(state, viewModel, stepFocus, onNext = advance)
                STEP_ADDRESS -> AddressStep(state, viewModel, stepFocus, onNext = advance)
                else -> IdentityReviewStep(state, hasDetail, onChangeFolder)
            }
            // Step 1's one quiet exit while the keyboard is up: the row at the end of the scroll
            // column, not a pinned button (116dp of the little room a keyboard leaves).
            if (step == STEP_NAME && typing) {
                CardWizardTypingExits(
                    keyboard = kb,
                    scroll = scroll,
                    enabled = !state.isSaving,
                    onDiscard = onLeave,
                    onEnterFullForm = null,
                )
            }
        }
    }
}

private const val STEP_NAME = 0
private const val STEP_IDS = 1
private const val STEP_CONTACT = 2
private const val STEP_ADDRESS = 3
private const val STEP_REVIEW = 4
private const val STEP_COUNT = 5

/** Whether every well on [step] is empty — the primary then reads Skip, not Continue. */
internal fun identityStepIsBlank(step: Int, state: ItemEditUiState): Boolean =
    identityStepValues(step, state)?.all(String::isBlank) ?: false

/** The wells a step asks for, or null for Review, which asks for nothing. */
private fun identityStepValues(step: Int, state: ItemEditUiState): List<String>? = when (step) {
    STEP_NAME -> listOf(state.identityFirstName, state.identityLastName, state.identityCompany)
    STEP_IDS -> listOf(state.identitySsn, state.identityPassport, state.identityLicense)
    STEP_CONTACT -> listOf(state.identityEmail, state.identityPhone)
    STEP_ADDRESS -> listOf(
        state.identityAddressLine1,
        state.identityAddressLine2,
        state.identityAddressCity,
        state.identityAddressState,
        state.identityAddressZip,
        state.identityAddressCountry,
    )
    else -> null
}

/**
 * Whether the user has typed anything at all. A title-only identity is a row that answers no
 * question, so Review will not save one: the photo route is the way to store a document with no
 * typed details, and it never reaches this screen.
 */
internal fun identityHasAnyDetail(state: ItemEditUiState): Boolean =
    (STEP_NAME until STEP_REVIEW).any { !identityStepIsBlank(it, state) }

/**
 * The heading for [step]. Review with nothing typed says so rather than "Ready to save": the
 * sentence under it already explains that there is nothing to store, and a heading promising a save
 * the primary action will refuse contradicts it.
 */
@androidx.annotation.StringRes
internal fun identityHeadingRes(step: Int, hasDetail: Boolean): Int = when (step) {
    STEP_NAME -> R.string.itemedit_identity_personal_details_heading
    STEP_IDS -> R.string.itemedit_identity_gov_ids_heading
    STEP_CONTACT -> R.string.itemedit_identity_contact_heading
    STEP_ADDRESS -> R.string.itemedit_identity_address_heading
    else ->
        if (hasDetail) {
            R.string.itemedit_card_wizard_review_title
        } else {
            R.string.itemedit_identity_wizard_review_empty_title
        }
}

@Composable
private fun IdentityWizardHeading(step: Int, hasDetail: Boolean) {
    val c = LkTheme.colors
    val title = stringResource(identityHeadingRes(step, hasDetail))
    val body = when {
        step != STEP_REVIEW -> stringResource(R.string.itemedit_identity_wizard_body)
        hasDetail -> stringResource(R.string.itemedit_card_wizard_review_body)
        else -> stringResource(R.string.itemedit_identity_wizard_review_empty)
    }
    Column(Modifier.fillMaxWidth().lkEnter(), verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = LkText.display,
            color = c.textStrong,
        )
        Text(body, style = LkText.bodyQuiet, color = c.textMuted)
    }
}

@Composable
private fun NameStep(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    focus: FocusRequester,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Field(
            state.identityFirstName,
            viewModel::onIdentityFirstNameChanged,
            stringResource(R.string.itemedit_identity_first_name_label),
            modifier = Modifier.focusRequester(focus),
        )
        Field(
            state.identityLastName,
            viewModel::onIdentityLastNameChanged,
            stringResource(R.string.itemedit_identity_last_name_label),
        )
        BoxedField(
            state.identityCompany,
            viewModel::onIdentityCompanyChanged,
            stringResource(R.string.itemedit_identity_company_label),
            Modifier.fillMaxWidth(),
            imeAction = ImeAction.Next,
            onImeAction = onNext,
        )
    }
}

@Composable
private fun IdsStep(state: ItemEditUiState, viewModel: ItemEditViewModel, focus: FocusRequester) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        SecretField(
            state.identitySsn,
            viewModel::onIdentitySsnChanged,
            stringResource(R.string.itemedit_identity_ssn_label),
            "ssn",
            state,
            viewModel,
            modifier = Modifier.focusRequester(focus),
        )
        SecretField(
            state.identityPassport,
            viewModel::onIdentityPassportChanged,
            stringResource(R.string.itemedit_identity_passport_field_label),
            "passport",
            state,
            viewModel,
        )
        SecretField(
            state.identityLicense,
            viewModel::onIdentityLicenseChanged,
            stringResource(R.string.itemedit_identity_license_field_label),
            "license",
            state,
            viewModel,
        )
    }
}

@Composable
private fun ContactStep(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    focus: FocusRequester,
    onNext: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Field(
            state.identityEmail,
            viewModel::onIdentityEmailChanged,
            stringResource(R.string.itemedit_identity_email_label),
            KeyboardType.Email,
            modifier = Modifier.focusRequester(focus),
        )
        BoxedField(
            state.identityPhone,
            viewModel::onIdentityPhoneChanged,
            stringResource(R.string.itemedit_detail_identity_phone_label),
            Modifier.fillMaxWidth(),
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Next,
            onImeAction = onNext,
        )
    }
}

@Composable
private fun AddressStep(
    state: ItemEditUiState,
    viewModel: ItemEditViewModel,
    focus: FocusRequester,
    onNext: () -> Unit,
) {
    // Six wells in one column rather than State beside ZIP: at a large font the half-width labels
    // ("State / region", "ZIP / postal code") wrap or clip, and a step that scrolls costs nothing the
    // keyboard's Next key does not already pay for.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
        Field(
            state.identityAddressLine1,
            viewModel::onIdentityAddressLine1Changed,
            stringResource(R.string.itemedit_identity_address_line1_label),
            modifier = Modifier.focusRequester(focus),
        )
        Field(
            state.identityAddressLine2,
            viewModel::onIdentityAddressLine2Changed,
            stringResource(R.string.itemedit_identity_address_line2_label),
        )
        Field(
            state.identityAddressCity,
            viewModel::onIdentityAddressCityChanged,
            stringResource(R.string.itemedit_identity_address_city_label),
        )
        Field(
            state.identityAddressState,
            viewModel::onIdentityAddressStateChanged,
            stringResource(R.string.itemedit_identity_address_state_label),
        )
        Field(
            state.identityAddressZip,
            viewModel::onIdentityAddressZipChanged,
            stringResource(R.string.itemedit_identity_address_zip_label),
        )
        BoxedField(
            state.identityAddressCountry,
            viewModel::onIdentityAddressCountryChanged,
            stringResource(R.string.itemedit_identity_address_country_label),
            Modifier.fillMaxWidth(),
            imeAction = ImeAction.Next,
            onImeAction = onNext,
        )
    }
}

/**
 * What will be stored, one line per group that has anything in it.
 *
 * Four lines, not fourteen: Review is a check, and the wall this flow replaces is not improved by
 * repeating it read-only. The ID numbers are never echoed — the line names *which* were entered, so
 * the screen confirms a number is there without being a second place it is drawn in the clear.
 */
@Composable
private fun IdentityReviewStep(state: ItemEditUiState, hasDetail: Boolean, onChangeFolder: () -> Unit) {
    if (hasDetail) {
        val name = listOf(state.identityFirstName, state.identityLastName)
            .map(String::trim).filter(String::isNotEmpty).joinToString(" ")
        val personal = listOf(name, state.identityCompany.trim()).filter(String::isNotEmpty).joinToString(" · ")
        val ids = listOf(
            state.identitySsn to R.string.itemedit_identity_ssn_label,
            state.identityPassport to R.string.itemedit_identity_passport_field_label,
            state.identityLicense to R.string.itemedit_identity_license_field_label,
        ).filter { it.first.isNotBlank() }.map { stringResource(it.second) }.joinToString(", ")
        val contact = listOf(state.identityEmail.trim(), state.identityPhone.trim())
            .filter(String::isNotEmpty).joinToString(" · ")
        val address = listOf(
            state.identityAddressLine1,
            state.identityAddressLine2,
            state.identityAddressCity,
            state.identityAddressState,
            state.identityAddressZip,
            state.identityAddressCountry,
        ).map(String::trim).filter(String::isNotEmpty).joinToString(", ")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.md)) {
            ReviewLine(R.string.itemedit_identity_personal_details_heading, personal)
            ReviewLine(R.string.itemedit_identity_gov_ids_heading, ids)
            ReviewLine(R.string.itemedit_identity_contact_heading, contact)
            ReviewLine(R.string.itemedit_identity_address_heading, address)
        }
    }
    // The wizard's only filing control, as on the card: without it an identity typed in by hand
    // would be the one item that could not be put in a folder at the moment it was created.
    LkFolderField(
        selectedName = state.folders.firstOrNull { it.id == state.folderId }?.name,
        onChange = onChangeFolder,
    )
}

@Composable
private fun ReviewLine(@androidx.annotation.StringRes label: Int, value: String) {
    if (value.isEmpty()) return
    val c = LkTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
        LkFieldLabel(stringResource(label))
        Text(value, style = LkText.body, color = c.textPrimary)
    }
}

@Composable
private fun IdentityWizardActions(
    step: Int,
    stepIsBlank: Boolean,
    hasDetail: Boolean,
    isSaving: Boolean,
    onAdvance: () -> Unit,
    onBack: () -> Unit,
    keyboard: CardWizardKeyboard,
    typing: Boolean,
) {
    val saveBlocked = step == STEP_REVIEW && !hasDetail
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
                    stepIsBlank -> stringResource(R.string.itemedit_identity_wizard_skip)
                    else -> stringResource(R.string.itemedit_card_wizard_continue)
                },
                onClick = onAdvance,
                enabled = !saveBlocked && !isSaving,
                haptic = if (step == STEP_REVIEW) Haptic.SUCCESS else Haptic.SELECT,
                modifier = if (saveBlocked && !isSaving) {
                    Modifier.fillMaxWidth().labelled(stringResource(R.string.itemedit_identity_wizard_save_disabled_a11y))
                } else {
                    Modifier.fillMaxWidth()
                },
            )
        },
        secondaries = {
            // Discard on the first step, Back on the rest — the card's reasoning: on step 1 the
            // second control is the one that throws the work away, so it must not be the brightest
            // thing on a screen whose primary has nothing to say yet.
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
        },
    )
}
