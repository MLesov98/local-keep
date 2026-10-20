package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an import collision must NOT destroy.
 *
 * Before `preserveStructure`, an imported item that won a collision replaced the vault item
 * outright. Every importer builds its items from a CSV row or a KDBX entry, so they arrive with a
 * fresh random id and no attachments, custom fields, tags, folder, favorite, reprompt or TOTP —
 * "winning" therefore meant blanking all of it on an item the user still had, and the new id
 * orphaned the attachment blobs into the next `BlobGarbageCollector` sweep, which deletes them.
 *
 * The id assertion is the one that matters most: it is the only handle the blobs have.
 */
class ItemMergePreserveStructureTest {

    private val existingId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val importedId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private val recent = Instant.parse("2026-07-28T00:00:00Z")

    /** What a user has built up in the vault: files, notes-in-fields, a folder, a second factor. */
    private fun existing() = VaultItem(
        id = existingId,
        title = "Bank",
        folderId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        tags = listOf("finance"),
        favorite = true,
        createdAt = old,
        updatedAt = old,
        reprompt = true,
        customFields = listOf(CustomField("Recovery codes", "abc-def", CustomFieldType.HIDDEN)),
        attachments = listOf(
            Attachment(
                id = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                filename = "statement.pdf",
                mime = "application/pdf",
                sizeBytes = 10,
                blobRef = "blob-1",
            ),
        ),
        content = Login(
            username = "me@example.com",
            password = "old-password",
            uris = listOf(UriMatch("https://bank.example")),
            totp = Totp(secretBase32 = "JBSWY3DPEHPK3PXP"),
        ),
    )

    /** What a CSV or KDBX import can actually carry: a title, a username, a password, maybe a URL. */
    private fun imported() = VaultItem(
        id = importedId,
        title = "Bank",
        createdAt = recent,
        updatedAt = recent,
        content = Login(username = "me@example.com", password = "new-password"),
    )

    @Test
    fun `a winning import keeps the vault item's id, so its attachments are not orphaned`() {
        val merged = ItemMerge.preserveStructure(winner = imported(), loser = existing())

        assertEquals(existingId, merged.id)
        assertEquals(listOf("blob-1"), merged.attachments.map { it.blobRef })
    }

    @Test
    fun `a winning import does not blank the fields it had no way to carry`() {
        val merged = ItemMerge.preserveStructure(winner = imported(), loser = existing())

        assertEquals(existing().folderId, merged.folderId)
        assertEquals(listOf("finance"), merged.tags)
        assertTrue(merged.favorite)
        assertEquals(1, merged.customFields.size)
        assertEquals("Recovery codes", merged.customFields.single().name)
        assertEquals(old, merged.createdAt, "the item dates from when the user made it, not the re-import")
    }

    @Test
    fun `reprompt is never downgraded by an import`() {
        // No importer sets `reprompt`, so a false on the incoming side always means "could not
        // carry it" — never "the user switched it off".
        val merged = ItemMerge.preserveStructure(winner = imported(), loser = existing())

        assertTrue(merged.reprompt)
    }

    @Test
    fun `the second factor survives an import that has no column for it`() {
        val merged = ItemMerge.preserveStructure(winner = imported(), loser = existing())

        assertEquals("JBSWY3DPEHPK3PXP", (merged.content as Login).totp?.secretBase32)
    }

    @Test
    fun `the import still wins where it actually carries a value`() {
        // The other half of the rule: this must not become "the existing item always wins", which
        // would make importing an updated password pointless.
        val merged = ItemMerge.preserveStructure(winner = imported(), loser = existing())

        assertEquals("new-password", (merged.content as Login).password)
        assertEquals(recent, merged.updatedAt)
    }

    @Test
    fun `a winner carrying its own structure keeps it`() {
        val richWinner = imported().copy(
            tags = listOf("imported-tag"),
            customFields = listOf(CustomField("Other", "x", CustomFieldType.TEXT)),
        )

        val merged = ItemMerge.preserveStructure(winner = richWinner, loser = existing())

        assertEquals(listOf("imported-tag"), merged.tags)
        assertEquals("Other", merged.customFields.single().name)
    }

