package com.localkeep.feature.vaultlist

import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.ProTypeQuota
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The badge on a spent allowance names the count, never the tier — in all five languages, and in
 * both places the app used to claim otherwise.
 *
 * Nothing in this app is Pro-gated. Cards and Identity documents became one-each quotas on
 * 2026-09-03, files were already one, and [AddDestinationTest] asserts that no combination of
 * inputs can produce `AddDestination.ProBlocked` at all. This badge took three attempts to say so:
 *
 * - **`PRO`** named a gate that does not exist. A vault holding its one identity and no card showed
 *   "Identity PRO" beside a plain "Card" and read as *identities are paid, cards are not*.
 * - **`USED`** named a state but withheld the number, which is the one fact that separates *your
 *   vault holds one* from *this type is special*. The same question came back twice more, from the
 *   same reader, about the same two cells.
 * - **`1 OF 1`** cannot be misread as either, and is the wording the refusal dialog one tap behind
 *   it already used.
 *
 * The other half of the same defect was [VaultFilterBar], whose type rows carried
 * `locked = filter.requiresPro && !entitled` — the pre-quota gate rule — so every free user saw a
 * padlock and a PRO badge on Cards and Identities *permanently*, on a control that has never been
 * refused. Filtering is display, and the cap limits creation only. That marking is gone.
 */
class SpentBadgeCopyTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private val key = "vaultlist_badge_allowance_spent"

    private fun xml(dir: String): String =
        File("src/main/res/$dir/strings.xml").takeIf { it.exists() }?.readText()
            ?: fail("$dir/strings.xml missing")

    private fun block(dir: String): String =
        Regex("""<plurals name="$key">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml(dir))?.groupValues?.get(1) ?: fail("$key missing from $dir/strings.xml")

    private fun items(dir: String): List<String> =
        Regex("""<item quantity="[a-z]+">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block(dir)).map { it.groupValues[1] }.toList()
            .ifEmpty { fail("$key has no items in $dir/strings.xml") }

    private fun quantities(dir: String): List<String> =
        Regex("""quantity="([a-z]+)"""").findAll(block(dir)).map { it.groupValues[1] }.toList()

    @Test
    fun everyLanguageCarriesTheBadge() {
        languages.forEach { dir ->
            assertTrue(
                items(dir).all { it.isNotBlank() },
                "$dir/$key is empty, so a refusing cell would render a blank badge",
            )
        }
    }

    /**
     * Both numbers, never one twice.
     *
     * `%1$d` is what the vault holds and `%2$d` the cap. Passing the cap twice is what made the
     * files title a literal wearing a count's clothes until 2026-08-25, so an over-cap vault read
     * "5 of 5 used" after every deletion while the app held the real number. An imported vault can
     * legitimately sit above the cap — import never charges against it — so "9 of 7" is a state this
     * badge has to be able to render truthfully.
     */
    @Test
    fun theBadgeCarriesWhatIsHeldAndTheCap() {
        languages.forEach { dir ->
            items(dir).forEach { text ->
                listOf("%1\$d", "%2\$d").forEach { arg ->
                    assertTrue(arg in text, "$dir/$key must carry $arg: \"$text\"")
                }
            }
        }
    }

    /**
     * The whole point of the key. A spent allowance is not a tier, and the row it sits on is one the
     * user filled themselves.
     *
     * Case-insensitive because [com.localkeep.core.ui.theme.LkBadge] uppercases what it draws, so a
     * lowercase "pro" in a catalogue reaches the screen as "PRO".
     */
    @Test
    fun theBadgeNeverNamesTheTier() {
        languages.forEach { dir ->
            items(dir).forEach { text ->
                assertTrue(
                    !text.contains("pro", ignoreCase = true),
                    "$dir/$key names the tier on a row whose only problem is a spent quota: \"$text\"",
                )
            }
        }
    }

    @Test
    fun theBadgeUsesTheSamePluralCategoriesAsTheQuotaCopy() {
        languages.forEach { dir ->
            val expected = Regex("""quantity="([a-z]+)"""")
                .findAll(
                    Regex(
                        """<plurals name="vaultlist_quota_blocked_body_base">(.*?)</plurals>""",
                        RegexOption.DOT_MATCHES_ALL,
                    ).find(xml(dir))!!.groupValues[1],
                )
                .map { it.groupValues[1] }.toList()
            assertEquals(expected, quantities(dir), "$dir/$key plural categories")
        }
    }

    /**
     * One key, where the badge it replaced needed three.
     *
     * "Used" is a past participle and agrees with a noun the string does not contain, and the three
     * metered categories do not share a gender — fr *Fichier* is masculine against *Carte* and
     * *Identité* feminine, pt *Cartão* against *Identidade*. Every word below was chosen to have
     * nothing to agree with: en "held" does not inflect, and fr *en tout*, de *gesamt*, es
     * *en total* and pt *no total* are adverbials containing no noun.
     *
     * Pinned word by word rather than by counting words. The badge used to be two numerals and one
     * preposition, so "at most one word" was the whole invariant; naming the cap as well as the
     * holding costs three or four, and a length bound would stop catching what it was for. A
     * rewording that reaches for a participle fails here by name, and the fix is to check agreement
     * before adding to this map — not to widen it because the test went red.
     */
    private val invariantWords = mapOf(
        "values" to setOf("limit", "held"),
        "values-fr" to setOf("limite", "en", "tout"),
        "values-de" to setOf("limit", "gesamt"),
        "values-es" to setOf("límite", "en", "total"),
        "values-pt" to setOf("limite", "no", "total"),
    )

    @Test
    fun theBadgeContainsNothingThatCouldAgreeWithANoun() {
        languages.forEach { dir ->
            val allowed = invariantWords.getValue(dir)
            items(dir).forEach { text ->
                val words = text.replace("%1\$d", "").replace("%2\$d", "").replace("·", " ")
                    .trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                words.forEach { word ->
                    assertTrue(
                        word.lowercase() in allowed,
                        "$dir/$key contains \"$word\", which is not one of the words this badge was " +
                            "reviewed for ($allowed). The badge names no noun, so any word that " +
                            "could agree with one would have to be right for Card, Identity and " +
                            "File at once — which is why the badge before it needed a key per " +
                            "category.",
                    )
                }
            }
        }
    }

    @Test
    fun everyMeteredTypeKnowsItsCapAndNoUnmeteredTypeClaimsOne() {
        val metered = listOf(NewItemType.CARD, NewItemType.IDENTITY, NewItemType.FILE)
        metered.forEach { type ->
            assertNotNull(
                type.freeLimit,
                "$type can be refused on quota but does not know its own cap, so its badge would " +
                    "render as \"1 of \" with nothing after it",
            )
        }
        (NewItemType.entries - metered.toSet()).forEach { type ->
            assertEquals(
                null,
                type.freeLimit,
                "$type has no quota and can never refuse, so it must not carry a cap",
            )
        }
    }

    /**
     * The cap is read from the quota object, never copied.
     *
     * It is on screen twice at once — this badge and the refusal dialog one tap behind it — so a
     * second copy would be visible to the user as two different numbers for one rule.
     */
    @Test
    fun eachCapIsTheOneItsOwnQuotaObjectStates() {
        assertEquals(ProTypeQuota.FREE_LIMIT, NewItemType.CARD.freeLimit)
        assertEquals(ProTypeQuota.FREE_LIMIT, NewItemType.IDENTITY.freeLimit)
        assertEquals(AttachmentQuota.FREE_LIMIT, NewItemType.FILE.freeLimit)
    }

    /**
     * The dead tier strings are gone, not merely unused.
     *
     * Left in the catalogue they are an invitation: the next author reaching for a marker finds
     * `vaultlist_badge_pro` sitting there and reuses it, which is how the filter sheet kept the
     * pre-quota gate rule for a day after the add sheet stopped using it.
     */
    @Test
    fun theSupersededBadgeStringsAreDeleted() {
        val gone = listOf(
            "vaultlist_badge_pro",
            "vaultlist_add_type_pro_suffix",
            "vaultlist_card_badge_used",
            "vaultlist_identity_badge_used",
            "vaultlist_file_badge_used",
        )
        gone.forEach { dead ->
            languages.forEach { dir ->
                assertTrue(
                    """name="$dead"""" !in xml(dir),
                    "$dir still defines $dead, which nothing renders any more",
                )
            }
        }
    }

    /**
     * The filter sheet marks nothing, and this reads the source to say so.
     *
     * Same idiom, and the same reasoning, as `NamingSheetAutoFocusTest`: the defect is the presence
     * of a call, and a Compose assertion here would need the whole sheet plus a Pro-entitlement
     * ambient to discriminate between "no badge" and "badge the test could not find".
     */
    @Test
    fun theFilterSheetMarksNoTypeAsPaid() {
        val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultFilterBar.kt")
            .takeIf { it.isFile } ?: fail("VaultFilterBar.kt not found")
        val code = source.readText().lineSequence()
            // Comments explain why the marking is gone and quote the rule it used to apply; they
            // must not trip the check, or the explanation becomes unwritable.
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertTrue(
            "LkBadge" !in code,
            "VaultFilterBar draws a badge again. Filtering is display, and the cap limits creation " +
                "only — a free vault may filter to Cards holding none, one, or twenty imported ones.",
        )
        assertTrue(
            "requiresPro" !in code,
            "VaultFilterBar consults requiresPro again. That is the pre-2026-09-03 gate rule, and " +
                "it marked Cards and Identities for every free user permanently, on rows that work.",
        )
        assertTrue(
            "LkIcons.Lock" !in code,
            "VaultFilterBar draws a padlock again, on a control that is never locked.",
        )
    }

    /** The control: prove the file being read is really the filter sheet. */
    @Test
    fun theFileBeingReadReallyIsTheFilterSheet() {
        val text = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultFilterBar.kt").readText()
        assertTrue("fun ColumnScope.FilterSheetContent(" in text, "read a file that is not the filter sheet")
        assertTrue("filter-type-" in text, "read a filter sheet with no type rows")
    }
}
