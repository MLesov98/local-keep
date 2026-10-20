package com.localkeep.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.app.navigation.AppNavGraph
import com.localkeep.app.navigation.CommandDrawer
import com.localkeep.core.ui.LocalDuressSession
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decoy session must not advertise that a decoy exists.
 *
 * Duress only works while the vault the coercer was shown is indistinguishable from the real one.
 * `performScrollTo()` before each assertion, added 2026-07-29. The drawer is a scrolling column
 * (`AppShell.kt`, `verticalScroll`), and on the smaller screen of the API 28 emulator the lower rows
 * start below the fold — `assertIsDisplayed` then fails on a row that is present and reachable. The
 * scroll is not a way of making the assertion softer: if a row is genuinely absent,
 * `performScrollTo` fails with "no node found" rather than passing, so the decoy-session assertions
 * below still mean exactly what they meant. Found by the first instrumented run at minSdk.
 *
 * The drawer used to carry a "Duress / decoy" row in every session, so anyone who had been handed
 * the duress password and then opened the drawer was told by the app itself that there was a second
 * vault to demand — which is precisely, and only, what the feature exists to hide.
 *
 * Asserted by composing the drawer rather than by reading the source, because "a row is absent" is
 * the kind of claim that survives a refactor in the code and dies on the screen.
 */
@RunWith(AndroidJUnit4::class)
class DuressDrawerTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theDecoySessionDrawerDoesNotNameTheDuressFeature() {
        compose.setContent {
            LocalKeepTheme {
                CompositionLocalProvider(LocalDuressSession provides true) {
                    CommandDrawer(current = null, onNavigate = {})
                }
            }
        }

        compose.onNodeWithText("Duress / decoy").assertDoesNotExist()
        // The rows either side of it are still there, so this cannot pass by drawing nothing.
        //
        // These follow the Security group's membership rather than being fixed names: the row below
        // used to be Trash, which has since moved to System as a data-management action, and the
        // drawer scrolls, so asserting a row that had slid below the fold failed on layout rather
        // than on the thing under test. Recovery Kit is the row that now sits under the absent one.
        compose.onNodeWithText("Password health").performScrollTo().assertIsDisplayed()
        // Trash, not Recovery Kit. The Recovery Kit row became duress-gated itself on 2026-08-30
        // — `if (!LocalDuressSession.current)` in AppShell — because in a decoy session the button
        // worked as far as "Couldn\'t create a Recovery Kit for this vault.", an outcome a healthy
        // real vault cannot produce. So the row chosen as the control for "the drawer still draws
        // its neighbours" had itself become absent, and this assertion was asserting the bug.
        // Trash is the next row that survives a decoy session.
        compose.onNodeWithText("Trash").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theRealSessionDrawerStillOffersIt() {
        compose.setContent {
            LocalKeepTheme {
                CompositionLocalProvider(LocalDuressSession provides false) {
                    CommandDrawer(current = null, onNavigate = {})
                }
            }
        }

        compose.onNodeWithText("Duress / decoy").performScrollTo().assertIsDisplayed()
    }

    /**
     * The Invisible-items row is present in a decoy session, and that is a decision, not an
     * oversight — so it is asserted rather than left to whatever the code happens to do.
     *
     * Same reasoning as Recovery Kit above: the rule is to conceal surfaces that *name the duress
     * feature*, and this one does not. The screen's gate accepts the current vault password, so a
     * coercer holding the duress password can pass it — and what they then see is the decoy's
     * hidden set, which is empty, because `revealPhrase` and `hiddenUntil` live inside the Vault
     * model. Nothing crosses the boundary, and a Security group that came up a row short would
     * itself be the tell.
     */
    @Test
    fun theDecoySessionDrawerStillOffersInvisibleItems() {
        compose.setContent {
            LocalKeepTheme {
                CompositionLocalProvider(LocalDuressSession provides true) {
                    CommandDrawer(current = null, onNavigate = {})
                }
            }
        }

        compose.onNodeWithText("Invisible items").performScrollTo().assertIsDisplayed()
    }

    /**
     * The row goes to the Invisible-items screen itself, not to Settings.
     *
     * Worth pinning because the two rows beside it — "Duress / decoy" and "Restore a snapshot" —
     * both route to a *container* screen and ask it to scroll to a section, and copying that
     * pattern here would land the user on Settings with a search term pre-filled. Invisible items
     * has its own route; the whole point of the new row is that it does not go through Settings.
     */
    @Test
    fun theInvisibleItemsRowGoesStraightToItsOwnScreen() {
        val routes = mutableListOf<String>()
        compose.setContent {
            LocalKeepTheme {
                CommandDrawer(current = null, onNavigate = { routes += it })
            }
        }

        compose.onNodeWithText("Invisible items").performScrollTo().performClick()

        assertEquals(listOf(AppNavGraph.HIDDEN_ITEMS), routes)
    }

    /**
     * The default matters on its own: [LocalDuressSession] falls back to `false` outside the
     * activity that provides it, and a default of `true` would silently hide a real setting from
     * every user rather than fail loudly.
     */
    @Test
    fun theDrawerOutsideAnyProviderBehavesAsARealSession() {
        compose.setContent {
            LocalKeepTheme {
                CommandDrawer(current = null, onNavigate = {})
            }
        }

        compose.onNodeWithText("Duress / decoy").performScrollTo().assertIsDisplayed()
    }
}
