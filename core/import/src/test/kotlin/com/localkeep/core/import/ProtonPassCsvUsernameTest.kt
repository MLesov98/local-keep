package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A CSV that carries `email` *and* `username` as separate columns keeps both.
 *
 * `email` used to sit in `USERNAME_HEADERS` beside `username`, so the two collapsed onto one column
 * slot and `indexOfFirst` bound whichever came first. Proton Pass writes both on every row and puts
 * `email` first, so `usernameCol` was permanently the email column: a login whose email cell was
 * blank imported with a blank username, and the value in the adjacent `username` cell was read by no
 * code path in `map` at all — not skipped, not rescued, not reported (2-3, 2026-09-13).
 *
 * The wizard's preview did render such a row's subtitle as "No username", which is a signal, but not
 * one that tells the user a value in their file was dropped.
 */
class ProtonPassCsvUsernameTest {

    private val header = "type,name,url,email,username,password,note,totp,createTime,modifyTime,vault"

    private fun onlyItem(row: String): VaultItem =
        when (val parsed = CsvImporter.parse((header + "\n" + row).toByteArray())) {
            is Outcome.Success -> parsed.value.items.single()
            is Outcome.Failure -> error("expected a successful parse, got: ${parsed.error.message}")
        }

    private fun onlyLogin(row: String): Login = onlyItem(row).content as Login

    @Test
    fun `a row whose email is blank imports the username beside it`() {
        assertEquals(
            "realuser",
            onlyLogin("login,Example,https://example.com,,realuser,hunter2,,,,,").username,
        )
    }

    /**
     * The control for every single-identity format the mapper already handled — Chrome, Firefox,
     * Apple, LastPass, Bitwarden all write one identity column, and some of them call it `email`.
     * Splitting the two slots must not stop an `email`-only file mapping onto `Login.username`.
     */
    @Test
    fun `a row whose username is blank still imports the email as the username`() {
        assertEquals(
            "ada@example.com",
            onlyLogin("login,Example,https://example.com,ada@example.com,,hunter2,,,,,").username,
        )
    }

    /** And a file whose only identity column is `email` is still recognised as a CSV at all. */
    @Test
    fun `an email-only header is still detected as an importable CSV`() {
        assertTrue(CsvImporter.detect("name,url,email,password\nBank,https://b.example,me@b.example,pw".toByteArray()))
    }

    /**
     * Two identities, one `Login.username`. The loser is carried as a custom field named by the
     * source column rather than dropped — the same rescue shape the unparseable-TOTP column already
     * uses, and the name is a cell read out of the export, not copy this module authored.
     */
    @Test
    fun `a row with both identities keeps the second one as a custom field`() {
        val item = onlyItem("login,Example,https://example.com,ada@example.com,realuser,hunter2,,,,,")

        assertEquals("realuser", (item.content as Login).username)
        val carried = item.customFields.single()
        assertEquals("email", carried.name)
        assertEquals("ada@example.com", carried.value)
        assertEquals(CustomFieldType.TEXT, carried.type)
    }

    /** Nothing to carry, nothing carried: a single-identity row must not grow an empty field. */
    @Test
    fun `a row with only one identity grows no custom field`() {
        assertTrue(onlyItem("login,Example,https://example.com,,realuser,hunter2,,,,,").customFields.isEmpty())
        assertTrue(
            onlyItem("login,Example,https://example.com,ada@example.com,,hunter2,,,,,").customFields.isEmpty(),
        )
    }
}
