package com.localkeep.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.app.navigation.CommandDrawer
import com.localkeep.core.ui.LocalDuressSession
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every drawer row answers to exactly one test tag, and no tag names two rows.
 *
 * `DrawerLink` derives its tag from the route, which is unique for every row *except* the pairs
 * that deliberately share a destination and differ only in the section they scroll to. "Restore a
 * snapshot" had its override from the start; "Duress / decoy" did not, so `drawer-settings` named
 * two rows until 2026-07-31.
 *
 * That is not cosmetic. `.maestro/subflows/open-drawer.yaml` navigates by tag — on purpose, so one
 * flow file runs in all five languages — and `tapOn` takes the first match in tree order. The
 * Security group is drawn before the System group, so `screens/settings.yaml` was tapping the
 * duress row and capturing Settings scrolled to that section, in every language of the i18n sweep,
 * while reporting itself as the Settings screen.
 *
 * Asserted against the composed semantics tree rather than by reading call sites, because the
 * defect is a collision between two rows that are individually correct.
 */
@RunWith(AndroidJUnit4::class)
class DrawerTagUniquenessTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()
    @get:Rule
    val compose = createComposeRule()

    private fun SemanticsNode.tags(): List<String> =
        listOfNotNull(config.getOrNull(SemanticsProperties.TestTag)) +
            children.flatMap { it.tags() }

    private fun drawerTags(duress: Boolean): List<String> {
        compose.setContent {
            LocalKeepTheme {
                CompositionLocalProvider(LocalDuressSession provides duress) {
                    CommandDrawer(current = null, onNavigate = {})
                }
            }
        }
        return compose.onRoot().fetchSemanticsNode().tags().filter { it.startsWith("drawer-") }
    }

    @Test
    fun noTwoDrawerRowsShareATag() {
        val tags = drawerTags(duress = false)
        val duplicated = tags.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(
            emptySet(),
            duplicated,
            "these tags name more than one drawer row, so navigating by tag is ambiguous",
        )
        // Cannot pass by drawing nothing: the drawer has well over a dozen rows.
        assertTrue(tags.size > 10, "expected the full drawer, found ${tags.size} tagged rows")
    }

    @Test
    fun theDuressRowAndTheSettingsRowAreSeparatelyAddressable() {
        val tags = drawerTags(duress = false)
        assertTrue("drawer-settings" in tags, "the Settings row lost its tag")
        assertTrue("drawer-settings-duress" in tags, "the Duress row lost its override")
    }

    /**
     * In a decoy session the duress row is absent entirely, so its tag must be too — a tag left
     * behind would be exactly the tell the row's absence exists to avoid.
     */
    @Test
    fun theDecoySessionCarriesNoDuressTag() {
        val tags = drawerTags(duress = true)
        assertTrue("drawer-settings-duress" !in tags, "the decoy session still names the duress row")
        assertTrue("drawer-settings" in tags, "the decoy session lost the Settings row")
    }
}
