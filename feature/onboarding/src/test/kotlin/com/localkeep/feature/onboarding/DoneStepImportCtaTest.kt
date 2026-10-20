package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Done step's primary must not repeat the migration step's, which is the screen before it.
 *
 * 2A-F7, on a device 2026-09-20: step 5's CTA read `Import my passwords`, tapping it advanced to
 * `You're set up`, and *that* screen's primary read `Import my passwords` again. The flow is
 * correct — `chooseMigration(migrate = true)` sets `step = DONE` and the second tap opens the
 * wizard — but two consecutive screens offering the identical words is what a user reads as the
 * first tap having done nothing.
 *
 * Pinned per language, because a translation that collapses the two back together reintroduces it
 * in that language alone, where nobody reading English would ever see it. The five files are read
 * directly: this is a pure JVM test with no Compose runtime to resolve resources from, the same way
 * `OnboardingCopyTest` reads the base file.
 */
class DoneStepImportCtaTest {

    private fun stringValue(locale: String, name: String): String {
        val dir = if (locale == "en") "values" else "values-$locale"
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue(file.exists(), "no strings.xml for $locale at ${file.absolutePath}")
        return Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())
            ?.groupValues
            ?.get(1)
            ?: error("$name is missing from $dir/strings.xml")
    }

    @Test
    fun `the done step and the migration step do not offer the same words`() {
        for (locale in listOf("en", "fr", "de", "es", "pt")) {
            val migrate = stringValue(locale, "onboarding_migrate_import_cta")
            val done = stringValue(locale, "onboarding_done_import_cta")
            assertTrue(done.isNotBlank(), "[$locale] the Done step's import CTA is blank")
            assertNotEquals(
                migrate,
                done,
                "[$locale] the migration step and the Done step carry the identical primary label, " +
                    "so the first tap reads as a no-op (2A-F7)",
            )
        }
    }
}
