package com.localkeep.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payment-card capture via the autofill save path (2026-07-29).
 *
 * Context for why this route and no other: Google Wallet, Samsung Wallet and Apple Wallet all
 * refuse third-party reads of a stored card — the Google Pay API returns a payment *token*, never
 * the PAN — Chrome does not offer its saved payment methods to a third-party autofill service, and
 * no browser exports card numbers. Capturing the card as the user types it into a checkout form is
 * the only route any platform leaves open.
 *
 * These cover the parts reachable without an `AssistStructure`, which needs an instrumented
 * environment: the [FieldType] contract and the wipe discipline on captured card data.
 */
class CardFieldTypeTest {

    @Test
    fun `the four card members report as card and nothing else does`() {
        assertTrue(FieldType.CardNumber.isCard)
        assertTrue(FieldType.CardExpiry.isCard)
        assertTrue(FieldType.CardSecurityCode.isCard)
        assertTrue(FieldType.CardHolder.isCard)

        // `isCard` drives which fields become SaveInfo optional ids and which get collected into a
        // card payload. A login field leaking in here would put a password in a Card item.
        assertFalse(FieldType.Username.isCard)
        assertFalse(FieldType.Password.isCard)
        assertFalse(FieldType.Totp.isCard)
        assertFalse(FieldType.Url.isCard)
    }

    @Test
    fun `a captured card wipes its number and security code`() {
        val card = PendingSaveStore.Card(
            number = "4242424242424242".toCharArray(),
            securityCode = "123".toCharArray(),
            holder = "A Person",
            expiry = "12/28",
        )

        card.wipe()

        assertTrue("the PAN survived the wipe", card.number.all { it.code == 0 })
        assertTrue("the security code survived the wipe", card.securityCode.all { it.code == 0 })
    }

    @Test
    fun `the wipe uses NUL rather than spaces`() {
        // A space-filled array still reads as plausible data in a heap dump, and shipping a
        // space-fill is a mistake this codebase has made before (KdbxImporter).
        val card = PendingSaveStore.Card(
            number = "4242424242424242".toCharArray(),
            securityCode = "123".toCharArray(),
            holder = "",
            expiry = "",
        )
        card.wipe()

        assertFalse("a space-fill is not a wipe", card.number.any { it == ' ' })
        assertTrue(card.number.all { it.code == 0 })
    }

    @Test
    fun `discarding a pending save wipes the card, not just the password`() {
        // A dismissed save used to wipe only the password, which was complete while a login was
        // the only thing an entry could hold. A captured PAN left behind would outlive the prompt
        // the user had just said no to.
        val card = PendingSaveStore.Card(
            number = "4242424242424242".toCharArray(),
            securityCode = "999".toCharArray(),
            holder = "",
            expiry = "",
        )
        // The store stamps and expires entries on `SystemClock.elapsedRealtime()`, which is not
        // mocked in plain JVM unit tests. Nothing here turns on time, so a fixed reading will do.
        val previousClock = PendingSaveStore.elapsedRealtimeMs
        PendingSaveStore.elapsedRealtimeMs = { 30_000L }
        val token = try {
            PendingSaveStore.put(
                PendingSaveStore.Entry(
                    username = "",
                    password = CharArray(0),
                    webDomain = "shop.example",
                    packageName = null,
                    existingItemId = null,
                    card = card,
                ),
            )
        } finally {
            PendingSaveStore.elapsedRealtimeMs = previousClock
        }

        PendingSaveStore.discard(token)

        assertTrue("a discarded card left its PAN in memory", card.number.all { it.code == 0 })
        assertTrue(card.securityCode.all { it.code == 0 })
    }

    @Test
    fun `two cards holding the same digits are equal`() {
        // The generated data-class members compare CharArray by identity, which would make a card
        // unequal to its own copy. Nothing depends on this today; the override is here so nothing
        // can quietly come to depend on the broken version.
        val a = PendingSaveStore.Card("4242".toCharArray(), "123".toCharArray(), "A", "12/28")
        val b = PendingSaveStore.Card("4242".toCharArray(), "123".toCharArray(), "A", "12/28")

        assertTrue(a == b)
        assertTrue(a.hashCode() == b.hashCode())
    }

    @Test
    fun `cards differing only in their number are not equal`() {
        val a = PendingSaveStore.Card("4242".toCharArray(), "123".toCharArray(), "A", "12/28")
        val b = PendingSaveStore.Card("4243".toCharArray(), "123".toCharArray(), "A", "12/28")

        assertFalse(a == b)
    }
}
