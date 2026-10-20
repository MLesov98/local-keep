package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `WelcomeStep` is the mandatory first screen (`OnboardingViewModel` defaults to `Step.WELCOME`,
 * and `OnboardingScreen` disables the back handler there) and, until now, the one step in this file
 * still laid out in a fixed-height `Column` while every sibling scrolls. `DoneStep` got the same
 * treatment first — `heightIn(min = viewport)` inside a `verticalScroll` `Column`, wrapped in
 * `BoxWithConstraints` for the viewport measurement — after `biometricSkipReason`'s footnote landed
 * under the pinned CTA stack at fontScale 2.0 with nowhere to scroll to.
 *
 * No device measurement puts WelcomeStep's own content under its button today: at fontScale 2.0 on
 * a 411dp window there is still roughly 180-240dp of headroom under the CTA with the English string.
 * What is real is that the margin is language-dependent (the French tagline+body run to 192 chars
 * against 116 for English, `values-fr/strings.xml:13`) and narrows further at the smallest supported
 * window (308dp) — and that this was the only step in the file without the wiring its siblings
 * already have.
 *
 * A Compose test cannot pin any of that without reproducing the exact string, window and fontScale
 * combination that would actually overflow — see `NamingSheetAutoFocusTest` in `:core:ui` and
 * `ErrorRevealPinTest`/`NamingSheetDismissOnErrorTest` for the same conclusion reached three other
 * times in this codebase. So this pins the wiring instead: does `WelcomeStep` carry the same scroll
 * treatment `DoneStep` already does.
 */
class WelcomeStepScrollableTest {

    private val welcomeStepBody: String
        get() {
            val source = File(
                REPO,
                "feature/onboarding/src/main/kotlin/com/localkeep/feature/onboarding/OnboardingScreen.kt",
            )
                .also { assertTrue(it.isFile, "OnboardingScreen.kt not found at ${it.absolutePath}") }
                .readText()
            val start = source.indexOf("private fun WelcomeStep(")
            assertTrue(start >= 0, "WelcomeStep not found in OnboardingScreen.kt")
            val end = source.indexOf("private fun PasswordStep(", start)
            assertTrue(end > start, "PasswordStep not found after WelcomeStep")
            return source.substring(start, end)
        }

    @Test
    fun `welcome step scrolls its content, like DoneStep already does`() {
        val body = welcomeStepBody
        assertTrue(
            "verticalScroll" in body,
            "WelcomeStep has no verticalScroll — its content is still in a fixed-height Column, " +
                "with no way out from under the CTA at a long string or a large fontScale.",
        )
        assertTrue(
            "BoxWithConstraints" in body && "heightIn(min = viewport" in body,
            "WelcomeStep scrolls but without heightIn(min = viewport) measured from a " +
                "BoxWithConstraints — that is what keeps Arrangement.Center centred on the ordinary " +
                "screens that already fit; a bare verticalScroll() flips everything to the top always.",
        )
    }

    /** The control. The assertions above would also pass reading nothing at all. */
    @Test
    fun `the region read really is WelcomeStep`() {
        val body = welcomeStepBody
        assertTrue(
            "fun WelcomeStep(viewModel: OnboardingViewModel)" in body,
            "read a region that is not WelcomeStep's declaration",
        )
        assertTrue("onboarding_welcome_cta" in body, "read a WelcomeStep with no CTA button")
    }
}

private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")
