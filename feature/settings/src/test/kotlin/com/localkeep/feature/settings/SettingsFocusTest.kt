package com.localkeep.feature.settings

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsFocusTest {

    @AfterTest
    fun reset() = SettingsFocus.clear()

    @Test
    fun `a request is delivered once and then forgotten`() {
        SettingsFocus.request(SettingsFocus.DURESS)
        assertEquals(SettingsFocus.DURESS, SettingsFocus.take())
        // Reaching Settings any other way afterwards must show the whole list, not the duress row.
        assertNull(SettingsFocus.take())
    }

    @Test
    fun `nothing is pending by default`() {
        assertNull(SettingsFocus.take())
    }

    @Test
    fun `clear drops a request the lock screen interrupted`() {
        // A pending "Duress vault" surviving a lock could be collected by a *decoy* session, which
        // would name the duress vault to exactly the person it is hidden from.
        SettingsFocus.request(SettingsFocus.DURESS)
        SettingsFocus.clear()
        assertNull(SettingsFocus.take())
    }

    /**
     * The focus term and the row's rendered title must be one resource, not two copies of a word.
     *
     * This used to compare the constant — then the English literal `"Duress vault"` — against a row
     * titled `"Duress vault"`, and passed for the same reason the feature was broken: both sides
     * were English. On the `en-XA` pseudolocale pass (2026-07-30) the drawer's Duress destination
     * opened Settings, typed the English literal into the search field and showed "No matches",
     * making the duress vault unreachable from the drawer in fr, de, es and pt. Resolving the same
     * id on both sides is what makes them equal in every language, so the guard is now that the
     * screen names the constant rather than the resource behind it.
     */
    @Test
    fun `the duress row is titled by the same resource the focus names`() {
        val screen = File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt").readText()

        assertTrue(
            "title = stringResource(SettingsFocus.DURESS)" in screen,
            "the duress row no longer takes its title from SettingsFocus.DURESS, so the drawer's " +
                "focus request can drift from the row it has to find",
        )
        assertEquals(
            R.string.settings_duress_title,
            SettingsFocus.DURESS,
            "SettingsFocus.DURESS must name the duress row's title resource",
        )
    }
}
