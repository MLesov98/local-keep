package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F50, 2026-09-13 (device walk): saving a new File item landed the vault list scrolled to the top
 * instead of scrolled-and-washed onto the new row, because the `FILE_NEW` route's `onSaved` was a
 * bare `navController::popBackStack` — unlike `ITEM_NEW`'s, which also calls
 * `PendingReveal.offer(...)` before popping. This pins that `FILE_NEW` now offers the reveal too,
 * with `FILE_SETTINGS` (editing an *existing* File item, which correctly does not reveal — same as
 * `ITEM_EDIT` beside it) as the control.
 *
 * Source scan rather than a render: exercising `AppNavGraph` needs a real `NavController` and the
 * full Hilt graph, which this module's unit tests do not stand up (there is no existing precedent in
 * `app/src/test` to follow, and one full-navigation-graph test harness is out of proportion to a
 * one-line wiring fix). [PassphraseNumberHintSourceScanTest] and [HeadingSemanticsSourceScanTest] in
 * other modules use the same technique for the same class of reason.
 */
class FileSaveRevealSourceScanTest {

    private fun source(): String {
        val file = File("src/main/kotlin/com/localkeep/app/navigation/AppNavGraph.kt")
        assertTrue(file.exists(), "AppNavGraph.kt not found at ${file.absolutePath}")
        return file.readText()
    }

    /**
     * The whole `composable(ROUTE, ...) { entry -> ... }` route body, found by brace-depth from the
     * *trailing* lambda's own opening brace — never from the first `{` after the route marker, which
     * a first draft of this test used and got wrong: `FILE_NEW` declares its own `navArgument("...")
     * { defaultValue = ...}` lambda before the trailing one, so the first `{` after the marker closes
     * almost immediately and truncates the body before `onSaved` is even in view. `") { entry ->"` is
     * unique to the trailing content lambda every `composable(...)` call in this file uses.
     */
    private fun routeBody(routeMarker: String): String {
        val text = source()
        val routeStart = text.indexOf(routeMarker)
        assertTrue(routeStart > 0, "$routeMarker not found — did the route move or get renamed?")
        val lambdaMarker = ") { entry ->"
        val lambdaIndex = text.indexOf(lambdaMarker, routeStart)
        assertTrue(lambdaIndex > routeStart, "no \"$lambdaMarker\" found after $routeMarker")
        val braceStart = lambdaIndex + lambdaMarker.indexOf('{')
        var depth = 0
        var i = braceStart
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(routeStart, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces scanning $routeMarker")
    }

    @Test
    fun `saving a new File item offers the reveal before popping back`() {
        val body = routeBody("composable(\n                FILE_NEW,")
        assertTrue(
            "PendingReveal.offer(fileEditorViewModel.state.value.itemId.toString())" in body,
            "FILE_NEW's onSaved does not offer a PendingReveal — the new row will not be scrolled " +
                "to or washed on return to the vault list",
        )
        assertTrue(
            "navController.popBackStack()" in body,
            "FILE_NEW's onSaved must still pop back after offering the reveal",
        )
    }

    /**
     * The control: editing an *existing* File item (reached from its own Settings screen, not the
     * FAB) must not reveal — mirrors ITEM_EDIT's onSaved, which is a bare popBackStack for the same
     * reason ("saving returns to the detail view... landing back on it lets them confirm the change
     * took", not a fresh row to point at). A fix that added the offer to every FileItemEditScreen
     * route indiscriminately would pass the test above while making every ordinary file edit
     * re-wash a row that was not new.
     */
    @Test
    fun `saving an edited File item does not offer a reveal`() {
        val body = routeBody("composable(FILE_SETTINGS)")
        assertTrue(
            "PendingReveal" !in body,
            "FILE_SETTINGS (editing an existing File item) must not offer a reveal",
        )
    }
}
