package com.localkeep.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The retention bound on [Login.passwordHistory].
 *
 * Before 2026-07-31 there was no bound and no reader: every autofill-captured password change
 * appended forever, an import carried a vendor's entire history across, and no screen ever showed
 * or deleted a single entry. These tests exist because "how many old passwords does this app keep"
 * must have an answer that a regression cannot quietly change back to "all of them".
 */
class PasswordHistoryTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun entry(password: String, minute: Long) =
        PasswordHistoryEntry(password, t0.plusSeconds(minute * 60))

    @Test
    fun `append keeps only the newest MAX_ENTRIES`() {
        var history = emptyList<PasswordHistoryEntry>()
        repeat(20) { i ->
            history = PasswordHistory.append(history, "pw$i", t0.plusSeconds(i * 60L))
        }
        assertEquals(PasswordHistory.MAX_ENTRIES, history.size)
        // Newest first, and the ones kept are the last five appended — not the first five.
        assertEquals(listOf("pw19", "pw18", "pw17", "pw16", "pw15"), history.map { it.password })
    }

    @Test
    fun `append puts the newest entry first`() {
        val history = PasswordHistory.append(listOf(entry("old", 0)), "new", t0.plusSeconds(600))
        assertEquals(listOf("new", "old"), history.map { it.password })
    }

    @Test
    fun `a blank previous password is not recorded`() {
        // A login that never had a password has nothing to remember. An empty entry in a list of
        // secrets reads as data loss rather than as an item that started out empty.
        assertTrue(PasswordHistory.append(emptyList(), "", t0).isEmpty())
    }

    @Test
    fun `trim keeps the newest, not the first — an import arrives in the vendor's order`() {
        // Oldest-first, which is how Bitwarden's export is written. Taking the first five would
        // discard exactly the entries worth keeping.
        val oldestFirst = (0 until 12).map { entry("pw$it", it.toLong()) }
        val trimmed = PasswordHistory.trim(oldestFirst)
        assertEquals(PasswordHistory.MAX_ENTRIES, trimmed.size)
        assertEquals(listOf("pw11", "pw10", "pw9", "pw8", "pw7"), trimmed.map { it.password })
    }

    @Test
    fun `trim leaves an already-bounded newest-first list untouched`() {
        val history = listOf(entry("b", 10), entry("a", 0))
        assertEquals(history, PasswordHistory.trim(history))
    }

    @Test
    fun `entries sharing a timestamp keep their relative order`() {
        // A bulk import stamps everything the same instant; an unstable sort would shuffle the
        // list on every read and make the screen's order meaningless.
        val same = (0 until 5).map { PasswordHistoryEntry("pw$it", t0) }
        assertEquals(same.map { it.password }, PasswordHistory.trim(same).map { it.password })
    }
}
