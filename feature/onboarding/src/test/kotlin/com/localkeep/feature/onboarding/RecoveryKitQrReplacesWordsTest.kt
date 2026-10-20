package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Show QR code instead" has to actually replace the words.
 *
 * 3A-F34, device 2026-09-20: with the QR up, the tree still contained `RECOVERY WORDS` and all 24
 * words *above* `desc='Recovery Kit QR code'`. The card was appended, not substituted, so the only
 * effect of a control whose label says "instead" was a longer scroll. Both screens that offer it —
 * the standalone Recovery Kit screen and the onboarding step — share the string, so both are fixed
 * and both are pinned here.
 *
 * The rule is a pure function so it can be driven without a `RecoveryKitViewModel`; the call sites
 * are asserted on the source, because what would rot is one screen being reverted while the other
 * is not, and that is a fact about the files rather than about the function.
 */
class RecoveryKitQrReplacesWordsTest {

    @Test
    fun `the QR replaces the words`() {
        assertFalse(
            mnemonicGridVisible(qrVisible = true, qrRendered = true),
            "the 24 words are still on screen behind a rendered QR — nothing is shown \"instead\"",
        )
    }

    @Test
    fun `the words are there whenever the QR is not`() {
        assertTrue(mnemonicGridVisible(qrVisible = false, qrRendered = false))
        assertTrue(mnemonicGridVisible(qrVisible = false, qrRendered = true))
    }

    /**
     * A QR that failed to encode replaces nothing: the error is shown beside the words, not instead
     * of them. Taking the 24 words away and leaving a message about a missing image would remove
     * the one thing the screen exists for.
     */
    @Test
    fun `a QR that would not render keeps the words`() {
        assertTrue(
            mnemonicGridVisible(qrVisible = true, qrRendered = false),
            "a failed QR hid the recovery words behind its own error",
        )
    }

    @Test
    fun `both screens that offer the QR go through the rule`() {
        listOf(
            "src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt",
            "src/main/kotlin/com/localkeep/feature/onboarding/OnboardingScreen.kt",
        ).forEach { path ->
            val source = File(path).readText()
            val guard = source.indexOf("mnemonicGridVisible(qrVisible =")
            assertTrue(
                guard >= 0,
                "${File(path).name} draws its word grid unconditionally again, so the QR is " +
                    "appended below it rather than shown instead of it.",
            )
            val grid = source.indexOf("MnemonicGrid(", guard)
            assertTrue(
                grid > guard && source.substring(guard, grid).count { it == '\n' } <= 2,
                "${File(path).name}: the guard must be the one wrapping the grid it precedes.",
            )
        }
    }
}
