package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The detail screen says which account a 2FA code belongs to.
 *
 * 2026-09-15: `VisualQAIssuer` / `qa@example.com` were typed at enrolment, round-tripped through the
 * vault, and shown in the *editor's* Two-factor card (`shots/065`) — while the detail screen, the
 * one people open to read a code, showed the code and nothing else (F-20). An item holding the wrong
 * issuer is indistinguishable from one holding the right one until the code is rejected.
 *
 * FAILS WITHOUT THE FIX: `TotpCard` drew the code, the ring and the refresh caption, and read
 * neither `totpIssuer` nor `totpAccount`.
 */
class TotpCardNamesItsFactorTest {

    private val card = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt")
        .readText()
        .substringAfter("private fun TotpCard(", "")
        .substringBefore("private fun rememberTotpFraction(")

    @Test
    fun `the card shows the issuer and the account it was enrolled for`() {
        assertTrue("state.totpIssuer" in card, "the 2FA card does not name its issuer")
        assertTrue("state.totpAccount" in card, "the 2FA card does not name its account")
    }

    /** Built the way the editor builds it, so the two cards cannot drift apart. */
    @Test
    fun `it is joined the same way the editor joins it`() {
        assertTrue("""joinToString(" · ")""" in card)
        assertTrue("filter(String::isNotBlank)" in card)
        val editor = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
            .readText()
            .substringAfter("private fun LoginTotpSection(", "")
            .substringBefore("private fun CoordinateFields(")
        assertTrue("""joinToString(" · ")""" in editor)
    }

    /** Nothing drawn for a factor that carries neither — an empty middot is not information. */
    @Test
    fun `a factor with neither is not announced as an empty line`() {
        assertTrue("if (issuerAndAccount.isNotEmpty())" in card)
    }
}
