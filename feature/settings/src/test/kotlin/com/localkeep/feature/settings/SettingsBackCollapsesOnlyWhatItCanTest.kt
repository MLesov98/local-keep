package com.localkeep.feature.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Back on Settings collapses the open rows only when collapsing them would change the screen.
 *
 * Found 2026-10-03: the drawer's "Duress / decoy" opens Settings searched to that row, and a
 * search shows every row it finds open whatever the expanded set says. Back cleared the set — which
 * was not what held the row open — so the first press did nothing visible and the second left.
 */
class SettingsBackCollapsesOnlyWhatItCanTest {

    @Test
    fun `an open row collapses on Back when nothing is searched`() {
        assertTrue(settingsBackCollapses(query = "", expanded = setOf("Duress vault")))
    }

    @Test
    fun `Back leaves Settings while a search is holding rows open`() {
        // FAILS WITHOUT THE FIX: the handler claimed the press on `expanded` alone.
        assertFalse(settingsBackCollapses(query = "Duress vault", expanded = setOf("Duress vault")))
    }

    @Test
    fun `Back leaves Settings when nothing is open`() {
        assertFalse(settingsBackCollapses(query = "", expanded = emptySet()))
    }
}
