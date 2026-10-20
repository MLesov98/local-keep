package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Advancing a step must ask for focus on the next frames, never after a timed wait.
 *
 * The wizard auto-advances when a field is complete, and the `when (step)` that draws the steps has
 * no `AnimatedContent` — so the departing field is disposed in the same frame the step changes.
 * Focus dies with it, the IME slides out, and the screen-level `imePadding()` collapses. Whatever
 * happens between that and the re-focus is dead screen, and the user watches the keyboard leave and
 * come back.
 *
 * Measured on emulator-5554 (API 36, 1080x2400) on 2026-09-04, from a screen recording of the
 * number→expiry advance, reading keyboard-band luminance per rendered frame: keyboard up through
 * t=1.08 s, fully gone by t=1.51 s, fully back by t=2.13 s. Two IME transitions and two 704 px
 * layout shifts for one auto-advance — reported as "double jump animation".
 *
 * The wait was `delay(LkMotion.Screen)` = 460 ms, added to stop the IME rising *through* the
 * entrance animation. It did stop the overlap, by moving the two motions far enough apart to read
 * as two separate events instead of one; the fix is to ask for focus back before the IME has
 * finished leaving, so it never lands.
 *
 * **Why this reads the source instead of driving the wizard.** The same reason
 * `CardWizardImeNextTest` does, recorded there: `CardWizard` needs a real `ItemEditViewModel`, whose
 * `VaultRepository` opens `AndroidKeyStore` in its constructor — absent on a JVM, and this module's
 * `build.gradle.kts` records that Compose UI tests do not reach idle here at all. A frame-accurate
 * assertion about IME timing is a device measurement, not a unit test; what a unit test can pin is
 * that the timed wait has not come back.
 */
class CardWizardAdvanceFocusTest {

    private val source = File("src/main/kotlin/com/localkeep/feature/itemedit/CardWizard.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    /**
     * Comments explain why the wait is gone and quote the constant it used to use; they must not
     * trip the check, or the explanation becomes unwritable and the next author gets the rule
     * without the reason. Same filter [NamingSheetAutoFocusTest]-style source guards use elsewhere.
     */
    private fun code(): String = text().lineSequence()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun `advancing a step does not wait before asking for focus`() {
        assertFalse(
            "delay(" in code(),
            "CardWizard waits before re-focusing the step's field again. The step's field is " +
                "disposed the moment the step changes, so any wait is time spent with the IME " +
                "sliding out and the layout collapsing — then both reverse. Re-measure on a device " +
                "(screen-record the number→expiry advance and read the keyboard band per frame) " +
                "before deciding a wait is wanted.",
        )
    }

    @Test
    fun `the focus request still waits for the entering step's field to attach`() {
        assertTrue(
            code().contains("withFrameNanos"),
            "CardWizard asks for focus with no frame wait at all. The field belonging to the step " +
                "being entered is not attached on the frame the step changes, so the request lands " +
                "on the departing step's requester and throws.",
        )
    }

    /**
     * The control. Both assertions above would pass against an empty file, a renamed file, or a
     * file this test cannot find — so prove the thing being read is really the wizard, and really
     * still the version that focuses on advance at all. Without this, deleting the focus request
     * outright reads as a pass, and that is the 2026-08-20 regression where an auto-advance left
     * the IME up with nothing focused and every keystroke went nowhere.
     */
    @Test
    fun `the file being read really is the wizard and it really still focuses on advance`() {
        val text = text()
        assertTrue("internal fun CardWizard(" in text, "read a file that is not CardWizard")
        assertTrue(
            "stepFocus.requestFocus()" in code(),
            "CardWizard no longer focuses the step it advanced to. Auto-advance leaves the IME up " +
                "with the departed field's focus gone, so keystrokes after an advance land nowhere " +
                "— measured on the emulator 2026-08-20, typing a number and an expiry straight " +
                "through left the expiry empty.",
        )
    }
}
