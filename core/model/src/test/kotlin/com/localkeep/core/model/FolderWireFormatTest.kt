package com.localkeep.core.model

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The schema-2 → 3 bump, pinned the way the previous one was.
 *
 * `VaultRoundTripTest`'s two golden hashes stay measured at the versions they were taken at; this
 * file carries everything that is new, so a future reader can see what schema 3 added without
 * having to diff a hash that is supposed to never move.
 */
class FolderWireFormatTest {

    private val t0: Instant = Instant.parse("2024-01-01T00:00:00Z")
    private val rootId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f0")
    private val childId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
    private val itemId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000e0")

    private fun vault(folders: List<Folder>, items: List<VaultItem> = emptyList()) =
        Vault(items = items, folders = folders, createdAt = t0, modifiedAt = t0)

    private fun item(
        folderId: UUID?,
        deletedAt: Instant? = null,
        trashedWithFolderId: UUID? = null,
    ) = VaultItem(
        id = itemId,
        title = "Note",
        folderId = folderId,
        createdAt = t0,
        updatedAt = t0,
        deletedAt = deletedAt,
        content = Note("body"),
        trashedWithFolderId = trashedWithFolderId,
    )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    @Test
    fun `schema 3 changed nothing on the wire except the version byte`() {
        // The compatibility claim behind the bump: a vault with no parentUpdatedAt set anywhere
        // must encode to exactly the bytes schema 2 produced, past the version varint. If this
        // drifts, an unrelated field was renumbered or a default started being written.
        val folders = listOf(Folder(rootId, "Root"), Folder(childId, "Child", parentId = rootId))
        val asSchema2 = VaultSerializer.encodeAtVersion(vault(folders), 2)
        val asSchema3 = VaultSerializer.encodeAtVersion(vault(folders), 3)

        assertContentEquals(byteArrayOf(0x08, 0x03), asSchema3.copyOf(2))
        assertContentEquals(asSchema2.drop(2).toByteArray(), asSchema3.drop(2).toByteArray())
    }

    @Test
    fun `schema 4 changed nothing on the wire except the version byte`() {
        // The same claim for the trash bump, and it has to cover an item as well as a folder:
        // schema 4 adds three fields to Folder (6-8) and one to VaultItem (13). All four are
        // nullable and unset on a vault where nothing has been trashed, so with encodeDefaults =
        // false the payload past the version varint must be byte-identical to schema 3's. Pinned to
        // encodeAtVersion(4) rather than encode() so a later bump does not silently rewrite it.
        val folders = listOf(Folder(rootId, "Root"), Folder(childId, "Child", parentId = rootId))
        val items = listOf(item(folderId = rootId))
        val asSchema3 = VaultSerializer.encodeAtVersion(vault(folders, items), 3)
        val asSchema4 = VaultSerializer.encodeAtVersion(vault(folders, items), 4)

        assertContentEquals(byteArrayOf(0x08, 0x04), asSchema4.copyOf(2))
        assertContentEquals(asSchema3.drop(2).toByteArray(), asSchema4.drop(2).toByteArray())
    }

    @Test
    fun `schema 5 changed nothing on the wire except the version byte`() {
        // The concealment bump: schema 5 adds two nullable fields to Folder (9/10), two to VaultItem
        // (14/15) and one to Vault (6). None is set on a vault where nothing has been hidden and no
        // reveal phrase exists, so with encodeDefaults = false the payload past the version varint
        // must be byte-identical to schema 4's.
        //
        // Pinned with encodeAtVersion(5) rather than encode(), which is the change schema 6 forced:
        // this test read `encode()` and asserted the stamp was 5, so it was really two claims —
        // "the payload did not change" and "5 is current" — and the second one turned the next
        // bump into a failure here about folders. The first claim is the one worth keeping.
        val folders = listOf(Folder(rootId, "Root"), Folder(childId, "Child", parentId = rootId))
        val items = listOf(item(folderId = rootId))
        val asSchema4 = VaultSerializer.encodeAtVersion(vault(folders, items), 4)
        val asSchema5 = VaultSerializer.encodeAtVersion(vault(folders, items), 5)

        assertContentEquals(byteArrayOf(0x08, 0x05), asSchema5.copyOf(2))
        assertContentEquals(asSchema4.drop(2).toByteArray(), asSchema5.drop(2).toByteArray())
    }

    @Test
    fun `schema 6 changed nothing on the wire for a vault holding no coordinate`() {
        // The Coordinate bump. Unlike 2-5 it adds no *field* at all — it adds a polymorphic case —
        // so a vault that contains no coordinate item encodes exactly as schema 5 did, and the
        // version varint is the only difference. Pinned with explicit versions so it keeps stating
        // the schema-6 fact after later bumps move `encode()` past 6.
        val folders = listOf(Folder(rootId, "Root"), Folder(childId, "Child", parentId = rootId))
        val items = listOf(item(folderId = rootId))
        val asSchema5 = VaultSerializer.encodeAtVersion(vault(folders, items), 5)
        val asSchema6 = VaultSerializer.encodeAtVersion(vault(folders, items), 6)

        assertContentEquals(byteArrayOf(0x08, 0x06), asSchema6.copyOf(2))
        assertContentEquals(asSchema5.drop(2).toByteArray(), asSchema6.drop(2).toByteArray())
    }

