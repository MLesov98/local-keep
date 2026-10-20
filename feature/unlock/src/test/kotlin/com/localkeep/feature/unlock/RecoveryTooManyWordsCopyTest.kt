package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * V3-01: past twenty-four words the screen has to say so, and the button has to stop claiming the
 * opposite.
 *
 * Driven on an emulator, 2026-09-17: thirty-two words
 * pasted into the Recovery Kit field left the counter neutral, no inline plate at all, and the
 * disabled CTA announced as "Open with Recovery Kit, available once all 24 words are entered".
 * Thirty-two *were* entered. A sighted user can infer the problem from "32 of 24"; a screen-reader
 * user was told to do the thing they had already done, and the real cause was never named.
 *
 * The colour and the predicate are covered by [RecoveryCountToneTest] and [RecoveryKitOpenGateTest].
 * What is left is the wiring — which string each state selects — and that lives inside private
 * composables taking a Hilt-built [UnlockViewModel], so it is read from the source. Same idiom, and
 * the same reasoning, as [LkSectionLabelGapCallSitesTest]: the defect is the *absence* of a call,
 * and a rendered assertion could not tell "no message" from "a message the test failed to find".
 */
class RecoveryTooManyWordsCopyTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private fun xml(dir: String): String =
        File("src/main/res/$dir/strings.xml").takeIf { it.isFile }?.readText()
            ?: fail("$dir/strings.xml missing")

    private fun string(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml(dir))?.groupValues?.get(1)
            ?: fail("$key missing from $dir/strings.xml")

    private val screen: String =
        File("src/main/kotlin/com/localkeep/feature/unlock/UnlockScreen.kt")
            .takeIf { it.isFile }?.readText() ?: fail("UnlockScreen.kt not found")

    @Test
    fun `every language names the over-count`() {
        languages.forEach { dir ->
            listOf(
                "unlock_recovery_too_many_words_message",
                "unlock_recovery_open_button_a11y_too_many",
            ).forEach { key ->
                assertTrue(
                    string(dir, key).isNotBlank(),
                    "$dir/$key is empty, so the over-count state would say nothing in this language",
                )
            }
        }
    }

    /**
     * The whole defect, stated as an invariant: the over-count label is not the incomplete one.
     *
     * Reusing it is exactly what shipped — "available once all 24 words are entered" is false the
     * moment a twenty-fifth word lands, and it is the only thing a screen reader has.
     */
    @Test
    fun `the over-count label is not the incomplete label`() {
        languages.forEach { dir ->
            assertNotEquals(
                string(dir, "unlock_recovery_open_button_a11y_incomplete"),
                string(dir, "unlock_recovery_open_button_a11y_too_many"),
                "$dir announces an over-full field with the label written for an unfinished one",
            )
        }
    }

    /** The message under the field is its own string, not the checksum one worn on a second state. */
    @Test
    fun `the over-count message is not the checksum message`() {
        languages.forEach { dir ->
            assertNotEquals(
                string(dir, "unlock_recovery_checksum_invalid_message"),
                string(dir, "unlock_recovery_too_many_words_message"),
                "$dir explains thirty-two words with the sentence written for a failed checksum",
            )
        }
    }

    @Test
    fun `the unlock screen renders the over-count message and label`() {
        listOf(
            "R.string.unlock_recovery_too_many_words_message",
            "R.string.unlock_recovery_open_button_a11y_too_many",
        ).forEach { reference ->
            assertTrue(
                reference in screen,
                "UnlockScreen.kt never reaches for $reference, so the state it names is silent again",
            )
        }
        assertTrue(
            "recoveryKitTooManyWords(" in screen,
            "UnlockScreen.kt no longer asks whether the field holds more than a kit",
        )
    }

    /** The control: prove the file being read is really the unlock screen. */
    @Test
    fun `the file being read really is the unlock screen`() {
        assertTrue("fun UnlockScreen(" in screen, "read a file that is not the unlock screen")
        assertTrue(
            "R.string.unlock_recovery_open_button_a11y_checksum_invalid" in screen,
            "read an unlock screen with no Recovery Kit action bar",
        )
    }
}
