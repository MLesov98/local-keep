package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Making a folder from the add sheet must not leave the screen it was made on.
 *
 * Reported 2026-10-12: "when creating folder why do I get out of the menu? ... What if I want to
 * create 5 or 10 folders or 20? Do I have to click every time?" The `FOLDER_NEW` route popped the
 * back stack as soon as the write landed, so every folder was an add sheet, a tap on New folder and
 * a name — and the list behind it, with its new row, was the only confirmation. The screen now stays
 * with the card open and empty; leaving is the user's own act, through `onDone` (after at least one
 * folder was made) or `onDismiss` (before any).
 *
 * Source scan for the reason [FileAddKeepsTheFolderSourceScanTest] gives: the nav graph needs a real
 * `NavController` and the Hilt graph, which this module's unit tests do not stand up.
 * `LkFolderPickerKeepsNamingAfterCreateTest` drives what the screen itself does once a create lands.
 */
class NewFolderStaysAfterCreateSourceScanTest {

    private val text = File("src/main/kotlin/com/localkeep/app/navigation/AppNavGraph.kt").readText()

    private fun routeBody(): String {
        val start = text.indexOf("composable(\n                FOLDER_NEW,")
        assertTrue(start > 0, "the FOLDER_NEW route moved or was renamed")
        return text.substring(start, text.indexOf("composable(FOLDERS)", start))
    }

    /** The `{ ... }` that follows [marker], braces balanced, or fails naming what was missing. */
    private fun lambdaAfter(body: String, marker: String): String {
        val at = body.indexOf(marker)
        assertTrue(at >= 0, "FOLDER_NEW no longer passes `$marker`")
        var i = body.indexOf('{', at)
        val open = i
        var depth = 0
        while (i < body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return body.substring(open, i + 1)
            }
            i++
        }
        throw AssertionError("unbalanced braces after `$marker`")
    }

    // The comments in the route are stripped before matching: they discuss the pop at length.
    private fun code(s: String) = s.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    @Test
    fun `creating a folder does not pop the back stack`() {
        val onCreate = code(lambdaAfter(routeBody(), "onCreate ="))
        assertTrue(
            "popBackStack" !in onCreate,
            "FOLDER_NEW's onCreate leaves the screen after one folder. Found:\n$onCreate",
        )
    }

    /** Leaving after a create is the user's own act, and it is a plain pop: the list is the confirmation. */
    @Test
    fun `finishing is its own callback and it pops`() {
        assertTrue("popBackStack" in code(lambdaAfter(routeBody(), "onDone =")), "onDone must leave the screen")
    }

    /** Control: cancelling before anything was made still returns to the door the user came through. */
    @Test
    fun `cancelling still returns to the sheet`() {
        assertTrue(
            "cancelBackToSheet" in code(lambdaAfter(routeBody(), "onDismiss =")),
            "onDismiss must still re-raise the sheet it came from",
        )
    }
}
