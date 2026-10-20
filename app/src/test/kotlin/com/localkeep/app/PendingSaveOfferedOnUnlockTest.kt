package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The queued autofill save is offered by the unlock, from the foreground, and never to a decoy.
 *
 * A save the user accepted on the platform's own "Save password to LocalKeep?" sheet is dropped in
 * silence when the vault locks in between: Android refuses `SaveConfirmActivity`'s PendingIntent as
 * a background activity launch (`BAL_BLOCK`, result code 102, API 35). `PendingSaveQueue` holds it
 * for the next unlock, and this pins the three properties of *where* that offer is made from —
 * which is the half that cannot be unit-tested in the module that owns the queue.
 *
 * Reads the source for the reason [UnlockSideEffectsDeferredTest] does: the app module's
 * unit-test classpath has no Robolectric and no Hilt test runtime, so the Activity cannot be
 * composed here at all.
 */
class PendingSaveOfferedOnUnlockTest {

    private val source = File("src/main/kotlin/com/localkeep/app/MainActivity.kt")

    /** The file with `//` comments blanked, so a brace in prose cannot move the depth measured
     *  below. Replaced with spaces rather than removed so every offset still lines up. */
    private val text: String by lazy {
        source.readText().lineSequence().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment < 0) line else line.take(comment) + " ".repeat(line.length - comment)
        }
    }

    private val collect: Int by lazy {
        text.indexOf("lockManager.lockState.collect").also {
            assertTrue(it >= 0, "the lock-state collector is gone — update this test")
        }
    }

    /** The innermost block still open at [at]: the line that opened it, then everything inside it
     *  up to the call. */
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

    private fun indexInCollector(call: String): Int =
        text.indexOf(call, collect).also {
            assertTrue(it >= 0, "$call no longer runs from the lock-state collector — update this test")
        }

    /**
     * The offer is the one place in this collector where running while stopped reproduces the very
     * failure being fixed: `startActivity` from a stopped Activity is a background activity launch,
     * which is what Android refused in the first place. The collector deliberately carries no
     * lifecycle guard — AuthActivity, PickerActivity and the credential provider share this
     * VaultLockManager, so an unlock arrives here with another app's window in front.
     */
    @Test
    fun `the queued save is offered only once the shell is resumed`() {
        val at = indexInCollector("PendingSaveQueue.offerIntent(")
        val enclosing = enclosingBlock(collect, at)
        assertTrue(
            "withResumed" in enclosing,
            "the queued save is offered on an unlock that may have arrived while MainActivity was " +
                "stopped, which is the background activity launch this whole path exists to avoid. " +
                "Its enclosing block is:\n$enclosing",
        )
    }

    /**
     * The decision belongs to [com.localkeep.autofill.PendingSaveQueue], which is where the duress
     * rule is written and tested. Routing the unlock through anything else here — a bare
     * `offerIntent` on every unlock, say — would hand a decoy session a credential the real user
     * captured minutes ago.
     */
    @Test
    fun `the unlock routes through the queue's own decision`() {
        val at = indexInCollector("PendingSaveQueue.actionFor(")
        val discard = text.indexOf("PendingSaveQueue.discardAll(", at)
        assertTrue(
            discard in at..(at + 800),
            "the unlock does not answer PendingSaveAction.DISCARD by discarding, so a duress " +
                "session keeps a queued capture from the real vault",
        )
        assertTrue(
            at < indexInCollector("PendingSaveQueue.offerIntent("),
            "the offer is launched without asking actionFor first",
        )
    }
}
