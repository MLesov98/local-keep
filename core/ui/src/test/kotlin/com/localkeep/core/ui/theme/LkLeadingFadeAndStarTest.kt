package com.localkeep.core.ui.theme

import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LkLeadingFadeAndStarTest {

    @Test
    fun `a scrolled value that outruns its well fades at the left as well`() {
        assertTrue(lkLeadingFadeActive(clipped = true, everFocused = true))
    }

    /** The controls: a value that fits, or one never scrolled, has nothing sliced at the left. */
    @Test
    fun `a value that fits or was never focused has no leading fade`() {
        assertFalse(lkLeadingFadeActive(clipped = false, everFocused = true))
        assertFalse(lkLeadingFadeActive(clipped = true, everFocused = false))
    }

    @Test
    fun `the filled star is solid and the outline star is not`() {
        val outline = LkIcons.Star.root.filterIsInstance<VectorPath>().single()
        val solid = LkIcons.StarFilled.root.filterIsInstance<VectorPath>().single()
        assertNull(outline.fill, "the outline star must stay unfilled")
        assertNotNull(solid.fill, "the favourite-on star must be filled")
        assertEquals(outline.pathData, solid.pathData, "same shape, so the two states read as one glyph")
    }
}
