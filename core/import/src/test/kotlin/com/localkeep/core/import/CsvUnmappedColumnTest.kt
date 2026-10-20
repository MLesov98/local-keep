package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A CSV column this mapper does not recognise used to be dropped in silence: no value, no field,
 * no skip entry, no count (1B-F1, 2026-09-20). The wizard reported full success and its last step
 * then offered to delete the export, so the only copy of the dropped column went with it.
 *
 * Three real instances drove the fix, and each has a case here: a `name,url,account,password,memo`
 * spreadsheet losing both the identity and the note; NordPass's `additional_urls` losing every
 * address after the first; Dashlane's `category` losing the folder. The fourth case is the one that
 * cannot be enumerated — a header nobody has seen — and it is carried as a custom field named after
 * the column rather than discarded.
 */
class CsvUnmappedColumnTest {

    private fun parse(csv: String): ParsedImport {
        val result = CsvImporter.parse(csv.toByteArray(Charsets.UTF_8), ImportDefaultTitles())
        assertTrue(result is Outcome.Success, "fixture must parse: $result")
        return result.value
    }

    /**
     * The fixture the finding was filed against, recreated here from its header and its one row:
     * `f10ux-silentdrop.csv` lived on the emulators only.
     */
    private val silentDrop = """
        name,url,account,password,memo
        UxSilentDropSite,https://silentdrop.example,uxsilentuser,hunter2,important recovery memo
    """.trimIndent()

    @Test
    fun `an account column is the username and a memo column is the note`() {
        val login = parse(silentDrop).items.single().content as Login

        assertEquals("uxsilentuser", login.username, "the account column was dropped")
        assertEquals("important recovery memo", login.notes, "the memo column was dropped")
    }

    /** Nothing was rescued into a custom field, because nothing needed rescuing. */
    @Test
    fun `a fully recognised header carries no columns as custom fields`() {
        val parsed = parse(silentDrop)

        assertEquals(emptyList(), parsed.items.single().customFields)
        assertEquals(emptyList(), parsed.carriedColumns)
    }

    @Test
    fun `a NordPass additional_urls cell becomes further addresses`() {
        val path = "nordpass/login-with-additinal-urls.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path))
        assertTrue(parsed is Outcome.Success, "$path must parse: $parsed")
        val login = parsed.value.items.single().content as Login

        assertEquals(
            listOf("https://example.com", "https://example.net"),
            login.uris.map { it.uri },
            "the JSON array in additional_urls was dropped",
        )
    }

    /** An address the array repeats is one address: two identical URIs read as one to anything. */
    @Test
    fun `an additional_urls cell that repeats the primary address adds nothing`() {
        val login = parse(
            """
            name,url,additional_urls,username,password
            Dup,https://a.example,"[""https://a.example"",""https://b.example""]",ada,pw
            """.trimIndent(),
        ).items.single().content as Login

        assertEquals(listOf("https://a.example", "https://b.example"), login.uris.map { it.uri })
    }

    @Test
    fun `a Dashlane category column files the row in a folder`() {
        val path = "dashlane/credentials.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path))
        assertTrue(parsed is Outcome.Success, "$path must parse: $parsed")

        assertEquals(listOf("Entertainment"), parsed.value.folders.map { it.name }, "category was dropped")
        assertEquals(parsed.value.folders.single().id, parsed.value.items.single().folderId)
    }

    @Test
    fun `a column no alias matches is kept as a custom field named after its header`() {
        val parsed = parse(
            """
            name,url,username,password,Security question,Recovery code
            Bank,https://bank.example,ada,pw,First pet,ZZZZ-9999
            """.trimIndent(),
        )

        val fields = parsed.items.single().customFields
        assertEquals(listOf("Security question", "Recovery code"), fields.map { it.name })
        assertEquals(listOf("First pet", "ZZZZ-9999"), fields.map { it.value })
        assertEquals(listOf(CustomFieldType.TEXT, CustomFieldType.TEXT), fields.map { it.type })
        assertEquals(listOf("Security question", "Recovery code"), parsed.carriedColumns)
    }

    /** Nothing was kept, so nothing is reported — a header alone is not a rescued column. */
    @Test
    fun `a column that is empty on every row is neither carried nor reported`() {
        val parsed = parse(
            """
            name,url,username,password,Security question
            Bank,https://bank.example,ada,pw,
            """.trimIndent(),
        )

        assertEquals(emptyList(), parsed.items.single().customFields)
        assertEquals(emptyList(), parsed.carriedColumns)
    }

    /**
     * The control for [IGNORED_HEADERS][CsvColumnMapper]: Firefox writes five browser internals
     * beside its three real columns, and hanging those off every login would be noise, not rescue.
     */
    @Test
    fun `a Firefox export's own bookkeeping columns are not carried`() {
        val path = "firefox/simple-password.csv"
        val parsed = CsvImporter.parse(RealWorldCorpus.bytes(path))
        assertTrue(parsed is Outcome.Success, "$path must parse: $parsed")

        assertEquals(emptyList(), parsed.value.items.single().customFields)
        assertEquals(emptyList(), parsed.value.carriedColumns)
    }

    /**
     * The guard `ColumnMapping.boundColumns`' kdoc points at.
     *
     * A column field added to the mapping but left out of `boundColumns` does not fail to compile —
     * its header would simply be read twice, once into its own field and again as a custom field
     * named after the column. This header binds every column the mapper knows, so the two counts
     * agree only while the set is complete.
     */
    @Test
    fun `every column field of the mapping is accounted for as bound`() {
        val header = (
            "name,url,additional_urls,username,email,password,totp,notes,fields,tags,fav,folder," +
                "type,cardholdername,cardnumber,cvc,expirydate,full_name,phone,address1,address2," +
                "city,state,country,zipcode,custom_fields"
            ).split(",")
        val mapping = CsvColumnMapper.autoDetect(header)
        val columnFields = CsvColumnMapper.ColumnMapping::class.java.declaredFields
            .filter { it.type == java.lang.Integer::class.java }
            .map { it.name }

        assertTrue(columnFields.isNotEmpty(), "the reflection scan is broken, not the code")
        assertEquals(
            columnFields.size,
            mapping.boundColumns.size,
            "these mapping fields bound no header index, so boundColumns is incomplete: $columnFields",
        )
        assertEquals(emptyList(), mapping.extraCols, "a header that binds everything leaves nothing over")
    }

    /** And the second half of that guard: the header above really does bind each one. */
    @Test
    fun `the all-columns header binds one distinct index per column field`() {
        val header = (
            "name,url,additional_urls,username,email,password,totp,notes,fields,tags,fav,folder," +
                "type,cardholdername,cardnumber,cvc,expirydate,full_name,phone,address1,address2," +
                "city,state,country,zipcode,custom_fields"
            ).split(",")

        assertEquals(header.size, CsvColumnMapper.autoDetect(header).boundColumns.size)
        assertNotNull(CsvColumnMapper.autoDetect(header).additionalUrlsCol)
    }
}
