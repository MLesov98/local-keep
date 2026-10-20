package com.localkeep.core.import

import com.localkeep.core.model.Card
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Note
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Importing the same export twice used to double every card, identity, note, 2FA code and seed
 * phrase in it, because only `Login` carried an identity for dedup and everything else was
 * "always appended". Found on a device 2026-09-14: a Bitwarden export with 12 logins, a note, two
 * cards and two identities merged its logins cleanly on the second pass and added the other five
 * again — the preview even said so ("5 items imported; 12 merged"), which is how it was noticed.
 *
 * The rule now: a non-login item has an identity when its content has a natural one — a card's
 * number, a 2FA seed, an identity document's number, a note's or seed phrase's exact contents. Two
 * different cards, or two notes that differ by a character, still both land.
 */
class ImportMergerNonLoginIdentityTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun item(title: String, content: ItemContent, updatedAt: Instant = base) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = base,
        updatedAt = updatedAt,
        content = content,
    )

    private fun sameExportTwice(): List<VaultItem> = listOf(
        item("Visa", Card(holder = "Ada Lovelace", number = "4111 1111 1111 1111", cvv = "123")),
        item("Passport", Identity(firstName = "Ada", lastName = "Lovelace", passport = "P1234567")),
        item("Wifi", Note(body = "SSID: home\npass: hunter2")),
        item("GitHub", Totp(issuer = "GitHub", account = "ada", secretBase32 = "JBSWY3DPEHPK3PXP")),
        item("Cold wallet", SeedPhrase(words = List(12) { "abandon" }, passphrase = "")),
    )

    @Test
    fun reimportingTheSameExportAddsNothingASecondTime() {
        val first = ImportMerger.merge(existing = emptyList(), imported = sameExportTwice())
        assertEquals(5, first.size)

        val plan = ImportMerger.plan(existing = first, imported = sameExportTwice())
        assertEquals(
            "every row must be recognised as already in the vault",
            List(5) { ImportMerger.Disposition.ALREADY_CURRENT },
            plan.map { it.disposition },
        )
        assertEquals(List(5) { true }, plan.map { it.collidesWithVault })

        val second = ImportMerger.merge(existing = first, imported = sameExportTwice())
        assertEquals("the second pass must not append duplicates", 5, second.size)
    }

    /** Control: a genuinely different item of the same type is still added. */
    @Test
    fun differentContentOfTheSameTypeIsStillNew() {
        val existing = ImportMerger.merge(existing = emptyList(), imported = sameExportTwice())
        val different = listOf(
            item("Visa", Card(holder = "Ada Lovelace", number = "5555 5555 5555 4444", cvv = "999")),
            item("Passport", Identity(firstName = "Ada", lastName = "Lovelace", passport = "P7654321")),
            item("Wifi", Note(body = "SSID: home\npass: hunter3")),
            item("GitHub", Totp(issuer = "GitHub", account = "ada", secretBase32 = "GEZDGNBVGY3TQOJQ")),
            item("Cold wallet", SeedPhrase(words = List(12) { "zoo" })),
        )
        val plan = ImportMerger.plan(existing = existing, imported = different)
        assertEquals(List(5) { ImportMerger.Disposition.NEW }, plan.map { it.disposition })
        assertEquals(10, ImportMerger.merge(existing = existing, imported = different).size)
    }

    /** A newer copy of the same card updates the vault's copy in place rather than sitting beside it. */
    @Test
    fun newerCopyOfTheSameCardUpdatesInPlace() {
        val old = item("Visa", Card(holder = "Ada", number = "4111111111111111", expMonth = 1, expYear = 2027))
        val newer = item(
            "Visa",
            Card(holder = "Ada Lovelace", number = "4111-1111-1111-1111", expMonth = 12, expYear = 2030),
            updatedAt = base.plusSeconds(60),
        )
        val plan = ImportMerger.plan(existing = listOf(old), imported = listOf(newer))
        assertEquals(ImportMerger.Disposition.UPDATES_EXISTING, plan.single().disposition)
        val merged = ImportMerger.merge(existing = listOf(old), imported = listOf(newer))
        assertEquals(1, merged.size)
        assertEquals(old.id, merged.single().id)
        assertEquals(2030, (merged.single().content as Card).expYear)
    }

    /** Cards without a usable number, and blank notes, carry no identity and stay additive. */
    @Test
    fun contentWithoutANaturalIdentityStaysAdditive() {
        val blank = listOf(item("Card", Card()), item("Note", Note()), item("2FA", Totp()))
        val once = ImportMerger.merge(existing = emptyList(), imported = blank)
        assertEquals(6, ImportMerger.merge(existing = once, imported = blank).size)
    }

    /**
     * V3-03. A File item is the biggest thing a vault holds, and it was the last content type with
     * no identity — so re-importing a `.1pux` merged its logins, cards, notes and identities and
     * added every attachment again.
     */
    @Test
    fun reimportingTheSameFileItemAddsNothingASecondTime() {
        val export = listOf(
            item("KeePassXC Logo", File(filename = "keepassxc.png", mime = "image/png", sizeBytes = 8_923L)),
        )
        val first = ImportMerger.merge(existing = emptyList(), imported = export)
        assertEquals(1, first.size)

        val plan = ImportMerger.plan(existing = first, imported = export)
        assertEquals(ImportMerger.Disposition.ALREADY_CURRENT, plan.single().disposition)
        assertEquals(true, plan.single().collidesWithVault)
        assertEquals("the second pass must not append the file again", 1, ImportMerger.merge(first, export).size)
    }

    /**
     * Control for the key's parts. A different name, a different byte count or a different type is a
     * different file — the identity may not collapse three unrelated attachments into one.
     */
    @Test
    fun filesThatDifferInNameSizeOrTypeStayDistinct() {
        val base = File(filename = "keepassxc.png", mime = "image/png", sizeBytes = 8_923L)
        val export = listOf(
            item("Logo", base),
            item("Logo", base.copy(filename = "keepassxc-dark.png")),
            item("Logo", base.copy(sizeBytes = 8_924L)),
            item("Logo", base.copy(mime = "image/webp")),
        )
        assertEquals(4, ImportMerger.merge(existing = emptyList(), imported = export).size)
    }

    /** A file with no name, or none of its bytes read, carries no identity and stays additive. */
    @Test
    fun fileWithoutANameOrASizeStaysAdditive() {
        val blank = listOf(
            item("No name", File(filename = "", mime = "image/png", sizeBytes = 12L)),
            item("No bytes", File(filename = "empty.bin", mime = "application/octet-stream")),
        )
        val once = ImportMerger.merge(existing = emptyList(), imported = blank)
        assertEquals(4, ImportMerger.merge(existing = once, imported = blank).size)
    }

    /**
     * `blobRef` is minted per import and is empty during the preview, so it must not be part of the
     * key — the vault's copy and the incoming one never share it, and a key including it would
     * de-duplicate nothing while looking as though it did.
     */
    @Test
    fun aFileIsRecognisedEvenThoughItsBlobRefDiffers() {
        val stored = item(
            "KeePassXC Logo",
            File(filename = "keepassxc.png", mime = "image/png", sizeBytes = 8_923L, blobRef = "blob-a"),
        )
        val incoming = item(
            "KeePassXC Logo",
            File(filename = "keepassxc.png", mime = "image/png", sizeBytes = 8_923L, blobRef = ""),
        )
        assertEquals(1, ImportMerger.merge(existing = listOf(stored), imported = listOf(incoming)).size)
    }

    /** One seed enrolled under two accounts is two entries, on the first import and on the second. */
    @Test
    fun theSameSeedUnderDifferentAccountsStaysTwoEntries() {
        val export = listOf(
            item("GitHub", Totp(issuer = "GitHub", account = "ada", secretBase32 = "JBSWY3DPEHPK3PXP")),
            item("Amazon", Totp(issuer = "Amazon", account = "ada@amazon", secretBase32 = "JBSWY3DPEHPK3PXP")),
        )
        val first = ImportMerger.merge(existing = emptyList(), imported = export)
        assertEquals(2, first.size)
        assertEquals(2, ImportMerger.merge(existing = first, imported = export).size)
    }
}
