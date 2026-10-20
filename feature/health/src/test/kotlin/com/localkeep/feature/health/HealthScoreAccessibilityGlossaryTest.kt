package com.localkeep.feature.health

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-09-13: `health_score_accessibility` said "Security score", and
 * the project glossary rules that word out here — *security* names a category (the
 * drawer group and the Settings group), both of which *contain* password health, and this screen's
 * own title is "Password health" (`health_screen_title`). Pins the English string reads
 * "Password health score", not "Security score", and does the same read-only value check across all
 * four locale catalogues so a future re-translation cannot quietly reintroduce the category word.
 */
class HealthScoreAccessibilityGlossaryTest {

    private fun stringValue(path: String, key: String): String {
        val file = File(path)
        assertTrue(file.exists(), "$path not found at ${file.absolutePath}")
        val xml = file.readText()
        val marker = "name=\"$key\""
        val idx = xml.indexOf(marker)
        assertTrue(idx > 0, "$key is not declared in $path")
        val valueStart = xml.indexOf('>', idx) + 1
        val valueEnd = xml.indexOf("</string>", valueStart)
        return xml.substring(valueStart, valueEnd)
    }

    @Test
    fun `the English string names password health, not security`() {
        val value = stringValue("src/main/res/values/strings.xml", "health_score_accessibility")
        assertTrue(value.contains("Password health", ignoreCase = false), "value was: $value")
        assertFalse(value.contains("Security", ignoreCase = true), "value was: $value")
    }

    /** The category word this glossary rules out is English-only ("security"/"sécurité"/etc. all
     * differ), so the cross-locale control checks the one thing that generalises: none of the four
     * catalogues' values still carry their own locale's word for "security". */
    @Test
    fun `no locale catalogue still says security`() {
        val bannedByLocale = mapOf(
            "fr" to "sécurité",
            "de" to "sicherheit",
            "es" to "seguridad",
            "pt" to "segurança",
        )
        bannedByLocale.forEach { (locale, banned) ->
            val value = stringValue("src/main/res/values-$locale/strings.xml", "health_score_accessibility")
            assertFalse(
                value.lowercase().contains(banned),
                "values-$locale/strings.xml health_score_accessibility still says \"$banned\": $value",
            )
        }
    }
}
