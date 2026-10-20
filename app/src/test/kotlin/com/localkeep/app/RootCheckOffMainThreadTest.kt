package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The root probe must not be answered during composition on the main thread.
 *
 * [RootCheck.looksRooted] is four `File.exists()` stat calls, and the expression it sits in also
 * reads a SharedPreferences file. That ran inside a plain `remember { }` initializer, so it was
 * disk I/O on the first composition of every cold launch and — `MainActivity` declares no
 * `android:configChanges`, which its own comment says — on every rotation as well. It gates a
 * dismissible advisory banner and nothing else, so there is nothing to be gained by knowing the
 * answer in the first frame.
 *
 * Reads the source for the same reason [StartupWordlistOffMainThreadTest] does: the app module's
 * unit-test classpath has no Robolectric and no Hilt test runtime, so the composable cannot be
 * invoked here at all.
 */
class RootCheckOffMainThreadTest {

    private val source = File("src/main/kotlin/com/localkeep/app/MainActivity.kt")

    /**
     * Line comments stripped first — the explanation above the call is prose, and a brace in prose
     * must not move the depth this test measures. Replaced with spaces, not removed, so every
     * offset still lines up with the real file.
     */
    private fun sourceWithoutLineComments(): String =
        source.readText().lineSequence().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment < 0) line else line.take(comment) + " ".repeat(line.length - comment)
        }

    @Test
    fun `the root probe runs inside a dispatcher hop, not in a remember initializer`() {
        val text = sourceWithoutLineComments()
        val call = text.indexOf("RootCheck.looksRooted()")
        assertTrue(call >= 0, "MainActivity no longer calls RootCheck.looksRooted() — update this test")

        // Every brace still open at the call site, innermost last.
        val open = ArrayDeque<Int>()
        for (i in 0 until call) {
            when (text[i]) {
                '{' -> open.addLast(i)
                '}' -> open.removeLastOrNull()
            }
        }
        val innermost = open.lastOrNull() ?: fail("could not find the block enclosing the root probe")

        val enclosing = text.substring(text.lastIndexOf('\n', innermost) + 1, innermost)
        assertTrue(
            "withContext(Dispatchers.IO)" in enclosing,
            "RootCheck.looksRooted() must sit inside a dispatcher hop. In a remember{} initializer " +
                "it is main-thread disk I/O on every cold launch and every rotation. Its enclosing " +
                "block is: $enclosing",
        )
    }

    /**
     * Control. This held before the fix and must keep holding: the assertion above is reading the
     * real banner wiring, not passing because the state has been renamed out from under it.
     */
    @Test
    fun `the banner is still gated on the probe and the dismissal flag`() {
        val text = sourceWithoutLineComments()
        assertTrue("showRootWarning" in text, "MainActivity no longer holds the root-warning flag")
        assertTrue(
            "RootWarningPreferences.isDismissed(context)" in text,
            "the one-time dismissal must still suppress the banner",
        )
    }
}
