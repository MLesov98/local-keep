package com.localkeep.feature.itemedit

import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the read view has literally nothing to show, and therefore says so.
 *
 * The reported case is the seeded `Acme Mail` login: a title and nothing else. `ReadField` renders
 * nothing for an empty value — correct for one blank field among several — so every field being
 * blank produced a header, an empty card, and an Attachments row. No statement of what the item is
 * and no way in except the icon in the corner.
 */
class ItemContentEmptinessTest {

    @Test
    fun `a login with nothing filled in has nothing to render`() {
        assertTrue(ItemEditUiState(type = ItemType.LOGIN).rendersNoContent())
    }

    @Test
    fun `any one login field is enough to render something`() {
        val login = ItemEditUiState(type = ItemType.LOGIN)
        assertFalse(login.copy(username = "a@b.c").rendersNoContent())
        assertFalse(login.copy(password = "hunter2").rendersNoContent())
        assertFalse(login.copy(totpSecret = "JBSWY3DPEHPK3PXP").rendersNoContent())
        assertFalse(login.copy(uris = listOf("example.com")).rendersNoContent())
    }

    @Test
    fun `a login with only a note is not empty`() {
        // KdbxImporter.kt:198 maps a KeePass note-only entry straight into loginNotes; the LOGIN
        // branch tested five fields and omitted it, so the note was persisted and searchable but
        // the read view still routed to the "nothing saved" placeholder instead of LoginDetail.
        val login = ItemEditUiState(type = ItemType.LOGIN)
        assertFalse(login.copy(loginNotes = "call the landlord").rendersNoContent())
    }

    @Test
    fun `a blank website row does not count as content`() {
        // The editor keeps a blank row as the thing the user is about to type into, and drops it at
        // save. It draws nothing on the read view, so it must not suppress the empty state either.
        assertTrue(ItemEditUiState(type = ItemType.LOGIN, uris = listOf("", "  ")).rendersNoContent())
    }

    @Test
    fun `an identity is empty only when every one of its fields is`() {
        val identity = ItemEditUiState(type = ItemType.IDENTITY)
        assertTrue(identity.rendersNoContent())
        assertFalse(identity.copy(identityFirstName = "Ada").rendersNoContent())
        assertFalse(identity.copy(identityAddressCountry = "IE").rendersNoContent())
    }

    @Test
    fun `the three types that always draw something are never empty`() {
        // Card draws its face — network, grouped bullets, expiry placeholder — with nothing filled
        // in; TOTP always draws its code card; and a Note has a better answer of its own, the
        // placeholder row that is itself the way into the editor.
        assertFalse(ItemEditUiState(type = ItemType.CARD).rendersNoContent())
        assertFalse(ItemEditUiState(type = ItemType.TOTP).rendersNoContent())
        assertFalse(ItemEditUiState(type = ItemType.NOTE).rendersNoContent())
    }

    @Test
    fun `a passkey is never empty`() {
        // F40: it has no save path at all, so every one that exists was created by PasskeyCreator
        // with a non-blank rpId and createdAt — there is no way to reach this screen with a passkey
        // that has nothing for PasskeyDetail to draw, with or without the display fields set here.
        assertFalse(ItemEditUiState(type = ItemType.PASSKEY).rendersNoContent())
    }

    @Test
    fun `a group outside the type block keeps the screen from being empty`() {
        // Each of these draws its own card further down. An "there is nothing here" line above one
        // of them would be contradicted by what is next to it.
        val login = ItemEditUiState(type = ItemType.LOGIN)
        assertFalse(login.copy(tags = listOf("work")).rendersNoContent())
        assertFalse(login.copy(folderId = UUID.randomUUID()).rendersNoContent())
        assertFalse(
            login.copy(
                customFields = listOf(CustomField("Account", "12345", CustomFieldType.TEXT)),
            ).rendersNoContent(),
        )
    }

    @Test
    fun `an Identity holding only a photograph is not empty`() {
        // The whole point of the photograph-the-document route: fourteen blank text fields and a
        // picture of a passport. Before `hasAttachments` joined the trailing conjunction, this
        // drew "there is nothing in this item yet" directly above the photograph it is about.
        val identity = ItemEditUiState(type = ItemType.IDENTITY)
        assertTrue(identity.rendersNoContent())
        assertFalse(identity.copy(hasAttachments = true).rendersNoContent())
    }

    @Test
    fun `attachments keep any type from reading as empty`() {
        // Not Identity-specific. Attachments are drawn by the detail screen for every type, so a
        // login with a file and no credentials has something on its screen too.
        val login = ItemEditUiState(type = ItemType.LOGIN)
        assertTrue(login.rendersNoContent())
        assertFalse(login.copy(hasAttachments = true).rendersNoContent())
    }
}
