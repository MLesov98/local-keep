package com.localkeep.core.ui.theme

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A masked field must be masked to the *screen reader*, not only to the screen.
 *
 * `SemanticsProperties.Password` is the property TalkBack consults before speaking an editable's
 * characters aloud — the Compose descendant of `InputType.TYPE_TEXT_VARIATION_PASSWORD`, and the
 * reason Android's "Speak passwords" setting (off by default) has something to switch on. Without
 * it, a blind user typing their master password can have it read back aloud, within earshot of
 * whoever is nearby.
 *
 * ### Why this test exists, given no app code sets it
 *
 * A 2026-09-07 accessibility audit searched the tree for `semantics { password() }`, found zero
 * call sites, and filed the absence as a critical leak. That reasoning was wrong, and this test is
 * what settled it: **Compose's own `BasicTextField` sets the property from
 * `PasswordVisualTransformation`**, so `LkPasswordField` has always been marked correctly and the
 * app never needed a call of its own. The fix drafted against that finding was reverted once these
 * three cases passed against unmodified `LkFields.kt` — measured, not argued: the property is
 * present with zero occurrences of `password()` anywhere in `core/ui`.
 *
 * The failure mode worth naming, because it nearly landed a redundant change into a security
 * component: *an absent call site is not an absent property.* Grep can only see what the app
 * writes; a semantics tree is what the app and the framework write together. The only way to know
 * which is to compose the thing and look at the node — which is what this does.
 *
 * So this pins framework behaviour the app depends on but does not implement, and that is exactly
 * what makes it worth keeping. It goes red if anyone swaps `PasswordVisualTransformation` for a
 * hand-rolled masking transformation (the obvious refactor — bullets are trivial to draw yourself,
 * and the semantics contract is invisible while you do it), or if a Compose upgrade changes the
 * rule. Either would silently un-mask every secret in the app to TalkBack while the screen kept
 * showing bullets and every other test stayed green.
 *
 * The adjacent test that does *not* cover this: `LkFormFieldVisualTransformationTest` asserts a
 * masked field renders as bullets, and passes — but it reads `GetTextLayoutResult`, the rendered
 * layout. Spoken output comes from `EditableText`. Two different surfaces; that one proves the
 * pixels, this one proves the announcement.
 *
 * ### Why Robolectric rather than androidTest
 *
 * Same reasoning as [LkButtonTouchTargetFloorTest]: the presence of a semantics key on a node is a
 * property of one composition, not of a device, so it belongs where it runs on every push
 * (`./gradlew test`) rather than only when a phone is attached.
 */@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkPasswordSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    /**
     * The node a typing user's screen reader is actually focused on.
     *
     * `LkPasswordField`'s `modifier` lands on the outer well, and `password()` is set on the inner
     * `BasicTextField` — so a `testTag` on the composable selects the wrapper, whose semantics do
     * not carry the property. The first draft of this test asserted on that wrapper: the masked
     * case failed correctly, but the two negative cases *passed vacuously*, because a property
     * absent from the wrapper is absent whether or not the field below it is marked. Selecting by
     * `hasSetTextAction()` picks the editable itself, which is both the node under test and the
     * node TalkBack reads from.
     */
    private fun editable() = compose.onNode(hasSetTextAction(), useUnmergedTree = true)

    @Test
    fun `a masked password field is marked password to the accessibility tree`() {
        compose.setContent {
            LocalKeepTheme {
                LkPasswordField(
                    value = "correct horse battery staple",
                    onValueChange = {},
                    label = "Master password",
                    revealed = false,
                )
            }
        }
        compose.waitForIdle()
        editable().assert(isPassword)
    }

    @Test
    fun `a revealed password field is not marked password`() {
        // Not an oversight, and the reason this case is pinned rather than left implicit: a reveal
        // is user-initiated and deliberate, and the app already speaks a revealed secret in full
        // (see `secretFieldSemantics`). A field that stayed marked `password()` after the user asked
        // to see it would be inconsistent with that, and would withhold from a screen-reader user
        // exactly what a sighted user just chose to display.
        compose.setContent {
            LocalKeepTheme {
                LkPasswordField(
                    value = "correct horse battery staple",
                    onValueChange = {},
                    label = "Master password",
                    revealed = true,
                )
            }
        }
        compose.waitForIdle()
        editable().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }

    @Test
    fun `an ordinary text field is not marked password`() {
        // The control, and it earns its place twice over. A change that marked *every* field
        // `password()` would satisfy the first case here while silencing a screen reader across the
        // whole app — invisible to a positive assertion on its own. It also caught this test's own
        // first draft: selecting the node by `testTag` read the outer well rather than the
        // editable, and on that node the two negative cases passed vacuously, because a property
        // absent from a wrapper is absent whether or not the field beneath it carries it.
        compose.setContent {
            LocalKeepTheme {
                LkTextField(
                    value = "ada@example.com",
                    onValueChange = {},
                    label = "Username",
                )
            }
        }
        compose.waitForIdle()
        editable().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }
}
