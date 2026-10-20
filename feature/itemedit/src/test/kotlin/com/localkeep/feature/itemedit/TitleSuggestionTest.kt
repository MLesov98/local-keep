package com.localkeep.feature.itemedit

import com.localkeep.core.model.Coordinate
import com.localkeep.feature.itemedit.ItemEditUiState.ItemType
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the naming step opens with, per type.
 *
 * The title left the top of the form on 2026-08-19 and became the last thing a new item is asked
 * for. That trade only pays if the answer is usually already there — a step whose field is empty
 * has moved the typing to the end rather than removed it — so these pin the one thing that makes
 * the new ordering cheaper than the old one, and they pin the empty cases too: a suggestion that
 * invents a name for an item with nothing in it would be worse than a blank field, because the
 * user would accept it and never find the item again.
 *
 * The two resource-bearing branches (card, location) are passed in already formatted, which is why
 * this is a plain JVM test rather than something needing a device.
 */
class TitleSuggestionTest {

    private fun suggest(state: ItemEditUiState) = suggestedTitle(state, CARD, LOCATION, LOGIN)

    @Test
    fun aLoginIsNamedAfterItsSiteAndFallsBackToTheUsername() {
        assertEquals(
            "github.com",
            suggest(ItemEditUiState(type = ItemType.LOGIN, uris = listOf("https://github.com/login"))),
        )
        // A blank first row is legal in the editor — it is the row about to be typed into — so the
        // host is looked for across the list rather than only at index 0.
        assertEquals(
            "example.org",
            suggest(ItemEditUiState(type = ItemType.LOGIN, uris = listOf("", "example.org"))),
        )
        // No website at all: a router, a safe, a work account. The username is what such a login
        // has to go on.
        assertEquals(
            "admin",
            suggest(ItemEditUiState(type = ItemType.LOGIN, username = "admin")),
        )
    }

    @Test
    fun aCodeIsNamedAfterItsIssuerNotTheAccountItIsBoundTo() {
        assertEquals(
            "GitHub",
            suggest(
                ItemEditUiState(
                    type = ItemType.TOTP,
                    totpIssuer = "GitHub",
                    totpAccount = "me@example.com",
                ),
            ),
        )
        // Scanned from a QR that carried no issuer — which is common enough that falling back to
        // nothing would leave the field empty on a real code.
        assertEquals(
            "me@example.com",
            suggest(ItemEditUiState(type = ItemType.TOTP, totpAccount = "me@example.com")),
        )
    }

    @Test
    fun aNoteIsNamedAfterItsFirstWrittenLine() {
        assertEquals(
            "Safe deposit box",
            suggest(
                ItemEditUiState(
                    type = ItemType.NOTE,
                    noteBody = "\n\n  Safe deposit box  \nBranch on Oak Street\n",
                ),
            ),
        )
    }

    @Test
    fun aLongFirstLineIsCutToSomethingThatFitsTheWell() {
        val body = "x".repeat(200)
        assertEquals(TITLE_SUGGESTION_MAX, suggest(ItemEditUiState(type = ItemType.NOTE, noteBody = body)).length)
    }

    @Test
    fun anIdentityIsNamedAfterThePersonAndNeverAfterHalfOfOne() {
        assertEquals(
            "Ada Lovelace",
            suggest(
                ItemEditUiState(
                    type = ItemType.IDENTITY,
                    identityFirstName = "Ada",
                    identityLastName = "Lovelace",
                ),
            ),
        )
        // Only a surname so far. The join must not leave a leading space, which would be a title
        // that sorts before every other row in the vault list.
        assertEquals(
            "Lovelace",
            suggest(ItemEditUiState(type = ItemType.IDENTITY, identityLastName = "Lovelace")),
        )
    }

