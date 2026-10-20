package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filename a document provider hands back is treated as untrusted input.
 *
 * Red before the fix: `queryDisplayNameAndSize` returned the provider's DISPLAY_NAME verbatim, and
 * that value was written into a MediaStore `ContentValues` on export and rendered throughout the UI.
 * A `DocumentsProvider` is any app on the device that registers as one, so the name is exactly as
 * trustworthy as whatever installed it.
 *
 * The interesting half of this is what is *not* stripped. A sanitiser that removes punctuation to
 * feel safe makes the app lose the user's own filenames, which is a real cost paid for no security:
 * a document legitimately called `Rechnung (März) #12.pdf` has to survive intact.
 */
class SanitizedDisplayNameTest {

    @Test
    fun `path separators cannot survive in a name`() {
        assertEquals(".._.._etc_passwd", sanitizedDisplayName("../../etc/passwd"))
        assertEquals("a_b", sanitizedDisplayName("""a\b"""))
        // A name is not a path; the traversal is neutralised rather than the dots removed, so what
        // the user sees still resembles what they were given.
        assertTrue("/" !in sanitizedDisplayName("/absolute/path.pdf"))
    }

    @Test
    fun `control characters are removed`() {
        // A newline or a DEL does not render and can visually truncate or reorder a label.
        assertEquals("invoice.pdf", sanitizedDisplayName("invo\u0000ice\n.pdf"))
        assertEquals("abc", sanitizedDisplayName("abc"))
    }

    @Test
    fun `length is bounded rather than trusted`() {
        val long = "x".repeat(5_000) + ".pdf"
        val name = sanitizedDisplayName(long)
        assertTrue(
            name.length <= 120,
            "a provider-supplied name must be bounded well inside the 255-byte filesystem limit, " +
                "with room for a MediaStore collision suffix; got ${name.length}",
        )
    }

    @Test
    fun `a name that sanitises to nothing falls back rather than becoming empty`() {
        // Whitespace and control characters really can leave nothing behind, and an empty
        // DISPLAY_NAME is the one input where keeping the original is worse than admitting we have
        // no name for it.
        assertEquals("file", sanitizedDisplayName("   "))
        assertEquals("file", sanitizedDisplayName("\u0000\u0001\u0002"))
        // Separators do NOT reach that fallback: they are replaced, not removed, so "///" is
        // "___" - an odd name and a harmless one. This test asserted "file" first time round and
        // was wrong about its own subject; the implementation was right.
        assertEquals("___", sanitizedDisplayName("///"))
    }

    /**
     * The control, and it must stay green while the tests above go red.
     *
     * This is the assertion that stops the fix from becoming a filename shredder. Every character
     * here is one a real document can carry, and losing any of them would be the app quietly
     * damaging the user's own data in the name of safety.
     */
    @Test
    fun `an ordinary filename is returned untouched`() {
        val ordinary = "Rechnung (März) #12 - final_v2.pdf"
        assertEquals(ordinary, sanitizedDisplayName(ordinary))
        assertEquals("スクリーンショット 2026-09-10.png", sanitizedDisplayName("スクリーンショット 2026-09-10.png"))
        assertEquals("report[2026].csv", sanitizedDisplayName("report[2026].csv"))
    }
}
