package com.localkeep.core.common

import java.text.Collator
import java.util.Locale

/**
 * The one comparator every human-visible name list in the app is ordered by.
 *
 * Every one of these lists used to sort on `name.lowercase(Locale.ROOT)`, which is a code-point
 * sort: it orders by UTF-16 value, so every accented letter lands after the whole unaccented Latin
 * alphabet. `café` is unaffected — its leading character is plain ASCII — but `école` sorts past
 * `zoo`, and in French, Spanish, Portuguese and German that is simply the wrong place. All four are
 * shipped locales.
 *
 * [Collator] orders by the locale's own collation rules instead, so `école` falls between `dossier`
 * and `finance` where a reader expects it. Shared rather than repeated because the folder tree is
 * drawn by five different screens — the vault list, the folder manager, the two editors' pickers and
 * the bulk-move sheet — and a tree that ordered its children differently in the picker than in the
 * list would read as a different tree.
 *
 * [Collator.SECONDARY] preserves the case-insensitivity the `lowercase()` call provided: accent
 * differences still separate two names, case differences no longer do. The default TERTIARY strength
 * would have quietly reintroduced case as a sort key.
 *
 * The locale defaults to [Locale.getDefault], which on Android follows the user's app/system
 * language — the same language the names are being read in. A collator is not thread-safe, so each
 * call builds its own; use one per sort rather than hoisting it to a shared field.
 */
fun displayNameComparator(locale: Locale = Locale.getDefault()): Comparator<String> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    return Comparator { a, b -> collator.compare(a, b) }
}

/**
 * Sorts by a name the user reads, under [displayNameComparator].
 *
 * Stable across equal keys — two names the collator cannot separate keep the order they arrived in,
 * which is what the callers that sort a vault's folders depend on for a steady list.
 */
fun <T> Iterable<T>.sortedByDisplayName(selector: (T) -> String): List<T> =
    sortedWith(compareBy(displayNameComparator(), selector))
