package com.localkeep.app.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a lock interrupts the app, the next unlock announces "your unsaved changes were discarded" —
 * but only if an *editor* was open. A broad `startsWith("item/")` also matched the read-only detail
 * route ("item/{itemId}"), so the notice fired after the user had merely *viewed* an item. These
 * pin [AppNavGraph.droppedEditRoute] to the route templates NavController reports, so a revert to
 * the broad prefix turns the read-only case red.
 */
class DroppedEditRouteTest {

    @Test
    fun `the read-only item detail route is not an editor`() {
        // The regression: "item/{itemId}" must NOT count. startsWith("item/") made it true.
        assertFalse(AppNavGraph.droppedEditRoute("item/{itemId}"))
    }

    @Test
    fun `the item edit and new routes are editors`() {
        assertTrue(AppNavGraph.droppedEditRoute("item/{itemId}/edit"))
        assertTrue(AppNavGraph.droppedEditRoute("item/new?type={type}&fromSheet={fromSheet}"))
    }

    @Test
    fun `file editors, attachments and import count as editors but viewer does not`() {
        assertTrue(AppNavGraph.droppedEditRoute("file/new?fromSheet={fromSheet}"))
        assertTrue(AppNavGraph.droppedEditRoute("file/{itemId}/settings"))
        assertFalse(AppNavGraph.droppedEditRoute("file/{itemId}"))
        assertTrue(AppNavGraph.droppedEditRoute("attachments/{itemId}"))
        assertTrue(AppNavGraph.droppedEditRoute(AppNavGraph.IMPORT))
    }

    @Test
    fun `a half-typed folder name is an unsaved edit like any other`() {
        // Red before the fix. `NewFolderScreen` holds the typed name in `rememberSaveable`, so an
        // auto-lock landing mid-word discarded it exactly as silently as a dropped item edit used
        // to - and this notice exists so that a lock under the user's hands is distinguishable
        // from the app simply losing their typing.
        assertTrue(AppNavGraph.droppedEditRoute("folder/new?parent={parent}&fromSheet={fromSheet}"))
    }

    @Test
    fun `browsing a folder is not an editor`() {
        // The control. "folder/new" must match without "folder/{id}" being swept in with it: one is
        // a form with unsaved typing, the other is a list the user is only looking at, and
        // reporting a discarded edit for a lock during ordinary browsing is a lie in the opposite
        // direction.
        assertFalse(AppNavGraph.droppedEditRoute("folder/{folderId}"))
    }

    @Test
    fun `an ordinary vault route is not an editor`() {
        // "vault/{type}" is not asserted here any more — that destination was removed (finding
        // 6-1, 2026-09-07 sweep) when type filtering moved into the vault list's filter sheet, so
        // it can no longer reach this function from a real navigation.
        assertFalse(AppNavGraph.droppedEditRoute(AppNavGraph.HOME))
        assertFalse(AppNavGraph.droppedEditRoute(""))
    }
}
