package com.localkeep.feature.files

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfficeTextExtractorTest {

    @Test
    fun `a docx yields its paragraphs, one per line`() {
        val docx = zipOf(
            "word/document.xml" to
                "<w:document><w:body>" +
                "<w:p><w:r><w:t>First paragraph</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>Second </w:t></w:r><w:r><w:t>paragraph</w:t></w:r></w:p>" +
                "</w:body></w:document>",
        )

        assertEquals("First paragraph\nSecond paragraph", OfficeTextExtractor.extract(docx))
    }

    @Test
    fun `xml entities come back as characters`() {
        val docx = zipOf(
            "word/document.xml" to "<w:p><w:t>Tom &amp; Jerry &lt;3 &quot;quotes&quot;</w:t></w:p>",
        )

        assertEquals("""Tom & Jerry <3 "quotes"""", OfficeTextExtractor.extract(docx))
    }

    /**
     * Regression for 4C.2-xlsx (release sweep 2026-09-20): a worksheet cell of type `s` holds an
     * INDEX into `xl/sharedStrings.xml`, and both parts used to be tag-stripped independently. A
     * sheet reading `Item|Cost / Widget|42 / Gadget|77` came out of the viewer as
     * `ItemCostWidgetGadget` followed by `0\t1\t2\t42\t3\t77` — the string table run together as
     * one word, then the indices printed as if they were the user's own numbers, under a note that
     * only disclaims layout. Without the fix the assertion below reports exactly that string.
     */
    @Test
    fun `a spreadsheet resolves shared-string cells and keeps one row per line`() {
        val xlsx = zipOf(
            "xl/sharedStrings.xml" to
                "<sst count=\"4\"><si><t>Item</t></si><si><t>Cost</t></si>" +
                "<si><t>Widget</t></si><si><t>Gadget</t></si></sst>",
            "xl/worksheets/sheet1.xml" to
                "<worksheet><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>42</v></c></row>" +
                "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>3</v></c><c r=\"B3\"><v>77</v></c></row>" +
                "</sheetData></worksheet>",
        )

        assertEquals(
            "Item\tCost\nWidget\t42\nGadget\t77",
            OfficeTextExtractor.extract(xlsx),
        )
    }

    @Test
    fun `the string table is never printed as content in its own right`() {
        val xlsx = zipOf(
            "xl/sharedStrings.xml" to "<sst><si><t>Rent</t></si><si><t>Mortgage</t></si></sst>",
            "xl/worksheets/sheet1.xml" to
                "<worksheet><sheetData><row><c t=\"s\"><v>1</v></c></row></sheetData></worksheet>",
        )

        // "Rent" is in the table and in no cell, so it is not this sheet's content.
        assertEquals("Mortgage", OfficeTextExtractor.extract(xlsx))
    }

    @Test
    fun `a run-split shared string comes back as one string`() {
        val xlsx = zipOf(
            "xl/sharedStrings.xml" to
                "<sst><si><r><t xml:space=\"preserve\">Quarterly </t></r><r><t>total</t></r></si></sst>",
            "xl/worksheets/sheet1.xml" to
                "<worksheet><sheetData><row><c t=\"s\"><v>0</v></c></row></sheetData></worksheet>",
        )

        assertEquals("Quarterly total", OfficeTextExtractor.extract(xlsx))
    }

    @Test
    fun `empty cells, inline strings and a missing table are all handled without inventing a value`() {
        val xlsx = zipOf(
            "xl/worksheets/sheet1.xml" to
                "<worksheet><sheetData>" +
                // No sharedStrings part at all: an `s` cell has nothing to resolve against.
                "<row><c r=\"A1\" t=\"s\"><v>0</v></c></row>" +
                "<row><c r=\"A2\" t=\"inlineStr\"><is><t>Tom &amp; Jerry</t></is></c></row>" +
                "<row><c r=\"A3\" t=\"str\"><v>=SUM</v></c><c r=\"B3\"/><c r=\"C3\"><v>9</v></c></row>" +
                "</sheetData></worksheet>",
        )

        // Row 1 resolves to nothing and is dropped; the gap in row 3 is kept because a cell follows it.
        assertEquals("Tom & Jerry\n=SUM\t\t9", OfficeTextExtractor.extract(xlsx))
    }

    @Test
    fun `slides are read in the order the zip holds them`() {
        val pptx = zipOf(
            "ppt/slides/slide1.xml" to "<a:p><a:t>Title slide</a:t></a:p>",
            "ppt/slides/slide2.xml" to "<a:p><a:t>Second slide</a:t></a:p>",
        )

        assertEquals("Title slide\n\nSecond slide", OfficeTextExtractor.extract(pptx))
    }

    @Test
    fun `parts that hold no user text are ignored`() {
        val docx = zipOf(
            "[Content_Types].xml" to "<Types><Override PartName=\"/word/document.xml\"/></Types>",
            "docProps/app.xml" to "<Properties><Application>Microsoft Word</Application></Properties>",
            "word/document.xml" to "<w:p><w:t>Only this</w:t></w:p>",
        )

        // "Microsoft Word" living in docProps must not appear as document content.
        assertEquals("Only this", OfficeTextExtractor.extract(docx))
    }

    @Test
    fun `something that is not a zip returns nothing rather than guessing`() {
        assertNull(OfficeTextExtractor.extract("this is a plain sentence".encodeToByteArray()))
        assertNull(OfficeTextExtractor.extract(ByteArray(0)))
    }

    @Test
    fun `a zip with no text parts returns null, not an empty document`() {
        val empty = zipOf("word/settings.xml" to "<w:settings/>")

        assertNull(OfficeTextExtractor.extract(empty))
    }

    @Test
    fun `an entry that decompresses past the cap aborts extraction instead of reading it all`() {
        // Highly compressible content (one repeated byte) so the zip on disk stays tiny while its
        // decompressed size is a classic zip-bomb shape: SecureMediaViewer only bounds the
        // *compressed* input at MAX_OFFICE_BYTES, 32 MiB, so a compact file like this one can still
        // expand into gigabytes unless extraction itself bounds the decompressed read. 70 MB clears
        // OfficeTextExtractor's 64 MiB per-entry cap. (This comment said 4 MiB until 2026-09-13 —
        // true of MAX_TEXT_BYTES when the extractor landed, stale since MAX_OFFICE_BYTES arrived.)
        val bomb = zipBombOf("word/document.xml", decompressedBytes = 70L * 1024 * 1024)

        assertNull(OfficeTextExtractor.extract(bomb))
    }

    /**
     * Regression test (2026-09-13): the per-entry cap was the only
     * bound there was, so a .pptx or .xlsx could present any number of `slideN.xml` / `sheetN.xml`
     * parts that were each individually legal and together inflated without limit — on whatever
     * thread called [OfficeTextExtractor.extract], which until the same fix was the composition one.
     * Every part below is 8 MiB, comfortably inside the 64 MiB per-entry cap, and their sum passes
     * the 128 MiB aggregate cap.
     *
     * The filler is one enormous tag rather than plain bytes, and both halves of that matter. Plain
     * 'A' bytes, as in [zipBombOf], would leave ~140M characters in the extractor's `parts` list,
     * the JVM would OOM, and `runCatching` would turn that into exactly the `null` this asserts — a
     * test that passes green WITHOUT the fix and proves nothing. Filler made only of tags would
     * strip to nothing and `text.ifEmpty { null }` would return null just as vacuously. One word
     * plus tag filler is the only shape that extracts non-null today and null once the cap exists.
     */
    @Test
    fun `many individually-legal parts abort once their combined size passes the cap`() {
        val deck = manyPartDeck(entries = 17, bytesPerEntry = 8 * 1024 * 1024)

        assertNull(OfficeTextExtractor.extract(deck))
    }

    /**
     * Control for the test above, and the reason the aggregate cap is not set low: the same shape,
     * well inside the cap, must still extract. A cap tight enough to refuse a real multi-part deck
     * would be a worse defect than the one it fixed.
     */
    @Test
    fun `a deck whose parts stay inside the aggregate cap is still read`() {
        val deck = manyPartDeck(entries = 2, bytesPerEntry = 8 * 1024 * 1024)

        assertEquals("S\n\nS", OfficeTextExtractor.extract(deck))
    }

    /**
     * A deck of [entries] slides, each exactly [bytesPerEntry] decompressed: one real word wrapped
     * in PowerPoint paragraph tags, then a single vast `<a:ext filler="xxx…"/>` tag. The filler
     * strips out, so each part contributes one character to the extracted text however large it is,
     * and the zip on disk stays a few KB.
     */
    private fun manyPartDeck(entries: Int, bytesPerEntry: Int): ByteArray {
        val prefix = "<a:p><a:t>S</a:t></a:p><a:ext filler=\"".encodeToByteArray()
        val suffix = "\"/>".encodeToByteArray()
        val body = ByteArray(bytesPerEntry) { 'x'.code.toByte() }
        prefix.copyInto(body)
        suffix.copyInto(body, bytesPerEntry - suffix.size)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            repeat(entries) { i ->
                zip.putNextEntry(ZipEntry("ppt/slides/slide${i + 1}.xml"))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun zipBombOf(name: String, decompressedBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            val chunk = ByteArray(64 * 1024) { 'A'.code.toByte() }
            var written = 0L
            while (written < decompressedBytes) {
                zip.write(chunk)
                written += chunk.size
            }
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
