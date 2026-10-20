package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A denial the system will still ask about must leave something on screen to ask with.
 *
 * The retriable branch of `PermissionDenial.after` returns a null `settingsActionLabel`, so
 * `LkInlineError` draws its message with no action button — which is correct only under that
 * component's own stated contract: the action slot is "for errors that leave the user with nothing
 * to press", on the assumption that the control which failed is still on screen. It is in the two
 * files flows, whose camera buttons survive the denial. It was not here: `IdentityCaptureFlow`
 * replaces the entire viewfinder with a note, that actionless error and Cancel. The message it
 * renders — `core_ui_permission_denial_retry`, "You can tap it again to allow access." — named an
 * action the screen did not offer, and Cancel pops the whole item editor, so the only route back to
 * the system dialog was Add > Identity > photograph from scratch.
 *
 * A source scan, for the reason `PhrasePillIsSafeToMissTest` gives; each claim carries a control
 * that held before the fix.
 */
class IdentityCameraDenialOffersRetryTest {

    private val locales = listOf("values", "values-de", "values-es", "values-fr", "values-pt")

    private fun flowBody(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/IdentityCapture.kt")
        assertTrue(src.exists(), "IdentityCapture.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("internal fun IdentityCaptureFlow(")
        assertTrue(start > 0, "IdentityCaptureFlow( not found — did the composable get renamed?")
        val end = text.indexOf("\nprivate fun ", start)
        assertTrue(end > start, "no private declaration after IdentityCaptureFlow — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `the denied screen can raise the system dialog again`() {
        val body = flowBody()
        // The control: the permanently-denied case is still served by the error's own action.
        // True before the fix and after — this test must not be satisfiable by removing it.
        assertTrue(
            "context.openAppSettings()" in body,
            "the settings-required denial no longer offers Open settings — region wrong?",
        )
        assertTrue(
            "showDisclosure = true" in body,
            "nothing in IdentityCaptureFlow ever re-arms showDisclosure. After a denial the " +
                "disclosure sheet is the only thing that calls permissionLauncher.launch, so the " +
                "screen's own copy points at an action it cannot perform.",
        )
    }

    @Test
    fun `the retry is offered only while Android will still ask`() {
        val body = flowBody()
        val gate = body.indexOf("d.settingsActionLabel == null")
        val rearm = body.indexOf("showDisclosure = true", gate.coerceAtLeast(0))
        assertTrue(
            gate > 0,
            "the retry control is not gated on the retriable denial. In the settings-required " +
                "state Android answers the request without showing anything, so the button would " +
                "be a control that visibly does nothing.",
        )
        assertTrue(
            rearm > gate,
            "showDisclosure is re-armed outside the retriable-denial branch.",
        )
        assertTrue(
            "ContextCompat.checkSelfPermission" in body.substring(gate, rearm),
            "the retry does not re-check the permission first, so a grant made in Settings while " +
                "this screen was up would raise the disclosure sheet again instead of the camera.",
        )
    }

    @Test
    fun `its label exists in every catalogue`() {
        for (locale in locales) {
            val file = File("src/main/res/$locale/strings.xml")
            assertTrue(file.exists(), "no catalogue at ${file.absolutePath}")
            val text = file.readText()
            // The control: this is still the catalogue that owns the capture copy. True before the
            // fix and after, so a wrong path cannot pass by finding nothing.
            assertTrue(
                """name="itemedit_identity_capture_denied"""" in text,
                "$locale/strings.xml does not look like this module's catalogue",
            )
            assertTrue(
                """name="itemedit_identity_capture_allow"""" in text,
                "itemedit_identity_capture_allow is missing from $locale/strings.xml — lint fails " +
                    "the build on MissingTranslation, and the retry button has no label.",
            )
        }
    }
}
