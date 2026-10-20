package com.localkeep.feature.settings

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A user who has decided to pay must be able to find where.
 *
 * The Pro group carried `"restore purchase pro unlock repurchase reinstall bought paid receipt"`,
 * and [searchSettings] is an AND-match over `title + summary + keywords + groupTitle`. So *buy*,
 * *upgrade*, *price*, *cost*, *premium* and *subscription* — six of the eight words someone
 * actually types — returned the empty state, in a screen whose search field sits above every
 * group and is the first thing a purposeful user reaches.
 *
 * That was the defect, and it was never a decision: no spec or plan weighed a standing purchase
 * entry point and rejected it. This test is the one that proves the defect is fixed rather than
 * merely moved, which is why it asserts against [PRO_KEYWORDS] itself and not against a fixture.
 *
 * "subscription" is in the list deliberately. It is a *search* term, not a claim: someone who types
 * it must reach the row that tells them there isn't one. No rendered string says the word.
 *
 * Pure JVM: [searchSettings] is a pure function over data classes, like `SettingsSearchTest`.
 */
class ProUpgradeRowSearchTest {

    /** The heading as it ships — folded into the haystack, so it earns matches for every row. */
    private val groupTitle = "LocalKeep Pro"

    private fun groups() = listOf(
        SettingsGroup(
            "Security",
            listOf(SettingsEntry.Expandable("Auto-lock", "", "timeout idle background grace") {}),
        ),
        SettingsGroup(
            groupTitle,
            listOf(
                SettingsEntry.Expandable(
                    title = "LocalKeep Pro",
                    summary = "One payment, no subscription",
                    keywords = PRO_KEYWORDS,
                    content = {},
                    testTag = "settings-pro-upgrade",
                ),
                SettingsEntry.Action(
                    title = "Restore purchases",
                    summary = "Already bought Pro? Re-check with Google Play",
                    keywords = PRO_KEYWORDS,
                    onClick = {},
                ),
            ),
        ),
    )

    @Test
    fun everyWordABuyerTypesReachesTheProGroup() {
        val vocabulary = listOf(
            "buy", "upgrade", "price", "cost", "premium", "subscription", "pay", "restore",
        )
        val missed = vocabulary.filter { term ->
            searchSettings(groups(), term).none { it.title == groupTitle }
        }
        assertTrue(
            missed.isEmpty(),
            "a buyer searching Settings for ${missed.joinToString()} is handed the empty state",
        )
    }

    @Test
    fun theUpgradeRowItselfIsWhatTheSearchFinds() {
        // Not merely "the group survives" — the row that can actually take the money must be the
        // hit. A test satisfied by the restore row would pass on the broken code it replaces.
        listOf("buy", "upgrade", "price").forEach { term ->
            val hits = searchSettings(groups(), term)
                .single { it.title == groupTitle }
                .entries
                .map { it.title }
            assertTrue(
                "LocalKeep Pro" in hits,
                "searching '$term' found $hits, not the upgrade row",
            )
        }
    }

    @Test
    fun searchStillNarrowsRatherThanMatchingEverything() {
        // The vocabulary is wide on purpose, so this guards the other direction: a term belonging
        // to another group must not drag the Pro group along with it.
        val hits = searchSettings(groups(), "fingerprint")
        assertTrue(hits.none { it.title == groupTitle }, "Pro group answered an unrelated search")
    }
}
