package com.localkeep.app.navigation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The `FILE_NEW` route consumes the folder the add flow was browsing, as `ITEM_NEW` does.
 *
 * Device, 2026-10-04 (API 36, foss release): a File added from inside "My Folder" — FAB reading
 * "Add to My Folder" — opened with FOLDER "None" and saved at the top level. `ITEM_NEW` seeds its
 * editor from `PendingFolderSelection` after construction; `FILE_NEW` never did, because until
 * 2026-08-19 the files editor had no folder field to seed. `FileAddedInsideAFolderIsFiledThereTest`
 * in `:feature:files` pins the editor's half; this pins that the route calls it.
 *
 * Source scan for the reason [FileSaveRevealSourceScanTest] gives: the nav graph needs a real
 * `NavController` and the Hilt graph, which this module's unit tests do not stand up.
 */
class FileAddKeepsTheFolderSourceScanTest {

    private fun routeBody(routeMarker: String): String {
        val text = File("src/main/kotlin/com/localkeep/app/navigation/AppNavGraph.kt").readText()
        val routeStart = text.indexOf(routeMarker)
        assertTrue(routeStart > 0, "$routeMarker not found — did the route move or get renamed?")
        val lambdaMarker = ") { entry ->"
        val lambdaIndex = text.indexOf(lambdaMarker, routeStart)
        assertTrue(lambdaIndex > routeStart, "no \"$lambdaMarker\" found after $routeMarker")
        var depth = 0
        var i = lambdaIndex + lambdaMarker.indexOf('{')
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(routeStart, i + 1)
            }
            i++
        }
        throw AssertionError("unbalanced braces scanning $routeMarker")
    }

    @Test
    fun `a new File starts in the folder the list was browsing`() {
        val body = routeBody("composable(\n                FILE_NEW,")
        assertTrue(
            "fileEditorViewModel.seedFolderFromPending()" in body,
            "FILE_NEW does not seed the editor from PendingFolderSelection — a file added from " +
                "inside a folder opens at None and saves at the top level",
        )
    }

    /**
     * The control: editing an existing file must never consume the pending. Its folder is the one
     * it is already in, and a seed here would re-file it into whatever folder the list last browsed.
     */
    @Test
    fun `an existing file's settings never take the pending folder`() {
        val body = routeBody("composable(FILE_SETTINGS)")
        assertTrue("seedFolderFromPending" !in body, "FILE_SETTINGS must not seed a folder")
    }
}
