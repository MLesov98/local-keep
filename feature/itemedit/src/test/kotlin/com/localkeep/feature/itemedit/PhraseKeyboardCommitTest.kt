package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The keyboard can commit a phrase word.
 *
 * Red before the fix. `onPhraseDraftChanged` strips everything outside `a`–`z`, so a typed Space
 * never reached the draft, and the only thing in the app that wrote a typed word into `phraseWords`
 * was the autocomplete chip. `BoxedField` had no `imeAction`/`onImeAction` to pass one through, and
 * neither did `LkFormField` beneath it.
 *
 * What that looked like: type all twelve words by hand, ignore the small chip, and the last slot
 * shows the word you just typed while Save stays dimmed and says nothing — because the word is in
 * `phraseDraft` and `phraseSaveBlocked` reads `phraseWords`. The only ways out were tapping the
 * chip or tapping a different slot, neither of which is signposted from a disabled button that
 * deliberately carries no message. This file's own ViewModel calls that chip "small,
 * optional-looking, and not where a user tapping through twelve slots is looking".
 *
 * Verified on emulator-5554 before this test was written: typing `abandon` into slot 1 and pressing
 * the IME key moved the counter from "0 of 12 words" to "1 of 12 words".
 */
class PhraseKeyboardCommitTest {

    @Test
    fun `the phrase entry field commits its word from the keyboard`() {
        val block = phraseFieldBlock()
        assertTrue(
            "onImeAction" in block && "commitPhraseWord" in block,
            "the phrase entry field must commit the typed word from the IME key; without it the " +
                "only path into phraseWords is the autocomplete chip. Found:\n$block",
        )
    }

    @Test
    fun `an ambiguous draft commits nothing`() {
        val block = phraseFieldBlock()
        // The resolution must be exact-then-unique, never "first suggestion wins". Guessing between
        // six candidates on the user's behalf is how a wrong word lands in a seed phrase, and the
        // checksum would only say so eleven slots later — by which point the user has no idea which
        // slot to doubt.
        assertTrue(
            "singleOrNull()" in block,
            "the IME commit must only resolve an unambiguous prefix; found:\n$block",
        )
        assertTrue(
            "firstOrNull { it == draft }" in block,
            "an exactly-typed wordlist entry must commit itself rather than depend on the " +
                "suggestion list having exactly one member; found:\n$block",
        )
    }

    /**
     * The control, and it must stay green while the two above go red.
     *
     * The lazy way to "let the keyboard commit a word" is to stop stripping non-letters so a Space
     * can act as a delimiter. That would let punctuation, digits and whitespace into a BIP-39 slot
     * and make the draft filter — which exists so the wordlist lookup only ever sees things that
     * could be words — meaningless.
     */
    @Test
    fun `the draft still admits only letters`() {
        val viewModel = source("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
        assertTrue(
            "phraseDraft = value.lowercase(Locale.ROOT).filter { it in 'a'..'z' }" in viewModel,
            "the phrase draft filter must still admit only a-z: loosening it to let Space act as " +
                "a delimiter would put punctuation and digits into a BIP-39 slot",
        )
    }

    private companion object {
        const val SCREEN = "src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt"

        /**
         * The phrase slot's entry field, from its own label to the end of the call.
         *
         * Anchor asserted before it is used: `substringAfter` returns the whole receiver when the
         * delimiter is absent, which would widen this to the entire file and pass on unrelated
         * lines. Three tests in this repo have already shipped that failure.
         */
        fun phraseFieldBlock(): String {
            val text = source(SCREEN)
            val anchor = "R.string.itemedit_phrase_word_label"
            assertTrue(
                anchor in text,
                "anchor '$anchor' is gone from ItemEditScreen.kt — update it, or this test scans " +
                    "the whole file and asserts nothing",
            )
            return text.substringAfter(anchor).substringBefore("\n                if (suggestions")
        }

        fun source(relative: String): String {
            val direct = File(relative)
            val file = if (direct.isFile) direct else File(repoRoot(), "feature/itemedit/$relative")
            assertTrue(file.isFile, "expected source file at $relative")
            return file.readText()
        }

        fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")
    }
}
