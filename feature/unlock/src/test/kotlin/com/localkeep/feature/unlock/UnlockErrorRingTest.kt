package com.localkeep.feature.unlock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The coral ring on the password and Recovery Kit wells belongs to errors that name the well.
 *
 * It was set from `state.error != null`, so a biometric cool-off, a sensor that would not complete
 * or an unreadable vault circled a password field the user had not typed in, and the field read as
 * the thing that was wrong. `LkColors`' rule for coral is "is the user's data less safe than they
 * believe", and a ring on an untouched input says the input is at fault.
 */
class UnlockErrorRingTest {

    @Test
    fun `a wrong password rings the well`() {
        assertTrue(UnlockError.Simple(R.string.unlock_error_wrong_password).namesTheField())
    }

    @Test
    fun `a refused recovery kit rings the words well`() {
        assertTrue(UnlockError.Simple(R.string.unlock_recovery_failed_message).namesTheField())
        assertTrue(UnlockError.Simple(R.string.unlock_recovery_qr_invalid_message).namesTheField())
    }

    @Test
    fun `the password lockout rings the well it is waiting on`() {
        assertTrue(UnlockError.RetryAfter(R.plurals.unlock_retry_after_seconds, 12).namesTheField())
    }

    @Test
    fun `a biometric cool-off does not ring the password well`() {
        assertFalse(UnlockError.Simple(R.string.unlock_error_biometric_lockout).namesTheField())
        assertFalse(UnlockError.RetryAfter(R.plurals.unlock_biometric_locked_retry, 30).namesTheField())
    }

    @Test
    fun `an incomplete or unavailable biometric does not ring the password well`() {
        assertFalse(UnlockError.Simple(R.string.unlock_error_biometric_incomplete).namesTheField())
        assertFalse(UnlockError.Simple(R.string.unlock_error_biometric_unavailable).namesTheField())
        assertFalse(UnlockError.Simple(R.string.unlock_biometric_panic_lock_message).namesTheField())
    }

    @Test
    fun `an unreadable vault does not ring the password well`() {
        assertFalse(
            UnlockError.Actionable(
                R.string.unlock_error_vault_unreadable,
                R.string.unlock_error_vault_unreadable_action,
            ).namesTheField(),
        )
    }

    @Test
    fun `no error draws no ring`() {
        assertFalse((null as UnlockError?).namesTheField())
    }

    /** A panic block that survived a restart must still explain the missing button. */
    @Test
    fun `the persisted panic suspension keeps the explanation after a restart`() {
        assertTrue(panicExplainsMissingBiometric(blockedThisProcess = true, suspendedOnDisk = false))
        assertTrue(
            panicExplainsMissingBiometric(blockedThisProcess = false, suspendedOnDisk = true),
            "the in-memory block is gone after process death; the persisted flag must carry it",
        )
        assertFalse(
            panicExplainsMissingBiometric(blockedThisProcess = false, suspendedOnDisk = false),
            "control: nothing panicked, nothing to explain",
        )
        assertEquals(false, panicExplainsMissingBiometric(false, false))
    }
}
