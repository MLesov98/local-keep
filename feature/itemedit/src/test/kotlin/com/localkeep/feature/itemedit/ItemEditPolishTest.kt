package com.localkeep.feature.itemedit

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Small decisions the editor, the detail screen and the wizards make, held in plain logic; where
 * the decision is wiring that needs a device to see, a source scan holds the wiring. This module
 * hosts no Compose UI tests, and `ItemEditViewModel` cannot be built on a JVM.
 */
class ItemEditPolishTest {

    private fun src(name: String) = File("src/main/kotlin/com/localkeep/feature/itemedit/$name").readText()

    private fun body(text: String, marker: String): String {
        assertTrue(marker in text, "cannot find marker: $marker")
        return text.substringAfter(marker).substringBefore("\n}\n")
    }

    // -- identity review heading ---------------------------------------------------------------

    @Test
    fun `an empty identity review does not say ready to save`() {
        assertEquals(
            R.string.itemedit_identity_wizard_review_empty_title,
            identityHeadingRes(step = 4, hasDetail = false),
        )
    }

    @Test
    fun `a review with something typed still says ready to save, and earlier steps ignore hasDetail`() {
        assertEquals(R.string.itemedit_card_wizard_review_title, identityHeadingRes(step = 4, hasDetail = true))
        assertEquals(
            R.string.itemedit_identity_personal_details_heading,
            identityHeadingRes(step = 0, hasDetail = false),
        )
    }

    // -- the title refusal is said once --------------------------------------------------------

    @Test
    fun `a title refusal the field already shows is not repeated in the banner`() {
        assertNull(errorBannerFor(R.string.itemedit_error_title_required, titleError = true))
    }

    @Test
    fun `every other refusal, and a title refusal with no field marked, still reaches the banner`() {
        assertEquals(
            R.string.itemedit_error_check_fields,
            errorBannerFor(R.string.itemedit_error_check_fields, titleError = true),
        )
        assertEquals(
            R.string.itemedit_error_title_required,
            errorBannerFor(R.string.itemedit_error_title_required, titleError = false),
        )
        assertNull(errorBannerFor(null, titleError = true))
    }

    // -- passkey dates -------------------------------------------------------------------------

    private val noon = Instant.parse("2026-03-03T12:00:00Z")

    @Test
    fun `a passkey added the day it was created does not repeat the date`() {
        assertTrue(
            addedRepeatsPasskeyCreated(
                ItemEditUiState.ItemType.PASSKEY,
                noon,
                noon.plusSeconds(60),
                ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun `an imported passkey, created on another day, keeps both dates`() {
        assertFalse(
            addedRepeatsPasskeyCreated(
                ItemEditUiState.ItemType.PASSKEY,
                noon,
                noon.minusSeconds(3 * 86_400L),
                ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun `only a passkey drops the Added row`() {
        assertFalse(
            addedRepeatsPasskeyCreated(ItemEditUiState.ItemType.LOGIN, noon, noon, ZoneOffset.UTC),
        )
        assertFalse(
            addedRepeatsPasskeyCreated(ItemEditUiState.ItemType.PASSKEY, noon, null, ZoneOffset.UTC),
        )
    }

    // -- identity camera refusal belongs to its page --------------------------------------------

    @Test
    fun `clearing the photo refusal clears the failure and the quota sentence behind it`() {
        val refused = ItemEditUiState(identityPhotoFailed = true, identityPhotoQuotaMustRemove = 2)
        val cleared = refused.withoutIdentityPhotoRefusal()
        assertFalse(cleared.identityPhotoFailed)
        assertNull(cleared.identityPhotoQuotaMustRemove)
        // Control: nothing else is touched.
        assertEquals(refused.copy(identityPhotoFailed = false, identityPhotoQuotaMustRemove = null), cleared)
    }

    @Test
    fun `walking back a page and moving on both clear the refusal`() {
        val capture = src("IdentityCapture.kt")
        val back = capture.substringAfter("BackHandler(enabled = !state.isSaving && !state.saved) {").substringBefore("    // The write, and then the confirmation")
        assertTrue("viewModel.clearIdentityPhotoFailure()" in back && "page--" in back)
        val advance = capture.substringAfter("val advance = {").substringBefore("if (reduce)")
        assertTrue("viewModel.clearIdentityPhotoFailure()" in advance, "Continue carries a refusal onto the next page")
    }

    // -- wiring a device is needed to see ------------------------------------------------------

    @Test
    fun `the cardholder Next key goes to the PIN well, not to the Show eye`() {
        val who = body(src("CardWizard.kt"), "private fun WhoStep(")
        assertTrue("pinFocus" in who && "ImeAction.Next" in who && "pinFocus.requestFocus()" in who)
        assertTrue("focusRequester(pinFocus)" in who, "the PIN well never receives the focus request")
    }

    @Test
    fun `the identity camera consent is a dialog like the other two consents`() {
        val capture = src("IdentityCapture.kt")
        assertTrue("LkDialog(" in capture)
        assertFalse("LkSheet" in capture, "the camera consent is a bottom sheet again")
    }

    @Test
    fun `the needs-camera note stops once a denial is shown`() {
        val capture = src("IdentityCapture.kt")
        val gate = capture.substringBefore("R.string.itemedit_identity_capture_needs_camera").takeLast(120)
        assertTrue("if (denial == null)" in gate, "the note and the denial both draw")
    }

    @Test
    fun `rail captions sit on an opaque plate, not on the camera picture`() {
        val capture = src("IdentityCapture.kt")
        val caption = capture.substringAfter("labels[index],").substringBefore("color = when {")
        assertTrue(".background(c.ground," in caption)
    }

    @Test
    fun `the frozen photo starts below the heading`() {
        val capture = src("IdentityCapture.kt")
        assertTrue("headingBottom" in capture && "onGloballyPositioned { headingBottom" in capture)
        assertNotNull(Regex("\\.background\\(c\\.ground\\)\\s*//[^\\n]*\\n(\\s*//[^\\n]*\\n)*\\s*\\.padding\\(").find(capture))
    }

    @Test
    fun `the detail reveal plates are amber and the favourite star fills when set`() {
        val detail = src("ItemDetailScreen.kt")
        assertFalse("if (revealed) c.jade.copy" in detail, "a reveal plate is jade-tinted again")
        assertTrue("LkIcons.StarFilled" in detail && "state.favorite) LkIcons.StarFilled" in detail)
    }

    @Test
    fun `the location Cancel button wears no location pin`() {
        val screen = src("ItemEditScreen.kt")
        val cancel = screen.substringAfter("LocationAction.CANCEL -> LkSecondaryButton(").substringBefore("onClick")
        assertFalse("LkIcons.Pin" in cancel)
    }

    @Test
    fun `the pinned card strip has an opaque backdrop that fades in with it`() {
        val kb = src("CardWizardKeyboard.kt")
        assertTrue("drawRect(ground, alpha = stripAlpha(" in kb)
    }
}
