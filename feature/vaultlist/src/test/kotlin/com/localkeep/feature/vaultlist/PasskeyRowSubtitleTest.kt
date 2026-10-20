package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A passkey row names its account, the way a Login row names its username.
 *
 * 2C-F13, on a device 2026-09-20: a vault holding three `Passkey Test — example.test` items showed
 * three rows reading the same title over the same bare type word "Passkey", with nothing on any of
 * them saying which was which. Every other type puts an identifying field on the subtitle line; the
 * passkey branch ignored its content entirely.
 *
 * 2026-09-21: the creation date follows the account, so several passkeys minted for the *same*
 * account read differently too — the credential provider's sheet entries carry the same date since
 * package J of the release-sweep fix wave, and the vault list should agree with the sheet. It still
 * does not touch how the *system* credential sheet collapses identical entries, which is the platform's.
 */
class PasskeyRowSubtitleTest {

    private val fallbacks = mapOf(
        "login" to "Login", "totp" to "Two-factor code", "card" to "Card", "identity" to "Identity",
        "note" to "Secure note", "file" to "File", "passkey" to "Passkey",
        "coordinate" to "Location", "phrase" to "Recovery phrase",
    )

    private fun subtitleOf(content: ItemContent): String = VaultItem(
        id = UUID.randomUUID(),
        title = "Passkey Test — example.test",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = content,
    ).subtitle(
        locale = Locale.US,
        zone = ZoneOffset.UTC,
        loginFallback = fallbacks.getValue("login"),
        totpFallback = fallbacks.getValue("totp"),
        cardFallback = fallbacks.getValue("card"),
        identityFallback = fallbacks.getValue("identity"),
        noteText = fallbacks.getValue("note"),
        fileText = fallbacks.getValue("file"),
        passkeyText = fallbacks.getValue("passkey"),
        coordinateText = fallbacks.getValue("coordinate"),
        phraseText = fallbacks.getValue("phrase"),
    )

    private fun passkey(userName: String, createdAt: Instant = Instant.EPOCH) = Passkey(
        rpId = "example.test",
        createdAt = createdAt,
        userName = userName,
    )

    @Test
    fun `the subtitle is the account, not the type word`() {
        assertEquals("passkey@example.test · Jan 1, 1970", subtitleOf(passkey("passkey@example.test")))
    }

    @Test
    fun `two passkeys for different accounts on one site read differently`() {
        // The case the row exists to answer, and the one it could not answer at all before.
        assertEquals("alice@example.test · Jan 1, 1970", subtitleOf(passkey("alice@example.test")))
        assertEquals("bob@example.test · Jan 1, 1970", subtitleOf(passkey("bob@example.test")))
    }

    @Test
    fun `two passkeys for the same account on different days read differently`() {
        val a = subtitleOf(passkey("alice@example.test", Instant.EPOCH))
        val b = subtitleOf(passkey("alice@example.test", Instant.EPOCH.plusSeconds(86_400)))
        assertEquals("alice@example.test · Jan 2, 1970", b)
        assert(a != b) { "same account, different day, identical rows again: $a" }
    }

    @Test
    fun `a passkey with no account falls back to the type word`() {
        assertEquals("Passkey · Jan 1, 1970", subtitleOf(passkey("")))
        assertEquals("Passkey · Jan 1, 1970", subtitleOf(passkey("   ")))
    }

    @Test
    fun `a coordinate still withholds its content`() {
        // The control, and the line this change must not cross: a Location's identifying field *is*
        // the secret, and rows are read over shoulders and announced while scrolling. An account
        // name is the same class of field as a Login's username, which the row has always shown.
        assertEquals("Location", subtitleOf(Coordinate(latitude = 51.5, longitude = -0.12, capturedAt = Instant.EPOCH)))
    }
}
