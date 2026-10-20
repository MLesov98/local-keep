package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The strength pass costs one zxcvbn measurement per DISTINCT password, not one per item.
 *
 * zxcvbn's matching is superlinear in length and runs in the low milliseconds per password, so on a
 * large vault this pass is what the whole scan waits on — a 10,000-login vault spent ~20 s in
 * [SecurityDashboard.analyze] on hardware. Nothing here measures time (a timing assertion on a
 * shared CI machine is a flake generator); it measures the only thing that is actually under this
 * class's control, which is how many times it asks.
 *
 * The counting test alone would pass for a "fix" that scored fewer passwords by scoring the wrong
 * ones, so it is paired with a control that pins the summary itself against the per-item verdicts of
 * the real analyzer.
 */
class SecurityDashboardStrengthCostTest {

    private val t: Instant = Instant.parse("2026-08-28T00:00:00Z")

    private fun login(password: String): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Login(username = "u", password = password, passwordChangedAt = t),
    )

    /** No corpus: breach matching is not what this test is about, and a null source never throws. */
    private fun noBreachData() = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null })

    @Test
    fun `scores each distinct password once, not once per item`() {
        // 12 logins, 3 distinct passwords, plus two items the strength pass must not touch at all:
        // an empty password (StrengthAnalyzer defines it as not-weak without measuring) and a
        // trashed item.
        val distinct = listOf("correct horse battery staple", "hunter2", "Tr0ub4dor&3")
        val items = (0 until 12).map { login(distinct[it % distinct.size]) } +
            login("") +
            login("only-in-the-trash").copy(deletedAt = t)

        val asked = mutableListOf<String>()
        val dashboard = SecurityDashboard(noBreachData()) { password ->
            asked += password
            StrengthAnalyzer.isWeak(password)
        }

        dashboard.analyze(items)

        // Counts only — a failure message must not echo the passwords it was handed, even fixture ones.
        assertEquals(distinct.size, asked.size, "one measurement per distinct password")
        assertEquals(distinct.toSet(), asked.toSet())
    }

    @Test
    fun `summary is unchanged from scoring every item separately`() {
        val items = listOf(
            login("123456"),
            login("123456"),
            login("password"),
            login("correct horse battery staple"),
            login("correct horse battery staple"),
            login("qF7\$zM2!vK9@wR4^"),
            login(""),
            login("hunter2").copy(deletedAt = t),
        )

        // The pre-fix computation, verbatim: the real analyzer, asked once per live login.
        val expectedWeak = items
            .filter { !it.isDeleted }
            .filter { StrengthAnalyzer.isWeak((it.content as Login).password) }
            .map { it.id }
            .toSet()

        val summary = SecurityDashboard(noBreachData()).analyze(items)

        assertEquals(expectedWeak, summary.weak.itemIds)
        // The dedup must not have swallowed an item from the counts that share its grouping key.
        assertEquals(7, summary.scannedCount)
        assertEquals(4, summary.reused.count)
    }
}
