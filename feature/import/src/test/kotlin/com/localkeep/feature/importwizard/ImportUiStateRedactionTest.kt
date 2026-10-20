package com.localkeep.feature.importwizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The import wizard's state prints nothing either.
 *
 * [ImportUiState.password] is the KDBX master password as typed — the one secret in this app that
 * belongs to a *different* password manager, and the one the user is most likely to be reusing. It
 * is deliberately a `String` (Compose has no CharArray-native text field), which makes redacting
 * its container the only guard available (V27, 2026-09-12).
 */
class ImportUiStateRedactionTest {

    private val password = "the-other-managers-master-password"

    @Test
    fun `the import state does not print the source password`() {
        val printed = ImportUiState(password = password).toString()
        assertFalse(
            printed.contains(password),
            "ImportUiState.toString() leaked the import source's master password",
        )
        assertEquals("ImportUiState(redacted)", printed)
    }
}
