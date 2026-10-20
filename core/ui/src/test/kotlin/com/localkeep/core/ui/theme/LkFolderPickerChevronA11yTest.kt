package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The folder picker's expand/collapse chevron must be a named, roled control of its own.
 *
 * `LkFolderOption`'s chevron `Box` set `onClickLabel` (an *action* label — the "double tap to…"
 * hint) but had no `contentDescription` and no `role`. `clickable` gives the node its own
 * `mergeDescendants` boundary, so it is never absorbed into the row's `.labelled(...)` name above
 * it — it was an independently focusable node with nothing to speak, the exact failure
 * `LkIconButton`'s KDoc says its mandatory `label` parameter exists to prevent.
 *
 * ### Why this reads the source instead of driving the component
 *
 * `LkFolderPickerScreen` is a full screen (`LkScreen` background, `imePadding()`, scroll state),
 * and a `createComposeRule` render of it never reaches idle under Robolectric — `setContent` itself
 * times out with `AppNotIdleException` after 60s, before any assertion runs. That is the same shape
 * of limitation [com.localkeep.core.ui.NamingSheetAutoFocusTest] and
 * `NewFolderCardAutoFocusTest` (`feature:vaultlist`) record for this codebase's Compose test rule:
 * when the harness cannot settle, the unambiguous fact left to assert on is the source.
 *
 * If a future author reshapes `LkFolderOption`'s toggle box, this test's `indexOf` anchors will stop
 * matching and it will fail with a clear "could not find" message rather than silently passing.
 */
class LkFolderPickerChevronA11yTest {

    private val source = File("src/main/kotlin/com/localkeep/core/ui/theme/LkFolderPicker.kt")

    private fun text(): String {
        if (!source.isFile) fail("cannot find ${source.absolutePath} — this test reads the source it guards")
        return source.readText()
    }

    @Test
    fun `the expand-collapse chevron carries a role and a name`() {
        val code = text()
        val start = code.indexOf("if (toggle != null) {")
        if (start < 0) {
            fail("could not find the chevron's `if (toggle != null)` block — has LkFolderOption changed shape?")
        }
        val end = code.indexOf("LkIcons.ChevronDown", start)
        if (end < 0) fail("could not find the chevron's Icon after the toggle block")
        val chevronBox = code.substring(start, end)

        assertTrue(
            "role = Role.Button" in chevronBox,
            "the chevron's clickable Box lost its Role.Button — see LkIconButton's KDoc on why an " +
                "icon-only control must announce a role",
        )
        assertTrue(
            ".labelled(" in chevronBox,
            "the chevron's clickable Box has no `.labelled(...)` name — onClickLabel alone becomes " +
                "the 'double tap to…' usage hint, not the node's name, so TalkBack announces it as " +
                "an unnamed, un-roled stop",
        )
    }
}
