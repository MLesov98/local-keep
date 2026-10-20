package com.localkeep.core.import

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A CSV that is not UTF-8 must not import with its non-ASCII characters replaced (S2-03, 2026-10-02).
 *
 * Excel's "CSV (Comma delimited)" writes the Windows code page, cp1252 in every language this app
 * ships in. The rows were decoded as UTF-8 with replacement, so `Pässwort1` arrived as
 * `P�sswort1` — masked in the preview, reported as a success, and a password that will never
 * log in.
 */
class CsvSourceEncodingTest {

    private val csv = "name,url,username,password\r\nCafé,https://example.com,josé,Pässwort1\r\n"

    private fun dataRow(bytes: ByteArray): List<String> = CsvColumnMapper.parseRows(bytes)[1]

    @Test
    fun `an Excel cp1252 export keeps every accented character`() {
        val row = dataRow(csv.toByteArray(Charset.forName("windows-1252")))

        assertEquals(listOf("Café", "https://example.com", "josé", "Pässwort1"), row)
    }

    /** The control: UTF-8, with and without its BOM, reads exactly as it always did. */
    @Test
    fun `a UTF-8 export is read unchanged`() {
        val expected = listOf("Café", "https://example.com", "josé", "Pässwort1")

        assertEquals(expected, dataRow(csv.toByteArray(Charsets.UTF_8)))
        assertEquals(expected, dataRow(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray(Charsets.UTF_8)))
        assertEquals("name", CsvColumnMapper.parseRows(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + csv.toByteArray())[0][0])
    }

    @Test
    fun `a UTF-16 export with its byte-order mark is read as UTF-16`() {
        val row = dataRow(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + csv.toByteArray(Charsets.UTF_16LE))

        assertEquals(listOf("Café", "https://example.com", "josé", "Pässwort1"), row)
    }
}
