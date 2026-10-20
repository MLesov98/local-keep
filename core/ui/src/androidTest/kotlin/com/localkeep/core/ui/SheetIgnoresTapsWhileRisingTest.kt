package com.localkeep.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LkSheet
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A sheet takes no taps while it is still rising.
 *
 * Found 2026-10-03: a double tap on the vault list's + raised the add sheet with the first tap,
 * and the second landed on whichever row had just slid under the finger — "File", on a 1080x2400
 * phone — opening an editor nobody chose. Back from it returns to the sheet by design, so it read as
 * two sheets stacked. Rows answer once the sheet is at rest.
 */
@RunWith(AndroidJUnit4::class)
class SheetIgnoresTapsWhileRisingTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aRowTappedDuringTheEntranceIsIgnoredAndOneTappedAtRestIsNot() {
        var taps = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                LkSheet(onDismiss = {}) {
                    Text(
                        "Row",
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable { taps++ },
                    )
                }
            }
        }
        // Part-way in: the row is on screen, and the sheet is still moving under it. Asserted, so a
        // tap that missed the window entirely cannot pass this test for the wrong reason.
        compose.mainClock.advanceTimeBy(ENTRANCE_PART_MS)
        compose.onNodeWithText("Row").assertIsDisplayed()
        compose.onNodeWithText("Row").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(0, taps, "a tap during the entrance reached the row")

        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Row").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals(1, taps, "a tap at rest must reach the row")
    }

    private companion object {
        const val ENTRANCE_PART_MS = 120L
    }
}
