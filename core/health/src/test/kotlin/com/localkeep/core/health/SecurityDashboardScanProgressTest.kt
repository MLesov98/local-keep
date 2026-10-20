package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

/**
 * F4-2: the strength pass (the scan's dominant cost — see [SecurityDashboardStrengthCostTest])
 * knows how many distinct passwords it will score before it scores the first one, so a caller can
 * show a determinate progress indicator instead of an indeterminate spinner. This pins that the
 * total really is reported up front, not derived after the fact.
 *
 * F4-1: `analyze()` has no suspension point of its own, so a coroutine cancelling the `Job` around
 * it cannot stop it mid-flight — only whether its result gets published. [checkCancelled] is the
 * seam that lets a caller actually stop the work. This pins that it is consulted often enough
 * (once per distinct password) to matter, not just accepted and ignored.
 */
class SecurityDashboardScanProgressTest {

    private val t: Instant = Instant.parse("2026-08-31T00:00:00Z")

    private fun login(password: String): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Login(username = "u", password = password, passwordChangedAt = t),
    )

    /** No corpus: breach matching is not what these tests are about, and a null source never throws. */
    private fun noBreachData() = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null })

    @Test
    fun `onProgress reports the true total before the first password is scored, then counts up to it`() {
        val distinct = listOf("correct horse battery staple", "hunter2", "Tr0ub4dor&3", "qF7\$zM2!vK9@wR4^")
        // Duplicated logins and one empty-password item, exactly like the item-vs-distinct-password
        // split SecurityDashboardStrengthCostTest already pins — total must be the distinct count,
        // not the login count.
        val items = distinct.flatMap { listOf(login(it), login(it)) } + login("")
        val dashboard = SecurityDashboard(noBreachData())

        val calls = mutableListOf<Pair<Int, Int>>()
        dashboard.analyze(items, onProgress = { scanned, total -> calls += scanned to total })

        assertEquals(distinct.size, calls.size, "one progress call per distinct password")
        assertTrue(calls.all { it.second == distinct.size }, "every call already knows the true total: $calls")
        assertEquals((1..distinct.size).toList(), calls.map { it.first }, "scanned counts up by one each call")
    }

    @Test
    fun `onProgress never fires when there is nothing to score`() {
        val items = listOf(login(""))
        val dashboard = SecurityDashboard(noBreachData())

        var fired = false
        dashboard.analyze(items, onProgress = { _, _ -> fired = true })

        assertTrue(!fired, "an empty-password-only vault has no distinct password to report progress for")
    }

    @Test
    fun `checkCancelled is asked before every distinct password, and stopping it stops the scan`() {
        val distinct = listOf("correct horse battery staple", "hunter2", "Tr0ub4dor&3", "qF7\$zM2!vK9@wR4^")
        val items = distinct.map { login(it) }
        var scored = 0
        val dashboard = SecurityDashboard(noBreachData()) { password ->
            scored++
            StrengthAnalyzer.isWeak(password)
        }

        var checks = 0
        val cancelAfter = 2
        assertFailsWith<CancellationException> {
            dashboard.analyze(
                items,
                checkCancelled = {
                    checks++
                    if (checks > cancelAfter) throw CancellationException("superseded")
                },
            )
        }

        // The loop must have actually stopped doing the expensive work, not merely thrown after
        // finishing it: scoring at most `cancelAfter` distinct passwords is the whole point of this
        // seam existing (F4-1) — a check that is consulted but not obeyed until the loop's own end
        // would still burn the full ~19.6s on a superseded scan.
        assertTrue(scored <= cancelAfter, "must stop scoring once cancelled, scored=$scored")
    }
}
