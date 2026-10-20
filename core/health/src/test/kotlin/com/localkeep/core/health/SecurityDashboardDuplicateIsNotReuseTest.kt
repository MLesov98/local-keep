package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reuse is one password across several *accounts*; a duplicate is one account saved several times.
 *
 * [DuplicateDetector]'s own KDoc draws that line and names the cost of blurring it: the advice for
 * the two is opposite. Two copies of one account with one password are not a security failure, yet
 * [ReuseOldDetector.findReused] groups on the password alone, so both copies came out under Reused -
 * coral, the 30-point weight, and "Start with the reused passwords - one breach there exposes every
 * account sharing it" for an account that exists once. The user was sent to rotate a password
 * instead of deleting a copy, and the "only duplicate entries left" verdict could never be reached
 * by the very case the detector's KDoc names (an export imported twice).
 */
class SecurityDashboardDuplicateIsNotReuseTest {

    private val t: Instant = Instant.parse("2026-10-02T00:00:00Z")

    private fun login(host: String, username: String, password: String): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Login(
            username = username,
            password = password,
            passwordChangedAt = t,
            uris = listOf(UriMatch(uri = "https://$host")),
        ),
    )

    private fun noBreachData() = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null })

    private val shared = "correct horse battery staple"

    @Test
    fun `two copies of one account with one password are a duplicate, not a reuse`() {
        val a = login("github.com", "ada", shared)
        val b = login("github.com", "ada", shared)

        val summary = SecurityDashboard(noBreachData()).analyze(listOf(a, b))

        assertEquals(setOf(a.id, b.id), summary.duplicate.itemIds, "the pair is still reported as a duplicate")
        assertTrue(summary.reused.itemIds.isEmpty(), "one account is not one password across accounts")
        assertTrue(summary.reusedGroupSizes.isEmpty())
    }

    /** The control: a copy must not hide a real reuse. */
    @Test
    fun `a password shared with a different account is still reused, copies included`() {
        val a = login("github.com", "ada", shared)
        val aCopy = login("github.com", "ada", shared)
        val other = login("gitlab.com", "ada", shared)

        val summary = SecurityDashboard(noBreachData()).analyze(listOf(a, aCopy, other))

        assertEquals(setOf(a.id, aCopy.id, other.id), summary.reused.itemIds)
        assertEquals(setOf(a.id, aCopy.id), summary.duplicate.itemIds)
    }

    @Test
    fun `two different accounts sharing a password are reused and not duplicates`() {
        val a = login("github.com", "ada", shared)
        val b = login("gitlab.com", "grace", shared)

        val summary = SecurityDashboard(noBreachData()).analyze(listOf(a, b))

        assertEquals(setOf(a.id, b.id), summary.reused.itemIds)
        assertTrue(summary.duplicate.itemIds.isEmpty())
    }

    /** Copies that carry different passwords were never in one reuse group; nothing changes for them. */
    @Test
    fun `copies of one account with different passwords are a duplicate and nothing else`() {
        val a = login("github.com", "ada", shared)
        val b = login("github.com", "ada", "hunter2")

        val summary = SecurityDashboard(noBreachData()).analyze(listOf(a, b))

        assertEquals(setOf(a.id, b.id), summary.duplicate.itemIds)
        assertTrue(summary.reused.itemIds.isEmpty())
    }
}
