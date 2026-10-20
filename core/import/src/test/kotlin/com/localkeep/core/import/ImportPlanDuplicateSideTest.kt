package com.localkeep.core.import

import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which side an import collision is *with*.
 *
 * The preview counted vault collisions and same-file repeats as one number and described the total
 * with one sentence — "N already looks like an entry you have" — which is a claim about the user's
 * vault. Measured on device 2026-09-11: a Chrome CSV carrying two byte-identical rows for
 * `dup.example / dupuser`, imported into a vault holding no such entry, reported "1 already looks
 * like an entry you have." The import itself was right (4 rows in, 3 items created); only the
 * sentence was wrong, and it was wrong about the one thing the user cannot check from that screen.
 *
 * `plan` walks a `slots` list that opens as the vault's own items and grows as imported rows claim
 * new identities, so the collision index alone answers this — below `existing.size` is the vault,
 * at or above it is an earlier row of the same import.
 */
class ImportPlanDuplicateSideTest {

    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private val newer = Instant.parse("2026-09-11T00:00:00Z")

    private fun login(title: String, user: String, host: String, at: Instant) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = at,
        updatedAt = at,
        content = Login(username = user, password = "p", uris = listOf(UriMatch("https://$host"))),
    )

    @Test
    fun `a row matching a vault item is marked as colliding with the vault`() {
        val vault = listOf(login("Bank", "me@example.com", "bank.example", old))
        val imported = listOf(login("Bank", "me@example.com", "bank.example", newer))

        val planned = ImportMerger.plan(vault, imported)

        assertEquals(1, planned.size)
        assertTrue(planned.single().disposition != ImportMerger.Disposition.NEW)
        assertTrue(planned.single().collidesWithVault)
    }

    /**
     * The case the sentence was wrong about. Fails without `collidesWithVault`: the second row is a
     * collision either way, and with one flag for both the preview calls it a vault match.
     */
    @Test
    fun `a row repeating an earlier row of the same file is not a vault collision`() {
        val imported = listOf(
            login("dup.example", "dupuser", "dup.example", newer),
            login("dup.example", "dupuser", "dup.example", newer),
        )

        val planned = ImportMerger.plan(existing = emptyList(), imported = imported)

        assertEquals(2, planned.size)
        assertEquals(ImportMerger.Disposition.NEW, planned[0].disposition)
        assertTrue(planned[1].disposition != ImportMerger.Disposition.NEW)
        assertFalse(planned[1].collidesWithVault)
    }

    /** Both kinds in one file are counted on their own sides rather than summed. */
    @Test
    fun `a file holding both kinds separates them`() {
        val vault = listOf(login("Bank", "me@example.com", "bank.example", old))
        val imported = listOf(
            login("Bank", "me@example.com", "bank.example", newer),
            login("Forum", "me@example.com", "forum.example", newer),
            login("Forum", "me@example.com", "forum.example", newer),
        )

        val planned = ImportMerger.plan(vault, imported)
        val collisions = planned.filter { it.disposition != ImportMerger.Disposition.NEW }

        assertEquals(2, collisions.size)
        assertEquals(1, collisions.count { it.collidesWithVault })
        assertEquals(1, collisions.count { !it.collidesWithVault })
    }

    /** A row that matches nothing carries the flag's default and is never counted as either. */
    @Test
    fun `a genuinely new row is NEW`() {
        val planned = ImportMerger.plan(
            existing = listOf(login("Bank", "me@example.com", "bank.example", old)),
            imported = listOf(login("Forum", "other@example.com", "forum.example", newer)),
        )

        assertEquals(ImportMerger.Disposition.NEW, planned.single().disposition)
    }
}
