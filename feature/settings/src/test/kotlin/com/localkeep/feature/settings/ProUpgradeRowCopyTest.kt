package com.localkeep.feature.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The standing Pro row states an account fact. It must never state a number, and it must never
 * claim Pro unlocks data.
 *
 * Both rules are load-bearing rather than stylistic:
 *
 * The **no-number rule** is what separates a disclosure from a nag. A collapsed row is drawn on
 * every free user's screen whether or not they asked; a count sitting there unprompted is the
 * filter-sheet padlock again, which this repo drew once and deleted. Counts belong in the expanded
 * body, which is opened only on request.
 *
 * The **no-data-claim rule** is claim law. The caps limit *creation* only — `AttachmentQuota` and
 * `ProTypeQuota` both state the invariant that import is never charged and display is never gated
 * — so a free vault that imported thirty cards keeps all thirty, readable and editable. Copy
 * saying Pro "unlocks" or "gives access to" anything already held would be false, and it is the one
 * sentence here a mistranslation could turn into a lie.
 *
 * Pure JVM: reads the catalogue rather than resolving ids, like `QuotaBlockedCopyTest` next door.
 */
class ProUpgradeRowCopyTest {

    private val languages = listOf("values", "values-fr", "values-de", "values-es", "values-pt")

    private val summaries = listOf(
        "settings_pro_row_summary_free",
        "settings_pro_row_summary_pro",
        "settings_pro_row_summary_foss",
    )

    private val bodies = listOf(
        "settings_pro_row_body_removes_caps",
        "settings_pro_row_body_pro",
    )

    private fun value(dir: String, name: String): String {
        val xml = File("src/main/res/$dir/strings.xml")
            .takeIf { it.exists() }
            ?.readText()
            ?: fail("$dir/strings.xml missing")
        return Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
            ?: fail("$name missing from $dir/strings.xml")
    }

    @Test
    fun theCollapsedSummaryNeverCarriesANumber() {
        languages.forEach { dir ->
            summaries.forEach { key ->
                val text = value(dir, key)
                assertTrue(
                    text.none { it.isDigit() },
                    "$dir/$key puts a number on an unopened row: \"$text\"",
                )
                assertTrue(
                    !text.contains("%"),
                    "$dir/$key takes a format argument, so it can grow a number: \"$text\"",
                )
            }
        }
    }

    @Test
    fun noStringInTheRowNamesAPrice() {
        // Play Billing is the authority on what a user is charged, and it localises the currency.
        // A literal here would be euros on every store on earth — the reason PRO_PRICE is an
        // ERROR-level deprecation rather than a warning.
        val currency = listOf('€', '$', '£', '¥')
        languages.forEach { dir ->
            (summaries + bodies + listOf("settings_pro_row_title")).forEach { key ->
                val text = value(dir, key)
                assertTrue(
                    currency.none { it in text },
                    "$dir/$key names a currency: \"$text\"",
                )
            }
        }
    }

    @Test
    fun theBodySaysWhatProRemovesAndDeniesTheDataReading() {
        // English is the source the other four are translated from, so the denial is asserted here
        // and carried outward by the back-translation pass rather than pattern-matched per
        // language — the wording differs, the claim must not.
        val body = value("values", "settings_pro_row_body_removes_caps")
        assertTrue(
            body.contains("Importing", ignoreCase = true) &&
                body.contains("viewing", ignoreCase = true),
            "the body must say import and viewing were never capped, got: \"$body\"",
        )
        listOf("unlock your", "access to your", "gives you access").forEach { claim ->
            assertTrue(
                !body.contains(claim, ignoreCase = true),
                "the body claims Pro unlocks data (\"$claim\"): \"$body\"",
            )
        }
    }

    @Test
    fun everyRowStringIsTranslatedIntoAllFourLanguages() {
        // MissingTranslation is a lint error, but lint runs on :app and this catches it in the
        // module that owns the keys, where a missing one is cheapest to fix.
        val keys = summaries + bodies + listOf("settings_pro_row_title")
        languages.forEach { dir -> keys.forEach { key -> value(dir, key) } }
    }
}
