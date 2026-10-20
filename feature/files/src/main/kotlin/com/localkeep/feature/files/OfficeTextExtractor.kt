package com.localkeep.feature.files

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Pulls the readable text out of an Office Open XML file (.docx / .xlsx / .pptx).
 *
 * **This is an extraction, not a rendering, and the UI must say so.** A .docx is a zip of XML; the
 * words are recoverable, the layout, tables, images and formatting are not. Presenting the result as
 * "the document" would be the kind of claim this project does not make — so the caller labels it.
 *
 * Deliberately hand-rolled over `ZipInputStream` and simple tag stripping rather than a document
 * library: the alternatives are large, and the XML ones tend to reach for `javax.xml.stream`, which
 * Android does not implement. `checkNoJvmOnlyXmlApis` fails the build on exactly that mistake, and
 * it exists because a StAX dependency once passed every unit test while every on-device KDBX parse
 * failed. Nothing here parses XML as a tree; it strips tags from the parts that hold text.
 *
 * Legacy binary formats (.doc, .xls, .ppt) are NOT this. They are not zips, this returns nothing for
 * them, and [fileKindOf] classifies them as [FileKind.OTHER] so they never arrive here.
 */
internal object OfficeTextExtractor {

    // Decompression-bomb guard: the caller (SecureMediaViewer) caps the *compressed* input at
    // MAX_OFFICE_BYTES, 32 MiB, but plain deflate can still expand a single entry roughly 1000x, so
    // a compact file can still decompress into gigabytes. A real document's text part is
    // realistically in the low tens of MB even for a large export; 64 MiB is generous headroom for
    // that while keeping the worst case well short of OOM. Far below OnePuxImporter's 256 MB, which
    // bounds a whole JSON export rather than one XML text part.
    //
    // This comment read "4 MiB" until 2026-09-13. It was true when the extractor landed — 446aee63
    // gated the OFFICE branch on MAX_TEXT_BYTES — and went stale when MAX_OFFICE_BYTES arrived in
    // 50670a16 without anyone updating the two comments that quoted the old figure.
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /**
     * The same guard applied to the *sum* across every text part, which the per-entry cap alone does
     * not give.
     *
     * A .pptx or .xlsx may carry an unlimited number of `ppt/slides/slideN.xml` or
     * `xl/worksheets/sheetN.xml` entries, so a file comfortably inside the 32 MiB compressed gate
     * can present hundreds of parts that are each individually legal under [MAX_ENTRY_BYTES] and
     * together inflate into tens of GB — bounded, before this, by nothing at all. The cost landed on
     * whichever thread called [extract]. Found 2026-09-13.
     *
     * Twice [MAX_ENTRY_BYTES]: one part may legitimately be the whole document, and two parts' worth
     * is already far more text than this viewer can put on a phone screen.
     */
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

    /** Zip entries that carry user-visible text, per format. */
    private fun holdsText(name: String): Boolean =
        name == "word/document.xml" ||
            name == SHARED_STRINGS_PART ||
            (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) ||
            isWorksheet(name)

    private fun isWorksheet(name: String): Boolean =
        name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")

    private const val SHARED_STRINGS_PART = "xl/sharedStrings.xml"

