package com.localkeep.feature.itemedit

import com.localkeep.core.location.LocationFailure
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A switched-off location provider must offer the one action that fixes it.
 *
 * Found in the 2026-09-09 functional sweep: `LocationCaptureField`'s failure banner offered a
 * settings trip only for [LocationFailure.PERMISSION_DENIED] and [LocationFailure.APPROXIMATE_ONLY]
 * — `isFixableInSettings()`'s own doc comment argued `PROVIDER_DISABLED` "sends the user somewhere
 * there is nothing to change", which is false: the system's location toggle is exactly where this
 * gets fixed, this app's own permission page simply cannot reach it. So a device with location
 * switched off got `itemedit_coordinate_error_provider_off` and no way out of the error short of
 * leaving the app to find Settings unassisted.
 *
 * The fix adds `PROVIDER_DISABLED` to `isFixableInSettings()`, gives it a distinct action label
 * (`settingsActionLabelRes` — "Turn on location" rather than the generic "Open settings", since it
 * leads somewhere the app's own settings page cannot), and routes it through a new
 * `Context.openLocationSettings()` (`ACTION_LOCATION_SOURCE_SETTINGS`) via `Context.openSettingsFor`,
 * rather than through `Context.openAppSettings()` — this app's own permission page, which has no
 * location toggle on it at all.
 *
 * `isFixableInSettings()` and `settingsActionLabelRes()` are plain functions on an enum and need no
 * Context, so they are tested directly. The Context-dependent routing (`openSettingsFor`,
 * `openLocationSettings`, and the call site in `ItemEditScreen`) is a source scan instead, for the
 * same reason `IdentityPhotoRouteEntitlementTest` is one: this module has no Robolectric wiring.
 */
class LocationProviderDisabledSettingsRouteTest {

    @Test
    fun `a switched-off provider is fixable from settings`() {
        assertTrue(
            LocationFailure.PROVIDER_DISABLED.isFixableInSettings(),
            "PROVIDER_DISABLED offers no settings action — the one route that actually fixes a " +
                "switched-off provider is unreachable from the error banner",
        )
    }

    @Test
    fun `its settings action is distinct from the app's own permission page`() {
        assertEquals(
            R.string.itemedit_coordinate_open_location_settings,
            LocationFailure.PROVIDER_DISABLED.settingsActionLabelRes(),
            "PROVIDER_DISABLED's settings label is not itemedit_coordinate_open_location_settings — " +
                "the generic 'Open settings' label is true of the app's own page too, and would " +
                "promise a fix that page cannot deliver",
        )
    }

    /**
     * The control: the two permission answers must keep their ordinary settings-page label and
     * fixability. True before this fix and must stay true after it — a change that swept every
     * failure into the location-settings route, not just the disabled provider, passes the two
     * tests above just as easily and would send a permission denial to the wrong settings page.
     */
    @Test
    fun `the two permission answers are unaffected — still fixable, still the app's own page`() {
        for (failure in listOf(LocationFailure.PERMISSION_DENIED, LocationFailure.APPROXIMATE_ONLY)) {
            assertTrue(failure.isFixableInSettings(), "$failure should still offer a settings action")
            assertEquals(
                R.string.itemedit_coordinate_open_settings,
                failure.settingsActionLabelRes(),
                "$failure should still point at this app's own settings page, not location settings",
            )
        }
    }

    /** The control's other half: a genuinely unfixable failure must still offer no action at all. */
    @Test
    fun `a timeout is still not fixable from any settings page`() {
        assertFalse(LocationFailure.TIMED_OUT.isFixableInSettings())
    }

    private fun coordinateCopySrc(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/CoordinateCopy.kt")
        assertTrue(src.exists(), "CoordinateCopy.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    @Test
    fun `openSettingsFor routes the disabled provider to system location settings, not app settings`() {
        val text = coordinateCopySrc()
        val start = text.indexOf("fun Context.openSettingsFor(")
        assertTrue(start > 0, "Context.openSettingsFor( is gone — did it get renamed?")
        val end = text.indexOf("\n}", start)
        assertTrue(end > start, "openSettingsFor's closing brace not found — region unbounded")
        val body = text.substring(start, end)
        assertTrue(
            "PROVIDER_DISABLED" in body && "openLocationSettings()" in body,
            "openSettingsFor no longer branches PROVIDER_DISABLED to openLocationSettings(): $body",
        )
        assertTrue(
            "ACTION_LOCATION_SOURCE_SETTINGS" in text,
            "openLocationSettings no longer opens ACTION_LOCATION_SOURCE_SETTINGS — the system's " +
                "location toggle, not this app's own permission page",
        )
    }

    @Test
    fun `the editor's failure banner routes through openSettingsFor, not the app-settings-only call`() {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val failureAt = text.indexOf("state.coordinateFailure?.let { failure ->")
        assertTrue(failureAt > 0, "the coordinateFailure banner block is gone — did it get renamed?")
        val end = text.indexOf("state.coordinateFix?.let", failureAt)
        assertTrue(end > failureAt, "coordinateFix block not found after coordinateFailure — region unbounded")
        val block = text.substring(failureAt, end)
        assertTrue(
            "context.openSettingsFor(failure)" in block,
            "the coordinateFailure banner no longer calls context.openSettingsFor(failure) — a " +
                "switched-off provider would be sent to this app's own settings page, which has no " +
                "location toggle: $block",
        )
    }
}
