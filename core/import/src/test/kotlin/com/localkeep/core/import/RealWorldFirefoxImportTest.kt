package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Firefox's `logins.csv`, from `src/test/resources/realworld/firefox/` (see `PROVENANCE.md` there).
 *
 * Firefox has no tile of its own; it is one of the exports behind `ImportSource.CSV` ("Other"), so
 * it goes through [CsvImporter] and has to be *recognised* by it — this test exists mostly to keep
 * that true, because Firefox's header is the one in the corpus with no `name`/`title` column at all
 * and two columns (`url`, `formActionOrigin`) that both match `URL_HEADERS`.
 */
class RealWorldFirefoxImportTest {

    private val files = listOf("firefox/simple-password.csv", "firefox/firefox-accounts.csv")

    @Test
    fun `every Firefox export is recognised and yields one item per data row`() {
        files.forEach { path ->
            assertTrue(CsvImporter.detect(RealWorldCorpus.bytes(path)), "$path was not recognised as a CSV export")
            val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(RealWorldCorpus.csvDataRows(path), parsed.items.size, "$path item count")
            assertEquals(emptyList(), parsed.skipped, "$path skipped nothing")
        }
    }

    /**
     * `url` is column 0 and `formActionOrigin` is column 4; both are in `URL_HEADERS`. The address
     * the user recognises is the first, and binding the second would title every item with an empty
     * string and file the real address nowhere.
     */
    @Test
    fun `the url column wins over formActionOrigin`() {
        val path = "firefox/simple-password.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.single()
        assertEquals("example.com", item.title)
        val login = item.content as Login
        assertEquals("foo", login.username)
        assertEquals("bar", login.password)
        assertEquals(listOf("https://example.com"), login.uris.map { it.uri })
    }

    /**
     * Firefox writes its own Sync key material into `logins.csv` as a row whose "password" is a
     * quote-escaped JSON blob. It is not a credential the user typed, but it is in the file, and an
     * importer that choked on the embedded quotes would take the rows after it down too.
     */
    @Test
    fun `the FirefoxAccounts row parses without disturbing the ordinary row beside it`() {
        val path = "firefox/firefox-accounts.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        assertEquals(2, parsed.items.size)
        val accounts = parsed.items.first().content as Login
        assertEquals(listOf("chrome://FirefoxAccounts"), accounts.uris.map { it.uri })
        assertTrue(accounts.password.startsWith("{\"version\":1"), "the JSON blob must survive unescaped")
        val ordinary = parsed.items.last().content as Login
        assertEquals("foo", ordinary.username)
        assertEquals("bar", ordinary.password)
    }
}
