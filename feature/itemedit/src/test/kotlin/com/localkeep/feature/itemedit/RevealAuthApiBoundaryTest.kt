package com.localkeep.feature.itemedit

import android.os.Build
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reveal gate's refusal names something the user can actually do (2026-09-20, sweep 3C-1).
 *
 * The bug this closes: `itemedit_reveal_no_auth_available` says "Set a screen lock or biometrics to
 * reveal this field", and on API 28 and 29 no screen lock can ever satisfy this gate.
 * `RevealAuth.allowedAuthenticators` asks for `BIOMETRIC_STRONG` alone below API 30 — androidx
 * .biometric rejects the `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` pair there, `canAuthenticate`
 * answers `BIOMETRIC_ERROR_UNSUPPORTED` without consulting the device, and `PromptInfo.build()`
 * throws. So a phone with no Class 3 sensor lost every reveal- and copy-gated secret behind an
 * instruction that could not work. Measured on emulator-5556 (API 29): that sentence on screen
 * while `locksettings verify --old 1234` answered "Lock credential verified successfully".
 *
 * Copy only. The authenticator policy is `:core:ui`'s and is deliberate: Class 3 alone rather than
 * `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, which would relax a reveal gate to a Class 2 biometric to
 * buy back the PIN fallback. Nothing here changes it.
 */
class RevealAuthApiBoundaryTest {

    @Test
    fun `below API 30 the refusal asks for a biometric, not a screen lock`() {
        listOf(Build.VERSION_CODES.P, Build.VERSION_CODES.Q).forEach { sdk ->
            assertEquals(
                R.string.itemedit_reveal_no_biometric_available,
                noAuthMessage(sdk),
                "API $sdk is told to set a screen lock that this gate can never accept",
            )
        }
    }

    @Test
    fun `from API 30 the screen-lock wording is still correct`() {
        listOf(Build.VERSION_CODES.R, Build.VERSION_CODES.S, 34, 36).forEach { sdk ->
            assertEquals(
                R.string.itemedit_reveal_no_auth_available,
                noAuthMessage(sdk),
                "API $sdk accepts a device credential, so naming only biometrics understates it",
            )
        }
    }

    /**
     * The boundary is written out twice — here and in `RevealAuth.allowedAuthenticators`, which is
     * `internal` to `:core:ui` and so cannot be read from this module. If the policy ever moves,
     * this fails rather than letting the message quietly describe the wrong rule.
     */
    @Test
    fun `the two halves of the boundary still agree`() {
        val policy = File("../../core/ui/src/main/kotlin/com/localkeep/core/ui/RevealAuth.kt").readText()
        assertTrue(
            "if (sdkInt < Build.VERSION_CODES.R) BIOMETRIC_STRONG else ALLOWED" in policy,
            "the authenticator policy changed; the API boundary this message is chosen on must " +
                "change with it",
        )
        val effect =
            File("src/main/kotlin/com/localkeep/feature/itemedit/RevealAuthEffect.kt").readText()
        assertTrue("if (sdkInt < Build.VERSION_CODES.R) {" in effect)
    }

    /**
     * And both refusal paths use it — the "device cannot challenge" pre-check and the prompt's own
     * `Unavailable` outcome are the same dead end and must not answer differently.
     */
    @Test
    fun `both refusal paths go through it`() {
        val effect =
            File("src/main/kotlin/com/localkeep/feature/itemedit/RevealAuthEffect.kt").readText()
        val body = effect.substringBefore("internal fun noAuthMessage(")
        assertEquals(
            2,
            Regex("onRevealDenied\\(noAuthMessage\\(forCopy = forCopy\\)\\)").findAll(body).count(),
            "one of the two unavailable paths still hardcodes the screen-lock wording",
        )
        assertTrue(
            "R.string.itemedit_reveal_no_auth_available" !in body,
            "a refusal still names the string directly instead of asking noAuthMessage()",
        )
    }

    /**
     * A Copy tap raises this same gate, so its refusal and its prompt must name Copy. The detail
     * screen answered a Copy tap with "reveal this field" until the 2026-10-16 device pass.
     */
    @Test
    fun `a copy request is refused in copy wording on both sides of the boundary`() {
        assertEquals(
            R.string.itemedit_copy_no_biometric_available,
            noAuthMessage(Build.VERSION_CODES.Q, forCopy = true),
        )
        assertEquals(
            R.string.itemedit_copy_no_auth_available,
            noAuthMessage(Build.VERSION_CODES.R, forCopy = true),
        )
        assertEquals(R.string.itemedit_copy_prompt_subtitle, revealPromptSubtitle(forCopy = true))
    }

    @Test
    fun `a reveal request keeps the reveal wording`() {
        assertEquals(R.string.itemedit_reveal_no_auth_available, noAuthMessage(34, forCopy = false))
        assertEquals(R.string.itemedit_reveal_prompt_subtitle, revealPromptSubtitle(forCopy = false))
    }

    /** Both screens hand the effect the copy flag, or the copy wording is unreachable. */
    @Test
    fun `both screens tell the effect when the prompt came from copy`() {
        listOf("ItemEditScreen.kt", "ItemDetailScreen.kt").forEach { f ->
            val src = File("src/main/kotlin/com/localkeep/feature/itemedit/$f").readText()
            assertTrue(
                "RevealAuthEffect(state.pendingRevealField, viewModel, forCopy = state.copyAfterReveal)" in src,
                "$f raises the reveal prompt without saying whether Copy asked for it",
            )
        }
    }
}
