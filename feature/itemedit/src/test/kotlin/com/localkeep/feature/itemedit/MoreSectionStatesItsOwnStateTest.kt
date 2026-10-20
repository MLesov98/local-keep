package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "Folder, tags and more" has to say whether it is open.
 *
 * The row is the only route from the edit form to Folder, Tags, custom fields and Reprompt, and the
 * open/closed fact lived entirely inside `onClickLabel` — which TalkBack speaks as the "double tap
 * to…" action hint, not as the node's name or its state, and not at all for a user who has hints
 * turned off. `LkFolderPicker` states that distinction in its own comment about the identical
 * mechanism. So a screen-reader user landing on the row had to activate it and listen for the
 * layout to change to learn what it had been.
 *
 * `stateDescription` rather than folding a state word into the name the way `SettingsScreen` does:
 * the row's summary is dynamic (folder, tag count, field count, reprompt) and `labelled()` merges
 * the subtree away, so that route would have meant rebuilding the summary sentence by hand. This
 * one leaves the existing announcement alone and adds the state beside it, which is also the
 * property that TalkBack re-speaks when it changes.
 *
 * A source scan, for the reason `PhrasePillIsSafeToMissTest` gives, with a control that held before.
 */
class MoreSectionStatesItsOwnStateTest {

    private val locales = listOf("values", "values-de", "values-es", "values-fr", "values-pt")

    private fun moreSectionBody(): String {
        val src = File("src/main/kotlin/com/localkeep/feature/itemedit/ItemEditScreen.kt")
        assertTrue(src.exists(), "ItemEditScreen.kt not found at ${src.absolutePath}")
        val text = src.readText()
        val start = text.indexOf("private fun MoreSection(")
        assertTrue(start > 0, "MoreSection( not found — did the composable get renamed?")
        val end = text.indexOf("private fun RepromptRow(", start)
        assertTrue(end > start, "RepromptRow( not found after MoreSection — region unbounded")
        return text.substring(start, end)
    }

    @Test
    fun `the disclosure row states expanded or collapsed`() {
        val body = moreSectionBody()
        // The control: the action hint is still there. True before the fix and after — the state
        // is an addition, and a test that could be satisfied by deleting the hint would be wrong.
        assertTrue(
            "onClickLabel = toggleLabel" in body,
            "the disclosure row no longer names its action — region wrong?",
        )
        assertTrue(
            "stateDescription" in body,
            "the disclosure row exposes no expanded/collapsed state. onClickLabel is the action " +
                "hint, not the node's state, so the only way to learn whether the section is open " +
                "is to toggle it and listen.",
        )
        assertTrue(
            "itemedit_edit_more_state_expanded" in body && "itemedit_edit_more_state_collapsed" in body,
            "the state word is not a resource, or only one of the two states is named.",
        )
    }

    @Test
    fun `both state words exist in every catalogue`() {
        for (locale in locales) {
            val file = File("src/main/res/$locale/strings.xml")
            assertTrue(file.exists(), "no catalogue at ${file.absolutePath}")
            val text = file.readText()
            // The control: still this module's catalogue, and still carrying the row's own label.
            assertTrue(
                """name="itemedit_edit_more_label"""" in text,
                "$locale/strings.xml does not look like this module's catalogue",
            )
            for (key in listOf("itemedit_edit_more_state_expanded", "itemedit_edit_more_state_collapsed")) {
                assertTrue(
                    """name="$key"""" in text,
                    "$key is missing from $locale/strings.xml — lint fails the build on " +
                        "MissingTranslation, and the row announces a state word in English.",
                )
            }
        }
    }
}
