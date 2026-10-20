package com.localkeep.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 7,776-word EFF list must not be read, split and validated on the main thread in `onCreate`.
 *
 * It is a startup cost paid by every process start — including the ones the platform makes purely to
 * answer an autofill request, which will never generate a passphrase — and nothing needs the list
 * until a person asks for one.
 *
 * This reads the source rather than driving the Application because the app module's unit-test
 * classpath has no Robolectric and no Hilt test runtime, so `onCreate` cannot be invoked here at
 * all; the sibling guardrails `checkNoPlatformDrag` and `checkNoApi30Signatures` make the same trade
 * for the same reason. What it costs in elegance it buys back in the one thing that matters: this
 * regression is invisible in review and invisible on a fast device, and reappears the moment
 * somebody "simplifies" the launch away.
 */
class StartupWordlistOffMainThreadTest {

    private val source = File("src/main/kotlin/com/localkeep/app/LocalKeepApplication.kt")

    @Test
    fun `the wordlist load is inside a coroutine launch, not a statement of onCreate`() {
        // Line comments are stripped first: the explanation sitting above the call is prose, and a
        // brace in prose must not be able to move the depth this test measures. Replaced with
        // spaces rather than removed so every offset below still lines up with the real file.
        val text = source.readText().lineSequence().joinToString("\n") { line ->
            val comment = line.indexOf("//")
            if (comment < 0) line else line.take(comment) + " ".repeat(line.length - comment)
        }

        val onCreate = text.indexOf("override fun onCreate()")
        assertTrue(onCreate >= 0, "LocalKeepApplication.onCreate not found — has it been renamed?")
        val call = text.indexOf("PasswordGenerator.setWordlist(", onCreate)
        assertTrue(call >= 0, "the wordlist is no longer installed from onCreate — update this test")

        // Every brace still open at the call site, innermost last. The first is `onCreate`'s own
        // body, so a call at the function's statement level leaves exactly one.
        val open = ArrayDeque<Int>()
        for (i in onCreate until call) {
            when (text[i]) {
                '{' -> open.addLast(i)
                '}' -> open.removeLastOrNull()
            }
        }

        assertTrue(
            open.size >= 2,
            "the wordlist is loaded at onCreate's statement level — that is the main thread",
        )
        val enclosing = text.substring(text.lastIndexOf('\n', open.last()) + 1, open.last())
        assertTrue(
            "launch" in enclosing,
            "the wordlist load must sit in a coroutine launch; its enclosing block is: $enclosing",
        )
    }
}
