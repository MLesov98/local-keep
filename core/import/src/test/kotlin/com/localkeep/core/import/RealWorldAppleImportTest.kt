package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Apple Passwords / iCloud Keychain (and Safari) CSV — both header variants Apple has shipped, from
 * `src/test/resources/realworld/apple/` (see that directory's `PROVENANCE.md`).
 *
 * The wizard routes `ImportSource.APPLE` to [CsvImporter].
 */
class RealWorldAppleImportTest {

    private val files = listOf("apple/safari-modern-header.csv", "apple/safari-old-header.csv")

    @Test
    fun `both header variants are recognised and yield one item per data row`() {
        files.forEach { path ->
            assertTrue(CsvImporter.detect(RealWorldCorpus.bytes(path)), "$path was not recognised as a CSV export")
            val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            assertEquals(RealWorldCorpus.csvDataRows(path), parsed.items.size, "$path item count")
            assertEquals(emptyList(), parsed.skipped, "$path skipped nothing")
            val login = parsed.items.single().content as Login
            assertEquals("example.com (example_user)", parsed.items.single().title, "$path title")
            assertEquals("example_user", login.username, "$path username")
            assertEquals("example_p@ssword", login.password, "$path password")
        }
    }

    /**
     * The modern header adds `Notes` and `OTPAuth`. The note in this fixture is a quoted cell with
     * an embedded newline — one row, two lines — and the seed is `otpauth://totp/test?secret=examplesecret`.
     *
     * The seed in the fixture is the placeholder `examplesecret`, which `TotpFieldParser` rejects —
     * it is not a secret `Base32.decode` can turn into bytes, and a `Totp` built from it would be
     * an item that silently never renders a code. What the importer must not do is *drop* it: it is
     * rescued as a HIDDEN custom field, which is what `CsvColumnMapper`'s `rescuedTotp` exists for.
     */
    @Test
    fun `the modern header carries the multi-line note and rescues the unparseable seed`() {
        val path = "apple/safari-modern-header.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.single()
        val login = item.content as Login
        assertEquals("Example note\nMore notes on new line", login.notes)
        assertEquals(null, login.totp, "the fixture's seed is not Base32, so no Totp can be built from it")
        val rescued = item.customFields.single()
        assertEquals("otpauth://totp/test?secret=examplesecret", rescued.value)
        assertEquals(CustomFieldType.HIDDEN, rescued.type)
    }

    @Test
    fun `the old four-column header carries no note and no seed`() {
        val path = "apple/safari-old-header.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.single()
        assertEquals("", (item.content as Login).notes)
        assertEquals(emptyList(), item.customFields)
    }
}
