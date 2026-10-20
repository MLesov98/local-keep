package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Empty-trash confirmation has to be grammatical when the trash holds one thing.
 *
 * 2C-F9, on a device 2026-09-20:
 * `text='This permanently removes all 1 item. There is no undo here, though an earlier snapshot or
 * backup may still hold them.'` — "all 1 item", and "hold **them**" for a single item. This is the
 * app's only irreversible control and its copy is tier A; a sentence the user has to agree to
 * should not read as a template that has not been filled in.
 *
 * Two separate causes, fixed separately:
 *  - `vaultlist_trash_empty_removes_phrase`'s `one` carried the word "all", which the French,
 *    German, Spanish and Portuguese `one` forms had already dropped — English was the outlier.
 *  - `vaultlist_trash_empty_no_undo_clause` is one `<string>` used at every count, so its pronoun
 *    cannot agree with one. It now names what may survive ("a copy") instead of counting it.
 *    Deliberately **not** turned into a `<plurals>`: `ios/Packages/LKDesign` mirrors this key as a
 *    flat string, and a structural change here would break a module this package does not own.
 *
 * Read from the shipped resource rather than from a literal, because a literal stand-in is exactly
 * how the old wording survived a sweep — see [EmptyTrashBodyTest]'s own note about asserting a
 * false claim until it stopped looking like an oversight.
 */
class EmptyTrashSingularCopyTest {

    private fun strings(locale: String = ""): String {
        val dir = if (locale.isEmpty()) "values" else "values-$locale"
        val file = File("src/main/res/$dir/strings.xml")
        if (!file.isFile) fail("cannot find ${file.absolutePath} — this test reads the copy it guards")
        return file.readText()
    }

    private fun singularRemovesPhrase(): String =
        Regex("""<plurals name="vaultlist_trash_empty_removes_phrase">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(strings())
            ?.groupValues
            ?.get(1)
            ?.let { Regex("""<item quantity="one">(.*?)</item>""").find(it)?.groupValues?.get(1) }
            ?: fail("vaultlist_trash_empty_removes_phrase no longer has a `one` form")

    private fun noUndoClause(locale: String = ""): String =
        Regex("""<string name="vaultlist_trash_empty_no_undo_clause">(.*?)</string>""")
            .find(strings(locale))
            ?.groupValues
            ?.get(1)
            ?: fail("vaultlist_trash_empty_no_undo_clause is missing from ${locale.ifEmpty { "the base locale" }}")

    @Test
    fun `the singular does not say all one item`() {
        val one = singularRemovesPhrase()
        assertFalse(
            "all " in one,
            "\"all\" belongs only to the plural — the singular read \"removes all 1 item\". Found: $one",
        )
        assertTrue("%1\$d item" in one, "the count must still be stated, and stated truthfully. Found: $one")
    }

    @Test
    fun `the no-undo clause does not use a plural pronoun`() {
        val clause = noUndoClause()
        assertFalse(
            "hold them" in clause,
            "one string covers every count, so it cannot say \"them\". Found: $clause",
        )
        // The claim itself is unchanged and must stay unchanged: this is tier-A copy on the only
        // irreversible control in the app, and it exists to stop the dialog overclaiming.
        assertTrue("no undo" in clause, "the clause must still say there is no undo. Found: $clause")
        assertTrue(
            "snapshot" in clause && "backup" in clause,
            "the clause must still name what may survive the purge. Found: $clause",
        )
    }

    @Test
    fun `every shipped locale still has the clause`() {
        // A translation lost here is a lint error at build time, but it would be a silent English
        // sentence inside a French confirmation first. Cheap to pin, and this test already has the
        // reader for it.
        listOf("fr", "de", "es", "pt").forEach { locale ->
            assertTrue(noUndoClause(locale).isNotBlank(), "$locale lost vaultlist_trash_empty_no_undo_clause")
        }
    }
}
