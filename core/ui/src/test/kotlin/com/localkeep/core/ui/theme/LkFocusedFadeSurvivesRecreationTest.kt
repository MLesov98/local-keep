package com.localkeep.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A well that has held focus remembers it across a recreation, so its leading fade survives (SCL-034).
 *
 * The platform restores a single-line field's scroll offset with the saved state after a rotation or
 * a return to the screen; the component's "has this ever been focused" flag was a plain `remember`
 * and was lost, so the restored well sat scrolled to its caret with the leading glyph sliced at full
 * opacity. The flag decides [lkLeadingFadeActive], so it is the thing to pin.
 *
 * FAILS WITHOUT THE FIX: after the restore the flag reads false.
 * CONTROL: a field that was never focused still reads false after the restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LkFocusedFadeSurvivesRecreationTest {

    @get:Rule
    val compose = createComposeRule()

    private var seen = emptyList<Boolean>()
    private var focusedNow = false

    @Composable
    private fun Probe() {
        seen = seen + rememberEverFocused(focusedNow)
    }

    @Test
    fun `focus once and the flag survives a recreation`() {
        val restoration = StateRestorationTester(compose)
        focusedNow = true
        restoration.setContent { Probe() }
        compose.waitForIdle()
        assertTrue(seen.last())

        // Focus has gone by the time the screen is restored.
        focusedNow = false
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue(seen.last(), "the restored well has forgotten it was ever focused: $seen")
    }

    @Test
    fun `a field that was never focused is still not after a recreation`() {
        val restoration = StateRestorationTester(compose)
        focusedNow = false
        restoration.setContent { Probe() }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertFalse(seen.last())
        assertEquals(false, seen.any { it })
    }
}