    @Test
    fun `a subset URI list is unioned, not replaced`() {
        // Finding 4-3: an import can carry at most one URI (a CSV row has one URL column), so a
        // vault item holding [A, B] colliding with an import carrying [A] must keep B — replacing
        // the list wholesale silently destroyed the extra URLs and changed where autofill offered
        // the credential.
        val multiUriLoser = existing().copy(
            content = (existing().content as Login).copy(
                uris = listOf(
                    UriMatch("https://bank.example"),
                    UriMatch("https://admin.bank.example"),
                ),
            ),
        )
        val singleUriWinner = imported().copy(
            content = (imported().content as Login).copy(uris = listOf(UriMatch("https://bank.example"))),
        )

        val merged = ItemMerge.preserveStructure(winner = singleUriWinner, loser = multiUriLoser)

        val mergedUris = (merged.content as Login).uris.map { it.uri }
        assertEquals(2, mergedUris.size)
        assertTrue("https://bank.example" in mergedUris)
        assertTrue("https://admin.bank.example" in mergedUris)
    }

    /**
     * Bitwarden exports carry `folders[]` and `BitwardenJsonImporter` maps them, so a colliding
     * imported item can arrive holding a folder of its own. The old rule — take the loser's folder
     * only where the winner has none — then never fired, and the import re-filed an item the user
     * had deliberately put somewhere. FEATURES.md promises the opposite.
     *
     * Fails without the `existing` parameter: the merged item comes back in the import's folder.
     */
    @Test
    fun `an import carrying its own folder does not re-file the vault item`() {
        val importedFolder = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val filedImport = imported().copy(folderId = importedFolder)

        val merged = ItemMerge.preserveStructure(
            winner = filedImport,
            loser = existing(),
            existing = existing(),
        )

        assertEquals(existing().folderId, merged.folderId)
    }

    /** An unfiled vault item still accepts the import's folder — this is preservation, not a veto. */
    @Test
    fun `an unfiled vault item takes the folder the import carries`() {
        val importedFolder = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val unfiled = existing().copy(folderId = null)

        val merged = ItemMerge.preserveStructure(
            winner = imported().copy(folderId = importedFolder),
            loser = unfiled,
            existing = unfiled,
        )

        assertEquals(importedFolder, merged.folderId)
    }

    /**
     * Notes are the only merged field holding prose a person typed. An import carrying one is not
     * evidence the user wanted theirs replaced.
     *
     * Fails without the fix: the merged note is the import's.
     */
    @Test
    fun `an import carrying a note does not replace the note the user wrote`() {
        val noted = existing().copy(
            content = (existing().content as Login).copy(notes = "Work account for CI"),
        )
        val importWithNote = imported().copy(
            content = (imported().content as Login).copy(notes = "Imported note"),
        )

        val merged = ItemMerge.preserveStructure(
            winner = importWithNote,
            loser = noted,
            existing = noted,
        )

        assertEquals("Work account for CI", (merged.content as Login).notes)
    }

    /** With no note in the vault, the import's note is kept rather than dropped. */
    @Test
    fun `an import's note fills an empty vault note`() {
        val blankNote = existing().copy(
            content = (existing().content as Login).copy(notes = ""),
        )
        val importWithNote = imported().copy(
            content = (imported().content as Login).copy(notes = "Imported note"),
        )

        val merged = ItemMerge.preserveStructure(
            winner = importWithNote,
            loser = blankNote,
            existing = blankNote,
        )

        assertEquals("Imported note", (merged.content as Login).notes)
    }

    /**
     * The vault item can be the newer side, in which case it is the *winner* and `loser` is the
     * import. `existing` has to be read, not inferred from which side won, or the rule inverts
     * exactly when the user's copy is the one that should be protected hardest.
     */
    @Test
    fun `the vault's folder survives when the vault item is the newer side`() {
        val newerVault = existing().copy(updatedAt = recent.plusSeconds(60))
        val olderImport = imported().copy(
            updatedAt = recent,
            folderId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
        )

        val merged = ItemMerge.preserveStructure(
            winner = newerVault,
            loser = olderImport,
            existing = newerVault,
        )

        assertEquals(existing().folderId, merged.folderId)
    }

    /**
     * The vault's id survives whichever side wins. Until 2026-09-14 the merged item took
     * `loser.id`, which was the vault's id only while the import was newer; an older export
     * colliding with a newer vault item re-identified it.
     */
    @Test
    fun theVaultItemKeepsItsIdWhenItIsTheWinner() {
        val vault = existing()
        val older = imported()
        val merged = ItemMerge.preserveStructure(winner = vault, loser = older, existing = vault)
        assertEquals(vault.id, merged.id)
    }

