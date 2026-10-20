package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Three more titles TalkBack's navigate-by-heading gesture could not stop on, fixed alongside
 * [SavedScreenAndIdentityStartHeadingTest]'s two (the remaining heading
 * defects, 2026-09-13).
 *
 * Source scans rather than renders, for the reason [IdentityStartScrollableTest] already gives for
 * this module and [CardWizardAdvanceFocusTest]/[IdentityCaptureConfirmIsHonestTest] already act on:
 * `CardWizardHeading` is `private` and reachable only through `CardWizard`, which needs a real
 * `ItemEditViewModel` (a real `VaultRepository`/`KeystoreManager`, unavailable under Robolectric);
 * `IdentityCaptureFlow`'s per-page prompt and `IdentityCaptureNaming` sit behind the same ViewModel
 * plus a `LocalContext`-driven camera-permission launcher. None of the three renders on a JVM.
 *
 * Each helper bounds its slice to the one function the finding names, and every test asserts its
 * anchor was actually found before trusting anything about the slice — an unbounded
 * `substringAfter` on a missing marker returns the whole file and would pass vacuously.
 */
class HeadingSemanticsSourceScanTest {

    private fun cardWizardSource(): String {
        val file = File("src/main/kotlin/com/localkeep/feature/itemedit/CardWizard.kt")
        assertTrue(file.exists(), "CardWizard.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    private fun identityCaptureSource(): String {
        val file = File("src/main/kotlin/com/localkeep/feature/itemedit/IdentityCapture.kt")
        assertTrue(file.exists(), "IdentityCapture.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    private fun cardWizardHeadingBody(): String {
        val text = cardWizardSource()
        val start = text.indexOf("internal fun CardWizardHeading(")
        assertTrue(start > 0, "CardWizardHeading( not found — did the composable get renamed?")
        val end = text.indexOf("private fun NumberStep(", start)
        assertTrue(end > start, "NumberStep( not found after CardWizardHeading — region unbounded")
        return text.substring(start, end)
    }

    // ── 21-1: CardWizard's five step titles ─────────────────────────────────

    @Test
    fun `the card wizard step title carries heading semantics`() {
        val body = cardWizardHeadingBody()
        val headingIndex = body.indexOf("modifier = Modifier.semantics { heading() }")
        assertTrue(headingIndex > 0, "no Modifier.semantics { heading() } found in CardWizardHeading")

        // Bounded to the title's own Text( call: LkText.display is the title's style token (the
        // body copy two lines down uses LkText.bodyQuiet), so this pins the heading to the title
        // rather than merely somewhere in the function.
        val displayIndex = body.indexOf("style = LkText.display", headingIndex)
        assertTrue(
            displayIndex in headingIndex..(headingIndex + 120),
            "heading() was found in CardWizardHeading but not immediately before the title's " +
                "LkText.display Text( call",
        )
    }

    @Test
    fun `the card wizard step body copy is not marked a heading`() {
        // The control: tagging every Text a heading would satisfy the assertion above while making
        // navigate-by-heading stop on both nodes of every step, which is the same uselessness as
        // stopping on neither.
        val body = cardWizardHeadingBody()
        val bodyQuietIndex = body.indexOf("LkText.bodyQuiet")
        assertTrue(bodyQuietIndex > 0, "LkText.bodyQuiet not found — did the body copy change style tokens?")
        val bodyCallStart = body.lastIndexOf("Text(", bodyQuietIndex)
        assertTrue(bodyCallStart in 0..bodyQuietIndex, "could not locate the body Text( call")
        assertTrue(
            "heading()" !in body.substring(bodyCallStart, bodyQuietIndex),
            "the step's body copy must not carry heading() semantics",
        )
    }

    // ── 21-3 (2 of 3): the per-page capture prompt ──────────────────────────

    private fun identityCapturePromptBody(): String {
        val text = identityCaptureSource()
        val start = text.indexOf("val prompt = stringResource(")
        assertTrue(start > 0, "the capture-page prompt was not found — did IdentityCaptureFlow change?")
        val end = text.indexOf("if (!awaitingConfirm) {", start)
        assertTrue(end > start, "region after the capture-page prompt Text is unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `the capture page prompt carries heading semantics`() {
        val body = identityCapturePromptBody()
        assertTrue(
            "itemedit_identity_capture_front" in body,
            "did not read the capture-page prompt region — the front/back/more branch is missing",
        )
        assertTrue(
            ".semantics { heading() }" in body,
            "the capture-page prompt (front/back/more) has no heading() semantic",
        )
        assertTrue(
            ".announce(prompt)" in body,
            "the prompt's live-region announce() was removed by this change — both must coexist",
        )
    }

    // ── 21-3 (3 of 3): the naming step's title ──────────────────────────────

    private fun identityCaptureNamingBody(): String {
        val text = identityCaptureSource()
        val start = text.indexOf("private fun IdentityCaptureNaming(")
        assertTrue(start > 0, "IdentityCaptureNaming( not found — did the composable get renamed?")
        val end = text.indexOf("private fun PageStack(", start)
        assertTrue(end > start, "PageStack( not found after IdentityCaptureNaming — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `the naming step title carries heading semantics`() {
        val body = identityCaptureNamingBody()
        val titleIndex = body.indexOf("itemedit_identity_capture_name_title")
        assertTrue(titleIndex > 0, "did not read the naming step's title region")

        val headingIndex = body.indexOf("modifier = Modifier.semantics { heading() }", titleIndex)
        assertTrue(
            headingIndex in titleIndex..(titleIndex + 220),
            "no heading() found on the naming step's title Text( call",
        )
    }
}
