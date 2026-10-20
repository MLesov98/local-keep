package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The two vault-password re-auth gates say when they are working.
 *
 * Red before the fix. Both gates — Hidden Items and the Recovery Kit — run a full Argon2id
 * derivation on submit, measured in seconds on a mid-range phone, and neither drew anything while
 * it ran. The button disabled itself and that was the whole signal.
 *
 * That signal stopped being one. A disabled `LkPrimaryButton` renders with a transparent fill and a
 * faint label, so "deriving your key, wait" and "you have not typed a password yet" are the same
 * picture. The user's reading of a gate that looks untouched for four seconds is that the tap
 * missed, and the repair is to tap again — on a gate whose whole purpose is to be slow.
 *
 * The fix is a label swap onto strings both modules already ship, and the pattern already exists
 * one branch above the Recovery Kit gate on the create-kit button. Two files rather than one
 * because this is a single defect wearing two faces; splitting it across modules would let one half
 * be fixed and the other quietly regress.
 */
class ReauthGatesReportProgressTest {

    @Test
    fun `the hidden-items gate labels its own KDF run`() {
        val gate = block(
            source("feature/settings/src/main/kotlin/com/localkeep/feature/settings/HiddenItemsScreen.kt"),
            anchor = "R.string.settings_hidden_gate_password_submit",
        )
        assertTrue(
            "R.string.settings_working" in gate,
            "the gate's primary button must read Working… while the derivation runs; disabling it " +
                "alone is indistinguishable from an empty password field. Found:\n$gate",
        )
    }

    @Test
    fun `the recovery-kit gate labels its own KDF run`() {
        val gate = block(
            source("feature/onboarding/src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt"),
            anchor = "R.string.onboarding_recoverykit_gate_submit",
        )
        assertTrue(
            "R.string.onboarding_recoverykit_working" in gate,
            "the gate's primary button must read Working… while the derivation runs. Found:\n$gate",
        )
    }

    /**
     * The control, and it must stay green while the two above go red.
     *
     * The lazy way to make a slow gate feel responsive is to stop disabling the button. That would
     * let a second tap start a second derivation against a vault key that is already being derived,
     * and on the Hidden Items gate it would also re-send a password the first tap already consumed
     * and wiped. Both gates must keep refusing input while they work.
     */
    @Test
    fun `both gates still refuse a second submission while working`() {
        listOf(
            "feature/settings/src/main/kotlin/com/localkeep/feature/settings/HiddenItemsScreen.kt",
            "feature/onboarding/src/main/kotlin/com/localkeep/feature/onboarding/RecoveryKitScreen.kt",
        ).forEach { relative ->
            assertTrue(
                "enabled = !working && password.isNotBlank()" in source(relative),
                "$relative must keep its gate button disabled while a derivation is in flight",
            )
        }
    }

    private companion object {

        /**
         * The button call site, from the submit label to the end of the call.
         *
         * The anchor is asserted before it is used: `substringAfter` returns the whole receiver when
         * the delimiter is absent, which would widen this to the entire file and pass on unrelated
         * lines. Four tests in this repo have already shipped that failure.
         */
        fun block(text: String, anchor: String): String {
            assertTrue(
                anchor in text,
                "anchor '$anchor' is gone — update it, or this test scans the whole file and " +
                    "asserts nothing",
            )
            return text.substringBefore(anchor).takeLast(400) + anchor +
                text.substringAfter(anchor).take(200)
        }

        fun source(relative: String): String {
            val file = File(repoRoot(), relative)
            assertTrue(file.isFile, "expected source file at $relative")
            return file.readText()
        }

        fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")
    }
}
