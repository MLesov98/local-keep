package com.localkeep.feature.importwizard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The preview's "N items found · <format>" line names the detected *format*, and for CSV that
 * must not be the source card's label. Three cards parse as CSV — Google / Chrome, Apple Passwords
 * and Other / CSV — and until 2026-09-14 the format name for all three was the third card's
 * "Other / CSV", so a Chrome import told the user it had read an "Other / CSV" export.
 */
class ImportPreviewFormatLabelTest {

    private fun strings(): Map<String, String> {
        val file = File("src/main/res/values/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    @Test
    fun theCsvFormatLabelIsNotTheOtherCsvSourceCard() {
        val strings = strings()
        val format = strings.getValue("import_format_label_csv")
        assertNotEquals(strings.getValue("import_source_label_csv"), format)
        assertEquals("CSV", format)
    }
}
