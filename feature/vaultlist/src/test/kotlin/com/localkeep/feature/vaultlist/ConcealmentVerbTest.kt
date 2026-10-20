package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The concealment feature never borrows the masking verb — in French or German.
 *
 * The project glossary binds *hide (verb)* to masking only: a two-factor code, a field value, a
 * collapsed folder tree, and it says in terms "NOT the concealment feature, which is
 * `make invisible` below". Line 61 binds the same two words to *dismiss*. The split was made on
 * 2026-08-19 because the two senses had shared one word and a user met both in one session.
 *
 * It came back anyway. Naturalness sweep 8 (2026-09-06) found the reveal banner reaching for the
 * masking verb in **both** languages at once — French `Masqué à nouveau dans %1$s` and
 * `seront masqués automatiquement`, German `Wird ausgeblendet in %1$s` and
 * `werden von selbst wieder ausgeblendet` — found independently by two reviewers who never saw
 * each other's work. Nothing in the build objected, so this test exists.
 *
 * **Why only fr and de.** These are the two languages where the glossary gives the concealment
 * feature a word of its own (`rendre invisible`, `unsichtbar machen`) *distinct* from the masking
 * verb, so a collision is detectable. Spanish and Portuguese use `ocultar` for both senses by
 * glossary decision, and their reviewed, shipped countdown and announcement legitimately say
 * `Se ocultan de nuevo` and `serão ocultadas` — banning the stem there would fail correct copy.
 * Adding es/pt to [banned] would not strengthen this test; it would break it.
 *
 * [theMaskingKeysStillUseTheMaskingVerb] is the control. Without it a typo in [banned], or a
 * matcher that silently reads comments instead of values, would leave a test that passes because
 * it looks at nothing — the failure mode this repo has recorded more than once.
 */
class ConcealmentVerbTest {

    /** Stems, lower-cased, matched against string values only. */
    private val banned = mapOf(
        "values-fr" to listOf("masqu"),
        "values-de" to listOf("ausblend", "ausgeblend"),
    )

    /**
     * Every key that names the concealment feature or its state. Taken from the "Rendre invisible"
     * / "Unsichtbar machen" block plus the two filter-sheet labels and the count-line fragment that
     * describe the same narrowing.
     */
    private val concealmentKeys = listOf(
        "vaultlist_revealed_header",
        "vaultlist_revealed_countdown",
        "vaultlist_revealed_state_description",
        "vaultlist_revealed_announcement",
        "vaultlist_hide_again",
        "vaultlist_hide_action",
        "vaultlist_hide_sheet_title",
        "vaultlist_hide_phrase_title",
        "vaultlist_hide_phrase_body",
        "vaultlist_hide_phrase_label",
        "vaultlist_hide_phrase_confirm",
        "vaultlist_hide_error_collision",
        "vaultlist_marker_hidden",
        "vaultlist_filter_section_hidden",
        "vaultlist_filter_hidden_only",
        "vaultlist_narrowed_hidden",
        "vaultlist_stop_hiding",
    )

    /** Keys that legitimately carry the masking verb: these are about filter and search hiding. */
    private val maskingKeys = listOf("vaultlist_selection_hidden_subtitle")

    private fun xml(dir: String): String =
        File("src/main/res/$dir/strings.xml").takeIf { it.exists() }?.readText()
            ?: fail("$dir/strings.xml missing")

    /**
     * Values only. Comments are excluded deliberately: both catalogues carry a section header
     * naming the feature that *does* contain the banned stem (fr "Rendre invisible (éléments
     * masqués)", de "Unsichtbar machen (ausgeblendete Einträge)"), and reading it would make every
     * key in the block look defective.
     */
    private fun valuesOf(dir: String, key: String): List<String> {
        val body = xml(dir)
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.let { return listOf(it.groupValues[1]) }
        val plural = Regex("""<plurals name="$key">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""<item quantity="[a-z]+">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(plural).map { it.groupValues[1] }.toList()
    }

    @Test
    fun everyConcealmentKeyIsPresentInBothCatalogues() {
        banned.keys.forEach { dir ->
            concealmentKeys.forEach { key ->
                assertTrue(
                    valuesOf(dir, key).isNotEmpty(),
                    "$key is missing from $dir/strings.xml. If it was renamed, rename it here too — " +
                        "a key this test cannot find is a key it cannot guard.",
                )
            }
        }
    }

    @Test
    fun theConcealmentFeatureNeverUsesTheMaskingVerb() {
        banned.forEach { (dir, stems) ->
            concealmentKeys.forEach { key ->
                valuesOf(dir, key).forEach { value ->
                    stems.forEach { stem ->
                        assertTrue(
                            !value.lowercase().contains(stem),
                            "$dir/$key says \"$value\", which uses \"$stem\" — the translation glossary reserves " +
                                "that word for masking (a 2FA code, a field value, a collapsed folder) " +
                                "and :61 for dismissing a banner. The concealment feature has its own " +
                                "word in this language; use it.",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theMaskingKeysStillUseTheMaskingVerb() {
        banned.forEach { (dir, stems) ->
            maskingKeys.forEach { key ->
                val values = valuesOf(dir, key)
                assertTrue(values.isNotEmpty(), "$key missing from $dir/strings.xml")
                assertTrue(
                    values.all { v -> stems.any { v.lowercase().contains(it) } },
                    "$dir/$key no longer contains any of $stems, so the matcher in " +
                        "theConcealmentFeatureNeverUsesTheMaskingVerb is no longer proven to match " +
                        "anything. Either this string changed, or the stems are wrong.",
                )
            }
        }
    }
}
