package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The scan reads each vault item a fixed number of times, however many items there are.
 *
 * ### Why this is a counting test and not a stopwatch
 *
 * A device sweep reported the Health scan as **superlinear in vault size** — 221.8 s at 12 148
 * items against the ~19.6 s measured for a 10 000-item scan, "~11× the time for ~1.2×
 * the items" (1B-F6, 2026-09-20). Profiled on a desktop JVM with the real zxcvbn, it is not:
 *
 * ```
 * n=1000 distinct=1000  118ms  0.118 ms/item
 * n=2000 distinct=2000  283ms  0.142 ms/item
 * n=4000 distinct=4000  491ms  0.123 ms/item
 * n=8000 distinct=8000  955ms  0.119 ms/item
 * ```
 *
 * Flat per item across an eightfold range. What the two device figures differ in is not size but
 * *distinct passwords*, which is the axis the cost actually lies along — the strength pass scores
 * each distinct password once, deliberately (see `SecurityDashboardStrengthCostTest`):
 *
 * ```
 * n=4000, 4000 distinct  468ms
 * n=4000,  400 distinct   41ms
 * n=4000,   40 distinct    7ms
 * ```
 *
 * The 12 148-item vault was 2 148 real items plus a 10 000-row import whose every row carries its
 * own password; the 10 000-item vault behind the 19.6 s figure did not. The report's own progress
 * trace says the same thing in the other direction: 0→51 % took 157 s and 51→100 % took 61 s, so
 * the scan ran *faster* as it went, which is not something a quadratic pass does.
 *
 * So there is nothing here to make linear — it already is, and a timing assertion on a shared CI
 * machine would be a flake rather than a guard. What is worth pinning is the property the report
 * doubted, in the one form that is deterministic: **how many times each pass touches the item
 * list.** `analyze` runs several whole-list passes (`loginItems`, reuse, age, duplicates), so reads
 * are a small multiple of the vault size; a nested scan anywhere in it would square that, and this
 * test would say so at a glance.
 *
 * The strength and breach functions are stubbed, because their cost is already pinned per distinct
 * password by the two tests named above and running the real zxcvbn here would only make this slow.
 */
class SecurityDashboardScanIsLinearTest {

    private val t: Instant = Instant.parse("2026-09-07T00:00:00Z")

    /**
     * A list that counts how many elements the code under test pulls out of it.
     *
     * Delegation gives every other `List` member for free, so only the two accessors that can read
     * an element are overridden. Extension functions — `filter`, `asSequence`, `flatMap` — all go
     * through `iterator()`, so they are counted too.
     */
    private class CountingList<T>(private val delegate: List<T>) : List<T> by delegate {
        var reads = 0
            private set

        override fun iterator(): Iterator<T> = object : Iterator<T> {
            private val inner = delegate.iterator()
            override fun hasNext(): Boolean = inner.hasNext()
            override fun next(): T {
                reads++
                return inner.next()
            }
        }

        override fun get(index: Int): T {
            reads++
            return delegate[index]
        }
    }

    private fun vault(n: Int): List<VaultItem> = (0 until n).map { i ->
        VaultItem(
            id = UUID.randomUUID(),
            title = "Site$i",
            createdAt = t,
            updatedAt = t,
            content = Login(
                username = "user$i",
                password = "Pass$i!aB",
                uris = listOf(UriMatch(uri = "https://site${i % 97}.example")),
                passwordChangedAt = t,
            ),
        )
    }

    /** No corpus, and no real scoring: this test is about traversal, not verdicts. */
    private fun readsFor(n: Int): Int {
        val items = CountingList(vault(n))
        SecurityDashboard(
            breachFilter = BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null }),
            isWeakPassword = { false },
            isBreached = { false },
        ).analyze(items)
        return items.reads
    }

    @Test
    fun `item reads grow with the vault, not with its square`() {
        val small = readsFor(SMALL)
        val large = readsFor(SMALL * 4)

        assertTrue(
            small <= SMALL * PASS_CEILING,
            "a $SMALL-item vault was read $small times — more than $PASS_CEILING whole-list passes, " +
                "which means a pass is doing more than walking the vault once.",
        )
        assertTrue(
            large <= small * 5,
            "quadrupling the vault multiplied item reads by ${large.toDouble() / small} " +
                "($small → $large). Linear is 4; anything near 16 is a nested scan over the item " +
                "list, which is what makes a large vault's Health scan unusable.",
        )
    }

    private companion object {
        const val SMALL = 400

        /**
         * How many whole-list passes `analyze` is allowed. It makes four today — the Login
         * projection, reuse, age and duplicates — and the slack is for one more being added
         * without this test having to be argued about; it is a ceiling on the shape, not a budget.
         */
        const val PASS_CEILING = 10
    }
}
