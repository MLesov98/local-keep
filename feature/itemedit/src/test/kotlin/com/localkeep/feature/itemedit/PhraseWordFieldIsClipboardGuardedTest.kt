package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The seed-phrase word entry field must sit inside a `GuardedSecretField`, so that Copy and Cut on
 * it go through the app's own clipboard path rather than the platform's default text toolbar.
 *
 * Found by the 2026-09-13 pre-iOS reconciliation sweep and sharpened by its verifier, which
 * refuted the larger claim and left this residual. The claim that this field is a *re-auth bypass*
 * is false: `ItemEditViewModel.focusPhraseSlot` gates loading a stored word into it behind the same
 * prompt that guards Reveal, and that function's own KDoc names the risk. What is real is narrower.
 * `PHRASE_FIELD` is in `ALWAYS_REAUTH_FIELDS`, and this is the one editable field in `PhraseFields`
 * drawn with `BoxedField` (an `LkFormField` alias, which installs no `SecretTextToolbar`) instead of
 * `SecretField`/`LkPasswordField`. So after a legitimate re-auth, a long-press Copy lifts the word
 * through the platform toolbar, skipping `ClipboardHelper` — and with it `EXTRA_IS_SENSITIVE` and
 * the 30-second auto-clear. A word of a seed phrase then sits in the clipboard until something else
 * overwrites it. `GuardedSecretField`'s own KDoc names this hole in general terms; this pins the one
 * call site that had it.
 *
 * The field stays *visually* unmasked on purpose — `LkFormField`'s KDoc argues that for BIP-39
 * capture you must see the word you are typing — so this test asserts the toolbar guard only, and
 * deliberately does not assert a `VisualTransformation`.
 */
class PhraseWordFieldIsClipboardGuardedTest {

    private val screen = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")

    /**
     * The extent of every `GuardedSecretField {` lambda, by counting braces from its opening one.
     * Counting rather than taking a fixed window matters here: the phrase field's call carries
     * roughly thirty lines of inline commentary, so any fixed-size slice would end mid-argument.
     */
    private fun guardedBlocks(text: String): List<IntRange> {
        val blocks = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val start = text.indexOf("GuardedSecretField {", from)
            if (start < 0) return blocks
            var depth = 0
            var i = text.indexOf('{', start)
            while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            blocks.add(start..i)
                            break
                        }
                    }
                }
                i++
            }
            from = start + 1
        }
    }

    @Test
    fun `the seed-phrase word field is wrapped in a clipboard-guarded toolbar`() {
        assertTrue(screen.exists(), "ItemEditScreen.kt not found at ${screen.absolutePath}")
        val text = screen.readText()

        val marker = "R.string.itemedit_phrase_word_label"
        val at = text.indexOf(marker)
        assertTrue(at > 0, "$marker not found — did the phrase word field move or get renamed?")

        val blocks = guardedBlocks(text)
        assertTrue(
            blocks.any { at in it },
            "The seed-phrase word field ($marker) is not inside a GuardedSecretField block. " +
                "Copy/Cut on it would use the platform toolbar, skipping ClipboardHelper's " +
                "EXTRA_IS_SENSITIVE flag and the auto-clear timer.",
        )
    }

    /**
     * The control. If a future change wrapped the whole screen in one `GuardedSecretField`, the
     * test above would pass while the guard stopped meaning anything in particular — and the
     * ordinary title/username fields would lose their normal Copy behaviour. The guard is supposed
     * to be applied per secret field, so the plain title field must stay outside every block.
     */
    @Test
    fun `the ordinary title field is not clipboard-guarded`() {
        val text = screen.readText()
        val marker = "R.string.itemedit_edit_title_field_label"
        val at = text.indexOf(marker)
        assertTrue(at > 0, "$marker not found — did the title field move or get renamed?")

        val blocks = guardedBlocks(text)
        assertTrue(
            blocks.none { at in it },
            "The title field is inside a GuardedSecretField block. The guard is meant to be " +
                "applied per secret field; blanket-wrapping strips Copy from ordinary fields.",
        )
    }
}
