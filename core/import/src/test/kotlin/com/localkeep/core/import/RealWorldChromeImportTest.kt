package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chrome / Google Password Manager CSV, against the real export rows in
 * `src/test/resources/realworld/chrome/` (see that directory's `PROVENANCE.md`).
 *
 * The wizard routes `ImportSource.CHROME` to [CsvImporter], so that is what these run.
 */
class RealWorldChromeImportTest {

    private val files = listOf("chrome/simple-password.csv", "chrome/android-app-credential.csv")

    @Test
    fun `every Chrome export is recognised and yields one item per data row`() {
        files.forEach { path ->
            assertTrue(CsvImporter.detect(RealWorldCorpus.bytes(path)), "$path was not recognised as a CSV export")
            val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(RealWorldCorpus.csvDataRows(path), parsed.items.size, "$path item count")
            assertEquals(emptyList(), parsed.skipped, "$path skipped nothing")
            // Chrome's four columns carry no per-row timestamp, so the merge must not treat the
            // import instant as newer than the vault. See ParsedImport.timestampsKnown.
            assertEquals(false, parsed.timestampsKnown, "$path timestampsKnown")
        }
    }

    @Test
    fun `an ordinary row keeps its name, url, username and password`() {
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes("chrome/simple-password.csv"))
            .orFail("chrome/simple-password.csv")
        val item = parsed.items.single()
        assertEquals("www.example.com", item.title)
        val login = item.content as Login
        assertEquals("username@example.com", login.username)
        assertEquals("wpC9qFvsbWQK5Z", login.password)
        assertEquals(listOf("https://www.example.com/"), login.uris.map { it.uri })
    }

    /**
     * Chrome exports credentials saved for an Android app with an `android://<hash>@<package>/` URL
     * and an empty `name` column — the one row shape in this corpus where the title has to be
     * derived rather than copied.
     */
    @Test
    fun `an Android app credential keeps its url and is titled from the package`() {
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes("chrome/android-app-credential.csv"))
            .orFail("chrome/android-app-credential.csv")
        val item = parsed.items.single()
        assertEquals("com.xyz.example.app.android", item.title)
        val login = item.content as Login
        assertEquals("username@example.com", login.username)
        assertEquals("Qh6W4Wz55YGFNU", login.password)
        assertTrue(
            login.uris.single().uri.startsWith("android://"),
            "the android:// URL must survive verbatim, not be normalised away",
        )
    }
}
