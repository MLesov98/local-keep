package com.localkeep.core.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared "does this graphic-beside-caption row stack" rule (decision 4, SCL-012, SCL-021).
 *
 * It used to be a font-scale comparison alone, which left the health score card side by side at
 * 308dp and 1.3 (ring below the fold) and the strength meter zero wide at 308dp and 1.0. The rule is
 * now big type OR a narrow row; the pairs below are the card's real widths, 228dp at the 308dp floor
 * and 280dp at 360dp.
 */
class LkStackRuleTest {

    @Test
    fun `a narrow row stacks at any font scale`() {
        assertEquals(true, LkText.stacks(1.0f, 228.dp))
        assertEquals(true, LkText.stacks(1.3f, 228.dp))
    }

    @Test
    fun `big type stacks at any width`() {
        assertEquals(true, LkText.stacks(1.5f, 411.dp))
        assertEquals(true, LkText.stacks(2.0f, 600.dp))
    }

    @Test
    fun `a roomy row at ordinary type keeps the comps layout`() {
        assertEquals(false, LkText.stacks(1.0f, 280.dp))
        assertEquals(false, LkText.stacks(1.3f, 331.dp))
        assertEquals(false, LkText.stacks(1.4f, 280.dp))
    }
}
