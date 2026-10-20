package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The breach pass costs one corpus probe per DISTINCT password, not one per item.
 *
 * The same argument as [SecurityDashboardStrengthCostTest], applied to the pass next to it, which
 * was left un-deduped: `isPotentiallyBreached` is a pure function of the password, so fanning one
 * verdict out over every item that shares it reports exactly the same set. A probe is much cheaper
 * than a zxcvbn measurement, so this is not a claim about scan latency — it is that the vault with
 * the most reuse, the one this screen exists to complain about, was the one repeating the most
 * lookups.
 *
 * Counting is the only observable difference between the two shapes, so — exactly as the strength
 * test does — the count is paired with a control pinning the summary against the per-item verdicts
 * it must keep reproducing.
 */
class SecurityDashboardBreachCostTest {

    private val t: Instant = Instant.parse("2026-09-07T00:00:00Z")

    private fun login(password: String): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Login(username = "u", password = password, passwordChangedAt = t),
    )

    /** No corpus. The real filter is not what this test is about; the probe count is. */
    private fun noBreachData() = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null })

    private val distinct = listOf("123456", "correct horse battery staple", "hunter2")

    private fun items() = (0 until 12).map { login(distinct[it % distinct.size]) } +
        login("") +
        login("only-in-the-trash").copy(deletedAt = t)

    @Test
    fun `probes each distinct password once, not once per item`() {
        val asked = mutableListOf<String>()
        val dashboard = SecurityDashboard(
            breachFilter = noBreachData(),
            isBreached = { password ->
                asked += password
                false
            },
        )

        dashboard.analyze(items())

        // Counts only — a failure message must not echo the passwords it was handed, even fixture ones.
        assertEquals(distinct.size, asked.size, "one probe per distinct password")
        assertEquals(distinct.toSet(), asked.toSet())
    }

    /**
     * Control. This held before the dedup and must keep holding: a "fix" that probes fewer
     * passwords by probing the wrong ones would satisfy the count above and fail here.
     */
    @Test
    fun `breached set is unchanged from probing every item separately`() {
        val items = items()
        val breached: (String) -> Boolean = { it == "123456" }

        // The pre-fix computation, verbatim: asked once per live login, guarded on non-empty.
        val expected = items
            .filter { !it.isDeleted }
            .map { it to (it.content as Login).password }
            .filter { (_, password) -> password.isNotEmpty() && breached(password) }
            .map { (item, _) -> item.id }
            .toSet()

        val summary = SecurityDashboard(breachFilter = noBreachData(), isBreached = breached).analyze(items)

        assertEquals(expected, summary.breached.itemIds)
        assertEquals(4, summary.breached.count, "the four items sharing the breached password")
    }

    @Test
    fun `an empty password is never probed`() {
        val asked = mutableListOf<String>()
        SecurityDashboard(
            breachFilter = noBreachData(),
            isBreached = { password ->
                asked += password
                false
            },
        ).analyze(listOf(login(""), login("")))

        assertEquals(0, asked.size, "the empty password carries no verdict and must not be looked up")
    }
}
