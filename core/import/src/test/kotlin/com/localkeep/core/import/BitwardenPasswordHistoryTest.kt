package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Login
import com.localkeep.core.model.PasswordHistory
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An import must not be a door around the app's own retention limit.
 *
 * Bitwarden keeps password history without a bound, so a long-lived account's export can carry
 * dozens of live secrets per item. Until 2026-07-31 every one of them came across and stayed in the
 * vault forever, invisible — the user migrated a history they had never seen into an app that
 * showed it to them even less.
 */
class BitwardenPasswordHistoryTest {

    private fun parse(json: String): ParsedImport =
        when (val outcome = BitwardenJsonImporter.parse(json.toByteArray())) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("expected a successful parse, got: ${outcome.error.message}")
        }

    /** Oldest first, which is the order Bitwarden writes them in. */
    private fun exportWithHistory(count: Int): String {
        val history = (0 until count).joinToString(",") { i ->
            val minute = "%02d".format(i)
            """{ "password": "pw$i", "lastUsedDate": "2026-01-01T00:$minute:00Z" }"""
        }
        return """
            { "encrypted": false, "folders": [], "items": [
              { "type": 1, "name": "Bank",
                "login": { "username": "me", "password": "current" },
                "passwordHistory": [$history] }
            ] }
        """.trimIndent()
    }

    @Test
    fun `an unbounded export history is cut to the cap on the way in`() {
        val login = parse(exportWithHistory(30)).items.single().content as Login
        assertEquals(PasswordHistory.MAX_ENTRIES, login.passwordHistory.size)
    }

    @Test
    fun `the entries kept are the newest, not the first the export happened to list`() {
        val login = parse(exportWithHistory(30)).items.single().content as Login
        // pw29 is the most recent change; pw0 is nine years of password ago. Taking the head of an
        // oldest-first list would have kept exactly the wrong five.
        assertEquals(
            listOf("pw29", "pw28", "pw27", "pw26", "pw25"),
            login.passwordHistory.map { it.password },
        )
    }

    @Test
    fun `a short history survives intact`() {
        val login = parse(exportWithHistory(2)).items.single().content as Login
        assertEquals(2, login.passwordHistory.size)
        assertTrue(login.passwordHistory.map { it.password }.containsAll(listOf("pw0", "pw1")))
    }

    /**
     * The date is not the entry's ticket in. An entry carrying a real password but a missing,
     * blank or unparseable `lastUsedDate` used to be dropped whole, and nothing recorded it —
     * `skipped` is only appended when a whole item fails to map, so the item still read as fully
     * imported and a secret the user believed was migrated was gone.
     */
    @Test
    fun `a history entry with no usable date keeps its password`() {
        val json = """
            { "encrypted": false, "folders": [], "items": [
              { "type": 1, "name": "Bank", "creationDate": "2020-03-04T05:06:07Z",
                "login": { "username": "me", "password": "current" },
                "passwordHistory": [
                  { "password": "dated", "lastUsedDate": "2026-01-01T00:00:00Z" },
                  { "password": "undated" },
                  { "password": "blank-date", "lastUsedDate": "" },
                  { "password": "nonsense-date", "lastUsedDate": "not a date" }
                ] }
            ] }
        """.trimIndent()
        val login = parse(json).items.single().content as Login

        assertEquals(
            listOf("dated", "undated", "blank-date", "nonsense-date"),
            login.passwordHistory.map { it.password },
        )
        // The stand-in is the item's own creation date — the earliest anything on it can have
        // happened — so an undated entry sorts oldest rather than jumping the dated ones.
        assertEquals(
            listOf(Instant.parse("2020-03-04T05:06:07Z")),
            login.passwordHistory.drop(1).map { it.changedAt }.distinct(),
        )
    }

    /**
     * Control for the test above: an entry with no password at all is still dropped. Only the
     * date requirement was loosened, and a blank secret is nothing to remember.
     */
    @Test
    fun `a history entry with no password is still dropped`() {
        val json = """
            { "encrypted": false, "folders": [], "items": [
              { "type": 1, "name": "Bank",
                "login": { "username": "me", "password": "current" },
                "passwordHistory": [
                  { "password": "", "lastUsedDate": "2026-01-01T00:00:00Z" },
                  { "lastUsedDate": "2026-01-02T00:00:00Z" }
                ] }
            ] }
        """.trimIndent()
        val login = parse(json).items.single().content as Login
        assertTrue(login.passwordHistory.isEmpty())
    }
}
