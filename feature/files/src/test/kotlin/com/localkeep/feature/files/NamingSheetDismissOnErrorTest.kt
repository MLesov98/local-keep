package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `save()` could fail (quota exceeded, missing blob) while the new-file naming sheet was still
 * showing. The error rendered in the form underneath, but the still-open modal sheet's scrim hid
 * it — the user saw nothing happen. The `LaunchedEffect` in `FileItemEditScreen` now closes the
 * sheet on [namingSheetShouldCloseOnError], which is what this pins.
 *
 * A unit test rather than a Compose one — see `ErrorRevealPinTest` in `:core:ui`, which found
 * that a `createComposeRule` window reproduces neither a real scrim nor the sheet-open/error
 * timing this defect depends on, so a naive Compose test would pass with the bug still in place.
 */
class NamingSheetDismissOnErrorTest {

    @Test
    fun `an error arriving closes the naming sheet`() {
        assertTrue(namingSheetShouldCloseOnError(R.string.files_error_quota_exceeded))
    }

    @Test
    fun `no error leaves the naming sheet alone`() {
        assertFalse(namingSheetShouldCloseOnError(null))
    }
}
