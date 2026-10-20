package com.localkeep.core.model

import com.localkeep.core.common.Outcome
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The schema-7 half of the Recovery-phrase item: what survives a save, that the words never appear
 * in a `toString`, and what an older build does when handed a vault carrying one.
 *
 * Separate from [VaultRoundTripTest] for the same reason [CoordinateSchemaTest] is — that fixture is
 * pinned by golden wire hashes and must not gain a field.
 */
class SeedPhraseSchemaTest {

    private val t0 = Instant.parse("2026-08-21T09:15:00Z")

    private fun vault(vararg items: VaultItem) = Vault(
        items = items.toList(),
        createdAt = t0,
        modifiedAt = t0,
    )

    private fun item(content: ItemContent, title: String = "Ledger — main wallet") = VaultItem(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000f0"),
        title = title,
        createdAt = t0,
        updatedAt = t0,
        content = content,
    )

    // A canonical, checksum-valid 12-word phrase (the official all-zero-entropy vector) — the model
    // stores whatever words it is given; validity is the add flow's job, not the codec's.
    private val phrase = SeedPhrase(
        words = List(11) { "abandon" } + "about",
        passphrase = "correct horse",
    )

    @Test
    fun `a seed-phrase item round-trips losslessly`() {
        val original = vault(item(phrase))

        val decoded = VaultSerializer.decode(VaultSerializer.encode(original))

        assertEquals(original.copy(schemaVersion = Vault.CURRENT_SCHEMA_VERSION), decoded)
        val restored = decoded.items.single().content as SeedPhrase
        assertEquals(List(11) { "abandon" } + "about", restored.words)
        assertEquals("correct horse", restored.passphrase)
        assertEquals(12, restored.wordCount)
    }

    @Test
    fun `an empty passphrase round-trips as empty, and word count follows the list`() {
        val decoded = VaultSerializer.decode(
            VaultSerializer.encode(vault(item(phrase.copy(passphrase = "", words = List(24) { "abandon" })))),
        )
        val restored = decoded.items.single().content as SeedPhrase
        assertEquals("", restored.passphrase)
        assertEquals(24, restored.wordCount)
    }

    /**
     * 4C-14, 2026-09-20: the editor now saves a phrase of real BIP-39 words whose checksum does not
     * verify, with a coral warning instead of a refusal. **No vault-format change was needed or
     * made** — [SeedPhrase] stores `words` and `passphrase` and has never held a verdict about
     * them, so a checksum-invalid phrase is the same wire shape as any other and an older build
     * reads it back without knowing the difference. This test is what pins that: the schema version
     * must not move, and the words must come back exactly as entered rather than being normalised,
     * dropped or repaired on the way through.
     */
    @Test
    fun `a checksum-invalid phrase round-trips unchanged, at the same schema version`() {
        // Twelve real BIP-39 words; the twelfth encodes no valid checksum over the other eleven.
        val broken = phrase.copy(words = List(12) { "abandon" })
        val original = vault(item(broken))

        val decoded = VaultSerializer.decode(VaultSerializer.encode(original))

        assertEquals(
            Vault.CURRENT_SCHEMA_VERSION,
            decoded.schemaVersion,
            "storing a checksum-invalid phrase must not bump the vault format",
        )
        val restored = decoded.items.single().content as SeedPhrase
        assertEquals(List(12) { "abandon" }, restored.words, "the words must survive verbatim")
        assertEquals("correct horse", restored.passphrase)
        assertEquals(12, restored.wordCount)
    }

    @Test
    fun `a newer vault is refused before the polymorphic decode can throw about it`() {
        val ahead = VaultSerializer.encodeAtVersion(vault(item(phrase)), Vault.CURRENT_SCHEMA_VERSION + 1)

        val result = VaultSerializer.decodeCatching(ahead)

        assertTrue(result is Outcome.Failure, "a newer schema must not open read-write")
        assertTrue(
            "newer version" in result.error.message,
            "the refusal must tell the user to update; got: ${result.error.message}",
        )
    }

    @Test
    fun `a seed phrase never prints its words or passphrase`() {
        val printed = phrase.toString()

        assertEquals("SeedPhrase(redacted)", printed)
        listOf("abandon", "about", "correct horse").forEach { secret ->
            assertFalse(secret in printed, "toString leaked $secret")
        }
        // The item that holds it is a data class too; its generated toString prints its content.
        assertFalse("abandon" in item(phrase).toString(), "VaultItem.toString leaked the words")
    }

    @Test
    fun `redaction does not cost equality, which the merge depends on`() {
        assertEquals(phrase, phrase.copy())
        assertEquals(phrase.hashCode(), phrase.copy().hashCode())
        assertFalse(phrase == phrase.copy(passphrase = "other"))
        assertFalse(phrase == phrase.copy(words = List(12) { "zoo" }))
    }

    @Test
    fun `a merge treats a seed phrase as an opaque payload, with no invented identity`() {
        val older = item(phrase).copy(updatedAt = t0)
        val newerPhrase = phrase.copy(words = List(12) { "zoo" }, passphrase = "")
        val newer = item(newerPhrase).copy(updatedAt = t0.plusSeconds(60))

        assertEquals(newerPhrase, ItemMerge.preserveStructure(winner = newer, loser = older).content)
        assertEquals(phrase, ItemMerge.preserveStructure(winner = older, loser = newer).content)
    }
}
