package com.localkeep.feature.health

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Always-on rows read one sentence break after the summary, not two.
 *
 * 2C-F11, on a device 2026-09-20:
 * `desc='What can leave this device. An encrypted backup, if you allow it. Nothing else.. Always on.'`
 *
 * `health_always_on_item_accessibility` is `"%1$s. %2$s. Always on."` and supplies the break itself.
 * Five of the six `AlwaysOn` summaries are fragments with no stop of their own and need it; the
 * sixth is two full sentences and already ends in one. Trimming here rather than rewriting the
 * format or that one summary keeps both true.
 */
class AlwaysOnSummarySpeechTest {

    @Test
    fun `a summary that ends in a stop loses it, so the format supplies exactly one`() {
        assertEquals(
            "An encrypted backup, if you allow it. Nothing else",
            alwaysOnSummaryForSpeech("An encrypted backup, if you allow it. Nothing else."),
        )
    }

    @Test
    fun `a summary with no stop of its own is untouched`() {
        // The other five. Stripping a stop that is not there would be harmless; adding one here
        // instead of in the format would double it for the sixth. Pinned so neither drifts.
        assertEquals(
            "Guesses get slower, and cannot be turned off",
            alwaysOnSummaryForSpeech("Guesses get slower, and cannot be turned off"),
        )
    }

    @Test
    fun `an internal full stop is not a trailing one`() {
        assertEquals("Argon2id, then AES-256-GCM-SIV", alwaysOnSummaryForSpeech("Argon2id, then AES-256-GCM-SIV"))
    }
}