    @Test
    fun `schema 7 changed nothing on the wire for a vault holding no seed phrase`() {
        // The SeedPhrase bump, on the same terms as 6: a new polymorphic case, no field added, so a
        // vault that contains no seed-phrase item encodes exactly as schema 6 did — the version
        // varint is the only difference. Pinned with explicit versions, as the schema-6 test above
        // is, so it keeps stating the schema-7 fact after later bumps move `encode()` past 7; the
        // current-version pin moved to the schema-8 test below when 8 landed.
        val folders = listOf(Folder(rootId, "Root"), Folder(childId, "Child", parentId = rootId))
        val items = listOf(item(folderId = rootId))
        val asSchema6 = VaultSerializer.encodeAtVersion(vault(folders, items), 6)
        val asSchema7 = VaultSerializer.encodeAtVersion(vault(folders, items), 7)

        assertContentEquals(byteArrayOf(0x08, 0x07), asSchema7.copyOf(2))
        assertContentEquals(asSchema6.drop(2).toByteArray(), asSchema7.drop(2).toByteArray())
    }

    @Test
    fun `schema 8 changed nothing on the wire for a file carrying no note`() {
        // The File.notes bump. Unlike 6 and 7 this one really does add a field, so the claim it has
        // to make is the stronger one: `encodeDefaults = false`, so an empty `notes` contributes no
        // bytes and a vault written before the field existed encodes exactly as schema 7 did. If
        // this ever fails, opening an existing vault on this build rewrites it.
        //
        // This also carries the current-version pin, moved here from the schema-7 test deliberately
        // as part of the 7 -> 8 bump: one test asserts what `encode()` stamps, and it is the one
        // guarding the newest bump.
        val folders = listOf(Folder(rootId, "Root"), Folder(childId, "Child", parentId = rootId))
        val items = listOf(item(folderId = rootId))
        val asSchema7 = VaultSerializer.encodeAtVersion(vault(folders, items), 7)
        val asCurrent = VaultSerializer.encode(vault(folders, items))

        assertEquals(8, Vault.CURRENT_SCHEMA_VERSION)
        assertContentEquals(byteArrayOf(0x08, 0x08), asCurrent.copyOf(2))
        assertContentEquals(asSchema7.drop(2).toByteArray(), asCurrent.drop(2).toByteArray())
    }

    @Test
    fun `a trashed subtree round-trips with its markers intact`() {
        // The fields exist to survive a save/load cycle; if any of the three lost its serializer or
        // its number, a restore after a re-lock would find a folder it could no longer identify.
        val folders = listOf(
            Folder(rootId, "Root", deletedAt = t0, trashUpdatedAt = t0, trashRootId = rootId),
            Folder(childId, "Child", parentId = rootId, deletedAt = t0, trashUpdatedAt = t0, trashRootId = rootId),
        )
        val items = listOf(item(folderId = childId, deletedAt = t0, trashedWithFolderId = rootId))
        val decoded = VaultSerializer.decode(VaultSerializer.encode(vault(folders, items)))

        assertEquals(folders, decoded.folders)
        assertEquals(items, decoded.items)
    }

    @Test
    fun `an unset trash marker is absent from the wire, not encoded as a zero`() {
        // encodeDefaults = false again, and it matters more here than for the earlier stamps: a
        // present-but-null trashRootId on every folder would make "null means live" unobservable,
        // and every re-saved vault would grow by three fields per folder for nothing.
        val trashed = VaultSerializer.encode(
            vault(listOf(Folder(rootId, "Root", deletedAt = t0, trashUpdatedAt = t0, trashRootId = rootId))),
        )
        val live = VaultSerializer.encode(vault(listOf(Folder(rootId, "Root"))))
        assertTrue(trashed.size > live.size)
    }

    @Test
    fun `an unset parent timestamp is absent from the wire, not encoded as a zero`() {
        // The same encodeDefaults = false guarantee Folder.updatedAt relies on. A present-but-empty
        // field 5 would grow every re-saved vault and would make "null means unknown" — the rule
        // the whole two-stamp merge rests on — unobservable.
        val stamped = VaultSerializer.encode(vault(listOf(Folder(rootId, "Root", parentUpdatedAt = t0))))
        val unstamped = VaultSerializer.encode(vault(listOf(Folder(rootId, "Root"))))
        assertTrue(stamped.size > unstamped.size)
    }

    @Test
    fun `a folder carrying a parent stamp round-trips byte-identically`() {
        val folders = listOf(
            Folder(rootId, "Root", updatedAt = t0),
            Folder(childId, "Child", parentId = rootId, updatedAt = t0, parentUpdatedAt = t0),
        )
        val encoded = VaultSerializer.encode(vault(folders))
        val decoded = VaultSerializer.decode(encoded)

        assertEquals(folders, decoded.folders)
        assertContentEquals(encoded, VaultSerializer.encode(decoded))
    }

    @Test
    fun `a schema 2 vault decodes with a null parent stamp`() {
        // What every existing install looks like on the first unlock after this build ships: the
        // field is simply not there, and null has to mean "older than any known move", not "moved
        // at the epoch" and not a decode failure.
        val legacy = VaultSerializer.encodeAtVersion(
            vault(listOf(Folder(rootId, "Root", parentId = null, updatedAt = t0))),
            2,
        )
        val decoded = VaultSerializer.decode(legacy)

        assertEquals(2, decoded.schemaVersion)
        assertNull(decoded.folders.single().parentUpdatedAt)
    }

    @Test
    fun `a three-deep tree round-trips`() {
        val grandchildId = UUID.fromString("00000000-0000-0000-0000-0000000000f2")
        val folders = listOf(
            Folder(rootId, "Root"),
            Folder(childId, "Child", parentId = rootId, parentUpdatedAt = t0),
            Folder(grandchildId, "Grandchild", parentId = childId, parentUpdatedAt = t0),
        )
        val decoded = VaultSerializer.decode(VaultSerializer.encode(vault(folders)))

        assertEquals(folders, decoded.folders)
        assertEquals(
            listOf("Root", "Child", "Grandchild"),
            FolderTree.pathOf(decoded.folders, grandchildId)?.map { it.name },
        )
    }
}
