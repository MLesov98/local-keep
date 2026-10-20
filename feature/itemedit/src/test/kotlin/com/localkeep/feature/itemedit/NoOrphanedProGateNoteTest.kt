package com.localkeep.feature.itemedit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `LockedFeatureNote` and the three strings only it rendered are gone, in all five catalogues.
 *
 * It was the shared locked-state body for a Pro-gated section. Tags and custom fields stopped being
 * gated, then attachments did too on 2026-08-30 — and its last call site went with them, leaving a
 * composable nothing reached and a KDoc claiming "attachments are the remaining caller", which was
 * false the day it was written. Dead UI in a password manager is worse than dead code elsewhere: it
 * is a Pro upsell nobody can see, so nobody notices when it starts describing a tier that no longer
 * exists, and its strings keep costing four translations each.
 *
 * The three strings named below are the ones with no other call site; `itemedit_view_on_play` and
 * the two unlock-button strings are kept because the `showProPrompt` dialog inside `ItemEditScreen`
 * still renders them.
 */
class NoOrphanedProGateNoteTest {

    private val locales = listOf("values", "values-de", "values-es", "values-fr", "values-pt")

    private val orphaned = listOf(
        "itemedit_edit_locked_pro_badge",
        "itemedit_edit_locked_unlock_hint",
        "itemedit_edit_locked_view_play_hint",
    )

    private fun catalogue(locale: String): String {
        val file = File("src/main/res/$locale/strings.xml")
        assertTrue(file.exists(), "no catalogue at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `the composable is gone from the module`() {
        val sources = File("src/main/kotlin/com/localkeep/feature/itemedit")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .toList()
        assertTrue(sources.isNotEmpty(), "no Kotlin sources found — is the working directory the module root?")
        val offenders = sources.filter { "LockedFeatureNote" in it.readText() }
        assertTrue(
            offenders.isEmpty(),
            "LockedFeatureNote is back in ${offenders.map(File::getName)}. It had no caller and its " +
                "KDoc claimed one; if a Pro-gated section needs a locked body again, give it a call " +
                "site in the same commit.",
        )
    }

    @Test
    fun `its strings are gone from every catalogue`() {
        for (locale in locales) {
            val text = catalogue(locale)
            // The control: the catalogue is still the one this module owns, and still carries the
            // Pro copy that has a live call site. True before the fix and after — without it, a
            // wrong path would report every string absent and pass.
            assertTrue(
                "itemedit_edit_pro_unlock_button" in text,
                "$locale/strings.xml does not look like this module's catalogue",
            )
            for (key in orphaned) {
                assertFalse(
                    """name="$key"""" in text,
                    "$key is still in $locale/strings.xml. Nothing renders it — its only caller, " +
                        "LockedFeatureNote, is gone — so it is four translations of a sentence no " +
                        "user can reach.",
                )
            }
        }
    }
}
