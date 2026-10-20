package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The editor's own state prints nothing, for the same reason `ItemContent` does not.
 *
 * `core/model` redacts every secret-bearing type, with the argument written on
 * `ItemContentRedactionTest`: `toString()` is what an exception message, a `Log` call and a crash
 * report all reach for, and none of them names the field, so none of them is a call site anyone
 * would think to audit. That argument stops at the model layer only by accident —
 * [ItemEditUiState] is the single state object behind viewing and editing *every* item type, and
 * it held the password, the TOTP secret, the card CVV and PIN, the identity numbers and the seed
 * phrase as raw properties of a plain `data class` (V27, 2026-09-12).
 *
 * Nothing interpolated it at the time. This test is what makes that still true later.
 */
class ItemEditUiStateRedactionTest {

    private val secrets = listOf(
        "correct-horse-battery-staple",
        "JBSWY3DPEHPK3PXP",
        "737",
        "4291",
        "078-05-1120",
        "X01234567",
        "LOVEL751213AL9AA",
        "abandon",
        "a passphrase nobody should see",
    )

    private val state = ItemEditUiState(
        password = secrets[0],
        totpSecret = secrets[1],
        cardCvv = secrets[2],
        cardPin = secrets[3],
        identitySsn = secrets[4],
        identityPassport = secrets[5],
        identityLicense = secrets[6],
        phraseWords = List(11) { secrets[7] } + "about",
        phrasePassphrase = secrets[8],
    )

    @Test
    fun `the edit state prints none of the secrets it holds`() {
        val printed = state.toString()
        secrets.forEach { secret ->
            assertFalse(
                printed.contains(secret),
                "ItemEditUiState.toString() leaked $secret — it must stay redacted",
            )
        }
    }

    @Test
    fun `the edit state prints a stable redacted marker`() {
        assertEquals("ItemEditUiState(redacted)", state.toString())
    }
}