    @Test
    fun aLocationIsNamedOnlyOnceThereIsAReadingToName() {
        assertEquals("", suggest(ItemEditUiState(type = ItemType.COORDINATE)))
        assertEquals(
            LOCATION,
            suggest(
                ItemEditUiState(
                    type = ItemType.COORDINATE,
                    coordinateFix = Coordinate(
                        latitude = 42.1354,
                        longitude = 24.7453,
                        capturedAt = Instant.parse("2026-08-19T09:14:00Z"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun aLocationSuggestionCarriesTheMinuteSoTwoSameDaySpotsGetDifferentNames() {
        // Date-only until 2026-09-02, when the car and the locker saved on the same afternoon both
        // came out as "…, 2 Sep 2026". A location has no other name to give, so the minute is what
        // tells two of them apart.
        val stamp = locationTitleStamp(
            Instant.parse("2026-09-02T09:14:00Z"),
            Locale.UK,
            ZoneOffset.UTC,
        )
        assertTrue(stamp.contains("09:14"), "no minute in $stamp")
        assertTrue(stamp.contains("2026"), "no date in $stamp")
    }

    @Test
    fun anItemWithNothingInItGetsNoSuggestionAtAllExceptALogin() {
        // Every type, so a branch added later cannot quietly start inventing names. An empty field
        // with the keyboard up is the correct answer for all of them but one: a login falls back to
        // "Login N" (principal's decision, 2026-10-15; the KDoc on `suggestedTitle` records it).
        assertEquals(
            listOf(ItemType.LOGIN),
            ItemType.entries
                .map { ItemEditUiState(type = it) }
                .filter {
                    suggestedTitle(it, cardLabel = "", locationLabel = LOCATION, loginLabel = LOGIN).isNotEmpty()
                }
                .map { it.type },
        )
    }

    @Test
    fun aLoginWithNoSiteAndNoUsernameIsNamedLoginN() {
        assertEquals("Login 1", suggest(ItemEditUiState(type = ItemType.LOGIN)))
        // Blank is not a username: a space the user typed must not win over the fallback.
        assertEquals("Login 1", suggest(ItemEditUiState(type = ItemType.LOGIN, username = "  ", uris = listOf(""))))
    }

    @Test
    fun theHostBeatsTheUsernameBeatsLoginN() {
        val taken = setOf("Login 1")
        assertEquals(
            "github.com",
            suggest(
                ItemEditUiState(
                    type = ItemType.LOGIN,
                    uris = listOf("https://github.com"),
                    username = "ada",
                    existingLoginTitles = taken,
                ),
            ),
        )
        assertEquals(
            "ada",
            suggest(ItemEditUiState(type = ItemType.LOGIN, username = "ada", existingLoginTitles = taken)),
        )
        assertEquals(
            "Login 2",
            suggest(ItemEditUiState(type = ItemType.LOGIN, existingLoginTitles = taken)),
        )
    }

    @Test
    fun loginNFillsTheSmallestGapAndIgnoresCaseAndSpace() {
        fun next(vararg titles: String) = suggest(
            ItemEditUiState(type = ItemType.LOGIN, existingLoginTitles = titles.toSet()),
        )
        assertEquals("Login 1", next("Login 2", "Login 3", "Bank"))
        assertEquals("Login 2", next("Login 1"))
        assertEquals("Login 2", next("Login 1", "Login 3"))
        assertEquals("Login 4", next("Login 1", "login 2", "  Login 3  "))
        // Only an exact "Login N" counts: a title that merely starts with it does not take N.
        assertEquals("Login 1", next("Login 1 backup", "Login"))
    }

    @Test
    fun theLoginLabelIsTheCallersToFormat() {
        // The number is chosen here and the words are not: a French device gets its own noun, and
        // the collision check compares against titles written in that same noun.
        assertEquals(
            "Identifiant 2",
            suggestedTitle(
                ItemEditUiState(type = ItemType.LOGIN, existingLoginTitles = setOf("Identifiant 1")),
                CARD,
                LOCATION,
            ) { "Identifiant $it" },
        )
    }

    /** The control for the dirty check: a title the user changed is not forgiven. */
    @Test
    fun theSuggestedTitleIsForgivenByTheDiscardCheckOnlyWhileUntouched() {
        // The title the form is compared against: the form's own while it is still the suggestion,
        // so an untouched "Login 1" equals itself; the pristine one (empty) once it is anything else,
        // so a typed "Bank" differs from it.
        assertEquals("Login 1", titleAfterForgiving("Login 1", applied = "Login 1", pristine = ""))
        assertEquals("", titleAfterForgiving("Bank", applied = "Login 1", pristine = ""))
        assertEquals("", titleAfterForgiving("Login 1", applied = null, pristine = ""))
    }

    @Test
    fun aCapReachedMidEmojiDoesNotLeaveHalfOfIt() {
        // 59 ASCII characters and then an emoji, so the 60-character cap falls exactly between the
        // surrogate pair's two halves. `take(60)` keeps the high surrogate and drops its partner,
        // and the suggestion the user is asked to accept opens with a replacement box — the same
        // defect `lkMonogram` was fixed for on the vault-list tiles.
        val note = "x".repeat(59) + "\uD83C\uDF89"
        val suggested = suggest(ItemEditUiState(type = ItemType.NOTE, noteBody = note))

        assertEquals("x".repeat(59), suggested)
        assertTrue(
            suggested.none { it.isHighSurrogate() || it.isLowSurrogate() },
            "suggestion ends in half a surrogate pair: $suggested",
        )
    }

    @Test
    fun anEmojiThatFitsWholeIsKept() {
        // The control: the same truncation must not start trimming text that was never too long.
        // Without this, "return the first 59 characters always" would pass the test above.
        val note = "x".repeat(58) + "\uD83C\uDF89"
        assertEquals(note, suggest(ItemEditUiState(type = ItemType.NOTE, noteBody = note)))
    }

    /**
     * Passkeys never reach this form (F40: they are read-only, created by the credential provider),
     * so this pins the `when` branch existing at all rather than any real naming behaviour.
     */
    @Test
    fun aPasskeySuggestsNothing() {
        assertEquals("", suggest(ItemEditUiState(type = ItemType.PASSKEY)))
    }

    private companion object {
        const val CARD = "Visa •••• 4821"
        const val LOCATION = "Location, 19 Aug 2026, 09:14"
        val LOGIN: (Int) -> String = { "Login $it" }
    }
}
