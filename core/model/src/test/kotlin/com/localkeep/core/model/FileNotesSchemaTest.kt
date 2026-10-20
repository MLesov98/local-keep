package com.localkeep.core.model

import com.localkeep.core.common.Outcome
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The schema-8 half of [File.notes]: that it survives a save, and — the part this bump actually
 * has to prove — that a vault written at schema 7 opens on this build completely unchanged.
 *
 * 4C-11, 2026-09-21. Every vault sitting on a phone today is a schema-7 one, so "the migration
 * path" for this bump is the absence of a migration: an absent field decodes as `""`, which is
 * exactly "no note was ever written", and nothing is rewritten or moved. The version stamp still
 * moves, for the reason bumps 4 and 5 moved it — an older build would drop field 8 silently and
 * write the truncation back — and that refusal is pinned at the bottom of this file.
 *
 * Separate from [VaultRoundTripTest] for the reason [CoordinateSchemaTest] and
 * [SeedPhraseSchemaTest] are: that fixture is pinned by golden wire hashes and must not gain a
 * field.
 */
class FileNotesSchemaTest {

    private val t0 = Instant.parse("2026-09-21T08:00:00Z")

    private fun vault(vararg items: VaultItem) = Vault(
        items = items.toList(),
        createdAt = t0,
        modifiedAt = t0,
    )

    private fun item(content: ItemContent, title: String = "Passport scan") = VaultItem(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000f1"),
        title = title,
        createdAt = t0,
        updatedAt = t0,
        content = content,
    )

    /** What a schema-7 build could write: every field of [File] except the new one. */
    private val legacyFile = File(
        filename = "passport.jpg",
        mime = "image/jpeg",
        sizeBytes = 918_273L,
        blobRef = "blob-7f0c",
        thumbnailRef = "thumb-7f0c",
        capturedInApp = true,
        removedFromGallery = true,
    )

    @Test
    fun `a version-7 vault opens unchanged, and its file reads as having no note`() {
        val original = vault(item(legacyFile))

        // encodeAtVersion, not encode: this is a payload this build would never write, which is the
        // whole point — it is the one sitting on the user's phone right now.
        val decoded = VaultSerializer.decode(VaultSerializer.encodeAtVersion(original, 7))

        assertEquals(
            7,
            decoded.schemaVersion,
            "decoding must not silently restamp; the upgrade happens on the next save, not on open",
        )
        assertEquals(original.copy(schemaVersion = 7), decoded, "a schema-7 vault must open unchanged")
        val file = decoded.items.single().content as File
        assertEquals("", file.notes, "an absent note must read as no note, never as null or a default word")
        // Every field a schema-7 build did write is still exactly what it wrote.
        assertEquals(legacyFile, file)
    }

    @Test
    fun `saving a version-7 vault stamps 8 and adds nothing to the file`() {
        val asSchema7 = VaultSerializer.encodeAtVersion(vault(item(legacyFile)), 7)

        val reSaved = VaultSerializer.encode(VaultSerializer.decode(asSchema7))
        val decoded = VaultSerializer.decode(reSaved)

        assertEquals(8, decoded.schemaVersion)
        assertEquals(legacyFile, decoded.items.single().content as File)
        // The bytes after the version varint are identical, because `encodeDefaults = false` means
        // an empty note contributes nothing. A save that grew the payload here would mean opening
        // an existing vault rewrote the item.
        assertEquals(
            asSchema7.drop(2).toList(),
            reSaved.drop(2).toList(),
            "re-saving a schema-7 vault must change only the version byte",
        )
    }

    @Test
    fun `a file's note round-trips verbatim`() {
        val noted = legacyFile.copy(notes = "Renewal due March 2029 — the office copy is the older one.")

        val decoded = VaultSerializer.decode(VaultSerializer.encode(vault(item(noted))))

        val file = decoded.items.single().content as File
        assertEquals("Renewal due March 2029 — the office copy is the older one.", file.notes)
        assertEquals(noted, file)
        assertEquals(8, decoded.schemaVersion)
    }

    @Test
    fun `a newer vault is refused rather than opened and truncated`() {
        val ahead = VaultSerializer.encodeAtVersion(
            vault(item(legacyFile.copy(notes = "from the future"))),
            Vault.CURRENT_SCHEMA_VERSION + 1,
        )

        val result = VaultSerializer.decodeCatching(ahead)

        assertTrue(result is Outcome.Failure, "a newer schema must not open read-write")
        assertTrue(
            "newer version" in result.error.message,
            "the refusal must tell the user to update; got: ${result.error.message}",
        )
    }
}
