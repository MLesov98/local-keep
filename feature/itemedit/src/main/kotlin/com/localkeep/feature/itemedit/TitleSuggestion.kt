package com.localkeep.feature.itemedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import com.localkeep.core.model.CardBrand
import com.localkeep.core.model.CardBrands
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The name the save step opens with, derived from what the user has already typed.
 *
 * **Why a suggestion at all.** The title stopped being the form's first field on 2026-08-19: it is
 * asked for once, at the end, in [SaveSheet], because at the top of an empty form it is a question
 * about an item that does not exist yet. Moving it there only helps if answering it is cheap —
 * otherwise the cost was not removed, it was relocated to the end, where the user is already
 * finished. So the step opens pre-filled and its Save is live on arrival: accepting is one tap, and
 * the field is a plain editable well for anyone who wants a different word.
 *
 * **It is a suggestion, never a silent default.** The value is visible in an editable field before
 * anything is written. That distinction is what keeps this from being the thing `save()` still
 * refuses — an item titled by the app behind the user's back is one they cannot find again by any
 * name they would think to search for.
 *
 * Every branch but one can return `""`, and that is a legitimate answer. **A login can not** (reversed
 * 2026-10-15). This KDoc used to say that a login with no website and no username had nothing to be
 * named after, and that inventing "Login 3" would be worse than an empty field with the keyboard up.
 * The principal decided the opposite: an empty Title on a form that otherwise needs no name read as
 * a chore, and a visible, editable "Login 1" costs one tap to replace. It stays a suggestion in
 * the sense above - in the well, editable, and replaced by the website or username the moment one is
 * typed - so the argument about silent defaults is untouched. [nextLoginTitle] keeps it from
 * colliding with a name the vault already holds.
 *
 * Pure, and takes its two resource-bearing strings as parameters, so the per-type choice below is a
 * plain JVM test (`TitleSuggestionTest`) rather than something that needs a device to check.
 */
internal fun suggestedTitle(
    state: ItemEditUiState,
    cardLabel: String,
    locationLabel: String,
    // "Login 3" for 3, already localized. A function rather than a string because the number is
    // chosen against the vault's other logins, which only this function sees.
    loginLabel: (Int) -> String,
): String = when (state.type) {
    // The website first, and the *host* of it — a login for a site is called after the site. Falls
    // back to the username, which is what a login with no URL (a router, a safe, a work account)
    // actually has to go on. `firstNotNullOfOrNull` rather than `first`, because a blank first row
    // is legal in the editor and is usually the row the user is about to type into.
    // Neither: "Login N", the smallest N no live login already carries.
    ItemType.LOGIN -> state.uris.firstNotNullOfOrNull { hostOf(it) }
        ?: state.username.takeIf { it.isNotBlank() }
        ?: nextLoginTitle(state.existingLoginTitles, loginLabel)
    // The issuer is the name on the code — "GitHub", not the email address it is bound to. The
    // account is the fallback, since a 2FA entry scanned from a QR with no issuer still has one.
    ItemType.TOTP -> state.totpIssuer.ifBlank { state.totpAccount }
    ItemType.CARD -> cardLabel
    // The first line someone writes in a note is a heading far more often than not, and when it is
    // not, it is still the sentence that says what the note is. Capped so a note written as one
    // long paragraph does not propose a 400-character title.
    ItemType.NOTE -> state.noteBody.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    ItemType.IDENTITY -> "${state.identityFirstName.trim()} ${state.identityLastName.trim()}"
    // Only once there is something to name. A location item with no fix cannot be saved at all
    // (`save()` refuses it), so proposing a date for one would name an item that will not exist.
    ItemType.COORDINATE -> if (state.coordinateFix != null) locationLabel else ""
    // Nothing to suggest, deliberately: a phrase is named after what it opens ("Ledger — main
    // wallet"), which is a fact only the user has, and the one string it must never be named after
    // is any of its own words. The empty field with the keyboard up is the right prompt.
    ItemType.PHRASE -> ""
    // Unreachable via this form: a passkey is never created or renamed here (PasskeyCreator titles
    // it itself, "<name> — <rpId>"), but the `when` must still cover it.
    ItemType.PASSKEY -> ""
}.trim().takeWholeCodePoints(TITLE_SUGGESTION_MAX).trim()

