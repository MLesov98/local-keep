package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LkSectionLabel] owns the gap between itself and the group it heads.
 *
 * One relationship — an uppercase heading above a rounded group — was rendered at 9dp on the item
 * view, 10dp on Settings and 16dp on Backup, because every caller supplied the gap itself and no two
 * callers agreed (found 2026-08-27). A caller cannot supply
 * a value it does not choose, so the wrapper is the durable fix rather than a third round of
 * swapping one hand-picked number for another.
 *
 * The trap this also closes is a known one: a `Spacer` inside a `spacedBy` column is
 * spaced too, so `spacedBy(16.dp)` plus `Spacer(8.dp)` renders 40dp. A composable that emits its own
 * gap is a single child and is spaced once.
 *
 * Measured against the bare `Text` the component wraps rather than against a sibling's position,
 * because the gap is *inside* the label's own layout node — which is the point. A caller measuring
 * from the label's reported bounds finds nothing to add.
 *
 * FAILS WITHOUT THE FIX: `LkSectionLabel` was `Text(...)` with the caller's modifier and nothing
 * else, so the difference below was 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class LkSectionLabelGapTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the label reserves its own gap below the text it draws`() {
        compose.setContent {
            LocalKeepTheme {
                Column {
                    LkSectionLabel("Section", Modifier.testTag(LABEL))
                    Text("SECTION", style = LkText.sectionLabel, modifier = Modifier.testTag(PLAIN))
                }
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        val label = compose.onNodeWithTag(LABEL).fetchSemanticsNode().size.height
        val plain = compose.onNodeWithTag(PLAIN).fetchSemanticsNode().size.height
        assertEquals(
            LkSectionLabelGap.value,
            (label - plain) / density,
            0.5f,
            "the label must be exactly LkSectionLabelGap taller than the text it wraps",
        )
    }

    private companion object {
        const val LABEL = "section-label"
        const val PLAIN = "section-label-text"
    }
}
