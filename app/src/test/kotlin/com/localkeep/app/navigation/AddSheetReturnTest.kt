package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The add sheet is one chooser with ten answers, so backing out of any answer returns to the
 * question.
 *
 * Only the folder row did. Every item type popped straight to the bare vault list, so cancelling
 * out of "Location" — or Login, or Note, or a file — read as being thrown home from a flow the user
 * had barely entered. Reported from a phone 2026-08-21: *"Clicked on Location, instantly back, I'm
 * into the main menu instead of vault… isn't it clear that it should work like that across all
 * items in the +"*.
 *
 * **This reads source, not a running back stack.** `:app` has no instrumented harness that can
 * drive navigation, and the thing that regresses here is structural: a destination that forgets to
 * carry `fromSheet`, or an `onBack` quietly simplified back to `navController::popBackStack`. Both
 * are visible in the text and neither is visible in a unit test of anything else.
 *
 * The save path is pinned too, in the negative. Re-raising the sheet after a *save* would cover the
 * row the user just made with the chooser they used to make it.
 */
class AddSheetReturnTest {

    private val graph: String by lazy {
        val file = File("src/main/kotlin/com/localkeep/app/navigation/AppNavGraph.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        file.readText()
    }

    /**
     * The body of one `composable(...) { ... }` block, from its route constant up to the next
     * `composable(`. Matched by regex rather than by an exact indented string so that reformatting
     * the graph cannot turn this test green by making it match nothing.
     */
    private fun destination(routeConst: String): String {
        val opener = Regex("""composable\(\s*$routeConst\s*[,)]""").find(graph)
            ?: fail("no destination declared for $routeConst")
        return graph.substring(opener.range.first + 1).substringBefore("composable(")
    }

    @Test
    fun `every screen the add sheet opens carries the door marker`() {
        for (route in listOf("ITEM_NEW", "FILE_NEW", "FOLDER_NEW")) {
            assertTrue(
                destination(route).contains("navArgument(\"fromSheet\")"),
                "$route must declare fromSheet, or the sheet cannot know it opened this screen",
            )
        }
    }

    @Test
    fun `cancelling any of them returns to the sheet`() {
        for (route in listOf("ITEM_NEW", "FILE_NEW", "FOLDER_NEW")) {
            assertTrue(
                destination(route).contains("cancelBackToSheet"),
                "$route must cancel through cancelBackToSheet — a bare popBackStack lands on the " +
                    "vault list and reads as a jump home",
            )
        }
    }

    @Test
    fun `saving does not re-raise the sheet over what was just made`() {
        val onSaved = destination("ITEM_NEW").substringAfter("onSaved = {").substringBefore("},")
        assertFalse(
            onSaved.contains("cancelBackToSheet") || onSaved.contains(REOPEN_KEY),
            "a saved item must land on the list that now shows it, not under the add sheet",
        )
    }

    @Test
    fun `the generator does not pretend the sheet opened the editor`() {
        val generator = destination("GENERATOR")
        assertTrue(
            generator.contains("itemNew(NewItemType.LOGIN)"),
            "the generator's route must take the fromSheet default — backing out of the editor " +
                "belongs to the generator, which is the screen the user actually came from",
        )
    }

    private companion object {
        /** Spelled out rather than referenced, so renaming the constant cannot silently pass this. */
        const val REOPEN_KEY = "REOPEN_ADD_SHEET"
    }
}
