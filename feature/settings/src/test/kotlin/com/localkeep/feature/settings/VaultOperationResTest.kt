package com.localkeep.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the mapping from VaultLockManager's operation literals to their localised phrases.
 *
 * The literals live in core/vault as deliberately-English exception text (they never reach the
 * UI as-is); [vaultOperationRes] is what keeps `settings_error_vault_session_ended` fully
 * translated. Before this mapping existed, the auto-lock error rendered the raw literal inside a
 * translated sentence — "Le coffre s'est verrouillé en essayant de save your changes." — in
 * every non-English locale. If VaultLockManager gains a new operation, this test's list and the
 * mapping (plus the settings_op_* rows in every settings strings.xml) must grow together.
 */
class VaultOperationResTest {

    private val knownOperations = mapOf(
        "prepare the Recovery Kit" to R.string.settings_op_prepare_recovery_kit,
        "save the Recovery Kit" to R.string.settings_op_save_recovery_kit,
        "save your changes" to R.string.settings_op_save_changes,
        "set up duress" to R.string.settings_op_set_up_duress,
        "remove duress" to R.string.settings_op_remove_duress,
        "inspect duress" to R.string.settings_op_inspect_duress,
        "update the encryption settings" to R.string.settings_op_update_encryption_settings,
        "change the master password" to R.string.settings_op_change_master_password,
        "rotate the encryption key" to R.string.settings_op_rotate_encryption_key,
        "enable biometric unlock" to R.string.settings_op_enable_biometric_unlock,
    )

    @Test
    fun everyKnownOperationMapsToItsOwnPhrase() {
        knownOperations.forEach { (operation, expectedRes) ->
            assertEquals(
                expectedRes,
                vaultOperationRes(operation),
                "operation \"$operation\" must map to its localised phrase",
            )
        }
        assertEquals(
            knownOperations.size,
            knownOperations.values.toSet().size,
            "every operation needs its own phrase — two literals sharing one hides a mapping gap",
        )
    }

    @Test
    fun unknownOperationFallsBackRatherThanLeakingEnglish() {
        assertNull(vaultOperationRes("defragment the vault"))
        assertNull(vaultOperationRes(""))
    }
}
