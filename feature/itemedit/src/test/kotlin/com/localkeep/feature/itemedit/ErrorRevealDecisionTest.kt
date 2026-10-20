package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One refusal, one reveal.
 *
 * The editor has two ways of showing a refused save: the offending field (ring + caption, scrolled
 * into view) and a banner at the very bottom of the form for errors that belong to no single field.
 * Firing both for the same error made them fight — on 2026-07-29 pressing Save with an empty title
 * scrolled the form to the bottom banner and left the errored field off-screen above it, and the
 * competing reveal requests then pinned the viewport so the form could not be scrolled at all.
 *
 * The banner is for errors the fields cannot own. This decides that, in one place, in plain logic.
 */
class ErrorRevealDecisionTest {

    @Test
    fun `a field-attributable error is left to its field`() {
        assertFalse(
            shouldRevealErrorBanner(error = R.string.itemedit_error_title_required, fieldOwnsError = true),
            "the banner must not chase an error the field is already showing",
        )
    }

    @Test
    fun `an error no field owns is revealed by the banner`() {
        assertTrue(
            shouldRevealErrorBanner(error = R.string.itemedit_error_check_fields, fieldOwnsError = false),
        )
    }

    @Test
    fun `no error reveals nothing`() {
        assertFalse(shouldRevealErrorBanner(error = null, fieldOwnsError = false))
        assertFalse(shouldRevealErrorBanner(error = null, fieldOwnsError = true))
    }
}
