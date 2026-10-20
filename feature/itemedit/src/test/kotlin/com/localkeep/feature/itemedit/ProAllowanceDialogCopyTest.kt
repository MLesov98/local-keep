package com.localkeep.feature.itemedit

import com.localkeep.core.vault.ProTypeQuota
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the editor's allowance dialog may and may not say, in every shipped language.
 *
 * This is the second door onto one refusal. The add sheet has the first; both fire because a free
 * vault holds one card and one identity and the user asked for another. `ProTypeQuotaCopyTest`
 * guards the add sheet's strings, and until 2026-09-03 nothing guarded these — a gap found by the
 * security-copy 3-vote check, not by the build.
 *
 * A JVM test reading the XML directly, the same shape as `CoordinateCatalogueTest` and
 * `TierGClaimsTest`: the question is about the words, and the words live in five files this module
 * owns.
 */
class ProAllowanceDialogCopyTest {

    private val bodies = listOf(
        "itemedit_edit_pro_dialog_body_purchasable",
        "itemedit_edit_pro_dialog_body_purchasable_no_price",
        "itemedit_edit_pro_dialog_body_not_purchasable",
    )

    @Test
    fun `no language calls these item types part of Pro`() {
        // The defect this copy replaced. All three bodies said "This item type is part of Pro",
        // which was true while a free vault was allowed none and went false on 2026-09-03 the
        // moment one card and one identity became free: it told a user holding a card that the type
        // was unavailable to them. The identical falsehood had already shipped once for the file
        // quota and been fixed there; nothing stopped it living on here, because no test knew about
        // these keys.
        //
        // Phrased as banned phrasings rather than as an expected string, so a future rewording is
        // free to change everything except the claim.
        val banned = mapOf(
            "values" to listOf("part of pro", "requires pro", "pro feature", "pro only"),
            "values-fr" to listOf("réservé", "réservée", "fait partie de pro", "fonctionnalité pro"),
            "values-de" to listOf("teil von pro", "pro-funktion", "nur mit pro", "erfordert pro"),
            "values-es" to listOf("parte de pro", "función pro", "solo con pro", "requiere pro"),
            "values-pt" to listOf("parte do pro", "recurso pro", "apenas com pro", "requer pro"),
        )
        LOCALES.forEach { dir ->
            val phrases = banned.getValue(dir)
            (bodies + "itemedit_edit_pro_dialog_title").forEach { key ->
                val value = string(dir, key).lowercase()
                phrases.forEach { phrase ->
                    assertTrue(
                        !value.contains(phrase),
                        "$dir/$key claims the type is gated: \"$phrase\" in \"$value\"",
                    )
                }
            }
        }
    }

    @Test
    fun `the cap is a format argument, never a literal`() {
        // The sentence names how many a free vault holds. Written as a digit it would drift from
        // ProTypeQuota.FREE_LIMIT silently and in five languages at once; as an argument it cannot.
        LOCALES.forEach { dir ->
            bodies.forEach { key ->
                val value = string(dir, key)
                assertTrue(
                    Regex("%\\d\\\$d").containsMatchIn(value),
                    "$dir/$key states the cap without a number argument: \"$value\"",
                )
                // The two nouns beside the cap are fixed-singular (see below), so a bare "1" in the
                // prose is exactly the drift this is here to stop.
                assertTrue(
                    !Regex("(?<!%)\\b[0-9]+\\b").containsMatchIn(value.replace(Regex("%\\d\\\$[sd]"), "")),
                    "$dir/$key hardcodes a number: \"$value\"",
                )
            }
        }
    }

    @Test
    fun `the cap is singular, so the sentence around it can be`() {
        // The mirror of ProTypeQuotaCopyTest's guard on the add sheet. Every language writes "holds
        // 1 card and 1 identity" with both nouns singular, which is correct only while the cap is 1.
        // Raising FREE_LIMIT without re-keying these three bodies as plurals would leave five
        // catalogues saying "holds 3 card". This fails the build first.
        assertEquals(
            1,
            ProTypeQuota.FREE_LIMIT,
            "FREE_LIMIT moved off 1; the three itemedit_edit_pro_dialog_body_* strings say " +
                "\"%d card and %d identity\" with singular nouns in all five languages and must " +
                "become <plurals> keyed on the cap before this can change",
        )
    }

    @Test
    fun `every language keeps the argument indices the call site passes`() {
        // ItemEditScreen passes (price, cap) to the purchasable body and (cap) to the other two, so
        // the indices differ between them — %2$d in one, %1$d in the others. A language that copied
        // the wrong index would print the price where the count belongs, or crash formatting.
        LOCALES.forEach { dir ->
            val withPrice = string(dir, "itemedit_edit_pro_dialog_body_purchasable")
            assertTrue(withPrice.contains("%1\$s"), "$dir: priced body lost the price argument")
            assertTrue(withPrice.contains("%2\$d"), "$dir: priced body lost the cap argument")
            assertTrue(!withPrice.contains("%3"), "$dir: priced body takes a third argument")

            listOf(
                "itemedit_edit_pro_dialog_body_purchasable_no_price",
                "itemedit_edit_pro_dialog_body_not_purchasable",
            ).forEach { key ->
                val value = string(dir, key)
                assertTrue(value.contains("%1\$d"), "$dir/$key lost the cap argument")
                assertTrue(!value.contains("%2"), "$dir/$key takes a second argument the caller does not pass")
                // The cap is named twice — once per noun. Both occurrences must survive translation.
                assertEquals(
                    2,
                    Regex("%1\\\$d").findAll(value).count(),
                    "$dir/$key names the cap ${Regex("%1\\\$d").findAll(value).count()} times, expected 2",
                )
            }
        }
    }

    @Test
    fun `the no-price body states no amount at all`() {
        // Shown when Play has not told us the price, and always in foss, which sells nothing. A
        // literal amount here would be a price this build cannot honour.
        LOCALES.forEach { dir ->
            listOf(
                "itemedit_edit_pro_dialog_body_purchasable_no_price",
                "itemedit_edit_pro_dialog_body_not_purchasable",
            ).forEach { key ->
                val value = string(dir, key)
                // Format arguments are stripped before the currency scan: every one of them
                // contains a literal '$', so a naive `[€$£]` over the raw string matches its own
                // placeholders and fails on correct copy. This test did exactly that on its first
                // run — worth keeping the note, because the same trap is one character wide.
                val prose = value.replace(Regex("%\\d\\\$[sd]"), "")
                assertTrue(
                    !value.contains("%1\$s") && !Regex("[€$£¥]").containsMatchIn(prose),
                    "$dir/$key names an amount: \"$value\"",
                )
            }
        }
    }

    private fun string(dir: String, key: String): String {
        val file = File(REPO, "feature/itemedit/src/main/res/$dir/strings.xml")
        assertTrue(file.isFile, "missing catalogue ${file.path}")
        val match = Regex("<string name=\"$key\">(.*?)</string>").find(file.readText())
            ?: error("$key missing from $dir/strings.xml")
        return match.groupValues[1]
    }

    private companion object {
        val LOCALES = listOf("values", "values-fr", "values-de", "values-es", "values-pt")
        val REPO: File = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found above ${File("").absolutePath}")
    }
}
