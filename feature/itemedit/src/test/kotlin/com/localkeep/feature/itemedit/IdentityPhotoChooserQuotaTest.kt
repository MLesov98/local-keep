package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The identity chooser must refuse the photo route itself, before the camera ever opens.
 *
 * Found in the 2026-09-09 functional sweep: `ItemEditViewModel.captureIdentityPhoto` charged every
 * frame against `AttachmentQuota` (see `IdentityPhotoRouteEntitlementTest`), but only *after* the
 * shutter had already produced bytes to strip and encrypt. A vault already at the seven-attachment
 * cap let the user frame a passport, press the shutter, and only then be told there was no slot for
 * it — camera permission granted, flash fired, frame decoded, all for a picture the app was always
 * going to refuse. The fix moves the ask to the chooser: `IdentityStart` now takes
 * `photoBlockedReason`, computed by `ItemEditViewModel.identityPhotoQuotaMustRemove()` — the same
 * `AttachmentQuota` read `captureIdentityPhoto` does — and `IdentityRouteTile` draws the photo route
 * disabled, with the reason in place of its ordinary hint, when it is non-null. Manual entry needs
 * no attachment slot and is never gated.
 *
 * Source scan, not a render: this module has no Robolectric wiring and `ItemEditViewModel` cannot
 * be constructed on a JVM at all (its `VaultRepository` needs a `VaultLockManager` whose
 * `KeystoreManager` opens AndroidKeyStore in its constructor) — the same reason
 * `IdentityPhotoRouteEntitlementTest` and `IdentityStartScrollableTest` are scans. Each claim below
 * is paired with a control that held before the fix, so a scan that stopped matching anything fails
 * loudly instead of passing vacuously.
 */
class IdentityPhotoChooserQuotaTest {

    private fun viewModelSrc(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
        assertTrue(src.exists(), "ItemEditViewModel.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    private fun screenSrc(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    private fun identityStartBody(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/IdentityCapture.kt")
        assertTrue(src.exists(), "IdentityCapture.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("fun IdentityStart(")
        assertTrue(start > 0, "IdentityStart( not found — did the composable get renamed?")
        val end = text.indexOf("fun IdentityRouteTile(", start)
        assertTrue(end > start, "IdentityRouteTile( not found after IdentityStart — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `the ViewModel exposes the same quota read captureIdentityPhoto uses, ahead of the shutter`() {
        val text = viewModelSrc()
        val fnStart = text.indexOf("fun identityPhotoQuotaMustRemove()")
        assertTrue(
            fnStart > 0,
            "identityPhotoQuotaMustRemove() is gone — the chooser has nothing to ask before the " +
                "shutter opens",
        )
        val fnEnd = text.indexOf("fun captureIdentityPhoto(", fnStart)
        assertTrue(fnEnd > fnStart, "captureIdentityPhoto( not found after identityPhotoQuotaMustRemove — region unbounded")
        val body = text.substring(fnStart, fnEnd)
        assertTrue(
            "AttachmentQuota.exceedsFreeLimit(" in body,
            "identityPhotoQuotaMustRemove no longer asks AttachmentQuota — it must fail closed the " +
                "same way captureIdentityPhoto does, not merely resemble it",
        )
    }

    @Test
    fun `the chooser call site wires the ViewModel's answer into the photo tile`() {
        val text = screenSrc()
        val at = text.indexOf("IdentityStart(")
        assertTrue(at > 0, "IdentityStart( call site is gone from ItemEditScreen")
        val end = text.indexOf(")", text.indexOf("photoBlockedReason", at).let { if (it < 0) at else it })
        assertTrue(
            "photoBlockedReason = viewModel.identityPhotoQuotaMustRemove()" in text.substring(at, (end + 1).coerceAtLeast(at)),
            "the IdentityStart call site does not pass photoBlockedReason from " +
                "viewModel.identityPhotoQuotaMustRemove() — the chooser would default to always open",
        )
    }

    @Test
    fun `the photo tile is disabled exactly when the chooser was told there is no room`() {
        val body = identityStartBody()
        val photoAt = body.indexOf("itemedit_identity_start_photo)")
        assertTrue(photoAt > 0, "the photo route tile's title string reference is gone")
        val nextTileAt = body.indexOf("IdentityRouteTile(", photoAt)
        val callEnd = if (nextTileAt > 0) nextTileAt else body.length
        val photoCall = body.substring(photoAt, callEnd)
        assertTrue(
            "enabled = photoBlockedReason == null" in photoCall,
            "the photo IdentityRouteTile no longer disables on photoBlockedReason — a full vault " +
                "would let the camera open again: $photoCall",
        )
        assertTrue(
            "itemedit_identity_start_photo_blocked" in photoCall,
            "the photo tile no longer shows the quota reason as its subtitle when blocked",
        )
    }

    /**
     * The control: the manual/type route is never gated by an attachment quota, because typing a
     * new identity in by hand spends no attachment slot at all. True before this fix (the tile had
     * no `enabled` parameter to gate with) and must stay true after it — a scan that started
     * matching the wrong tile, or that started gating manual entry too, fails here.
     */
    @Test
    fun `the manual-entry tile is never gated on the attachment quota`() {
        val body = identityStartBody()
        val typeAt = body.indexOf("itemedit_identity_start_type)")
        assertTrue(typeAt > 0, "the manual-entry route tile's title string reference is gone")
        val callStart = body.lastIndexOf("IdentityRouteTile(", typeAt)
        assertTrue(callStart > 0, "no IdentityRouteTile( call precedes the manual-entry title")
        val callEnd = body.indexOf("onClick = onType,", typeAt).let { if (it < 0) body.length else it }
        val typeCall = body.substring(callStart, callEnd)
        assertFalse(
            "enabled =" in typeCall,
            "the manual-entry tile now carries an enabled= gate — it should never be blocked by " +
                "the attachment quota, since it takes no attachment slot: $typeCall",
        )
    }
}
