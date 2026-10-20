package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The "Search" app shortcut puts the caret in the search field, once.
 *
 * The flag arrives through a `SavedStateHandle` key, exactly as `reopenAddSheet` does, and an
 * unconsumed one replays: `LaunchedEffect` re-runs on every composition the key survives, so without
 * `onSearchFocusHandled()` the keyboard comes back up each time the user rotates away from it.
 * Consuming it inside the same effect is the whole of the fix and the whole of what this pins.
 *
 * ### Why this reads the source
 *
 * Same limitation as [ListViewportWeightTest]: `VaultListScreen` is Hilt-backed and never reaches
 * idle under Robolectric's compose rule, and a hand-built miniature of the effect would be a test of
 * the miniature.
 */
class SearchFocusRequestTest {

    private fun screen(): String {
        val file = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the source it guards")
        return file.readText()
    }

    @Test
    fun `the request is consumed in the same effect that acts on it`() {
        val code = screen()
        val start = code.indexOf("LaunchedEffect(focusSearch) {")
        if (start < 0) fail("could not find the focusSearch effect — has VaultListScreen changed shape?")
        val body = code.substring(start, code.indexOf("\n    }", start))
        assertTrue("searchFocus.requestFocus()" in body, "the effect must move focus: $body")
        assertTrue(
            "onSearchFocusHandled()" in body,
            "the effect must consume the request, or it replays on every configuration change: $body",
        )
    }

    @Test
    fun `the search field is the thing that gets the focus`() {
        // The screen hands the requester to [VaultSearchRow], which puts it on the field's own chain.
        // Whitespace-insensitive: the field's indentation moves whenever the header's nesting does.
        assertTrue(
            Regex("""searchModifier\s*=\s*Modifier\.focusRequester\(searchFocus\)""").containsMatchIn(screen()),
            "the screen must pass the focus requester to the search row",
        )
        val row = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListChrome.kt").readText()
        assertTrue(
            // `.then(placement)` is the weight beside the buttons, or the full width above them.
            Regex("""modifier\s*=\s*searchModifier\s+\.then\(placement\)\s+\.lkTag\("vaultlist-search"\)""").containsMatchIn(row),
            "the requester must sit on the search field's own modifier chain",
        )
    }
}
