package com.localkeep.feature.backup

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two rules a vault-to-vault merge applies to one item, and the interaction between them that
 * had a defect.
 *
 * Overall recency picks the winner. Concealment merges **separately**, on its own stamp — and a hide
 * never touches `updatedAt`, so the two are genuinely independent. While the lease merge sat inside
 * the recency branch, two devices that last edited an item at the same instant tied on `updatedAt`
 * and the incoming hide was dropped unread: the same backup carried a folder's hide across
 * correctly (folders have always merged unconditionally) and silently lost the identical hide on an
 * item inside it.
 */
class ImportedItemMergeTest {

    private val edited: Instant = Instant.parse("2026-08-18T12:00:00Z")
    private val id: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")

    private fun item(
        username: String,
        updatedAt: Instant = edited,
        hiddenUntil: Instant? = null,
        hiddenUpdatedAt: Instant? = null,
    ) = VaultItem(
        id = id,
        title = "Offshore account",
        createdAt = edited,
        updatedAt = updatedAt,
        content = Login(username = username, password = "p"),
        hiddenUntil = hiddenUntil,
        hiddenUpdatedAt = hiddenUpdatedAt,
    )

    @Test
    fun anIncomingHideIsAdoptedEvenWhenTheItemItselfDidNotWinOnRecency() {
        // The exact tie the defect lived in: same `updatedAt` on both sides, and the incoming copy
        // has since been hidden. Nothing about the item is newer except the lease.
        val hiddenLater = edited.plusSeconds(60)
        val existing = item("local")
        val incoming = item("imported", hiddenUntil = edited.plusSeconds(3600), hiddenUpdatedAt = hiddenLater)

        val merged = mergeImportedItem(existing = existing, incoming = incoming)

        assertEquals(edited.plusSeconds(3600), merged.hiddenUntil, "the newer lease is adopted")
        assertEquals(hiddenLater, merged.hiddenUpdatedAt)
        assertEquals(
            "local",
            (merged.content as Login).username,
            "and the item itself still loses the recency race — the two rules stay independent",
        )
    }

    @Test
    fun anIncomingUnhideIsAdoptedOnTheSameTerms() {
        val unhiddenLater = edited.plusSeconds(60)
        val existing = item("local", hiddenUntil = edited.plusSeconds(3600), hiddenUpdatedAt = edited)
        val incoming = item("imported", hiddenUntil = null, hiddenUpdatedAt = unhiddenLater)

        val merged = mergeImportedItem(existing = existing, incoming = incoming)

        assertNull(merged.hiddenUntil, "the newer unhide is adopted too, or the merge is one-way")
        assertEquals(unhiddenLater, merged.hiddenUpdatedAt)
    }

    @Test
    fun aBlankSidedImportCannotRevealALocalHide() {
        // The guard that makes merging unconditionally safe. Every foreign-format importer (CSV,
        // KDBX, Bitwarden) leaves the hidden fields untouched, so the incoming stamp is null — and a
        // null stamp must never win, or importing an address book would un-hide someone's entries.
        val existing = item("local", hiddenUntil = edited.plusSeconds(3600), hiddenUpdatedAt = edited)
        val incoming = item("imported", updatedAt = edited.plusSeconds(120))

        val merged = mergeImportedItem(existing = existing, incoming = incoming)

        assertEquals(edited.plusSeconds(3600), merged.hiddenUntil, "the local hide survives")
        assertEquals(
            "imported",
            (merged.content as Login).username,
            "even though the incoming item did win the recency race",
        )
    }

    @Test
    fun anOlderIncomingLeaseLoses() {
        val existing = item("local", hiddenUntil = edited.plusSeconds(3600), hiddenUpdatedAt = edited)
        val incoming = item("imported", hiddenUntil = null, hiddenUpdatedAt = edited.minusSeconds(60))

        val merged = mergeImportedItem(existing = existing, incoming = incoming)

        assertEquals(
            edited.plusSeconds(3600),
            merged.hiddenUntil,
            "an ancient export must not reveal an entry the user hid since",
        )
    }
}
