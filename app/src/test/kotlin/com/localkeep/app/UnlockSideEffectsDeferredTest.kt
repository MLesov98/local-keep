package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The lock-state collector runs while MainActivity is stopped, and two of the things it used to do
 * are once-only.
 *
 * It carries no `repeatOnLifecycle` on purpose: the vault can be unlocked by AuthActivity,
 * PickerActivity, SaveConfirmActivity or CredentialActivity, which all share the same
 * VaultLockManager, and the nav host has to be re-routed off the unlock screen when that happens.
 * So the branch also fires with this Activity behind another app's window — and there it consumed
 * the one-shot "vault restored" flag into a notice nobody could see, and spent one of the three
 * review asks this install will ever make on a card Play either drew over a third-party sign-in
 * form or refused to draw at all (`PlatformReview` swallows the refusal).
 *
 * Both now sit inside `lifecycle.withResumed`, so this asserts the enclosing block rather than the
 * behaviour. It reads the source for the same reason [StartupWordlistOffMainThreadTest] does: the
 * app module's unit-test classpath has no Robolectric and no Hilt test runtime, so the Activity
 * cannot be composed here at all — and a device test cannot express "the Activity is stopped
 * because another app is in front" without a second app to put there.
 */
class UnlockSideEffectsDeferredTest {

    private val source = File("src/main/kotlin/com/localkeep/app/MainActivity.kt")

    /** The file with `//` comments blanked — a brace in prose must not move the depth measured
     *  below. Replaced with spaces rather than removed so every offset still lines up. */
    private val text: String by lazy {
        source.readText().lineSequence().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment < 0) line else line.take(comment) + " ".repeat(line.length - comment)
        }
    }

    /**
     * The innermost block still open at [at]: the line that opened it, then everything inside it up
     * to the call. Either half may carry the gate — `withResumed`'s lambda does not suspend, so a
     * suspending call has to sit after it in the enclosing `launch` rather than inside it.
     */
    private fun enclosingBlock(from: Int, at: Int): String {
        val open = ArrayDeque<Int>()
        for (i in from until at) {
            when (text[i]) {
                '{' -> open.addLast(i)
                '}' -> open.removeLastOrNull()
            }
        }
        val brace = requireNotNull(open.lastOrNull()) { "no open block between $from and $at" }
        return text.substring(text.lastIndexOf('\n', brace) + 1, at)
    }

    private fun assertDeferredToResume(call: String) {
        val collect = text.indexOf("lockManager.lockState.collect")
        assertTrue(collect >= 0, "the lock-state collector is gone — update this test")
        val at = text.indexOf(call, collect)
        assertTrue(at >= 0, "$call no longer runs from the lock-state collector — update this test")
        val enclosing = enclosingBlock(collect, at)
        assertTrue(
            "withResumed" in enclosing,
            "$call runs on an unlock that may have arrived while MainActivity was stopped — " +
                "nothing gates it on the Activity being resumed. Its enclosing block is:\n$enclosing",
        )
    }

    @Test
    fun `the restore confirmation is not consumed while the shell is off screen`() {
        assertDeferredToResume("BackupPreferences.consumeRestoreNoticePending(")
    }

    @Test
    fun `a review ask is not spent while the shell is off screen`() {
        assertDeferredToResume("ReviewGate.onAsked(")
        assertDeferredToResume("launchPlatformReview(")
    }
}
