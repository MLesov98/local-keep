package com.localkeep.feature.health

import com.localkeep.core.health.SecurityDashboard
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Two rows that would read the same carry something to tell them apart.
 *
 * S3-F-19 (functional pass, 2026-10-15): the Duplicate, Weak and Compromised sheets listed
 * title-only rows, so two copies of one entry were identical until tapped, unlike Reused rows,
 * which carry the username. Where an entry has no username the saved date takes its place; where it
 * has one, the username stays the line and no date is added.
 */
class CategoryRowsTellCopiesApartTest {

    private val saved1: Instant = Instant.parse("2026-08-01T10:00:00Z")
    private val saved2: Instant = Instant.parse("2026-09-12T10:00:00Z")

    private fun login(username: String, savedAt: Instant) = VaultItem(
        id = UUID.randomUUID(),
        createdAt = savedAt,
        updatedAt = savedAt,
        content = Login(username = username, password = "p", passwordChangedAt = savedAt),
    )

    private fun note(savedAt: Instant) = VaultItem(
        id = UUID.randomUUID(),
        createdAt = savedAt,
        updatedAt = savedAt,
        content = Note(body = "b"),
    )

    @Test
    fun `duplicate rows without a username fall back to when they were saved`() {
        val a = note(saved1)
        val b = note(saved2)

        val rows = categoryRows(listOf(a, b), SecurityDashboard.Category.DUPLICATE, emptyMap())

        assertEquals(listOf(saved1, saved2), rows.map { it.savedAt }, "two identical title-only rows")
        rows.forEach { assertNull(it.secondary) }
    }

    @Test
    fun `an item with no real timestamp gets no date line`() {
        // D3-L-18: an item stored with updatedAt 0 drew "Saved Jan 1, 1970", which is not a date
        // it was saved. Control: the same row with a real timestamp still carries its date.
        val unstamped = categoryRows(listOf(note(Instant.EPOCH)), SecurityDashboard.Category.OLD, emptyMap()).single()
        assertNull(unstamped.savedAt, "the Unix epoch is a missing timestamp, not a save date")
        assertEquals(
            saved1,
            categoryRows(listOf(note(saved1)), SecurityDashboard.Category.OLD, emptyMap()).single().savedAt,
        )
    }

    @Test
    fun `weak and compromised rows are covered too`() {
        listOf(SecurityDashboard.Category.WEAK, SecurityDashboard.Category.BREACHED).forEach { category ->
            val row = categoryRows(listOf(login("", saved1)), category, emptyMap()).single()
            assertEquals(saved1, row.savedAt, "$category rows are still bare")
        }
    }

    @Test
    fun `a username is the line on every category, and no date is added beside it`() {
        val rows = categoryRows(
            listOf(login("ana@example.com", saved1)),
            SecurityDashboard.Category.DUPLICATE,
            emptyMap(),
        )

        assertEquals("ana@example.com", rows.single().secondary)
        assertNull(rows.single().savedAt)
    }

    @Test
    fun `control - reused rows keep their shared-with count`() {
        val a = login("ana", saved1)
        val rows = categoryRows(listOf(a), SecurityDashboard.Category.REUSED, mapOf(a.id to 3))

        assertEquals(2, rows.single().sharedWithOthersCount)
        assertEquals("ana", rows.single().secondary)
    }
}
