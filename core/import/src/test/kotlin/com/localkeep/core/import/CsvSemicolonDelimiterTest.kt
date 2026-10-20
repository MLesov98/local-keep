package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A semicolon-delimited CSV — what a spreadsheet saved in a comma-decimal locale writes, and what
 * several European password managers export — was not read at all. It reached the user as the one
 * generic refusal, advising them to check for an encryption and a truncation that were not the
 * problem (1B-F3, 2026-09-20).
 *
 * The rule is comma-first: `;` is tried only after a comma reading produces no password column, so
 * nothing that parsed before can start parsing differently. The control below is what pins that.
 */
class CsvSemicolonDelimiterTest {

    private val semicolon = """
        name;url;username;password;notes
        Bank;https://bank.example;ada;hunter2;keep this
    """.trimIndent()

    @Test
    fun `a semicolon-delimited export is recognised`() {
        assertTrue(CsvImporter.detect(semicolon.toByteArray()), "a semicolon CSV was refused outright")
    }

    @Test
    fun `a semicolon-delimited export maps its columns`() {
        val parsed = CsvImporter.parse(semicolon.toByteArray(), ImportDefaultTitles())
        assertTrue(parsed is Outcome.Success, "must parse: $parsed")
        val item = parsed.value.items.single()

        assertEquals("Bank", item.title)
        val login = item.content as Login
        assertEquals("ada", login.username)
        assertEquals("hunter2", login.password)
        assertEquals("keep this", login.notes)
        assertEquals("https://bank.example", login.uris.single().uri)
        assertEquals(emptyList(), parsed.value.carriedColumns, "no column was left over")
    }

    /**
     * The control that makes the retry safe: a comma file whose cells legitimately contain
     * semicolons is still read as comma-delimited, because the comma reading binds a password
     * column and the `;` attempt is never made.
     */
    @Test
    fun `a comma file whose cells contain semicolons is still comma-delimited`() {
        val parsed = CsvImporter.parse(
            """
            name,url,username,password,notes
            Bank,https://bank.example,ada,hunter2,"one; two; three"
            """.trimIndent().toByteArray(),
            ImportDefaultTitles(),
        )
        assertTrue(parsed is Outcome.Success, "must parse: $parsed")
        val login = parsed.value.items.single().content as Login

        assertEquals("hunter2", login.password)
        assertEquals("one; two; three", login.notes)
    }

    /** And a file that is neither is still refused, rather than forced through one reading. */
    @Test
    fun `a file with no password column under either delimiter is still refused`() {
        assertFalse(CsvImporter.detect("alpha;beta;gamma\n1;2;3".toByteArray()))
        assertFalse(CsvImporter.detect("alpha,beta,gamma\n1,2,3".toByteArray()))
    }
}
