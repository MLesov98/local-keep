package com.localkeep.feature.backup

import com.localkeep.core.health.StrengthAnalyzer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The export passphrase must clear the same strength floor as the master password.
 *
 * Creating a master password (`OnboardingViewModel`) and changing one (`SettingsViewModel`) both
 * score the candidate with [StrengthAnalyzer] against
 * [StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE]. The export passphrase was gated on being non-empty
 * and nothing else — no call site of `StrengthAnalyzer` existed anywhere in `:feature:backup` — so
 * the one credential on the one artifact this app is designed to let leave the device was the one
 * credential in the app with no floor under it. An export is a single-slot container
 * (`VaultExporter` passes `revokedRecoveryBlock()`, so no Recovery Kit travels with it), which
 * makes this passphrase the only way back into that file, and `THREAT-MODEL.md` names the strength
 * floor as exactly the control standing in front of an offline attack on an at-rest container.
 *
 * Two things are asserted, because a floor with no meter is a dead button: the gate, and the live
 * verdict beside the field that lets the user reach it.
 *
 * Source-reading, for the reason [ExportPassphraseGateTest] states — `:feature:backup` declares no
 * Compose UI test dependency, and the scoring deliberately lives in the composable rather than the
 * ViewModel so the passphrase stays in plain `remember` and out of the instance-state Bundle.
 */
class ExportPassphraseStrengthGateTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/backup/BackupSettingsScreen.kt").readText()

    /**
     * A slice bounded at both ends, or a loud failure.
     *
     * `substringAfter`/`substringBefore` return the *whole* receiver when the delimiter is absent,
     * so a renamed anchor silently widens the slice to the rest of the file rather than failing.
     * That matters here: `useIndependentPassphrase` occurs eight times in this screen and
     * `exportPassphrase.isNotEmpty()` twice, so a widened block would still satisfy every
     * `contains` below with the gate it is named for deleted. Checking both delimiters match
     * first is what keeps these assertions about the Export gate.
     */
    private fun bounded(after: String, before: String): String {
        assertTrue(
            source.contains(after),
            "anchor \"$after\" no longer occurs in BackupSettingsScreen.kt, so the slice would " +
                "widen to the whole file and these assertions would pass on unrelated code",
        )
        val tail = source.substringAfter(after)
        assertTrue(
            tail.contains(before),
            "delimiter \"$before\" no longer occurs after \"$after\", so the slice would run " +
                "to end of file and these assertions would pass on unrelated code",
        )
        return tail.substringBefore(before)
    }

    /**
     * The Export gate plus the button that reads it.
     *
     * The terms used to sit inline on `LkPrimaryButton(enabled = ...)`. They were hoisted to a
     * `val exportEnabled` on 2026-09-07 so the two password wells could fire the
     * same action from the keyboard's Done key against the same gate — a button and a well
     * disagreeing about when an export is allowed is the defect that change closed. Slicing both
     * regions keeps every assertion below about the gate's *terms*, and
     * `the export button reads the hoisted gate` is what stops the gate being declared and ignored.
     */
    private fun exportButtonBlock(): String =
        bounded("val exportEnabled", "// Both launchers") +
            bounded("R.string.backup_export_button", "onClick = { viewModel.requestExportDestination(")

    @Test
    fun `the export button is disabled while the chosen passphrase is below the strength floor`() {
        val block = exportButtonBlock()
        assertTrue(
            block.contains("exportPassphraseScore") &&
                block.contains("StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE"),
            "a passphrase that would be refused as a master password must not be accepted as the " +
                "sole credential on an export, which carries no Recovery Kit; found: $block",
        )
    }

    @Test
    fun `the floor is the shared constant, not a number typed here`() {
        // The same reason the constant exists: onboarding, the suggested passphrase and this gate
        // have to move together, or the app suggests something it then refuses.
        assertTrue(
            source.contains("StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE") &&
                !source.contains("exportPassphraseScore >= 3"),
            "the export gate must read the shared floor, not repeat its value",
        )
    }

    @Test
    fun `the floor excludes something a non-empty check would have let through`() {
        // The gate has to be more than a rename of the emptiness one. "Password1" is non-empty and
        // would have exported happily; it is also exactly what zxcvbn exists to catch.
        assertTrue(
            StrengthAnalyzer.score("Password1") < StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE,
            "a guessable passphrase must not clear the floor the export is now gated on",
        )
        assertTrue(
            StrengthAnalyzer.score("7Qv#kLm2xRt9!zBw4Jd") >=
                StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE,
            "and the floor must be reachable, or Export can never be pressed with a passphrase",
        )
    }

    @Test
    fun `the passphrase field shows a live strength verdict`() {
        assertTrue(
            source.contains("LkStrengthMeter(LkStrength.ofScore(exportPassphraseScore))"),
            "without the meter the gate is a button that dims for no stated reason — the four " +
                "bands are the vocabulary the rest of the app already uses for this",
        )
        assertTrue(
            source.contains("R.string.backup_export_passphrase_too_weak"),
            "and the refusal has to say what to do about it",
        )
    }

    @Test
    fun `zxcvbn is not run on the composition thread`() {
        // Control on the wiring, not on the gate: the analyser's matching is superlinear in length
        // and this screen goes to lengths to keep Argon2 off the frame. `produceState` also cancels
        // the previous run on each keystroke rather than queueing one per character.
        assertTrue(
            source.contains("withContext(Dispatchers.Default) { StrengthAnalyzer.score(exportPassphrase) }"),
            "scoring must stay off the frame, the way OnboardingViewModel and SettingsViewModel do",
        )
    }
}
