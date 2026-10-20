package com.localkeep.feature.itemedit

import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import com.localkeep.core.model.NOTE_BODY_FIELD
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A note marked "reprompt" is actually gated — on **both** screens that render its body.
 *
 * Red before the fix, and the reason it needed a test of this shape rather than a ViewModel one:
 * `requiresReauthFor` already returned true for a reprompt item through its `|| reprompt` arm. The
 * ViewModel was right the whole time. What was wrong was that neither renderer ever asked it —
 * `NoteDetail` drew `state.noteBody` as a plain `Text` and `NoteFields` handed it straight to a
 * `BoxedField` — so a ViewModel test would have passed identically before and after and proved
 * nothing. The claim under test is *which call site passes which argument*, which is exactly the
 * case [BiometricModalityCopyTest] and [ScreenFrameConsistencyTest] answer from source.
 *
 * What made it worth fixing rather than documenting: the detail screen draws a "Reprompt" badge and
 * `itemedit_reprompt_caption_on` — a Tier G string — promises that "every secret in this item asks
 * for your screen lock before it is shown or copied, even while the vault is open". `RepromptRow`
 * is not type-gated, so a NOTE can carry the flag, and `ItemContent.kt` states plainly that a
 * secure note IS the secret. The app asserted a protection it had not implemented.
 *
 * **Both screens or neither.** Gating only the read view would have been worse than gating nothing:
 * Edit sits one tap from the body on that screen, so the secret would still be two taps away while
 * the badge and the caption both looked satisfied. `RevealAuthEffect`'s KDoc states the rule — "a
 * gate that applies on one screen and not the other is no gate at all".
 */
class NoteRepromptGateTest {

    @Test
    fun `the note detail body is gated when the item asks for a reprompt`() {
        val body = block(DETAIL, "private fun NoteDetail(", "private fun ")
        assertTrue(
            "state.reprompt" in body,
            "NoteDetail must branch on the item's reprompt flag; it renders the note body, and the " +
                "badge beside it claims that body is gated. Found:\n$body",
        )
        assertTrue(
            NOTE_KEY in body && "SecretReadField" in body,
            "NoteDetail must route the gated body through SecretReadField under $NOTE_KEY, the way " +
                "CoordinateDetail does — otherwise the reveal never reaches requiresReauthFor",
        )
    }

    @Test
    fun `the note editor body is gated on the same key`() {
        val body = block(EDITOR, "private fun NoteFields(", "private fun ")
        assertTrue(
            NOTE_KEY in body && "state.reprompt" in body,
            "NoteFields must gate on the same key as the read view. Gating one screen and not the " +
                "other leaves the secret one Edit tap away and the Tier G caption still false. " +
                "Found:\n$body",
        )
    }

    /**
     * The control, and the reason it is here: it must stay green while the two above go red.
     *
     * The tempting wrong fix is to drop the note key into [ALWAYS_REAUTH_FIELDS] and call it done.
     * That gates *every* note ever written, including the ones whose owner never asked — and a
     * shopping list behind a fingerprint prompt is how a gate teaches people to dismiss gates. A
     * note is high-value by what its owner put in it, not by what it is, and the reprompt tick is
     * how they say so. Without this assertion the over-broad fix would pass the two tests above.
     */
    @Test
    fun `a note is gated by its own reprompt flag and never by the always-reauth set`() {
        assertFalse(
            NOTE_BODY_FIELD in ALWAYS_REAUTH_FIELDS,
            "NOTE_BODY_FIELD must not join ALWAYS_REAUTH_FIELDS: that would gate every note, not " +
                "the ones the user marked",
        )
        val model = source("core/model/src/main/kotlin/com/localkeep/core/model/ReauthFields.kt")
        assertTrue(
            "const val NOTE_BODY_FIELD" in model,
            "the note reveal key belongs in :core:model beside the others, so every surface that " +
                "can release a secret reads the same list",
        )
    }

    private companion object {
        const val DETAIL = "src/main/kotlin/com/localkeep/feature/itemedit/ItemDetailScreen.kt"
        const val EDITOR = "src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt"
        const val NOTE_KEY = "NOTE_BODY_FIELD"

        /**
         * One function's body, from [start] to the next [next].
         *
         * Asserts its own anchor, because `substringAfter` returns the WHOLE receiver when its
         * delimiter is absent — so a renamed composable would not break this helper, it would
         * silently widen it to the entire file and every assertion above would pass on some
         * unrelated line. `BackupInFlightControlsTest` shipped exactly that failure for a day
         * after a string rename, which is why this helper checks before it cuts.
         */
        fun block(path: String, start: String, next: String): String {
            val text = source(path)
            assertTrue(
                start in text,
                "anchor '$start' is gone from $path — this test would otherwise scan the whole " +
                    "file and assert nothing. Update the anchor.",
            )
            return text.substringAfter(start).substringBefore(next)
        }

        fun source(relative: String): String {
            val direct = File(relative)
            val file = if (direct.isFile) direct else File(repoRoot(), relative)
            assertTrue(file.isFile, "expected source file at $relative")
            return file.readText()
        }

        fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")
    }
}
