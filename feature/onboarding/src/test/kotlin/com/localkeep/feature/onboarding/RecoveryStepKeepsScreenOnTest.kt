package com.localkeep.feature.onboarding

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The display stays on while the Recovery Kit step is up, and only then (O3-04, 2026-10-02).
 *
 * Every screen-off locks the vault, and the lock pops onboarding. The Recovery Kit step is where a
 * user sits still for minutes copying 24 words by hand, so the display's own timeout (30 s is a
 * common default) ended first run mid-transcription: the kit dropped with the ViewModel, the
 * remaining steps never ran, and the next unlock said nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecoveryStepKeepsScreenOnTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the screen is held on while shown and released when it leaves`() {
        var shown by mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (shown) KeepScreenOnWhileShown()
        }
        compose.waitForIdle()
        assertTrue(view.keepScreenOn, "the step does not hold the display on")

        shown = false
        compose.waitForIdle()
        assertFalse(
            view.keepScreenOn,
            "the display stays pinned on after the step has gone — battery for nothing",
        )
    }

    @Test
    fun `the recovery kit step uses it, and no other step does`() {
        val source = File("src/main/kotlin/com/localkeep/feature/onboarding/OnboardingScreen.kt").readText()
        val recoveryStep = source.substringAfter("private fun RecoveryStep(", "")
            .substringBefore("\n@Composable", "")
        assertTrue(
            "KeepScreenOnWhileShown()" in recoveryStep,
            "the Recovery Kit step lets the display time out, and the screen-off lock ends first run",
        )
        assertTrue(
            Regex("""(?<!fun )KeepScreenOnWhileShown\(\)""").findAll(source).count() == 1,
            "KeepScreenOnWhileShown is called from more than the one step it is for",
        )
    }
}
