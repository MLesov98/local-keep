package com.localkeep.autofill

import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What an accepted autofill save does to the vault: capture, dedup, and which login it replaces.
 *
 * Three defects, all found 2026-10-02 and all past the point where the user had already tapped the
 * platform's "Save to LocalKeep?":
 *  - a form with no recognised username (a reset or change-password page, a phone-number sign-up)
 *    was dropped as "nothing to confirm", and a password LocalKeep had just generated for it was
 *    then stored nowhere;
 *  - a change-password form's Current box could be what got saved, and nothing used it to find
 *    the login whose password was changing;
 *  - a login saved for `example.com` was overwritten by a sign-up on `community.example.com`
 *    with the same email, because the parent-domain match counted as "the same login".
 */
class AutofillSaveDecisionTest {

    private fun item(username: String, password: String, vararg uris: String): Pair<VaultItem, Login> {
        val login = Login(username = username, password = password, uris = uris.map { UriMatch(uri = it) })
        return VaultItem(
            id = UUID.randomUUID(),
            title = uris.firstOrNull().orEmpty(),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            content = login,
        ) to login
    }

    // ---- capture --------------------------------------------------------------------------------

    @Test
    fun `a password-only form is captured with an empty username instead of dropped`() {
        val extracted = SaveHandler.extractedFrom(
            values = mapOf(FieldType.Password to "Gen3rated-Pa55word!"),
            currentPassword = null,
            webDomain = "example.com",
            packageName = "com.android.chrome",
        )

        assertNotNull(extracted, "an accepted save with no username field was dropped")
        assertEquals("", extracted.username)
        assertEquals("Gen3rated-Pa55word!", extracted.password)
    }

    @Test
    fun `a form with no password is still nothing to save`() {
        assertNull(
            SaveHandler.extractedFrom(
                values = mapOf(FieldType.Username to "me@example.com"),
                currentPassword = null,
                webDomain = "example.com",
                packageName = "com.android.chrome",
            ),
        )
    }

    @Test
    fun `a checkout without a username still goes to the card path`() {
        // The control for the relaxed rule: a card number plus a password-looking box and no
        // username was a card save before, and must not become a nameless login now.
        assertNull(
            SaveHandler.extractedFrom(
                values = mapOf(
                    FieldType.CardNumber to "4111111111111111",
                    FieldType.Password to "something",
                ),
                currentPassword = null,
                webDomain = "shop.example",
                packageName = "com.android.chrome",
            ),
        )
    }

    @Test
    fun `the new password is saved, not the Current one, and the Current one is kept apart`() {
        val extracted = SaveHandler.extractedFrom(
            values = mapOf(FieldType.Password to "the-new-one"),
            currentPassword = "the-old-one",
            webDomain = "example.com",
            packageName = "com.android.chrome",
        )

        assertNotNull(extracted)
        assertEquals("the-new-one", extracted.password)
        assertEquals("the-old-one", extracted.currentPassword)
    }

    @Test
    fun `a sign-in box declared current-password is still what gets saved`() {
        val extracted = SaveHandler.extractedFrom(
            values = mapOf(FieldType.Username to "me@example.com"),
            currentPassword = "only-box",
            webDomain = "example.com",
            packageName = "com.android.chrome",
        )

        assertNotNull(extracted)
        assertEquals("only-box", extracted.password)
        assertNull(extracted.currentPassword)
    }

    // ---- dedup ----------------------------------------------------------------------------------

    @Test
    fun `a password-only sign-in whose password is already stored is not offered again`() {
        val matches = listOf(item("me@example.com", "stored", "example.com"))

        assertTrue(SaveHandler.isAlreadyStored(matches, username = "", password = "stored"))
        assertFalse(SaveHandler.isAlreadyStored(matches, username = "", password = "rotated"))
    }

    @Test
    fun `with a username, the username still has to match`() {
        val matches = listOf(item("me@example.com", "stored", "example.com"))

        assertFalse(SaveHandler.isAlreadyStored(matches, username = "other@example.com", password = "stored"))
        assertTrue(SaveHandler.isAlreadyStored(matches, username = "me@example.com", password = "stored"))
    }

    // ---- which login is replaced -----------------------------------------------------------------

    @Test
    fun `a change-password form with no username updates the login its Current box names`() {
        val mine = item("me@example.com", "the-old-one", "example.com")
        val other = item("other@example.com", "something-else", "example.com")

        val candidate = SaveHandler.updateCandidate(
            matches = listOf(other, mine),
            webDomain = "example.com",
            username = "",
            currentPassword = "the-old-one",
        )

        assertSame(mine, candidate)
    }

    @Test
    fun `a password-only form with nothing to go on is a new login`() {
        val candidate = SaveHandler.updateCandidate(
            matches = listOf(item("me@example.com", "stored", "example.com")),
            webDomain = "example.com",
            username = "",
            currentPassword = null,
        )

        assertNull(candidate)
    }

    @Test
    fun `a sign-up on a subdomain does not replace the parent domain's login`() {
        val parent = item("me@x.com", "main-account", "example.com")

        assertNull(
            SaveHandler.updateCandidate(
                matches = listOf(parent),
                webDomain = "community.example.com",
                username = "me@x.com",
            ),
            "the forum sign-up overwrote the main account's password",
        )
    }

    @Test
    fun `the same site still updates, www or not`() {
        val stored = item("me@x.com", "old", "example.com")

        assertSame(
            stored,
            SaveHandler.updateCandidate(listOf(stored), webDomain = "example.com", username = "me@x.com"),
        )
        assertSame(
            stored,
            SaveHandler.updateCandidate(listOf(stored), webDomain = "www.example.com", username = "me@x.com"),
        )
        val savedOnSubdomain = item("me@x.com", "old", "https://community.example.com/login")
        assertSame(
            savedOnSubdomain,
            SaveHandler.updateCandidate(
                listOf(savedOnSubdomain),
                webDomain = "community.example.com",
                username = "me@x.com",
            ),
        )
    }

    @Test
    fun `an app save matched by package is unaffected by the domain rule`() {
        val app = item("me@x.com", "old")

        assertSame(app, SaveHandler.updateCandidate(listOf(app), webDomain = null, username = "me@x.com"))
    }
}
