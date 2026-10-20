package com.localkeep.feature.itemedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A new Card or Identity opened through its wizard is named after the card or the person, not
 * "Login N".
 *
 * `ItemEditUiState` starts as a LOGIN and the preset type arrives a frame later. The screen's
 * auto-title effect ran on that first composition, and a login now always has a non-blank suggestion
 * ("Login 1"), so it wrote "Login 1" into Title. The wizards `return` before the form — and the
 * effect used to sit below those returns — so once the type became CARD or IDENTITY the effect was
 * never composed again to correct it, and the Name-it sheet (`state.title.ifBlank { suggestion }`)
 * offered the stale "Login 1". The flat form fell past the returns and re-keyed, which is why only
 * the wizards showed it.
 *
 * `ItemEditViewModel` cannot be built on a JVM, so [Screen] reproduces the screen's own shape — the
 * auto-title effect, a branch that stops composing the form for a wizard, and the Name-it sheet —
 * around the real [AutoTitleEffect] and [SaveSheet]. [placedBelowTheWizardReturn] is the negative
 * control: with the effect placed the old way the same harness still offers "Login 1", so a green
 * result below is not the harness being unable to see the bug. That the screen really composes the
 * effect before its returns is pinned by [theScreenComposesTheEffectBeforeItsFirstEarlyReturn].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WizardItemsGetTheirOwnTitleTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(ItemEditUiState(isNew = true))

    /** The sheet is raised from the wizard's Review step, long after the type and the effect settled. */
    private var reviewing by mutableStateOf(false)

    /** Mirrors the wizard path: the VM's title write lands in state, the sheet reads that state. */
    @Composable
    private fun Screen(effectFirst: Boolean) {
        if (effectFirst) AutoTitleEffect(state, titleTyped = false) { state = state.copy(title = it) }
        val inWizard = state.type == ItemType.CARD || state.type == ItemType.IDENTITY
        if (inWizard) {
            // The wizard's own screen; the Name-it sheet it raises on Review.
            if (reviewing) SaveSheet(state = state, onDismiss = {}, onSave = {})
            return
        }
        if (!effectFirst) AutoTitleEffect(state, titleTyped = false) { state = state.copy(title = it) }
    }

    private fun offered(): String {
        reviewing = true
        compose.waitForIdle()
        return compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
    }

    private fun startAsLogin(effectFirst: Boolean) {
        compose.setContent { LocalKeepTheme { Screen(effectFirst) } }
        compose.waitForIdle()
    }

    @Test
    fun aNewCardIsOfferedItsCardLabelNotLoginN() {
        startAsLogin(effectFirst = true)
        // The control: a new login really is named "Login 1" first, which is the value that leaked.
        assertEquals("Login 1", state.title)

        state = state.copy(type = ItemType.CARD, cardNumber = "4111 1111 1111 1111")
        compose.waitForIdle()

        assertEquals("Visa •••• 1111", offered())
    }

    @Test
    fun aNewIdentityIsOfferedItsOwnersName() {
        startAsLogin(effectFirst = true)
        assertEquals("Login 1", state.title)

        state = state.copy(type = ItemType.IDENTITY, identityFirstName = "Ada", identityLastName = "Lovelace")
        compose.waitForIdle()

        assertEquals("Ada Lovelace", offered())
    }

    @Test
    fun aNewLoginStillOpensNamedLoginN() {
        startAsLogin(effectFirst = true)
        assertEquals("Login 1", state.title)

        state = state.copy(existingLoginTitles = setOf("Login 1"), title = "")
        compose.waitForIdle()

        assertEquals("Login 2", state.title)
    }

    /** The harness can see the bug: the old placement offers "Login 1" for a card. */
    @Test
    fun placedBelowTheWizardReturn() {
        startAsLogin(effectFirst = false)
        assertEquals("Login 1", state.title)

        state = state.copy(type = ItemType.CARD, cardNumber = "4111 1111 1111 1111")
        compose.waitForIdle()

        assertEquals("Login 1", offered())
    }

    @Test
    fun theScreenComposesTheEffectBeforeItsFirstEarlyReturn() {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
        val body = src.substring(src.indexOf("fun ItemEditScreen("))
        val effect = body.indexOf("AutoTitleEffect(")
        val firstReturn = Regex("\\n\\s+return\\n").find(body)?.range?.first ?: -1
        assertTrue(firstReturn > 0, "no early return found in ItemEditScreen — region wrong?")
        assertTrue(effect in 1 until firstReturn, "the auto-title effect is composed after an early return")
    }
}
