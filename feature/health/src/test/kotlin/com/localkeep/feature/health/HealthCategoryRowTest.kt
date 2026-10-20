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
 * F-30: the Reused drill-through lists two rows with an identical label ("LocalKeep Autofill Test"
 * twice in a QA vault) and nothing says which password is shared with which. [categoryRows] adds the
 * username as a disambiguating secondary line and the size of the reuse group as a badge count. The
 * count is [SecurityDashboard.Category.REUSED]'s alone; the username line is every category's since
 * S3-F-19 (see [CategoryRowsTellCopiesApartTest]).
 */
class HealthCategoryRowTest {

    private val now: Instant = Instant.parse("2026-09-15T00:00:00Z")

    private fun login(username: String, title: String = "acct"): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = now,
        updatedAt = now,
        content = Login(username = username, password = "shared-password", passwordChangedAt = now),
    )

    @Test
    fun `reused rows carry the username and the OTHER-item count, not the group's own size`() {
        val a = login(username = "alice@example.com", title = "LocalKeep Autofill Test")
        val b = login(username = "bob@example.com", title = "LocalKeep Autofill Test")
        val c = login(username = "carol@example.com", title = "LocalKeep Autofill Test")
        val groupSizes = mapOf(a.id to 3, b.id to 3, c.id to 3)

        val rows = categoryRows(listOf(a, b, c), SecurityDashboard.Category.REUSED, groupSizes)

        assertEquals(listOf("alice@example.com", "bob@example.com", "carol@example.com"), rows.map { it.secondary })
        // Group of 3 -> 2 OTHER items, not 3 - the row is about what it shares with, not its own size.
        assertEquals(listOf(2, 2, 2), rows.map { it.sharedWithOthersCount })
    }

    @Test
    fun `a blank username is left null, never a fabricated placeholder`() {
        val blank = login(username = "")
        val groupSizes = mapOf(blank.id to 2)

        val rows = categoryRows(listOf(blank), SecurityDashboard.Category.REUSED, groupSizes)

        assertNull(rows.single().secondary)
    }

    @Test
    fun `only Reused rows carry the shared-with count - the username line is every category's`() {
        val item = login(username = "alice@example.com")

        for (category in listOf(SecurityDashboard.Category.WEAK, SecurityDashboard.Category.OLD, SecurityDashboard.Category.BREACHED, SecurityDashboard.Category.DUPLICATE)) {
            val row = categoryRows(listOf(item), category, reusedGroupSizes = mapOf(item.id to 5)).single()
            // S3-F-19 widened the username line from Reused to every category (two copies of one
            // entry were indistinguishable); the group-size count is still about reuse alone.
            assertEquals("alice@example.com", row.secondary, "category=$category")
            assertNull(row.sharedWithOthersCount, "category=$category")
        }

        val nullCategory = categoryRows(listOf(item), category = null, reusedGroupSizes = mapOf(item.id to 5)).single()
        assertNull(nullCategory.sharedWithOthersCount)
    }

    @Test
    fun `a non-Login item in Reused (should not happen, but must not crash) gets no secondary line`() {
        val note = VaultItem(id = UUID.randomUUID(), createdAt = now, updatedAt = now, content = Note(body = "n"))

        val row = categoryRows(listOf(note), SecurityDashboard.Category.REUSED, emptyMap()).single()

        assertNull(row.secondary)
        assertNull(row.sharedWithOthersCount)
    }
}
