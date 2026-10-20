package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The identity capture's confirm step must only ever ask about a picture that is on screen.
 *
 * Two ways it stopped doing that, both fixed here and both asserted by scanning the source of
 * `IdentityCaptureFlow` — a render test is not available in this module (see the note in
 * `PhrasePillIsSafeToMissTest`), and the claims are structural anyway.
 *
 * 1. **The shutter took a second tap during the camera round trip.** `enabled` depended only on
 *    `controller.isReady` and `state.identityPhotoSaving`, and neither flips between the tap and
 *    the CameraX callback. `CameraCaptureController.capture()` has no in-flight guard of its own,
 *    so the second tap fired a second capture; whichever of the pair then lost
 *    `captureIdentityPhoto`'s `identityPhotoSaving` check got `onDone(false)` and raised "that
 *    picture did not save. Try again." over a page that had in fact been sealed into the vault.
 *
 * 2. **A rotation restored the confirm state without the frame.** `awaitingConfirm` is
 *    `rememberSaveable` and `lastFrame` cannot be — a full-resolution Bitmap has no business in a
 *    Bundle — so Retake/Continue came back drawn over a live, un-frozen viewfinder.
 *
 * Each test carries a control that held before the fix, so a region that stopped matching fails
 * rather than passing empty.
 */
class IdentityCaptureConfirmIsHonestTest {

    private fun captureSource(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/IdentityCapture.kt")
        assertTrue(src.exists(), "IdentityCapture.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    /** The body of `IdentityCaptureFlow`, bounded by the first private declaration after it. */
    private fun flowBody(): String {
        val text = captureSource()
        val start = text.indexOf("internal fun IdentityCaptureFlow(")
        assertTrue(start > 0, "IdentityCaptureFlow( not found — did the composable get renamed?")
        val end = text.indexOf("\nprivate fun ", start)
        assertTrue(end > start, "no private declaration after IdentityCaptureFlow — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `the shutter is disarmed at the tap, not at the write`() {
        val body = flowBody()
        val shutter = body.indexOf("ShutterButton(")
        assertTrue(shutter > 0, "the shutter is no longer called from the flow — region wrong?")
        val onClick = body.indexOf("controller.capture {", shutter)
        assertTrue(onClick > shutter, "the shutter no longer fires controller.capture — region wrong?")
        val armed = body.indexOf("captureInFlight = true", shutter)
        assertTrue(
            armed in (shutter + 1) until onClick,
            "nothing sets an in-flight flag between the shutter's onClick and controller.capture(). " +
                "state.identityPhotoSaving only flips once the first callback has returned, so a " +
                "second tap in that window fires a second capture and the loser's onDone(false) " +
                "reports a failure for a photograph that saved.",
        )
        assertTrue(
            "captureInFlight" in body.substring(shutter, onClick).substringBefore("onClick"),
            "the in-flight flag is not part of the shutter's enabled expression, so the button " +
                "stays pressable during the camera round trip.",
        )
        // The control: the write-time guard the shutter already had is still there. True before
        // the fix and after — the tap-time flag is an addition, not a replacement.
        assertTrue(
            "state.identityPhotoSaving" in body.substring(shutter, onClick),
            "the shutter no longer consults state.identityPhotoSaving — region wrong?",
        )
    }

    @Test
    fun `the tap is spent whichever way the camera answers`() {
        val body = flowBody()
        val capture = body.indexOf("controller.capture {")
        assertTrue(capture > 0, "controller.capture not found — region wrong?")
        assertTrue(
            "captureInFlight = false" in body.substring(capture),
            "the in-flight flag is never cleared inside the capture callback. A failed capture " +
                "would leave the shutter disabled for the rest of the flow, which is worse than " +
                "the double tap it was added to stop.",
        )
    }

    @Test
    fun `a confirm state restored without its frame is resolved, not shown blind`() {
        val body = flowBody()
        // The controls, both true before the fix: these are the two declarations whose different
        // lifetimes create the desync. If either changes, the reconciliation below is describing
        // something that no longer exists.
        assertTrue(
            "var awaitingConfirm by rememberSaveable" in body,
            "awaitingConfirm is no longer saveable — region wrong, or the desync moved.",
        )
        assertTrue(
            "var lastFrame by remember {" in body,
            "lastFrame is no longer a plain remember — region wrong, or the desync moved.",
        )
        assertTrue(
            "awaitingConfirm && lastFrame == null" in body,
            "nothing reconciles awaitingConfirm with lastFrame. A rotation between the shutter " +
                "and Retake/Continue restores the confirm controls over a live preview and asks " +
                "the user to judge a picture that is not on screen.",
        )
        val guard = body.indexOf("awaitingConfirm && lastFrame == null")
        val rollback = body.indexOf("viewModel.retakeLastIdentityPhoto()", guard)
        assertTrue(
            rollback > guard && rollback - guard < 400,
            "the reconciliation does not drop the unconfirmed page, so identityPhotos would keep " +
                "a page the user never accepted and the next capture would add a second one.",
        )
        assertTrue(
            "!state.identityPhotoSaving" in body.substring(guard, rollback),
            "the reconciliation is not gated on the write being finished. Mid-write the page has " +
                "not been appended yet, so the rollback would drop the previous page instead.",
        )
    }
}
