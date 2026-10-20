package com.localkeep.feature.onboarding

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every step that takes the onboarding state must render the notice that state can carry.
 *
 * `OnboardingViewModel.back()` refuses the gesture on the steps where there is nothing to unwind to
 * — the vault is already on disk — and says so by setting `notice`. It sets that on whichever step
 * the gesture came from, and three of the six steps rendered no notice at all: `CloudBackupStep`,
 * `MigrateStep` and `DoneStep` each pulled `state.error` out and dropped `state.notice` on the
 * floor. The refusal was therefore consumed with nothing moving and nothing said, which from the
 * outside is indistinguishable from a screen that has stopped responding — reported from a device
 * on step 4 of 6, "no toast/snackbar explaining why" (2A-F6, 2026-09-20).
 *
 * A source test rather than a rendered one: what is asserted is that no step *omits* the call, and
 * an omission is exactly what a composition test of the steps that do have it cannot see. Each step
 * needs a vault behind its ViewModel to render at all, which is the same reason
 * `PasswordStepFieldOnScreenTest` drives the frame rather than the steps.
 *
 * `WelcomeStep` is exempt and takes no state: back is disabled outright before the vault exists
 * (`BackHandler(enabled = state.step != WELCOME)`), so nothing can raise a notice there.
 */
class EveryStepShowsItsNoticeTest {

    private val source: String = File(
        "src/main/kotlin/com/localkeep/feature/onboarding/OnboardingScreen.kt",
    ).let {
        if (!it.exists()) fail("OnboardingScreen.kt was not found at ${it.absolutePath}")
        it.readText()
    }

    /** The body of `private fun <name>(`, up to the next top-level `@Composable`. */
    private fun bodyOf(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue(start > 0, "$name is not declared in OnboardingScreen.kt any more")
        val next = source.indexOf("\n@Composable", start)
        return source.substring(start, if (next == -1) source.length else next)
    }

    @Test
    fun `every state-carrying step renders its notice`() {
        val steps = listOf(
            "PasswordStep",
            "BiometricStep",
            "RecoveryStep",
            "CloudBackupStep",
            "MigrateStep",
            "DoneStep",
        )
        for (step in steps) {
            val body = bodyOf(step)
            assertTrue(
                "NoticeText(" in body,
                "$step renders no NoticeText, so a `back()` refusal raised on it is swallowed in " +
                    "silence — the 2A-F6 dead screen.",
            )
        }
    }

    /** The control: the step that is genuinely exempt is exempt because it holds no state. */
    @Test
    fun `the welcome step takes no state to have a notice in`() {
        assertTrue(
            "state" !in bodyOf("WelcomeStep").substringBefore(")"),
            "WelcomeStep now takes the onboarding state, so it has a notice it must render too",
        )
    }
}
