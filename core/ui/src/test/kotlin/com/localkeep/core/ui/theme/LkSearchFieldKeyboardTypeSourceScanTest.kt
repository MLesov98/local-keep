package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F44, 2026-09-13: `LkSearchField` had no `keyboardType` parameter at all, so every search well —
 * including the vault list's, which doubles as the make-invisible reveal-phrase entry point — asked
 * for an ordinary text keyboard with no anti-learning treatment. `LkFormField`'s own KDoc already
 * names `KeyboardType.Password` as "what suppresses autocorrect and the IME's learning of the
 * value"; this pins that `LkSearchField` now accepts the same parameter and actually threads it into
 * the `KeyboardOptions` it builds, defaulting to `Text` so the other three call sites (Trash,
 * Health's category dialog, Settings) are unaffected.
 *
 * Source scan, not a render: Compose does not expose `KeyboardType` on the semantics tree the way it
 * exposes `SemanticsProperties.Password` (see [LkPasswordSemanticsTest]'s KDoc for that one) — it is
 * consumed only at the platform text-input layer, which a Robolectric composition does not drive far
 * enough to observe. [PassphraseNumberHintSourceScanTest] in `feature/generator` and
 * [HeadingSemanticsSourceScanTest] in `feature/itemedit` use the same technique for the same reason.
 */
class LkSearchFieldKeyboardTypeSourceScanTest {

    private fun source(): String {
        val file = File("src/main/kotlin/com/localkeep/core/ui/theme/LkFields.kt")
        assertTrue(file.exists(), "LkFields.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    private fun searchFieldBody(): String {
        val text = source()
        val start = text.indexOf("fun LkSearchField(")
        assertTrue(start > 0, "LkSearchField( not found — did the composable get renamed?")
        val end = text.indexOf("\n@Composable\n", start).let { if (it > start) it else text.length }
        return text.substring(start, end)
    }

    @Test
    fun `LkSearchField accepts a keyboardType parameter defaulting to Text`() {
        val body = searchFieldBody()
        assertTrue(
            "keyboardType: KeyboardType = KeyboardType.Text" in body,
            "LkSearchField has no keyboardType parameter defaulting to KeyboardType.Text",
        )
    }

    @Test
    fun `LkSearchField's keyboardType parameter actually reaches KeyboardOptions`() {
        val body = searchFieldBody()
        val optionsStart = body.indexOf("keyboardOptions = KeyboardOptions(")
        assertTrue(optionsStart > 0, "no keyboardOptions = KeyboardOptions( call found")
        val optionsEnd = body.indexOf(")", optionsStart)
        val optionsCall = body.substring(optionsStart, optionsEnd)
        assertTrue(
            "keyboardType = keyboardType" in optionsCall,
            "the keyboardType parameter is declared but never passed into KeyboardOptions — a " +
                "parameter that does nothing is the same defect as no parameter at all",
        )
    }
}
