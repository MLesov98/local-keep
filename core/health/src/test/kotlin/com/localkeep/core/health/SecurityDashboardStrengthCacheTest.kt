package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A re-scan only pays for passwords that changed.
 *
 * The Health screen re-scans on every vault commit (`HealthDashboardViewModel` collects
 * `presentationState`), and the strength pass is the scan's dominant cost — 221.8 s at 12 148 items
 * on device, 2026-09-20 (1B-F6). Editing one login therefore re-measured every other password in
 * the vault, although zxcvbn's verdict is a pure function of the password and had already been
 * computed for all of them.
 *
 * Counts, not a stopwatch: a timing assertion on a shared machine is a flake generator, and the
 * number of times this class *asks* is the only thing under its own control. Same reasoning, and
 * the same fake scorer, as [SecurityDashboardStrengthCostTest] — which stays green because the
 * first scan is unchanged.
 */
class SecurityDashboardStrengthCacheTest {

    private val t: Instant = Instant.parse("2026-09-20T00:00:00Z")

    private fun login(password: String): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        createdAt = t,
        updatedAt = t,
        content = Login(username = "u", password = password, passwordChangedAt = t),
    )

    private fun noBreachData() =
        BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null })

    private val asked = mutableListOf<String>()

    private fun dashboard() = SecurityDashboard(
        breachFilter = noBreachData(),
        isWeakPassword = { password ->
            asked += password
            StrengthAnalyzer.isWeak(password)
        },
    )

    @Test
    fun `a second scan scores nothing that has not changed`() {
        val items = listOf(login("123456"), login("correct horse battery staple"), login("hunter2"))
        val dashboard = dashboard()

        dashboard.analyze(items)
        assertEquals(3, asked.size, "the first scan must be unchanged — one measurement per distinct password")

        asked.clear()
        dashboard.analyze(items)
        assertTrue(
            asked.isEmpty(),
            "a re-scan of an unchanged vault must measure nothing; it measured ${asked.size} passwords",
        )
    }

    @Test
    fun `only the changed password is re-scored`() {
        val unchanged = listOf(login("123456"), login("correct horse battery staple"))
        val dashboard = dashboard()

        dashboard.analyze(unchanged + login("hunter2"))
        asked.clear()

        dashboard.analyze(unchanged + login("qF7\$zM2!vK9@wR4^"))
        assertEquals(
            listOf("qF7\$zM2!vK9@wR4^"),
            asked,
            "only the password that changed should have been measured again",
        )
    }

    /** The counts must not drift from the verdicts: a cached answer is the same answer. */
    @Test
    fun `the second scan reports the same summary as the first`() {
        val items = listOf(
            login("123456"),
            login("123456"),
            login("password"),
            login("correct horse battery staple"),
            login("qF7\$zM2!vK9@wR4^"),
            login(""),
        )
        val dashboard = dashboard()

        val first = dashboard.analyze(items)
        val second = dashboard.analyze(items)

        assertEquals(first.weak.itemIds, second.weak.itemIds)
        assertEquals(first.passwordsChecked, second.passwordsChecked, "progress denominator moved")
        assertEquals(first.scannedCount, second.scannedCount)
    }

    /**
     * Locking must empty the cache. The verdicts are booleans, so a survivor is never wrong — what
     * would be wrong is holding anything derived from the passwords through the one state in which
     * nothing password-derived is supposed to exist.
     */
    @Test
    fun `locking forgets every remembered verdict`() {
        val items = listOf(login("123456"), login("hunter2"))
        val dashboard = dashboard()

        dashboard.analyze(items)
        dashboard.forgetStrengthScores()
        asked.clear()

        dashboard.analyze(items)
        assertEquals(
            2,
            asked.size,
            "after a lock the next scan must measure from scratch; it measured ${asked.size}",
        )
    }

    /**
     * A lock landing while a scan is still running - the normal case on a big vault, where the first
     * scan takes minutes. `forget()` wipes what exists at that instant; the scan then went on asking
     * the cache, which drew a fresh key and tagged every remaining password, so a vault reporting
     * itself locked still held a live key and a tag per password until the scan's coroutine was
     * cancelled. The verdicts still have to come back - the scan is not told it failed.
     */
    @Test
    fun `a scan still running when the vault locks leaves no key and no tags behind`() {
        val cache = StrengthCache()
        val items = listOf(login("123456"), login("hunter2"), login("correct horse battery staple"))
        val dashboard = SecurityDashboard(
            breachFilter = noBreachData(),
            strengthCache = cache,
            isWeakPassword = { password ->
                asked += password
                // The lock lands while the first password is being scored.
                if (asked.size == 1) cache.forget()
                StrengthAnalyzer.isWeak(password)
            },
        )

        val summary = dashboard.analyze(items)

        assertEquals(2, summary.weak.count, "the scan itself must still complete and report")
        assertEquals(0, cache.size, "a scan that began before the lock must not cache anything after it")
        assertTrue(!cache.holdsKey, "a scan that began before the lock must not draw a new key after it")
    }

    /** The control: the scan after the unlock is a new session and caches as before. */
    @Test
    fun `a scan that begins after a lock caches normally`() {
        val cache = StrengthCache()
        val items = listOf(login("123456"), login("hunter2"))
        val dashboard = SecurityDashboard(breachFilter = noBreachData(), strengthCache = cache, isWeakPassword = {
            asked += it
            StrengthAnalyzer.isWeak(it)
        })

        dashboard.analyze(items)
        dashboard.forgetStrengthScores()
        assertEquals(0, cache.size)
        assertTrue(!cache.holdsKey)

        dashboard.analyze(items)
        assertEquals(2, cache.size, "the next session's scan is cached")
        asked.clear()
        dashboard.analyze(items)
        assertTrue(asked.isEmpty(), "and a re-scan within it measures nothing")
    }

    /** The cache never holds a password, in any form a guess could be checked against. */
    @Test
    fun `nothing cached is the password`() {
        val cache = StrengthCache()
        cache.getOrPut("correct horse battery staple") { true }
        assertEquals(1, cache.size)
        cache.forget()
        assertEquals(0, cache.size)
    }

    /** Two different passwords are two entries; the same password twice is one. */
    @Test
    fun `tags separate distinct passwords and collapse equal ones`() {
        val cache = StrengthCache()
        cache.getOrPut("a") { true }
        cache.getOrPut("b") { false }
        cache.getOrPut("a") { error("re-scored a password it had already seen") }
        assertEquals(2, cache.size)
    }
}
