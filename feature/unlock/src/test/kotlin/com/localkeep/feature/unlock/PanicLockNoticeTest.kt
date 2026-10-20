package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * After a panic lock the unlock screen must explain the missing biometric button, and must not
 * describe a permanent change as a temporary one.
 *
 * Two defects, one cause. `VaultLockManager.panicLock()` sets the block **and** destroys the
 * device-wrapped DEK in the same call, so `DeviceKeyStore.load(context)` is already empty by the
 * time any unlock screen asks. `biometricBlockedByPanicLock()` required that store to be non-empty,
 * which made it unsatisfiable: the notice branch in `UnlockScreen` had never run once, and the
 * button simply vanished (device, 2026-09-20). And the notice it would have shown said the block
 * lasts "until you enter your master password", which is false — the key is gone and biometric
 * unlock has to be enrolled again.
 *
 * Asserted on the source and the base resource file. `:feature:unlock` has no mocking library on
 * its unit-test classpath, and `VaultLockManager`'s real constructor reaches AndroidKeyStore, which
 * Robolectric does not implement — so there is no way to drive the guard here. What can be held is
 * the shape of the condition and the words of the claim, which is what each defect was.
 *
 * **The copy check guards English only**, for the reason `UnlockSubtitleTest` gives at length: a
 * substring match on an English phrase cannot see the same false claim in French. The translations
 * are covered by the Tier C security-copy review.
 */
class PanicLockNoticeTest {

    private val viewModel: String =
        File("src/main/kotlin/com/localkeep/feature/unlock/UnlockViewModel.kt").readText()

    /** The guard alone, from its declaration to the one that follows it. */
    private val guard: String =
        viewModel.substringAfter("private fun biometricBlockedByPanicLock(): Boolean =")
            .substringBefore("private fun biometricAvailable(): Boolean =")

    private val xml: String = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(xml)?.groupValues?.get(1)
            ?: error("$name not found in strings.xml")

    @Test
    fun `the slice is bounded`() {
        assertTrue(
            guard.isNotEmpty() && guard.length < viewModel.length,
            "the guard slice is unbounded, so the assertion below is measuring the whole file " +
                "and cannot fail; length=${guard.length} of ${viewModel.length}",
        )
        assertTrue(
            "isBiometricUnlockBlocked" in guard,
            "the slice does not contain the guard it is named for",
        )
    }

    @Test
    fun `the notice does not depend on a key panic lock has already destroyed`() {
        assertTrue(
            "DeviceKeyStore.load" !in guard,
            "biometricBlockedByPanicLock reads DeviceKeyStore again: panicLock() deletes it in " +
                "the same call that sets the block, so this condition can never hold and the " +
                "notice can never render. Guard: $guard",
        )
    }

    @Test
    fun `the notice does not promise the block ends at the next unlock`() {
        val notice = string("unlock_biometric_blocked_notice")
        assertTrue(
            "until you enter your master password" !in notice,
            "the block outlives the next unlock — the device-wrapped key is destroyed, not " +
                "suspended. Said: $notice",
        )
        assertTrue(
            "until you unlock" !in notice,
            "said: $notice",
        )
        assertTrue(
            "Settings" in notice,
            "the notice must say where biometric unlock is turned back on, since it does not " +
                "come back on its own. Said: $notice",
        )
    }

    @Test
    fun `the refusal raised by the biometric button makes the same claim`() {
        val message = string("unlock_biometric_panic_lock_message")
        assertTrue(
            "until you enter your master password" !in message,
            "said: $message",
        )
    }
}
