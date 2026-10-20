package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The free tier may photograph its one identity document — and may not photograph its way past the
 * attachment cap.
 *
 * Two halves of one fix, and shipping either alone is worse than shipping neither.
 *
 * **The gate.** `ItemEditScreen` opened the route chooser only when `state.isPro`, justified by a
 * comment arguing from the binary Pro gate that `ProTypeQuota` replaced on 2026-09-03. Identity has
 * not been a Pro-only type since: a free vault holds one, the add sheet refuses the second before
 * this screen is reached, and `creationBlockedByAllowance` is false at zero used — so the free user
 * arriving here was silently denied the marquee route *and* met no prompt explaining it. The two
 * compensations that comment named were both dead code (`ProGate` had no call site in the module,
 * and the save-time prompt is raised only behind `showProPrompt`). Card, which shares the quota
 * kind and the limit, carries no `isPro` term — the control showing this was oversight, not policy.
 *
 * **The cap.** The harm the stale comment *was* written to prevent is real: photograph three
 * passport pages, then be refused. `captureIdentityPhoto` performed no `AttachmentQuota` check at
 * all, where `AttachmentPickerViewModel.attach` refuses at the cap. Removing the tier term without
 * adding a count check would have let a free user with seven attachments spent photograph a whole
 * passport into a path that never asked. So the check is asserted here beside the gate, and it must
 * run *before* the strip and the encrypt, charging the pages already taken for this unsaved item.
 *
 * Source scan for the reason `IdentityStartScrollableTest` gives — this module has no Robolectric
 * wiring, and `ItemEditViewModel` cannot be constructed on a JVM at all (its `VaultRepository`
 * needs a `VaultLockManager` whose `KeystoreManager` opens AndroidKeyStore in its constructor).
 * Each claim is paired with a control that held before the fix.
 */
class IdentityPhotoRouteEntitlementTest {

    private fun screen(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        return src.readText()
    }

    private fun captureBody(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditViewModel.kt")
        assertTrue(src.exists(), "ItemEditViewModel.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("fun captureIdentityPhoto(")
        assertTrue(start > 0, "captureIdentityPhoto( not found — did it get renamed?")
        val end = text.indexOf("fun retakeLastIdentityPhoto()", start)
        assertTrue(end > start, "retakeLastIdentityPhoto( not found after it — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `the route chooser is not gated on Pro`() {
        val text = screen()
        val gate = "if (state.isNew && state.type == ItemType.IDENTITY"
        val at = text.indexOf(gate)
        // The control: the route is still limited to a *new* Identity. True before and after.
        assertTrue(at > 0, "the identity route gate is gone or reshaped — expected `$gate`")
        val condition = text.substring(at, text.indexOf(") {", at) + 1)
        assertFalse(
            "isPro" in condition,
            "the identity route is still gated on isPro. The free tier is entitled to one identity " +
                "document, so this silently withholds the photograph route and raises no prompt: `$condition`",
        )
    }

    @Test
    fun `the route predicate agrees with the gate`() {
        val text = screen()
        val at = text.indexOf("val identityPhotoRoute =")
        assertTrue(at > 0, "identityPhotoRoute is gone — did it get renamed?")
        // Bounded by the last term of the predicate itself rather than by a blank line: there is no
        // blank line after it, and a slice that ran on into the comment below would read that
        // comment's own words as if they were code.
        val tail = "IdentityRoute.PHOTO"
        val end = text.indexOf(tail, at)
        // The control: it still asks for IdentityRoute.PHOTO. True before and after.
        assertTrue(end > at, "identityPhotoRoute no longer tests the PHOTO route")
        val expression = text.substring(at, end + tail.length)
        assertFalse(
            "isPro" in expression,
            "identityPhotoRoute still carries isPro while the gate above does not. A route the " +
                "chooser can enter but this predicate calls false draws the encrypting and saved " +
                "screens over a live camera: `$expression`",
        )
    }

    @Test
    fun `a captured page is charged against the attachment quota`() {
        val body = captureBody()
        // The control: the frame is still stripped of Exif before it is encrypted. True before and after.
        // `sanitize` since 2026-10-02 (S3-08): the fail-closed path, see IdentityPhotoStripIsFailClosedTest.
        assertTrue("ExifStripper.sanitize(" in body, "captureIdentityPhoto no longer strips Exif — region wrong?")
        assertTrue(
            "AttachmentQuota.exceedsFreeLimit(" in body,
            "captureIdentityPhoto asks AttachmentQuota nothing. Every page it takes becomes an " +
                "attachment on the saved item, so this is an add path and the cap applies to it " +
                "exactly as it applies to AttachmentPickerViewModel.attach.",
        )
    }

    @Test
    fun `the quota is checked before any work is done, and counts the pages already taken`() {
        val body = captureBody()
        val check = body.indexOf("AttachmentQuota.exceedsFreeLimit(")
        val work = body.indexOf("viewModelScope.launch")
        assertTrue(work > 0, "captureIdentityPhoto no longer launches — region wrong?")
        assertTrue(
            check in 1 until work,
            "the quota check must run before viewModelScope.launch. Refusing after the strip and " +
                "the chunked encrypt is the 'photograph three pages, then be told no' harm the " +
                "removed isPro gate was written to prevent.",
        )
        assertTrue(
            "identityPhotos.size + 1" in body,
            "the check charges only one frame. The pages already taken for this item are encrypted " +
                "blobs the repository has not heard of yet — the item is unsaved — so a per-frame " +
                "'one more' test lets a whole passport through one page at a time.",
        )
    }
}
