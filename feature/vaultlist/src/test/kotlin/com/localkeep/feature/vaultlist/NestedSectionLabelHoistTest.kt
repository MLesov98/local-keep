package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Nothing that scans the item list is read from inside the row lambda.
 *
 * The comment above `itemsIndexed` already argues this for the code it replaced: composing a row
 * used to end with `state.items.indexOf(item)`, and "every row paid for a scan of the list to learn
 * a number the lambda could have been given, and the cost of it grew with the square of the vault".
 * The very next statement kept the shape — `state.nestedItems.isNotEmpty()`, whose getter is
 * `items.filter { it.folderId != folderStack.last() }`, so every composed row ran a full pass over
 * the scoped list and allocated a fresh List to answer a question about emptiness. On the main
 * thread, during scroll, which is where dropped frames are most visible, and growing with the size
 * of the browsed subtree — the 10,000-item scale this project's D1 device pass targets.
 *
 * Hoisted beside `favourites` and `nestedStart`, which are the other two answers the lambda needs
 * and which were already computed once.
 */
class NestedSectionLabelHoistTest {

    private val source =
        File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")

    private fun code(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    private val rowLambda = "itemsIndexed(state.orderedItems"

    @Test
    fun `the row lambda never filters the whole list`() {
        val code = code()
        assertTrue(rowLambda in code, "VaultListScreen no longer renders its rows with $rowLambda")
        assertTrue(
            "state.nestedItems" !in code.substringAfter(rowLambda),
            "a per-row read of state.nestedItems is back. Its getter filters the whole scoped item " +
                "list and allocates a new one, once per composed row, to answer whether it is empty.",
        )
    }

    @Test
    fun `the answer is hoisted beside the other two the lambda needs`() {
        val hoisted = code().substringBefore(rowLambda)
        assertTrue(
            "state.nestedItems" in hoisted,
            "the section break's emptiness answer must be computed once, above the row lambda, " +
                "next to `favourites` and `nestedStart`",
        )
    }
}
