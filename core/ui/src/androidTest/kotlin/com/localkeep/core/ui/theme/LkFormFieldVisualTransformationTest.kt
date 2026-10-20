package com.localkeep.core.ui.theme

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Pins [LkFormField]'s `visualTransformation` seam — the fix `5-1` needed to mask the BIP-39 25th
 * word without routing a capture-screen field through `SecretField`'s re-auth-gated reveal.
 *
 * Before this parameter existed, [LkFormField] built [LkTextField] without passing one through at
 * all, so the recovery-phrase passphrase field was unmaskable from `core/ui` up: the 25th word — the
 * piece that makes a stolen seed phrase useless without it — rendered in clear regardless of what a
 * caller asked for.
 *
 * Read via the field's own `GetTextLayoutResult` semantics action rather than a screenshot, for the
 * same reason [LkPasswordPlaceholderTest] does: every window in this app carries `FLAG_SECURE`, so a
 * device screenshot of a real screen is black, and this is what a screen reader's rendering pass —
 * and TalkBack's braille output — actually sees.
 */
@RunWith(AndroidJUnit4::class)
class LkFormFieldVisualTransformationTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val VALUE = "hello"
        const val MASKED_LABEL = "Secret"
        const val PLAIN_LABEL = "Plain"
    }

    // The field's own contentDescription, not a testTag on LkFormField's outer Column — the
    // GetTextLayoutResult action lives on LkTextField's BasicTextField node, which is what
    // LkTextField's own doc describes as carrying the label as its content description.
    private fun renderedText(label: String): String {
        val results = mutableListOf<TextLayoutResult>()
        val action =
            rule.onNodeWithContentDescription(label).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult]
                .action
        checkNotNull(action) { "the field exposed no GetTextLayoutResult action" }
        action(results)
        return results.single().layoutInput.text.text
    }

    @Test
    fun aPasswordVisualTransformationMasksTheFieldsRenderedText() {
        rule.setContent {
            LocalKeepTheme {
                LkFormField(
                    value = VALUE,
                    onValueChange = {},
                    label = MASKED_LABEL,
                    visualTransformation = PasswordVisualTransformation('*'),
                )
            }
        }

        assertEquals(
            "*".repeat(VALUE.length),
            renderedText(MASKED_LABEL),
            "LkFormField did not honour the visualTransformation it was given",
        )
    }

    @Test
    fun theDefaultLkFormFieldRendersItsValueInClear() {
        rule.setContent {
            LocalKeepTheme {
                LkFormField(
                    value = VALUE,
                    onValueChange = {},
                    label = PLAIN_LABEL,
                )
            }
        }

        assertEquals(VALUE, renderedText(PLAIN_LABEL), "the default LkFormField must stay unmasked")
    }
}
