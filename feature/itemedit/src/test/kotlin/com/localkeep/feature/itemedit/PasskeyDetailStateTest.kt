package com.localkeep.feature.itemedit

import com.localkeep.core.model.Passkey
import com.localkeep.core.model.PasskeyAlgorithm
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F40: a stored passkey used to fall through `ItemEditViewModel.loadItem`'s unmapped-type branch,
 * which left `state.type` at its LOGIN default — `ItemDetailScreen` then mislabelled it "Login" and
 * rendered `LoginDetail` with blank fields. `withPasskeyContent` (`ItemEditViewModel.kt`) is the pure
 * mapping this pins, the same split `ScannedTotpTitleTest` and `PersonaWalkFixesTest` use so the
 * behaviour is checkable without a repository, a lock manager or an entitlement.
 *
 * The security property this exists to protect: nothing here may expose the passkey's private key
 * or let the item become editable. Both are checked structurally rather than through the UI —
 * [Passkey.privateKeyRef] and [Passkey.credentialId] never reach [ItemEditUiState] at all (there is
 * no field for either), and [readOnly] is what withholds the Edit button (`ItemDetailScreen`'s
 * `EditButton`), disables Save (`ItemEditScreen`'s two `enabled = ... && !state.readOnly` sites) and
 * makes `save()` return immediately (`ItemEditViewModel.kt`, checked twice). The whole-state
 * `assertEquals` below is the regression guard for the first part: it fails the moment a future edit
 * adds a passkey secret field here, whether or not that edit remembers to gate it.
 */
class PasskeyDetailStateTest {

    private val blank = ItemEditUiState()
    private val createdAt: Instant = Instant.parse("2026-08-01T12:00:00Z")

    private fun passkey(
        userName: String = "",
        userDisplayName: String = "",
        userHandle: ByteArray = byteArrayOf(1, 2, 3, 4),
    ) = Passkey(
        rpId = "example.com",
        credentialId = byteArrayOf(9, 9, 9),
        userHandle = userHandle,
        privateKeyRef = byteArrayOf(-1, -2, -3, -4, -5),
        algorithm = PasskeyAlgorithm.ES256,
        signCounter = 7L,
        createdAt = createdAt,
        userName = userName,
        userDisplayName = userDisplayName,
    )

    /**
     * The regression proof for F40 itself: type becomes PASSKEY, not the LOGIN default, and the
     * four display fields the spec asks for (rpId, username, created, sign counter) all land. Every
     * other field is untouched from a blank state, including `error` — the old fallback branch set
     * `itemedit_error_unsupported_type`, which no longer applies now that this type is supported.
     */
    @Test
    fun `loading a passkey sets its own type and detail fields, and nothing else`() {
        val expected = blank.copy(
            type = ItemType.PASSKEY,
            passkeyRpId = "example.com",
            passkeyUsername = "Ada Lovelace",
            passkeyCreatedAt = createdAt,
            passkeySignCounter = 7L,
            readOnly = true,
        )
        assertEquals(expected, blank.withPasskeyContent(passkey(userDisplayName = "Ada Lovelace")))
    }

    /** The gate that makes editing impossible: see the class KDoc for the three sites that read it. */
    @Test
    fun `a loaded passkey is always read-only`() {
        assertTrue(blank.withPasskeyContent(passkey()).readOnly)
    }

    @Test
    fun `username prefers the display name over the account name`() {
        val state = blank.withPasskeyContent(passkey(userName = "ada", userDisplayName = "Ada Lovelace"))
        assertEquals("Ada Lovelace", state.passkeyUsername)
    }

    @Test
    fun `username falls back to the account name when there is no display name`() {
        val state = blank.withPasskeyContent(passkey(userName = "ada"))
        assertEquals("ada", state.passkeyUsername)
    }

    /**
     * A native caller (no browser origin) may supply neither — WebAuthn only requires `user.id`.
     * The base64url readout is the one thing left to show; it is not a secret (it is the public
     * WebAuthn user handle, not [Passkey.privateKeyRef]), just an opaque account key with no name.
     */
    @Test
    fun `username falls back to the raw user handle when the relying party named nothing`() {
        val state = blank.withPasskeyContent(passkey(userHandle = byteArrayOf(1, 2, 3, 4)))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(1, 2, 3, 4)), state.passkeyUsername)
    }
}
