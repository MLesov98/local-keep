package com.localkeep.core.import

import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A password is opaque bytes, not prose, so the CSV mapper must not tidy it.
 *
 * `mapRows` used to `.trim()` every mapped cell, the password included. The row parser already
 * preserves whitespace inside quotes exactly — RFC 4180 says `" hunter2 "` is a five-plus-two
 * character value — so a correctly quoted password with a leading or trailing space arrived in the
 * vault a different string from the one that logs in. Nothing on screen said so: the preview
 * deliberately never renders a password, and the login simply failed later at the site.
 *
 * There was no test file for this mapper at all before this one.
 */
class CsvPasswordWhitespaceTest {

    private val mapping = CsvColumnMapper.ColumnMapping(titleCol = 0, urlCol = 1, usernameCol = 2, passwordCol = 3, notesCol = 4)

    private fun mapOne(rows: List<List<String>>) =
        CsvColumnMapper.mapRows(rows, mapping, hasHeader = false).single()

    @Test
    fun `whitespace inside a quoted password survives the import`() {
        val item = mapOne(listOf(listOf("Bank", "https://bank.example", "me", " hunter2 ", "")))

        assertEquals(" hunter2 ", (item.content as Login).password)
    }

    @Test
    fun `a password that is only whitespace is still carried verbatim`() {
        // Deliberate: the mapper is not the place to decide a password is "wrong". Dropping it here
        // would lose the row silently, which is the failure mode this whole lens is about.
        val item = mapOne(listOf(listOf("Bank", "https://bank.example", "me", "   ", "")))

        assertEquals("   ", (item.content as Login).password)
    }

    @Test
    fun `notes keep their leading indentation`() {
        val item = mapOne(listOf(listOf("Bank", "https://bank.example", "me", "pw", "  indented note")))

        assertEquals("  indented note", (item.content as Login).notes)
    }

    @Test
    fun `the fields the user reads as text are still tidied`() {
        // The other half: trimming is right for a title and a username, and this must not become
        // "nothing is ever trimmed".
        val item = mapOne(listOf(listOf("  Bank  ", " https://bank.example ", "  me  ", "pw", "")))

        assertEquals("Bank", item.title)
        assertEquals("me", (item.content as Login).username)
    }
}
