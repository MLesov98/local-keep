package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemMergeTest {
    private val t0 = Instant.ofEpochMilli(1_000_000L)
    private val t1 = Instant.ofEpochMilli(2_000_000L)
    private val t2 = Instant.ofEpochMilli(3_000_000L)
    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun login(pw: String, pwAt: Instant?, updatedAt: Instant): VaultItem = VaultItem(
        id = id,
        createdAt = t0,
        updatedAt = updatedAt,
        content = Login(username = "u", password = pw, passwordChangedAt = pwAt),
    )

    @Test
    fun `loser's fresher password is preserved on the winning item`() {
        val winner = login("winner-pw", pwAt = t1, updatedAt = t2) // newer overall, older password
        val loser = login("fresher-pw", pwAt = t2, updatedAt = t1) // older overall, newer password

        val merged = ItemMerge.preserveNewerPassword(winner, loser)

        assertEquals("fresher-pw", (merged.content as Login).password)
        assertEquals(t2, (merged.content as Login).passwordChangedAt)
        assertEquals(winner.updatedAt, merged.updatedAt) // non-secret fields still come from the winner
    }

    @Test
    fun `winner keeps its own password when it is the fresher one`() {
        val winner = login("winner-pw", pwAt = t2, updatedAt = t2)
        val loser = login("loser-pw", pwAt = t1, updatedAt = t1)

        assertEquals(winner, ItemMerge.preserveNewerPassword(winner, loser))
    }

    @Test
    fun `falls back to updatedAt when passwordChangedAt is null`() {
        val winner = login("w", pwAt = null, updatedAt = t1)
        val loser = login("l", pwAt = null, updatedAt = t2) // loser touched later ⇒ its password preserved

        assertEquals("l", (ItemMerge.preserveNewerPassword(winner, loser).content as Login).password)
    }

    @Test
    fun `undated import cannot overwrite a password changed in-vault`() {
        // The defect's shape: the importer stamps updatedAt = import-now (fabricated), so the
        // old fallback `passwordChangedAt ?: updatedAt` always saw the import as "newest" and the
        // guard never fired — a stale export silently replaced a password the user rotated
        // in-vault. With no passwordChangedAt on the winner, the vault's real change time wins.
        val winner = login("stale-export-pw", pwAt = null, updatedAt = t2) // updatedAt = import-now
        val loser = login("fresh-in-vault-pw", pwAt = t1, updatedAt = t0)

        val merged = ItemMerge.preserveNewerPassword(winner, loser)

        assertEquals("fresh-in-vault-pw", (merged.content as Login).password)
        assertEquals(t1, (merged.content as Login).passwordChangedAt)
        assertEquals(winner.updatedAt, merged.updatedAt) // non-secret fields still come from the winner
    }

    @Test
    fun `real-dated import with newer password still wins over an undated vault item`() {
        // The Bitwarden/1Password shape: the import carries its own real dates, so its password
        // evidence is the item's real updatedAt and "newest wins" applies as designed.
        val winner = login("newer-export-pw", pwAt = null, updatedAt = t2) // real revisionDate
        val loser = login("older-pw", pwAt = null, updatedAt = t1) // never changed in-vault

        assertEquals(winner, ItemMerge.preserveNewerPassword(winner, loser))
    }

    @Test
    fun `non-login items are returned unchanged`() {
        val winner = VaultItem(id = id, createdAt = t0, updatedAt = t2, content = Note("winner"))
        val loser = VaultItem(id = id, createdAt = t0, updatedAt = t1, content = Note("loser"))

        assertEquals(winner, ItemMerge.preserveNewerPassword(winner, loser))
    }
}
