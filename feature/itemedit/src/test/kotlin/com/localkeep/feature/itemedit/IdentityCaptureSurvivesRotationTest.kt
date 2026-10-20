package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Identity photo capture survives a rotation landing in the middle of it.
 *
 * Two findings, one root cause: an activity recreate tears down the composition while the encrypt
 * is still running, and the capture's bookkeeping lived in that composition.
 *
 * **The rollback fired once.** `awaitingConfirm` is `rememberSaveable` and comes back true; the
 * full-resolution `lastFrame` cannot be saved to a Bundle and comes back null. The effect that
 * resolves that mismatch — drop the unconfirmable photo — was keyed on `Unit`, so a rotation timed
 * *during* the write found `identityPhotoSaving` still true, skipped, and never got another chance.
 * The confirm controls then sat over a live viewfinder with nothing to judge, permanently.
 *
 * **The failure landed nowhere.** `captureIdentityPhoto`'s callback closes over the state object of
 * the composition that started the capture. `rememberSaveable` makes the *value* survive, but the
 * recreate builds a new state object, so a failure resolving after the rotation wrote `true` into
 * something nothing renders any more: no message, and the user free to Continue past a page whose
 * photo never reached the vault.
 *
 * Asserted against the source: both claims are about which argument a call site passes, which is
 * what this repo answers from source rather than by standing up a camera.
 */
class IdentityCaptureSurvivesRotationTest {

    @Test
    fun `the unconfirmable-frame rollback re-arms when the write finishes`() {
        val screen = source(CAPTURE)
        assertTrue(
            "LaunchedEffect(state.identityPhotoSaving)" in screen,
            "the rollback must re-evaluate when the write completes; keyed on Unit it fires once " +
                "and a rotation during the encrypt loses its only chance",
        )
        assertEquals(
            0, Regex("""LaunchedEffect\(Unit\) \{\s*if \(awaitingConfirm""").findAll(screen).count(),
            "the rollback is still keyed on Unit somewhere",
        )
    }

    @Test
    fun `a capture failure is held where a rotation cannot lose it`() {
        val screen = source(CAPTURE)
        assertTrue(
            "captureFailed || state.identityPhotoFailed" in screen,
            "the failure banner must also read the ViewModel's durable flag, or a failure that " +
                "resolves after a rotation shows nothing at all",
        )
        assertTrue(
            "viewModel.clearIdentityPhotoFailure()" in screen,
            "taking another shot must clear the durable flag, or the banner outlives its cause",
        )
        val viewModel = source(VIEW_MODEL)
        assertTrue(
            "identityPhotoSaving = false, identityPhotoFailed = true" in viewModel,
            "the asynchronous failure paths must set the durable flag alongside clearing the " +
                "in-flight flag",
        )
    }

    /**
     * The control, and it must stay green while the two above go red.
     *
     * The three early refusals in `captureIdentityPhoto` — no store, a capture already in flight,
     * and the attachment quota — all return *before* `viewModelScope.launch`, so the composition
     * that called them is still alive and its local flag works. What matters about them is
     * something else entirely, and it predates this change: each must still resolve `onDone`. A
     * fast double tap reaches the second refusal with a capture in flight, and returning without
     * resolving the callback left the thumbnail rail desynced from `identityPhotos`. Sweeping the
     * durable flag into those paths for symmetry would risk disturbing that.
     *
     * Five, not four: there are two asynchronous failure paths, not one — the strip/encrypt
     * returning no attachment, and the surrounding catch. Both carry the durable flag. This
     * test was written asserting four and was wrong, which is the sort of thing a count
     * catches and a `>= 1` would not.
     */
    @Test
    fun `every refusal still resolves the callback`() {
        val viewModel = source(VIEW_MODEL)
        val body = viewModel.substringAfter("fun captureIdentityPhoto(")
            .substringBefore("\n    fun ")
        assertTrue(
            body.count { it == '\n' } > 10,
            "captureIdentityPhoto's body did not extract — the anchor is stale",
        )
        assertEquals(
            5, Regex("""onDone\(false\)""").findAll(body).count(),
            "expected the three synchronous refusals plus BOTH asynchronous failure paths to " +
                "resolve onDone(false); a refusal that returns without resolving desyncs the rail",
        )
    }

    private companion object {
        const val CAPTURE = "src/main/kotlin/com/localkeep/feature/itemedit/IdentityCapture.kt"
        const val VIEW_MODEL = "src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt"

        fun source(relative: String): String {
            val direct = File(relative)
            val file = if (direct.isFile) direct else File(repoRoot(), "feature/itemedit/$relative")
            assertTrue(file.isFile, "expected source file at $relative")
            return file.readText()
        }

        fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")
    }
}