    /**
     * Returns the extracted text, or null when there was none to find — a document whose text lives
     * somewhere this does not look, a file that is not the zip its name claimed, or one whose parts
     * pass [MAX_ENTRY_BYTES] or [MAX_TOTAL_BYTES].
     *
     * @param cancelled polled once per zip entry. This runs off the main thread (see
     *   `OfficeTextViewer`), and without it a caller that navigates away cancels its coroutine while
     *   this loop carries on inflating to the end. Defaults to never-cancelled for callers that have
     *   no coroutine to ask — the tests, above all.
     */
    fun extract(bytes: ByteArray, cancelled: () -> Boolean = { false }): String? {
        val parts = mutableListOf<String>()
        // A spreadsheet is held back and resolved after the whole zip has been walked, because a
        // worksheet cell of type `s` is an INDEX into xl/sharedStrings.xml and means nothing on its
        // own. Stripping the two parts independently — which is what this did until 2026-09-20 —
        // printed the string table as one run-on word and then printed the indices as if they were
        // the user's numbers: a sheet reading `Item|Cost / Widget|42` came out as
        // `ItemCostWidgetGadget` followed by `0 1 2 42`. Wrong data presented as data, under a note
        // that only disclaims layout.
        var sharedStrings: List<String> = emptyList()
        val worksheets = mutableListOf<String>()
        // Aggregate accounting for MAX_TOTAL_BYTES: the budget each entry is read against is what
        // the entries before it left, so a deck of individually-legal parts stops at the sum.
        var decompressed = 0L
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    if (cancelled()) return null
                    val entry = zip.nextEntry ?: break
                    if (holdsText(entry.name)) {
                        val raw = readCapped(zip, MAX_TOTAL_BYTES - decompressed)
                        decompressed += raw.size
                        val xml = raw.decodeToString()
                        when {
                            entry.name == SHARED_STRINGS_PART -> sharedStrings = parseSharedStrings(xml)
                            isWorksheet(entry.name) -> worksheets += xml
                            else -> parts += stripTags(xml)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }.getOrElse { return null }
        // After the walk, not during it: the zip may hold the sheets before the string table.
        worksheets.forEach { sheet ->
            val rows = worksheetText(sheet, sharedStrings)
            if (rows.isNotEmpty()) parts += rows
        }
        val text = parts.joinToString("\n\n").trim()
        return text.ifEmpty { null }
    }

    /**
     * The workbook's string table, one entry per `<si>`.
     *
     * An `<si>` may be a single `<t>` or a sequence of `<r>` runs each holding one — the same
     * sentence split by a formatting change — so every `<t>` inside it is concatenated with no
     * separator, which is how the original string reads.
     */
    private fun parseSharedStrings(xml: String): List<String> =
        SHARED_ITEM.findAll(xml).map { item ->
            TEXT_RUN.findAll(item.groupValues[1])
                .joinToString("") { decodeEntities(it.groupValues[1]) }
        }.toList()

    /**
     * One worksheet as lines: a line per `<row>`, cells separated by tabs.
     *
     * Empty trailing cells are dropped rather than emitted as a run of tabs; a row that holds
     * nothing at all is dropped entirely, so a sheet with data in the first ten rows does not come
     * back followed by hundreds of blank lines.
     */
    private fun worksheetText(xml: String, sharedStrings: List<String>): String =
        ROW.findAll(xml)
            .map { row ->
                CELL.findAll(row.groupValues[1])
                    .map {
                        cellText(
                            attributes = it.groupValues[1],
                            body = it.groupValues[2],
                            sharedStrings = sharedStrings,
                        )
                    }
                    .toList()
                    .dropLastWhile { it.isEmpty() }
                    .joinToString("\t")
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /**
     * One cell's value as the spreadsheet shows it.
     *
     * `t="s"` is the case that was broken: the `<v>` is an index into [parseSharedStrings], not a
     * number. `t="inlineStr"` carries its text in place, `t="str"` is a formula's string result, and
     * everything else — including a cell with no `t` at all — is a literal in `<v>`. A cell whose
     * shared index is out of range yields nothing rather than a guess.
     */
    private fun cellText(attributes: String, body: String, sharedStrings: List<String>): String {
        val type = CELL_TYPE.find(attributes)?.groupValues?.get(1)
        if (type == "inlineStr") {
            return TEXT_RUN.findAll(body).joinToString("") { decodeEntities(it.groupValues[1]) }
        }
        val value = VALUE.find(body)?.groupValues?.get(1) ?: return ""
        if (type != "s") return decodeEntities(value)
        val index = value.trim().toIntOrNull() ?: return ""
        return sharedStrings.getOrElse(index) { "" }
    }

    /**
     * Reads one zip entry's decompressed bytes against two hard caps: [MAX_ENTRY_BYTES] for this
     * entry on its own, and [remainingBudget] — what the entries before it left of [MAX_TOTAL_BYTES].
     * Either one exceeded throws, which the caller's `runCatching` turns into extraction failure
     * (`null`) rather than reading an unbounded stream into memory. Thrown mid-read, so the work
     * stops at the cap instead of after it.
     */
    private fun readCapped(input: ZipInputStream, remainingBudget: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) {
                throw java.io.IOException("office zip entry exceeds $MAX_ENTRY_BYTES byte cap")
            }
            if (total > remainingBudget) {
                throw java.io.IOException("office zip entries exceed $MAX_TOTAL_BYTES byte cap")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Tags out, text in, with the few boundaries that carry meaning turned into line breaks.
     *
     * `</w:p>` ends a Word paragraph, `</a:p>` a PowerPoint one, and `</w:tr>` a table row; without
     * them every paragraph in the file runs together into one wall of words. `<w:tab/>` and
     * `<w:br/>` are the in-line equivalents.
     */
    private fun stripTags(xml: String): String {
        val withBreaks = xml
            .replace("</w:p>", "\n")
            .replace("</a:p>", "\n")
            .replace("</w:tr>", "\n")
            .replace("<w:br/>", "\n")
            .replace("<w:br />", "\n")
            .replace("<w:tab/>", "\t")
            .replace("<w:tab />", "\t")
        return TAG.replace(withBreaks, "")
            .let(::decodeEntities)
            // Three or more blank lines is the tag-stripping showing through, not the author's
            // intent.
            .replace(EXCESS_BLANK_LINES, "\n\n")
            .lines().joinToString("\n") { it.trimEnd() }
            .trim()
    }

    private fun decodeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // Ampersand last, or every entity above would be decoded twice.
        .replace("&amp;", "&")

    private val TAG = Regex("<[^>]*>")
    private val EXCESS_BLANK_LINES = Regex("\n{3,}")

    // Hand-rolled over the XML the same way `stripTags` is, and for the same reason: this file may
    // not reach for a tree parser (see the class KDoc and `checkNoJvmOnlyXmlApis`).
    private val SHARED_ITEM = Regex("<si\\b[^>]*>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
    private val TEXT_RUN = Regex("<t\\b[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    // Both cell shapes: `<c .../>` is an empty cell and carries no body, `<c ...>…</c>` a filled one.
    private val CELL = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val CELL_TYPE = Regex("\\bt=\"([^\"]*)\"")
    private val VALUE = Regex("<v\\b[^>]*>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
}
