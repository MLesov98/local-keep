package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-30: the Reused drill-through lists items by title alone, and two reused Logins routinely share
 * one (a QA vault had "LocalKeep Autofill Test" twice) with nothing on the row to say which items
 * are reused *with each other*. [SecurityDashboard.Summary.reusedIds] is a flat id set that cannot
 * answer that; [SecurityDashboard.Summary.reusedGroupSizes] is the grouping a drill-down row needs,
 * flattened to "how many total items (itself included) share my password".
 */
class SecurityDashboardReusedGroupSizesTest {

    private val t: Instant = Instant.parse("2026-09-15T00:00:00Z")

    private fun login(password: String): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Login(username = "u", password = password, passwordChangedAt = t),
    )

    /** No corpus: breach matching is not what this test is about, and a null source never throws. */
    private fun noBreachData() = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null })

    @Test
    fun `reports the size of the reuse group each item was found in, not a global count`() {
        val pairA = List(2) { login("correct horse battery staple") }
        val trioB = List(3) { login("hunter2") }
        val unique = login("qF7\$zM2!vK9@wR4^")

        val summary = SecurityDashboard(noBreachData()).analyze(pairA + trioB + unique)

        pairA.forEach { assertEquals(2, summary.reusedGroupSizes.getValue(it.id), "pair-A member") }
        trioB.forEach { assertEquals(3, summary.reusedGroupSizes.getValue(it.id), "trio-B member") }
        assertTrue(unique.id !in summary.reusedGroupSizes, "a password used once has no reuse group at all")
    }

    @Test
    fun `every reused id has a group size, and nothing else does`() {
        val distinct = listOf("correct horse battery staple", "hunter2")
        val items = distinct.flatMap { listOf(login(it), login(it)) } + login("only-once")

        val summary = SecurityDashboard(noBreachData()).analyze(items)

        assertEquals(summary.reused.itemIds, summary.reusedGroupSizes.keys)
    }
}
