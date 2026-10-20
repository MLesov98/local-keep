package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `LkSlider`'s accessibility label used to be built by string interpolation —
 * `"$label, $value"` — with the comma hardcoded in Kotlin. A screen-reader label assembled that
 * way cannot be reordered or re-punctuated by a translator, so it moved to a format-string
 * resource (`core_ui_slider_label`, 2026-08-10). This is a pure JVM `kotlin.test`, with no
 * Compose/Robolectric runtime to call `stringResource` from, so it reads the base (English)
 * resource file directly — same approach as `OnboardingCopyTest`.
 */
class LkIndicatorsCopyTest {

    private val strings: String = File("src/main/res/values/strings.xml").readText()

    private fun stringResource(name: String): String {
        val match = Regex("""<string name="$name"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings)
            ?: error("$name not found in strings.xml")
        return match.groupValues[1]
    }

    @Test
    fun sliderLabelIsAFormatStringWithTwoPositionalArgs() {
        val label = stringResource("core_ui_slider_label")
        assertTrue("%1\$s" in label, "expected a %1\$s placeholder, got: $label")
        assertTrue("%2\$s" in label, "expected a %2\$s placeholder, got: $label")
    }

    /** Guards against a regression back to a hardcoded, non-positional `%s` in either slot. */
    @Test
    fun sliderLabelUsesNoBarePercentS() {
        val label = stringResource("core_ui_slider_label")
        assertEquals(0, Regex("""(?<!\$)%s""").findAll(label).count(), "bare %s in: $label")
    }
}
