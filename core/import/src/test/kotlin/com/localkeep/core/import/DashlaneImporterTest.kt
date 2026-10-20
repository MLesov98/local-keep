package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [DashlaneImporter]'s own behaviour: header-set recognition and the per-schema mappings. */
class DashlaneImporterTest {

    /**
     * Recognition is an **exact** header-set match, not synonym matching.
     *
     * The alternative was the generic mapper's approach, and it is the wrong one here: being
     * recognised by column synonyms is not the same as being understood — `payments.csv`'s
     * `cc_number` means a card number, and a mapper that merely recognised the file would have put
     * it in no field at all.
     */
    @Test
    fun `a header missing one of its columns is not recognised`() {
        assertTrue(
            DashlaneImporter.detect("title,note\n01,test".toByteArray()),
            "the secure-notes header, exactly",
        )

        val extraColumn = "title,note,extra\n01,test,x".toByteArray()
        assertFalse(DashlaneImporter.detect(extraColumn), "one more column is a different file")

        val missingColumn = "type,number,name,issue_date,expiration_date,place_of_issue\ncard,1,A,,,"
        assertFalse(DashlaneImporter.detect(missingColumn.toByteArray()), "id.csv minus its state column")
    }

    /** Header matching ignores case and surrounding space, as CSV exporters vary on both. */
    @Test
    fun `header matching is case and whitespace insensitive`() {
        assertTrue(DashlaneImporter.detect(" Title , Note \n01,test".toByteArray()))
    }

    /**
     * `personal-info.csv`'s `website` row has a URL and nothing else a person is. The url is
     * carried under the source's own column name rather than being dropped or turned into a login
     * the user never had.
     */
    @Test
    fun `a website row keeps its url as a named field`() {
        val path = "dashlane/personal-info.csv"
        val parsed = DashlaneImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val website = parsed.items.first { it.title == "Website" }

        assertEquals("website.com", website.customFields.first { it.name == "url" }.value)
    }

    /**
     * `id.csv`'s `name` cell is one string. Split on the **last** space, so a middle name stays with
     * the first name rather than being lost: [Identity] has no middle-name field, and a record the
     * user cannot find by name is worse than one whose parts sit slightly off.
     */
    @Test
    fun `a single name cell is split on the last space`() {
        val rows = """
            type,number,name,issue_date,expiration_date,place_of_issue,state
            passport,1,Mary Jane Watson,,,,
        """.trimIndent().toByteArray()
        val identity = DashlaneImporter.parse(rows).orFail("inline").items.single().content as Identity

        assertEquals("Mary Jane", identity.firstName)
        assertEquals("Watson", identity.lastName)
    }

    /** A blank data row states nothing and is named as an incomplete entry rather than imported. */
    @Test
    fun `a row with nothing but its type is reported, not imported`() {
        val rows = """
            type,number,name,issue_date,expiration_date,place_of_issue,state
            passport,,,,,,
        """.trimIndent().toByteArray()
        val parsed = DashlaneImporter.parse(rows).orFail("inline")

        assertEquals(emptyList(), parsed.items)
        assertEquals(listOf(SkipReason.IncompleteEntry), parsed.skipped.map { it.reason })
    }

    /** Every non-blank cell the model has no field for survives, named by its own column. */
    @Test
    fun `unmapped columns are carried under their own header`() {
        val path = "dashlane/id.csv"
        val parsed = DashlaneImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val passport = parsed.items.first { item ->
            item.customFields.any { it.name == "type" && it.value == "passport" }
        }

        assertEquals(
            listOf("type", "issue_date", "expiration_date", "place_of_issue"),
            passport.customFields.map { it.name },
        )
        assertEquals("somewhere in Germany", passport.customFields.first { it.name == "place_of_issue" }.value)
    }

    /** A bank row's note body is empty; everything it says lives in its fields. */
    @Test
    fun `a bank account note carries the account it names`() {
        val path = "dashlane/payments.csv"
        val bank = DashlaneImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            .items.first { it.content is Note }

        assertEquals("John's savings account", bank.title)
        assertEquals("US-ALLY", bank.customFields.first { it.name == "issuing_bank" }.value)
        assertEquals("John Doe", bank.customFields.first { it.name == "account_holder" }.value)
    }
}
