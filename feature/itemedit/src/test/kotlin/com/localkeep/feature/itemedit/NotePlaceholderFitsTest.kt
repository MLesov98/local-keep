package com.localkeep.feature.itemedit

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LkText
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.util.Locale
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * SCL-033: the secure note's placeholder is as short as the languages it already fits in.
 *
 * The well draws a placeholder on one line up to font scale 1.3 and on two above it. Measured on
 * 360dp devices at 1.3, English, French and German are whole and Spanish and Portuguese are cut with
 * an ellipsis: a sentence a few glyphs wider than the budget, at the one scale where the budget has
 * not yet stepped up to two lines.
 *
 * The window cannot be asserted directly: Robolectric's text metrics are wider than a device's, so
 * every language reads as cut in a composed well, the three good ones included (checked). What can
 * be asserted is the comparison the device gave — each language's placeholder, measured in the
 * well's own style at font scale 1.3, is no wider than the widest of the three that fit. The
 * strings are the real catalogue entries, read per locale.
 *
 * [GraphicsMode.Mode.NATIVE] is load-bearing: under legacy graphics there is no real text measure.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
class NotePlaceholderFitsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun placeholder(lang: String): String {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return base.createConfigurationContext(config).getString(R.string.itemedit_note_placeholder)
    }

    private fun widths(): Map<String, Float> {
        var out by mutableStateOf<Map<String, Float>>(emptyMap())
        compose.setContent {
            LocalKeepTheme {
                val measurer = rememberTextMeasurer()
                out = LANGS.associateWith { measurer.measure(placeholder(it), LkText.body).size.width.toFloat() }
                Box {}
            }
        }
        compose.waitForIdle()
        return out
    }

    @Test
    fun `the placeholder is no wider in spanish or portuguese than in the languages that fit`() {
        val w = widths()
        val fitting = listOf("en", "fr", "de").maxOf { w.getValue(it) }
        for (lang in listOf("es", "pt")) {
            assertTrue(
                w.getValue(lang) <= fitting,
                "$lang '${placeholder(lang)}' measures ${w.getValue(lang)}px; the widest placeholder that " +
                    "fits at 1.3 measures ${fitting}px (all: $w)",
            )
        }
    }

    /** The control: the harness can tell the languages apart, so a green result is not a flat zero. */
    @Test
    fun `the measure distinguishes the languages`() {
        val w = widths()
        assertTrue(w.values.all { it > 0f } && w.values.toSet().size > 1, "widths: $w")
    }

    private companion object {
        val LANGS = listOf("en", "fr", "de", "es", "pt")
    }
}