/**
 * The first "Login N" (N = 1, 2, ...) that none of [existingTitles] equals, ignoring case and
 * surrounding space - so "Login 1" and "login 3" taken gives "Login 2", and deleting "Login 1" frees
 * it again. At most `existingTitles.size + 1` labels are tried, so it always ends.
 */
internal fun nextLoginTitle(existingTitles: Set<String>, loginLabel: (Int) -> String): String {
    val taken = existingTitles.mapTo(HashSet()) { it.trim().lowercase() }
    var n = 1
    while (loginLabel(n).trim().lowercase() in taken) n++
    return loginLabel(n)
}

/**
 * 60 characters. Long enough for a full sentence-shaped note heading, short enough that the value
 * still fits one line of the well it lands in — a suggestion the user has to scroll to read is one
 * they cannot check before accepting.
 */
internal const val TITLE_SUGGESTION_MAX = 60

/**
 * [take] by [Char], except it never cuts a surrogate pair in half.
 *
 * A note whose first line runs 59 characters and then an emoji lands the cap exactly between that
 * emoji's two halves, and a plain `take` keeps the high surrogate alone — a replacement box at the
 * end of the value the user is asked to accept. Same defect [lkMonogram] was fixed for on the
 * vault-list tiles, in the other place a title gets cut.
 *
 * Backing off one [Char] is free: the cap is a readability bound, not a storage limit, so 59
 * characters is as good an answer as 60.
 */
private fun String.takeWholeCodePoints(max: Int): String {
    if (length <= max) return this
    return substring(0, if (max > 0 && this[max - 1].isHighSurrogate()) max - 1 else max)
}

/** [suggestedTitle] with this composition's locale and string resources supplied. */
@Composable
internal fun rememberSuggestedTitle(state: ItemEditUiState): String {
    // Detected rather than read off `state.cardBrand`: that field is only written at save and on
    // import, so on a new card it is still UNKNOWN at exactly the moment this runs.
    val digits = state.cardNumber.filter(Char::isDigit)
    val brand = CardBrands.detect(state.cardNumber).takeIf { it != CardBrand.UNKNOWN }
    val last4 = digits.takeLast(4)
    val cardLabel = when {
        last4.length < 4 -> brand?.displayName().orEmpty()
        brand != null -> stringResource(R.string.itemedit_title_suggestion_card, brand.displayName(), last4)
        else -> stringResource(R.string.itemedit_title_suggestion_card_no_brand, last4)
    }
    val locationLabel = state.coordinateFix?.let { fix ->
        val locale = Locale.forLanguageTag(ComposeLocale.current.toLanguageTag())
        val stamp = locationTitleStamp(fix.capturedAt, locale, ZoneId.systemDefault())
        stringResource(R.string.itemedit_title_suggestion_location, stamp)
    }.orEmpty()
    // Resolved lazily because the number is not known until the vault's other logins are compared.
    // `resources` is read at composition and the lambda is remembered per instance, the same shape
    // FolderScreen uses for its "copy N" suffix; `stringResource` cannot be called from a lambda.
    val resources = LocalContext.current.resources
    val loginLabel: (Int) -> String = remember(resources) {
        { n -> resources.getString(R.string.itemedit_title_suggestion_login, n) }
    }
    return suggestedTitle(state, cardLabel, locationLabel, loginLabel)
}

/**
 * The date *and* time a location suggestion is named after — "2 Sep 2026, 09:14".
 *
 * Time included, reversing the 2026-08-19 date-only choice. The argument then was that a title is
 * a name rather than a timestamp; the trouble is that a location has no other name to give. Two
 * spots saved on the same day — the car, then the locker — both came out as "…, 2 Sep 2026", and
 * the save sheet's own subtitle says the name is all search and the list have to go on. The minute
 * is what tells them apart, so it stays. The rest of the name is the category noun, resolved by the
 * caller from `itemedit_title_suggestion_location`.
 *
 * Localized through the platform formatter, for the same reason `coordinateCapturedCaption` is —
 * a date here is read by a person and by nothing else. Pure, so `TitleSuggestionTest` can pin the
 * minute being present.
 */
internal fun locationTitleStamp(capturedAt: Instant, locale: Locale, zone: ZoneId): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone)
        .format(capturedAt)
