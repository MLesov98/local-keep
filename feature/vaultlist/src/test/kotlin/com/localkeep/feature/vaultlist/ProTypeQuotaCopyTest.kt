package com.localkeep.feature.vaultlist

import com.localkeep.core.vault.ProTypeQuota
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Card / Identity allowance copy, checked against the catalogue in all five languages.
 *
 * Sibling of [QuotaBlockedCopyTest] and written the same way — pure JVM, reading the XML rather
 * than resolving ids, so a missing translation fails here instead of at runtime in one locale.
 *
 * These three keys exist because `core_ui_pro_gate_lock_title` ("%1$s — part of Pro") could not be
 * reused: it is true of a tier that may hold no cards and false the moment a free vault holds one.
 * That is the same defect the file quota was given its own dialog to avoid.
 */
class ProTypeQuotaCopyTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private fun xml(dir: String): String =
        File("src/main/res/$dir/strings.xml").takeIf { it.exists() }?.readText()
            ?: fail("$dir/strings.xml missing")

    private fun block(dir: String, name: String): String =
        Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml(dir))?.groupValues?.get(1)
            ?: fail("$name missing from $dir/strings.xml")

    private fun items(dir: String, name: String): List<String> =
        Regex("""<item quantity="[a-z]+">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block(dir, name)).map { it.groupValues[1] }.toList()
            .ifEmpty { fail("$name has no items in $dir/strings.xml") }

    private fun quantities(dir: String, name: String): List<String> =
        Regex("""quantity="([a-z]+)"""").findAll(block(dir, name)).map { it.groupValues[1] }.toList()

    private val bodies = listOf(
        "vaultlist_card_quota_blocked_body_base",
        "vaultlist_identity_quota_blocked_body_base",
    )

    private val titles = listOf(
        "vaultlist_card_quota_blocked_title",
        "vaultlist_identity_quota_blocked_title",
    )

    @Test
    fun eachTitleCountsWhatIsHeldAgainstTheCap() {
        // Two separate arguments. Passing the cap twice is what made the files title a literal
        // wearing a count's clothes until 2026-08-25, so an over-cap vault read "1 of 1 used"
        // after every deletion while the app held the real number.
        languages.forEach { dir ->
            titles.forEach { key ->
                items(dir, key).forEach { text ->
                    listOf("%1\$d", "%2\$d").forEach { arg ->
                        assertTrue(arg in text, "$dir/$key must carry $arg: \"$text\"")
                    }
                }
            }
        }
    }

    /**
     * The noun is baked into the title, never interpolated.
     *
     * One templated title took the category name as `%1$s` and shipped a participle that could not
     * agree with it: **"Cartes — 1 sur 1 utilisé"**, **"Tarjetas — 1 de 1 usado"**, **"Identidades
     * — 1 de 1 usado"** — masculine participles against feminine plural nouns. Portuguese Cards was
     * the only one of the six that was right, by luck, because *cartão* is masculine.
     *
     * And it was the *common* form, not an edge case: this dialog fires when the user holds one and
     * tries to add a second, so `used` is 1 and the `one` item is what almost everyone reads.
     *
     * The body keys were split per type for exactly this reason and the title was left one key
     * short. With the noun baked in, gender is settled per language per noun once, and a category
     * added later cannot silently get it wrong.
     */
    @Test
    fun noTitleInterpolatesTheCategoryName() {
        languages.forEach { dir ->
            titles.forEach { key ->
                items(dir, key).forEach { text ->
                    assertTrue(
                        "%1\$s" !in text,
                        "$dir/$key must bake its noun in, not interpolate it: \"$text\"",
                    )
                }
            }
        }
    }

    @Test
    fun theTwoCategoriesNeverShareOneTitleTemplate() {
        // The structural half of the guard above: two categories rendering byte-identical text is
        // the signature of a template that has had its noun taken out again.
        languages.forEach { dir ->
            val card = items(dir, "vaultlist_card_quota_blocked_title")
            val identity = items(dir, "vaultlist_identity_quota_blocked_title")
            assertTrue(
                card.none { it in identity },
                "$dir gives Cards and Identities the same title text, so one of them cannot be " +
                    "agreeing with its own noun: $card",
            )
        }
    }

    @Test
    fun eachBodyNamesHowManyMustGoTheCapAndWhatIsStored() {
        languages.forEach { dir ->
            bodies.forEach { key ->
                items(dir, key).forEach { text ->
                    listOf("%1\$d", "%2\$d", "%3\$d").forEach { arg ->
                        assertTrue(arg in text, "$dir/$key must carry $arg: \"$text\"")
                    }
                }
            }
        }
    }

    /**
     * The cap's noun is fixed-SINGULAR, so the cap may not rise above one.
     *
     * The exact mirror of [QuotaBlockedCopyTest]'s guard, which fails if the *files* cap drops to
     * one. Here the body's plural quantity is how many must go — the number the reader acts on —
     * which leaves "%2$d card" agreeing with nothing. At one that is correct in all five
     * languages; at two it is "2 card", the untruth Tier F exists to stop. Cheaper to fail here
     * than to discover it in a catalogue, or on a device in Portuguese.
     */
    @Test
    fun theCapIsSingularSoTheSentenceAroundItCanBe() {
        assertEquals(
            1,
            ProTypeQuota.FREE_LIMIT,
            "a cap of ${ProTypeQuota.FREE_LIMIT} needs both body plurals re-keyed on the cap " +
                "rather than on the remove-count, in all five catalogues",
        )
    }

    @Test
    fun everyLanguageUsesTheSamePluralCategoriesItAlreadyUsesForTheFileQuota() {
        // fr, es and pt carry one/many/other; en and de carry one/other. A new key that invents a
        // different set for the same file is a lint error waiting to happen, and a missing `many`
        // renders the wrong form for large counts an imported vault can genuinely reach.
        languages.forEach { dir ->
            val expected = quantities(dir, "vaultlist_quota_blocked_body_base")
            (bodies + titles).forEach { key ->
                assertEquals(expected, quantities(dir, key), "$dir/$key plural categories")
            }
        }
    }

    /**
     * Portuguese uses the deletion verb for freeing a slot, not the detaching one.
     *
     * The project glossary splits these and records it as a decision (2026-08-14): *excluir* is
     * deletion, *remover* is detaching a favourite, tag or folder — "where the thing itself
     * survives". Freeing a quota slot is the first sense: the record leaves the vault. fr
     * (*Supprimez-en*), es (*Elimine*) and de (*Entfernen Sie*) were already on the deletion verb;
     * pt was the only break, and it was in the *pre-existing* files body as well as the two new
     * ones.
     *
     * Narrow on purpose — three keys, one language, one word. A general glossary linter is a
     * different job, and `check_catalogue.py`'s glossary pass is advisory precisely because it has
     * false positives. What this pins is the specific pair the glossary spent a sweep separating,
     * on the three strings that are meant to read as one voice.
     */
    @Test
    fun portugueseFreesASlotWithTheDeletionVerb() {
        val quotaBodies = bodies + "vaultlist_quota_blocked_body_base"
        quotaBodies.forEach { key ->
            items("values-pt", key).forEach { text ->
                assertTrue(
                    "Exclua" in text,
                    "values-pt/$key must use the deletion verb (excluir), not the detaching one: \"$text\"",
                )
                assertTrue(
                    "Remova" !in text,
                    "values-pt/$key uses `remover`, which the translation glossary reserves for detaching " +
                        "something that survives: \"$text\"",
                )
            }
        }
    }

    @Test
    fun theAllowanceCopyNeverCallsTheseTypesPro() {
        // The regression this whole change exists to prevent. "Cards — part of Pro" said to a user
        // who is storing a card is the files defect repeated, and it is what the old
        // core_ui_pro_gate_lock_title would have said had it been reused here.
        val banned = mapOf(
            "values" to "part of Pro",
            "values-fr" to "réservée à Pro",
            "values-de" to "Teil von Pro",
            "values-es" to "parte de Pro",
            "values-pt" to "parte do Pro",
        )
        languages.forEach { dir ->
            (bodies + titles).forEach { key ->
                items(dir, key).forEach { text ->
                    assertTrue(
                        banned.getValue(dir) !in text,
                        "$dir/$key must not call a type the free tier can create \"Pro\": \"$text\"",
                    )
                }
            }
        }
    }

    /**
     * Where the vault's word for itself has to appear, and why the cap is not a denominator.
     *
     * F-38 (device pass 2026-09-15): the titles read "%1$d of %2$d used", which renders as
     * "Cards — 5 of 1 used" on a vault holding five against a cap of one. Both numbers are true —
     * import is never charged against the cap, so an over-cap vault is a supported state — but "of"
     * asserts a ratio that does not exist, and a reader who believes it computes the remedy as
     * 5 - 1 = 4 while the app correctly asks for 5.
     *
     * The replacement states two plain facts side by side: how many are in the vault, and what the
     * limit is. This pins the half a reworder is most likely to undo — that something stands between
     * the two numbers other than a connector — by requiring each language's glossary word for the
     * vault, since "in the vault / dans le coffre / im Tresor" is the phrase that makes the first
     * number a location rather than a numerator.
     */
    @Test
    fun noTitleReadsAsAFractionOfTheCap() {
        val vaultWord = mapOf(
            "values" to "vault",
            "values-fr" to "coffre",
            "values-de" to "Tresor",
            "values-es" to "caja fuerte",
            "values-pt" to "cofre",
        )
        languages.forEach { dir ->
            (titles + "vaultlist_quota_blocked_title").forEach { key ->
                items(dir, key).forEach { text ->
                    val between = text.substringAfter("%1\$d").substringBefore("%2\$d")
                    assertTrue(
                        vaultWord.getValue(dir) in between,
                        "$dir/$key puts \"$between\" between the two numbers, which reads as a " +
                            "fraction of the cap — the first number is what the vault holds, and the " +
                            "cap does not bound it (F-38): \"$text\"",
                    )
                }
            }
        }
    }

    /**
     * The number the body asks the reader to act on, against the guard that enforces it.
     *
     * The visual pass read "Cards — 5 of 1 used … Remove 5 to make room" and expected 4. Four is
     * wrong: [ProTypeQuota.exceedsFreeLimit] refuses when `used + 1 > FREE_LIMIT`, so with a cap of
     * one the vault has to reach *zero* before another card fits, and 5 is the honest number. What
     * was wrong was the sentence around it — see [noTitleReadsAsAFractionOfTheCap] — so this pins
     * the arithmetic so a later "fix" cannot quietly make the instruction false to make it feel
     * right. The file quota's half of this contract lives in `QuotaBlockedCopyTest`.
     */
    @Test
    fun theRemoveCountIsWhatTheGuardWouldStopRefusing() {
        val limit = ProTypeQuota.FREE_LIMIT
        assertEquals(1, ProTypeQuota.mustRemove(limit), "at exactly the cap, one has to go")
        assertEquals(5, ProTypeQuota.mustRemove(5), "five held against a cap of one: five have to go")
        listOf(limit, limit + 1, 5, 20).forEach { used ->
            val after = used - ProTypeQuota.mustRemove(used)
            assertTrue(
                !ProTypeQuota.exceedsFreeLimit(after, isPro = false),
                "removing ${ProTypeQuota.mustRemove(used)} of $used must make room, left $after",
            )
        }
    }
}
