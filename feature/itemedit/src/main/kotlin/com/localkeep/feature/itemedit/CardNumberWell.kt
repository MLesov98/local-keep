package com.localkeep.feature.itemedit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.localkeep.core.ui.theme.LkCardDigitGroups
import com.localkeep.core.ui.theme.LkCardNumberTransformation
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LkTextField

/**
 * Whether a card number well keeps to one line. **Never**: it is the one value in the app that is
 * both long and fixed-length, and a number you cannot read back off the screen is exactly the
 * "critical content" UI/UX spec line 43 says dynamic type must not truncate.
 *
 * One constant read by both entry points -- the editor's masked [SecretField] and the wizard's
 * [CardNumberTextField] -- because the fix has already been applied to one and missed the other
 * once: the editor wrapped at font scale 2.0 from 2026-09-20 (2B-3) while the wizard kept
 * `singleLine`'s default and lost two digits at 411dp and six at 308dp (SCL-005, 2026-10-19).
 */
internal const val CARD_NUMBER_WELL_SINGLE_LINE = false

/**
 * The card wizard's number well: unmasked, grouped by the network's own digit groups, and wrapping.
 *
 * Wrapping happens at the group gaps [LkCardNumberTransformation] inserts, so a number that does not
 * fit one line breaks between groups, never inside one -- including the regrouped-in-fours shape a
 * 17-19 digit number takes.
 */
@Composable
internal fun CardNumberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    groups: LkCardDigitGroups,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    LkTextField(
        // Not masked here, deliberately. This is the moment the user is copying digits off
        // the plastic in front of them, and bullets make that unverifiable -- the value is
        // masked everywhere it is *stored and shown back*, which is where the risk is.
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier.fillMaxWidth(),
        textStyle = LkText.monoValue,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = keyboardActions,
        visualTransformation = LkCardNumberTransformation(groups),
        singleLine = CARD_NUMBER_WELL_SINGLE_LINE,
    )
}

/**
 * The mask the editor's card number well shows while the number is hidden: one bullet per digit,
 * with the same gaps [LkCardNumberTransformation] puts in the revealed number.
 *
 * A plain run of bullets has no break opportunity, so a 19-digit number wrapped wherever the line
 * ran out and stranded its last bullet on a second line (R-L-04) while the revealed number of the
 * same width broke between groups. The digit count and the caret mapping come from the transformation
 * it wraps, so the bullets are still exactly as many as the digits and the caret still lands on the
 * right one.
 */
internal fun cardNumberMaskFor(groups: LkCardDigitGroups): VisualTransformation {
    val grouped = LkCardNumberTransformation(groups)
    return VisualTransformation { text ->
        val spaced = grouped.filter(text)
        TransformedText(
            AnnotatedString(spaced.text.text.map { if (it == ' ') ' ' else '\u2022' }.joinToString("")),
            spaced.offsetMapping,
        )
    }
}
