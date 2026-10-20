package com.localkeep.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.app.navigation.AppNavGraph
import com.localkeep.app.navigation.CommandDrawer
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.test.device.DeviceReadyRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where you are in the drawer has to reach the accessibility tree, not only the palette.
 *
 * The current destination was marked by a jade tint and nothing else, so every row carried the same
 * accessible name: a screen-reader user opening the drawer to orient themselves was told the names
 * of ten destinations and nothing about which one they were already on.
 *
 * Asserted on the *name*, not on `SemanticsProperties.Selected`. The property is the tidier API and
 * a test against it passes — but a `uiautomator dump` of the running app showed every row still
 * reporting `selected="false"`, the current one included, while the bottom bar's spelled-out
 * "Vault, selected" appeared in the same tree. The dump is what TalkBack reads.
 *
 * `assertExists` rather than `assertIsDisplayed`: the drawer scrolls, and the rows below the fold
 * are named just as wrongly as the ones above it.
 */
@RunWith(AndroidJUnit4::class)
class DrawerSelectionTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()
    @get:Rule
    val compose = createComposeRule()

    private fun drawerOn(route: String) {
        compose.setContent {
            LocalKeepTheme {
                CommandDrawer(current = route, onNavigate = {})
            }
        }
    }

    @Test
    fun theCurrentDestinationSaysSoInItsName() {
        drawerOn(AppNavGraph.HOME)
        compose.onNodeWithContentDescription("All items, selected").assertExists()
    }

    @Test
    fun everyOtherDestinationIsNamedWithoutIt() {
        drawerOn(AppNavGraph.HOME)
        // "Generator", not "Password generator": renamed by 47c7d28c on 2026-08-10 and this
        // assertion was never updated. It went unseen for a month because the local gate does
        // not compile androidTest, so `./gradlew test` stayed green the whole time.
        compose.onNodeWithContentDescription("Generator").assertExists()
        compose.onNodeWithContentDescription("Trash").assertExists()
        compose.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun selectionFollowsTheRouteRatherThanTheFirstRow() {
        drawerOn(AppNavGraph.TRASH)
        compose.onNodeWithContentDescription("Trash, selected").assertExists()
        compose.onNodeWithContentDescription("All items").assertExists()
    }

    // `namingTheRowDoesNotSwallowTheProBadge` stood here and is deliberately gone rather than
    // repaired. It asserted "Cards, Pro" on a per-type drawer row, and the per-type rows were
    // removed on 2026-09-02 when type filtering collapsed onto the vault list's filter control
    // — the brass padlock moved with
    // them. No drawer row carries a Pro badge any more, so there is nothing here for a merged
    // description to swallow; the test could only have been made to pass by asserting something
    // other than its own subject.
    //
    // The invariant itself did not stop mattering, it moved. It belongs on the filter sheet now,
    // and there is no test for it there — recorded as a follow-up rather than left implied.
}
