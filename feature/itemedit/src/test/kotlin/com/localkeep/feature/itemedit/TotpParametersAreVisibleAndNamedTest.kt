package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 2FA form states its own rules, and shows the parameter an import applied
 * (2026-09-20, sweep 4B-3alg and 4B-6).
 *
 * Two bugs, one form:
 * - importing a link carrying `algorithm=SHA256` applied it — DIGITS became 8, PERIOD 60 and the
 *   preview produced the SHA256 code — while the row still read "Different algorithm?", because
 *   `showAlgorithms` is seeded by a `rememberSaveable(state.itemId)` initialiser that runs once,
 *   and on a *new* item that id is fixed before the paste. So the one parameter the preview cannot
 *   confirm stayed hidden on exactly the path its own KDoc promised to open it on.
 * - DIGITS = 7 got a coral ring and nothing else: the a11y node stayed `desc='Digits'`, Save
 *   answered "Check the item fields", and nothing on screen ever said "6 or 8". Colour-only
 *   signalling, which a TalkBack user cannot see at all.
 */
class TotpParametersAreVisibleAndNamedTest {

    private val manual =
        File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt").readText()
            .substringAfter("private fun TotpManualFields(", "")
            .substringBefore("\n@Composable")

    @Test
    fun `an imported non-SHA1 algorithm opens the disclosure that shows it`() {
        assertTrue(manual.isNotEmpty(), "TotpManualFields moved; this test no longer reads it")
        assertTrue(
            "LaunchedEffect(state.totpAlgorithm)" in manual,
            "the disclosure is still seeded once and never re-opened, so an imported SHA256 stays hidden",
        )
        assertTrue("showAlgorithms = true" in manual)
    }

    /**
     * And only opens. A user who opened it by hand keeps it open, and one who sets SHA1 back still
     * needs the control they just used — so there must be no branch closing it.
     */
    @Test
    fun `the disclosure is never closed for the user`() {
        assertTrue(
            "showAlgorithms = false" !in manual,
            "something now closes the algorithm disclosure out from under the user",
        )
    }

    @Test
    fun `an out-of-range digits or period value is named, not just ringed`() {
        assertTrue("if (digitsError || periodError) {" in manual, "the marks still carry no words")
        assertTrue("R.string.itemedit_totp_digits_error" in manual)
        assertTrue("R.string.itemedit_totp_period_error" in manual)
    }

    /**
     * Announced as well as drawn — the ring is the only other signal and a screen reader does not
     * carry it. Same treatment the card number's refusal already gets.
     */
    @Test
    fun `the refusal is announced`() {
        val caption = manual.substringAfter("if (digitsError || periodError) {", "")
            .substringBefore("\n        }")
        assertTrue("Modifier.announce(message)" in caption)
    }

    /**
     * The English rule really is the one the form enforces, so the sentence cannot drift from the
     * predicate above it.
     */
    @Test
    fun `the digits rule as stated matches the digits rule as enforced`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val message = strings.substringAfter("\"itemedit_totp_digits_error\">", "").substringBefore("<")
        assertTrue("6" in message && "8" in message, "the stated rule is \"$message\"")
        assertTrue("it != 6 && it != 8" in manual, "the enforced rule is no longer 6 or 8")
    }
}
