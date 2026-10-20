package com.localkeep.feature.vaultlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the add-sheet quota badge actually renders, at every relation the two numbers can have.
 *
 * [SpentBadgeCopyTest] reads the catalogue; this resolves it. The defect it exists for was not a
 * missing string or a wrong argument — it was a shape. "%1$d of %2$d" is well-formed, carries both
 * numbers, and passed every catalogue assertion there was, and on a vault holding ten cards against
 * a limit of one it rendered **`10 OF 1`** (V3-05, `shots/048-addsheet-pro-off.png`): a fraction
 * that cannot exist. Import never charges against the cap, so that is not an exotic state — it is
 * where anyone who used the import wizard lands.
 *
 * So the assertion has to be about the rendered sentence, and it has to be made at held above the
 * cap, at the cap, and below it. Below the cap the badge is not drawn — `proTypeMark` returns
 * `None` — but the text is computed from the same one key, and a wording that only reads correctly
 * on one side of the cap is the bug this replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpentBadgeComputedTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun badge(held: Int, limit: Int): String =
        context.resources.getQuantityString(
            R.plurals.vaultlist_badge_allowance_spent,
            held,
            held,
            limit,
        )

    @Test
    fun `an imported vault above the cap reads as two facts, not a fraction`() {
        assertEquals("limit 1 · 10 held", badge(held = 10, limit = 1))
        assertEquals("limit 1 · 25 held", badge(held = 25, limit = 1))
        assertEquals("limit 7 · 9 held", badge(held = 9, limit = 7))
    }

    @Test
    fun `a vault exactly at the cap reads the same way`() {
        assertEquals("limit 1 · 1 held", badge(held = 1, limit = 1))
        assertEquals("limit 7 · 7 held", badge(held = 7, limit = 7))
    }

    /**
     * Below the cap the cell carries no badge, so this is about the string rather than the screen:
     * the wording must not depend on the holding having reached the limit, or the next author who
     * decides to show an allowance in hand inherits a sentence that is false.
     */
    @Test
    fun `a vault below the cap reads the same way`() {
        assertEquals("limit 1 · 0 held", badge(held = 0, limit = 1))
        assertEquals("limit 7 · 3 held", badge(held = 3, limit = 7))
    }

    /**
     * The shape, at the relation that broke it. Both numbers present, the cap first, and nothing
     * between them that reads as "out of".
     */
    @Test
    fun `the cap is named before the holding at every relation`() {
        listOf(10 to 1, 1 to 1, 0 to 1, 9 to 7, 7 to 7, 3 to 7).forEach { (held, limit) ->
            val text = badge(held, limit)
            assertTrue("·" in text, "\"$text\" no longer separates the cap from the holding")
            assertTrue(
                text.indexOf(limit.toString()) < text.lastIndexOf(held.toString()),
                "\"$text\" leads with the holding, which is the fraction V3-05 caught",
            )
        }
    }

    /** French, to prove the other four catalogues are resolved rather than merely present. */
    @Test
    @Config(sdk = [34], qualifiers = "fr")
    fun `the badge renders in French`() {
        assertEquals("limite 1 · 10 en tout", badge(held = 10, limit = 1))
        assertEquals("limite 1 · 1 en tout", badge(held = 1, limit = 1))
    }
}
