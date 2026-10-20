package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The identity route picker must scroll, and its Cancel must not.
 *
 * Measured on a device at fontScale 2.0 on the largest display size (a 309dp window): both route
 * tiles grow to four lines each, the `Spacer(weight(1f))` that used to hold Cancel against the
 * bottom resolves to zero, and **Cancel left the hierarchy entirely** — not clipped, absent. The
 * screen advertises a way out of the flow and then does not draw it.
 *
 * This is a source scan rather than a render, and deliberately: `:feature:itemedit` has no
 * Robolectric wiring and its Compose UI tests never reach idle (see the note in its
 * `build.gradle.kts`), so the structural claim — "this composable owns a scroller, and Cancel sits
 * outside it" — is the part that can be asserted here. The rendered proof is the device walk that
 * found it; `tools/fontscale_walk.py` reproduces it.
 *
 * A source scan is only worth committing if it can fail, so the control below reads the same region
 * and asserts on something that was true before the fix too. Without it, a scan that silently
 * matched nothing would pass forever.
 */
class IdentityStartScrollableTest {

    private fun identityStartBody(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/IdentityCapture.kt")
        assertTrue(src.exists(), "IdentityCapture.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("fun IdentityStart(")
        assertTrue(start > 0, "IdentityStart( not found — did the composable get renamed?")
        // Up to the next top-level private composable, which is IdentityRouteTile.
        val end = text.indexOf("fun IdentityRouteTile(", start)
        assertTrue(end > start, "IdentityRouteTile( not found after IdentityStart — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `the route picker scrolls when its tiles outgrow the window`() {
        val body = identityStartBody()
        assertTrue(
            "verticalScroll(scroll)" in body,
            "IdentityStart has no verticalScroll. At fontScale 2.0 on a 309dp window its two route " +
                "tiles outgrow the viewport and Cancel is pushed off a screen that cannot scroll.",
        )
        assertTrue(
            "heightIn(min = viewport)" in body,
            "IdentityStart scrolls but sets no minimum height, so at ordinary sizes the column " +
                "would collapse to its content instead of filling the screen.",
        )
    }

    @Test
    fun `cancel sits outside the scroller`() {
        val body = identityStartBody()
        val scrollerEnd = body.lastIndexOf("BoxWithConstraints(")
        val cancel = body.indexOf("itemedit_identity_start_cancel")
        assertTrue(scrollerEnd > 0, "no BoxWithConstraints in IdentityStart")
        assertTrue(cancel > 0, "the cancel string is not referenced in IdentityStart")
        assertTrue(
            cancel > scrollerEnd,
            "Cancel is rendered before the scroller. The way out of a flow should stay put rather " +
                "than being something the user has to scroll to find.",
        )
    }

    /**
     * The control. Both assertions above are about text this file gained; this one is about text it
     * had before and after, so a scan that stopped finding the region at all fails here first and
     * says so, instead of the two tests above passing vacuously.
     */
    @Test
    fun `the region really is the route picker`() {
        val body = identityStartBody()
        assertTrue("itemedit_identity_start_photo" in body, "route picker region did not contain the photo route")
        assertTrue("itemedit_identity_start_type" in body, "route picker region did not contain the type route")
    }
}