    @Test
    fun theVaultItemKeepsItsIdWhenTheImportIsTheWinner() {
        val vault = existing()
        val merged = ItemMerge.preserveStructure(winner = imported(), loser = vault, existing = vault)
        assertEquals(vault.id, merged.id)
    }

    /**
     * A vault login saved with no password at all, colliding with an import that has one. The
     * vault copy is the newer side here, so it wins and `winner.password` is the blank — which is
     * the only case where the import's password may be taken, and the only one where taking it
     * loses nothing.
     */
    @Test
    fun `a blank vault password is filled from the import`() {
        val blankVault = existing().copy(
            updatedAt = recent.plusSeconds(60),
            content = (existing().content as Login).copy(password = "", passwordChangedAt = null),
        )
        val olderImport = imported()

        val merged = ItemMerge.preserveStructure(
            winner = blankVault,
            loser = olderImport,
            existing = blankVault,
        )

        assertEquals("new-password", (merged.content as Login).password)
    }

    /** The control: a password the user actually has is never replaced by an undated source. */
    @Test
    fun `a non-blank vault password is never replaced by the import`() {
        val newerVault = existing().copy(updatedAt = recent.plusSeconds(60))
        val olderImport = imported()

        val merged = ItemMerge.preserveStructure(
            winner = newerVault,
            loser = olderImport,
            existing = newerVault,
        )

        assertEquals("old-password", (merged.content as Login).password)
    }

    /**
     * The same rule from the other side: the import wins, the vault's password is not blank, and
     * the import carries none. Nothing here may reach back for the vault's secret — that contest
     * belongs to `preserveNewerPassword`, which reads the change times this function cannot see.
     */
    @Test
    fun `an import with no password does not borrow the vault's`() {
        val vault = existing()
        val passwordlessImport = imported().copy(
            content = (imported().content as Login).copy(password = ""),
        )

        val merged = ItemMerge.preserveStructure(
            winner = passwordlessImport,
            loser = vault,
            existing = vault,
        )

        assertEquals("", (merged.content as Login).password)
    }

    // --- File, which gained a `notes` field in schema 8 ----------------------
    //
    // A File reaches this function from the `.vault` and `.1pux` merge paths — `ImportMerger`
    // keys files on filename + size + MIME, so a re-import really does collide with the vault's
    // own copy. Until schema 8 there was nothing on a File worth protecting here (every other
    // field is the blob's own identity); `notes` is prose a person typed, and the same rule the
    // Login branch above applies is what stops a re-import eating it.

    private fun fileItem(notes: String, id: UUID, at: Instant) = VaultItem(
        id = id,
        title = "Passport scan",
        createdAt = at,
        updatedAt = at,
        content = File(
            filename = "passport.jpg",
            mime = "image/jpeg",
            sizeBytes = 918_273L,
            blobRef = "blob-1",
            notes = notes,
        ),
    )

    /** Fails without the File branch in `preserveContent`: the merged note is the import's. */
    @Test
    fun `an import carrying a file note does not replace the note the user wrote`() {
        val vaultFile = fileItem("Renewal due March 2029", existingId, old)
        val importedFile = fileItem("Imported note", importedId, recent)

        val merged = ItemMerge.preserveStructure(
            winner = importedFile,
            loser = vaultFile,
            existing = vaultFile,
        )

        assertEquals("Renewal due March 2029", (merged.content as File).notes)
        assertEquals(existingId, merged.id, "the blob is reachable only through the vault's id")
    }

    /** With no note in the vault, the import's note is kept rather than dropped. */
    @Test
    fun `an import's file note fills an empty vault note`() {
        val vaultFile = fileItem("", existingId, old)
        val importedFile = fileItem("Imported note", importedId, recent)

        val merged = ItemMerge.preserveStructure(
            winner = importedFile,
            loser = vaultFile,
            existing = vaultFile,
        )

        assertEquals("Imported note", (merged.content as File).notes)
    }

    /** And from the other side, where the vault's copy is the newer one and therefore the winner. */
    @Test
    fun `the vault's file note survives when the vault item is the newer side`() {
        val vaultFile = fileItem("Renewal due March 2029", existingId, recent.plusSeconds(60))
        val importedFile = fileItem("Imported note", importedId, recent)

        val merged = ItemMerge.preserveStructure(
            winner = vaultFile,
            loser = importedFile,
            existing = vaultFile,
        )

        assertEquals("Renewal due March 2029", (merged.content as File).notes)
    }
}
