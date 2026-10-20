package com.localkeep.autofill

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The locked vault's inline chip has to carry a subtitle, in every language.
 *
 * The platform's inline-suggestion builder draws the chip's pill from its content, so the one call site that passed
 * `subtitle = null` got no pill: measured off the IME strip on 2026-09-20 (3B-F1) the unlock chip
 * rendered `#A1A1A7` on `#F1F0F7` — **2.29:1** — against roughly 14:1 for an ordinary suggestion
 * chip and for the browser-gate chip in the same strip. It reads as an empty strip, and it is the
 * only way back into filling once the vault has locked, so a chip nobody can see is a dead end
 * dressed as nothing at all.
 *
 * Pinned per locale because a missing translation is the same defect: `setSubtitle("")` is what an
 * absent string resolves to at runtime once lint's `MissingTranslation` is ever relaxed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnlockChipHasASubtitleTest {

    private fun textIn(locale: String): Pair<String, String> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(locale))
        return DatasetBuilder.unlockPresentationText(context.createConfigurationContext(config))
    }

    @Test
    fun `every shipped language gives the unlock chip a title and a subtitle`() {
        for (locale in listOf("en", "fr", "de", "es", "pt")) {
            val (title, subtitle) = textIn(locale)
            assertTrue(title.isNotBlank(), "[$locale] the unlock chip has no title")
            assertTrue(
                subtitle.isNotBlank(),
                "[$locale] the unlock chip has no subtitle, so the platform draws it with no " +
                    "pill — the 2.29:1 strip of 3B-F1.",
            )
            assertNotEquals(
                title,
                subtitle,
                "[$locale] the chip says the same thing twice, which buys the pill and says nothing",
            )
        }
    }
}
