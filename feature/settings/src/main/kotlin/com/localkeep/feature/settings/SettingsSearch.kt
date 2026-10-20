package com.localkeep.feature.settings

import androidx.compose.runtime.Composable

/**
 * One row of settings.
 *
 * Every entry is a **row** first: a title, one line of summary, and an affordance. What varies is
 * what the row does, which is what the three subtypes name. This shape exists because the previous
 * one had a single kind — a card with a title and a paragraph, all of them expanded at once — and
 * eighteen of those is a page of prose with the controls buried in it. A settings screen is read by
 * scanning for one thing, so the default state of every entry is now "one line", and the detail is
 * a tap away for the few people who want it.
 *
 * [keywords] carries the words a user would actually type that don't appear in [title]: someone
 * looking for "dark mode" will never type "Appearance", and someone worried about a stolen phone
 * types "steal" or "coercion", not "duress". Without them, search only finds settings by the name
 * the developer happened to choose, which is the wrong way round.
 */
sealed class SettingsEntry {
    abstract val title: String
    abstract val summary: String
    abstract val keywords: String

    /**
     * A row that does something when tapped — opens a screen, opens system settings, runs an action.
     *
     * These carried a paragraph and a button before. The paragraph explained what the button would
     * do and the button said the same thing again, so the row is the whole thing now.
     */
    class Action(
        override val title: String,
        override val summary: String,
        override val keywords: String = "",
        val onClick: () -> Unit,
        val destructive: Boolean = false,
        /**
         * True when this row's summary reports a standing risk rather than describing the setting.
         *
         * Distinct from [destructive], which is about what the row DOES when tapped; this is about
         * what the summary SAYS is currently true. The Recovery Kit row is destructive in neither
         * sense and yet carries the most consequential sentence on the screen.
         */
        val warning: Boolean = false,
        /** Set when the row leaves the app, so the glyph can say so. */
        val external: Boolean = false,
        /**
         * A stable, language-independent handle for the locale sweep and the device pass — see
         * `Modifier.lkTag`. Null for the rows nothing automated has to find; the tag is exposed to
         * the accessibility tree in debuggable builds only.
         */
        val testTag: String? = null,
    ) : SettingsEntry()

    /** A row that opens inline to reveal controls. Closed until asked for. */
    class Expandable(
        override val title: String,
        override val summary: String,
        override val keywords: String = "",
        /**
         * A stable, language-independent handle for the locale sweep and the device pass — see
         * `Modifier.lkTag`. Null for the rows nothing automated has to find; the tag is exposed to
         * the accessibility tree in debuggable builds only.
         *
         * Declared *before* [content] and not after it. Every other call site passes the body as a
         * trailing lambda, so a parameter added below [content] would silently capture that lambda
         * — the compiler reports it as "argument type mismatch: '() -> Unit' but 'String?' was
         * expected" at each of the twenty-odd call sites, not here where the cause is. Defaulting
         * it is not enough; position is what keeps those call sites compiling untouched.
         */
        val testTag: String? = null,
        val content: @Composable () -> Unit,
    ) : SettingsEntry()

    /**
     * A row that draws itself — a switch, or a title with a value on the right.
     *
     * [summary] is still declared, because search reads it even when the row renders its own.
     */
    class Custom(
        override val title: String,
        override val summary: String = "",
        override val keywords: String = "",
        val content: @Composable (first: Boolean) -> Unit,
    ) : SettingsEntry()
}

/** A titled group of entries — the spec's Security / Vault & Backup / Autofill / Appearance / About. */
class SettingsGroup(
    val title: String,
    val entries: List<SettingsEntry>,
)

/**
 * Filters [groups] to what matches [query], dropping groups left with nothing.
 *
 * Matching is per *entry*, not per group: "Security" holds five unrelated things, and a search for
 * "biometric" that returned the whole section would be no better than scrolling. A group heading
 * survives only because one of its children did.
 */
fun searchSettings(
    groups: List<SettingsGroup>,
    query: String,
): List<SettingsGroup> {
    val needle = query.trim()
    if (needle.isEmpty()) return groups
    return groups.mapNotNull { group ->
        val hits = group.entries.filter { it.matches(needle, group.title) }
        if (hits.isEmpty()) null else SettingsGroup(group.title, hits)
    }
}

/**
 * An entry matches if every whitespace-separated term appears somewhere in its title, summary,
 * keywords or group name. AND rather than OR: "lock timeout" should narrow to the auto-lock entry,
 * not widen to everything mentioning either word.
 */
private fun SettingsEntry.matches(
    query: String,
    groupTitle: String,
): Boolean {
    val haystack = "$title $summary $keywords $groupTitle".lowercase()
    return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in haystack }
}
