package com.localkeep.core.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The naming step must not ask for focus, because asking for focus is what raised the keyboard.
 *
 * Reported from the file import flow on 2026-08-25: pressing Save opened the naming sheet with the
 * IME already up and over the sheet's own confirm button, so accepting the suggested name — the
 * common case — meant dismissing a keyboard nobody asked for. Measured before and after on
 * `emulator-5556` with `adb shell dumpsys input_method`: `mInputShown=true` with the focus request,
 * `mInputShown=false` without it, on both callers of the sheet.
 *
 * **Why this reads the source instead of driving the component.** The obvious test — compose the
 * sheet and assert the field is not focused — was written first and thrown away, because it does
 * not discriminate. Under `createComposeRule` the sheet is a separate window and Material3 moves
 * focus into its content a frame after composition, so the tagged wrapper reports `Focused = true`
 * *with or without* the requester, while the editable node reports `false` in both. Both assertions
 * were run against the fixed and the unfixed component: the first failed twice, the second passed
 * twice. Neither says anything about the defect, and a test that has never been red for the right
 * reason is worse than no test — it reads as coverage.
 *
 * So this asserts the one thing that is unambiguously true of the fix and false of the bug, in the
 * idiom `checkNoPlatformDrag` already uses in this repo: the code does not contain the call. It is
 * a blunt instrument and deliberately so. If a future author needs focus here, deleting this test
 * is the honest way to do it — and then the keyboard is back, which is the conversation this file
 * exists to force.
 */
class NamingSheetAutoFocusTest {

    private val source: String
        get() = File(REPO, "core/ui/src/main/kotlin/com/localkeep/core/ui/theme/LkNamingSheet.kt")
            .also { assertTrue(it.isFile, "LkNamingSheet.kt not found at ${it.absolutePath}") }
            .readText()

    @Test
    fun `the naming sheet requests focus for nothing`() {
        val code = source.lineSequence()
            // Comments explain why the focus request is gone; they must not trip the check, or the
            // explanation becomes unwritable and the next author gets the rule without the reason.
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "requestFocus" in code || "FocusRequester" in code,
            "LkNamingSheet asks for focus again. That reopens the software keyboard over its own " +
                "confirm button — see the KDoc above, and re-measure with dumpsys input_method " +
                "before deciding this is wanted.",
        )
    }

    /**
     * The control. The assertion above would also pass against an empty file, a renamed file, or a
     * file this test cannot find — so prove the thing being read is really the sheet.
     */
    @Test
    fun `the file being read really is the naming sheet`() {
        assertTrue("fun LkNamingSheet(" in source, "read a file that is not LkNamingSheet")
        assertTrue("naming-sheet-confirm" in source, "read a LkNamingSheet with no confirm button")
    }
}

private val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
    .firstOrNull { File(it, "settings.gradle.kts").isFile }
    ?: error("repo root not found above ${File("").absolutePath}")
