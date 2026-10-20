package com.localkeep.feature.itemedit

import com.localkeep.feature.itemedit.ItemEditUiState.ItemType

/**
 * Whether the read view's type block would draw nothing at all.
 *
 * `ReadField` renders nothing for an empty value, which is right for one blank field among several
 * and wrong when it is every one of them: the seeded `Acme Mail` login — a title and nothing else —
 * produced a detail screen with a header, an Attachments row and a bare card in between. No
 * statement of what the item is, and no way in except the icon in the corner.
 *
 * Only two of the six types can reach that state, and the other four are excluded on inspection
 * rather than by omission:
 *
 * - **Login** and **Identity** are `ReadField`s all the way down, so all-blank draws an empty card —
 *   for Login that includes the conditional notes block, so a notes-only login also counts as
 *   content rather than tripping the empty state.
 * - **Card** always draws its face, which shows the network, the grouped bullets and the expiry
 *   placeholder even with nothing filled in.
 * - **TOTP** always draws `TotpCard`.
 * - **Coordinate** cannot exist without a fix — see the note in the `when` below.
 * - **Note** has a better answer of its own — the placeholder row itself is the way into the
 *   editor — so routing it here would replace a specific invitation with a generic one.
 *
 * Deliberately the *unambiguous* case, and not the interesting one. Whether a login with a username
 * and no password counts as empty is a product question about each type's minimum, recorded in
 * as a follow-up; this asks only whether anything at all was filled in.
 */
internal fun ItemEditUiState.rendersNoContent(): Boolean = when (type) {
    ItemType.LOGIN -> username.isBlank() &&
        password.isBlank() &&
        totpSecret.isBlank() &&
        uris.none(String::isNotBlank) &&
        passwordHistory.isEmpty() &&
        loginNotes.isBlank()

    ItemType.IDENTITY -> listOf(
        identityFirstName,
        identityLastName,
        identityCompany,
        identitySsn,
        identityPassport,
        identityLicense,
        identityEmail,
        identityPhone,
        identityAddressLine1,
        identityAddressLine2,
        identityAddressCity,
        identityAddressState,
        identityAddressZip,
        identityAddressCountry,
    ).all(String::isBlank)

    // COORDINATE joins the three that always draw something. A coordinate item cannot exist without
    // a fix — Save refuses one — so its detail block always has a value, a capture time and the
    // reveal control to render, and routing it here would replace that with a generic "nothing in
    // this item yet" line under a heading that says Location.
    // PHRASE joins them: a recovery phrase cannot be saved until its checksum is valid, so a saved
    // one always has words for [PhraseDetail] to render behind the reveal gate — a generic "nothing
    // here yet" line under a heading that says Recovery phrase would be a contradiction.
    // PASSKEY joins them too, and more strongly: it has no save path at all (the type is read-only,
    // see `withPasskeyContent`), so every instance was created by `PasskeyCreator` with a non-blank
    // `rpId` and `createdAt` — there is no way to reach this screen with a passkey that has nothing
    // for [PasskeyDetail] to draw.
    ItemType.NOTE, ItemType.TOTP, ItemType.CARD, ItemType.COORDINATE, ItemType.PHRASE,
    ItemType.PASSKEY,
    -> false
} &&
    // The groups that live outside the type block. Any one of them and the screen has something on
    // it, so an "empty" line under it would be contradicted by what is next to it.
    customFields.isEmpty() &&
    tags.isEmpty() &&
    folderId == null &&
    // An Identity holding nothing but a photograph of a passport is the case this clause exists
    // for: fourteen blank text fields, and a picture of the document on the screen underneath a
    // line saying there is nothing in this item yet. Attachments are drawn by the detail screen
    // exactly like the three above, so they answer the same question — is there anything here.
    !hasAttachments
