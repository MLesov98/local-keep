package com.localkeep.core.vault

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A password change says it voided the Recovery Kit only when there was a kit to void.
 *
 * Device, 2026-10-04 (API 36, foss release): with Settings › Recovery Kit reading "Not saved yet",
 * changing the master password still answered "Your old Recovery Kit no longer works — make a new
 * one under Security → Recovery Kit." Reproduced three times. `rotateDekUnderSlotZero` reported
 * `oldRecoveryKitVoided = true` unconditionally, and Settings appends that sentence exactly when
 * the result says so — so the first change after a kit is made is right, and every change after
 * it tells the user about a kit the previous one had already revoked.
 *
 * The container answers the question itself: a real kit block carries its magic, a revoked one is
 * noise ([VaultFileCodec.recoveryWrappedDek] is null), the same read `restoreVaultFile` already
 * makes for the same flag. Read from the bytes *before* the rotation overwrites them.
 *
 * Source assertion for the reason [BlobReKeyJournalFailureTest] gives — the rotation needs a real
 * container and Context, which is what the instrumented suite is for; the behaviour itself is
 * pinned there by `passwordChange_withNoKitReportsNoneVoided` and
 * `passwordChange_aSecondChangeReportsNoKitLeftToVoid` in `VaultLockManagerInstrumentedTest`.
 */
class PasswordChangeReportsOnlyARealKitTest {

    private val source = java.io.File("src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt")

    private fun rotationBody(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        val text = source.readText()
        val start = text.indexOf("private fun rotateDekUnderSlotZero(")
        assertTrue(start >= 0, "VaultLockManager no longer declares rotateDekUnderSlotZero(")
        val end = text.indexOf("return ChangeResult(", start)
        assertTrue(end > start, "rotateDekUnderSlotZero no longer returns a ChangeResult")
        return text.substring(start, text.indexOf(")", text.indexOf("oldRecoveryKitVoided", end)) + 1)
    }

    @Test
    fun `the kit is reported voided only when the container held one`() {
        val body = rotationBody()
        assertFalse(
            "oldRecoveryKitVoided = true" in body,
            "every password change claims to have voided a Recovery Kit, including the ones where " +
                "none existed",
        )
        val read = body.indexOf("VaultFileCodec.recoveryWrappedDek(currentBytes)")
        assertTrue(read >= 0, "the rotation must ask the pre-rotation container whether it held a kit")
        assertTrue(
            read < body.indexOf("VaultFileCodec.revokedRecoveryBlock()"),
            "read it before the revocation is built, from the bytes the rotation replaces",
        )
    }
}
