package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test: the card wizard's number and expiry steps advertise `ImeAction.Next` but wire no
 * `keyboardActions`, and each step holds exactly one text field — so pressing the keyboard's Next
 * key runs Compose's default focus search, finds nothing to land on, and does not advance the
 * wizard. Filed as a focus-and-keyboard defect.
 *
 * **What this test proves, and what it does not.** It pins the *structure* of the fix: that each
 * step's field wires a `keyboardActions`. It does NOT observe the keypress landing on a device —
 * `CardWizard` needs a real `ItemEditViewModel`, whose `VaultRepository` opens `AndroidKeyStore` in
 * its constructor, absent on a JVM and unimplemented under Robolectric (this module's own
 * `build.gradle.kts` records that Compose UI tests do not reach idle here at all). The device pass
 * is where the keypress itself is verified.
 *
 * Every assertion here fails against the unfixed source, which is the property a regression test
 * has to have.
 */
class CardWizardImeNextTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/itemedit/CardWizard.kt")

    /** The body of a top-level function, from its signature to its column-0 closing brace. */
    private fun bodyOf(marker: String): String {
        val text = text()
        if (!text.contains(marker)) fail("cannot find marker: $marker")
        return text.substringAfter(marker).substringBefore("\n}\n")
    }

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    @Test
    fun `the number step wires its Next key to advance the wizard`() {
        val body = bodyOf("private fun NumberStep(")
        assertTrue(
            body.contains("keyboardActions"),
            "NumberStep's card-number field advertises ImeAction.Next but wires no keyboardActions. " +
                "With exactly one field in this step, the default focus search Next falls back to " +
                "has nothing to land on, so the key that advertises 'next' does nothing.",
        )
    }

    @Test
    fun `the expiry step wires its Next key to advance the wizard`() {
        val body = bodyOf("private fun ExpiryStep(")
        assertTrue(
            body.contains("keyboardActions"),
            "ExpiryStep's field advertises ImeAction.Next but wires no keyboardActions — the same " +
                "gap as NumberStep, and for the same reason: one field, no default focus target.",
        )
    }
}
