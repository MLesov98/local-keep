package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every denial sentence this module composes into `core_ui_permission_denial_retry` ends in a full
 * stop (2026-09-20, release sweep 4C-13).
 *
 * The bug this closes: `itemedit_coordinate_denial_sentence` had no terminal stop in any of the
 * five locales, and `core_ui_permission_denial_retry` is `"%1$s You can tap it again to allow
 * access."` — so denying location produced "LocalKeep can't save your location without location
 * access You can tap it again to allow access.", two sentences run together on screen. Its two
 * siblings, the 2FA camera denial and the recovery-QR camera denial, both ended in one, so this
 * was one string rather than a pattern.
 *
 * Asserted as a rule rather than as one key, because the composition is what makes the stop
 * load-bearing: any sentence added to this family has the same obligation.
 */
class PermissionDenialSentencesEndTest {

    private val locales = listOf("", "-fr", "-de", "-es", "-pt")

    @Test
    fun `every denial sentence ends in a full stop, in every locale`() {
        val pattern = Regex("""<string name="(\w*_denial_sentence)">(.*?)</string>""")
        locales.forEach { locale ->
            val strings = File("src/main/res/values$locale/strings.xml").readText()
            val found = pattern.findAll(strings).toList()
            assertTrue(found.isNotEmpty(), "values$locale declares no denial sentence at all")
            found.forEach { match ->
                assertTrue(
                    match.groupValues[2].trimEnd().endsWith("."),
                    "values$locale/${match.groupValues[1]} runs into the retry sentence: " +
                        "\"${match.groupValues[2]}\"",
                )
            }
        }
    }

    /**
     * The premise: the retry sentence really is appended directly, with only a space between. If
     * `core:ui` ever punctuates for the caller, this rule stops being the right one.
     */
    @Test
    fun `the retry sentence still assumes the caller punctuated`() {
        val coreUi = File("../../core/ui/src/main/res/values/strings.xml").readText()
        val retry = coreUi.substringAfter("\"core_ui_permission_denial_retry\">", "")
            .substringBefore("<")
        assertTrue(retry.startsWith("%1\$s "), "the composition changed shape: \"$retry\"")
    }
}
