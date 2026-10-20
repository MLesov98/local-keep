package com.localkeep.feature.unlock

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The lockout sentence counts down instead of freezing.
 *
 * After five wrong master passwords the exponential backoff trips and the screen says "Try again
 * in N seconds". N was computed once, in the `catch (locked: VaultLockedException)` handler, and
 * then never recomputed — a device pass watched "Try again in 5 seconds" stay on screen for ten
 * seconds past the stated window. A user who is locked out has no way to tell when the wait is
 * over except to guess and retype, and a wrong guess inside the window extends it.
 *
 * Asserted on the source for the same reason as `RecoveryWordsSurviveFailureTest`: constructing
 * `UnlockViewModel` needs a `Context`, a `VaultLockManager`, a `KeystoreManager` and a
 * `BiometricUnlock`, and this module has no Robolectric. `RetryCountdownTickTest` covers the
 * ticking itself, which is a free function precisely so it can be driven by a fake clock.
 */
class RetryCountdownTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/unlock/UnlockViewModel.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    /**
     * Every place that shows a lockout has to start the ticker. There are three — password,
     * Recovery Kit and biometric — and the biometric one has its own backoff, so a fix that
     * covered only the password path would leave two frozen sentences.
     */
    @Test
    fun `every lockout error starts a countdown`() {
        val handlers = text()
            .split("catch (locked: VaultLockedException) {")
            .drop(1)
            .map { it.substringBefore("} catch (") }
        assertTrue(
            handlers.size == 3,
            "expected the three lockout handlers (password, recovery kit, biometric); " +
                "found ${handlers.size}",
        )
        handlers.forEachIndexed { index, handler ->
            assertTrue(
                "startRetryCountdown(" in handler,
                "lockout handler ${index + 1} shows a number and never updates it — the sentence " +
                    "freezes at the value caught with the exception. Found: $handler",
            )
        }
    }

    /**
     * The ticker measures against the same monotonic clock `VaultLockManager.checkThrottle` uses.
     * A wall-clock countdown would drift against the very deadline it describes.
     */
    @Test
    fun `the countdown is driven by the monotonic clock`() {
        val countdown =
            File("src/main/kotlin/com/localkeep/feature/unlock/RetryCountdown.kt")
        if (!countdown.isFile) fail("cannot find ${countdown.absolutePath}")
        val text = countdown.readText()
        assertTrue(
            "SystemClock::elapsedRealtime" in text,
            "the countdown must read elapsedRealtime, the clock the throttle deadline is set on",
        )
        assertTrue(
            "currentTimeMillis" !in text,
            "wall clock in the countdown would drift against the throttle deadline",
        )
    }
}
