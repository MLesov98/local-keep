package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `RecoveryKit.parseQrCode` (`:core:vault`) was written and tested, and then — against this codebase's own
 * "unreachable code hides defects: wire it or delete it" rule — never called from anywhere: its
 * only callers were `RecoveryKitTest`. The QR the Recovery Kit export screen draws
 * (`RecoveryKit.formatForQrCode`) was decoration next to the 24-word grid, unreadable by anything.
 *
 * This guards the wiring that fixes that: `UnlockViewModel.scanRecoveryQrCode` must run a scanned
 * payload through `RecoveryKit.parseQrCode` and put the *parsed, validated mnemonic* into
 * `recoveryWords` — never the raw scanned string. A payload that does not parse (wrong QR, a
 * stray TOTP code, a half-read frame) must be refused with a real error, not silently dropped and
 * not echoed onto the screen — echoing it would put an unrelated QR's contents in front of anyone
 * looking at this vault's unlock screen.
 *
 * Asserted on the source, same technique and limitation as `RecoveryWordsSurviveFailureTest`:
 * `UnlockViewModel` needs a `VaultLockManager` and a `Context` to construct, and this module has no
 * Robolectric. Every `substringAfter`/`substringBefore` anchor is asserted present before it is
 * used to cut: `substringAfter` silently returns the whole receiver when its
 * delimiter is absent, which would make a moved or renamed function read as an empty, "passing"
 * body instead of failing loudly.
 */
class RecoveryQrScanWiringTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/unlock/UnlockViewModel.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    /** The body of `scanRecoveryQrCode`, from its declaration up to the next function's. */
    private fun scanFunctionBody(): String {
        val full = text()
        val startAnchor = "fun scanRecoveryQrCode(raw: String) {"
        assertTrue(startAnchor in full, "expected to find `$startAnchor` in ${source.path} — has it moved or been renamed?")
        val afterStart = full.substringAfter(startAnchor)
        val endAnchor = "fun unlockWithRecoveryKit()"
        assertTrue(
            endAnchor in afterStart,
            "expected `$endAnchor` to still follow scanRecoveryQrCode in ${source.path}",
        )
        return afterStart.substringBefore(endAnchor)
    }

    @Test
    fun `a scanned payload is run through RecoveryKit parseQrCode`() {
        val body = scanFunctionBody()
        assertTrue(
            "RecoveryKit.parseQrCode(raw)" in body,
            "scanRecoveryQrCode must run the scanned payload through RecoveryKit.parseQrCode, " +
                "the parser that was written, tested and never called. Found: $body",
        )
    }

    @Test
    fun `a parsed mnemonic fills the words field, never the raw scan`() {
        val body = scanFunctionBody()
        assertTrue(
            "recoveryWords = mnemonic" in body,
            "the success branch must set recoveryWords to parseQrCode's *validated* result. Found: $body",
        )
        assertFalse(
            "recoveryWords = raw" in body,
            "the raw scanned payload must never be written into recoveryWords directly — only " +
                "RecoveryKit.parseQrCode's validated mnemonic may reach the field, or an " +
                "unparseable code (someone else's QR, a mis-scanned frame) would be typed " +
                "straight into the credential the user is about to submit. Found: $body",
        )
    }

    /**
     * The control. If this ever stopped being true — the error assignment deleted, or moved
     * outside the null branch — the test above would still pass, because it only checks that the
     * literal `recoveryWords = raw` never appears; it says nothing about whether a failed parse is
     * handled at all. This is what actually proves the refusal happens rather than a silent no-op.
     */
    @Test
    fun `an unparseable code is refused with a real error, not silently ignored`() {
        val body = scanFunctionBody()
        assertTrue(
            "mnemonic != null" in body,
            "expected an explicit null check on parseQrCode's result. Found: $body",
        )
        assertTrue(
            "UnlockError.Simple(R.string.unlock_recovery_qr_invalid_message)" in body,
            "a null parse result must set a real, distinct error — not the generic unlock " +
                "failure, and not nothing. Found: $body",
        )
    }
}
