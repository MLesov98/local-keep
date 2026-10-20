package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Nothing is left wearing a focus ring after the re-auth prompt closes.
 *
 * `shots/085-phrase-revealed.png`, 2026-09-15: PIN accepted, phrase revealed — and the Back chevron
 * in the top-left carrying a bright jade focus ring, reading as the screen's primary control, on a
 * screen whose point is the secret now visible below it (F-27). The prompt is a window of its own;
 * closing it hands focus back to the first focusable node in ours, and nothing on the way back took
 * it away again.
 *
 * FAILS WITHOUT THE FIX: `RevealAuthEffect` reported the outcome to the ViewModel and ended.
 */
class RevealPromptReleasesFocusTest {

    private val effect = File("src/main/kotlin/com/localkeep/feature/itemedit/RevealAuthEffect.kt").readText()

    @Test
    fun `the effect clears focus once the prompt has answered`() {
        assertTrue("LocalFocusManager" in effect, "the effect cannot reach focus at all")
        assertTrue(
            "focusManager.clearFocus(force = true)" in effect,
            "focus is left wherever the closing prompt put it",
        )
    }

    /**
     * Only on the branch that actually raised a prompt. The two refusals above it — no Activity, no
     * enrolled authentication — never leave this window, so focus is wherever the user put it and
     * clearing it would be taking it from them.
     */
    @Test
    fun `the refusals that raise no prompt do not touch focus`() {
        val clearIndex = effect.indexOf("focusManager.clearFocus")
        val authenticateIndex = effect.indexOf("RevealAuth.authenticate(")
        assertTrue(authenticateIndex in 0 until clearIndex, "focus is cleared outside the prompt branch")
        val refusals = effect.substring(effect.indexOf("when {"), authenticateIndex)
        assertTrue("clearFocus" !in refusals)
    }
}
