package com.localkeep.core.model

import com.localkeep.core.common.Outcome
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The schema-6 half of the coordinate item: what survives a save, what is refused on the way back
 * in, and what an older build does when handed a vault it cannot represent.
 *
 * Deliberately separate from [VaultRoundTripTest], whose `completeVault()` is measured by two
 * golden wire hashes. Adding an item to that fixture would have moved both, and a moved golden hash
 * is exactly as invisible whether it records an intended change or an accidental renumbering.
 */
class CoordinateSchemaTest {

    private val t0 = Instant.parse("2026-08-19T09:15:00Z")
    private val capturedAt = Instant.parse("2026-08-19T09:14:58Z")

    private fun vault(vararg items: VaultItem) = Vault(
        items = items.toList(),
        createdAt = t0,
        modifiedAt = t0,
    )

    private fun item(content: ItemContent, title: String = "Where I parked") = VaultItem(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000c0"),
        title = title,
        createdAt = t0,
        updatedAt = t0,
        content = content,
    )

    private val fix = Coordinate(
        latitude = 42.135_098,
        longitude = 24.745_129,
        capturedAt = capturedAt,
        accuracyMeters = 8.5f,
    )

    @Test
    fun `a coordinate item round-trips losslessly`() {
        val original = vault(item(fix))

        val decoded = VaultSerializer.decode(VaultSerializer.encode(original))

        assertEquals(original.copy(schemaVersion = Vault.CURRENT_SCHEMA_VERSION), decoded)
        // Named individually as well as compared whole: an equals() that passed while the numbers
        // had been rounded through a Float somewhere would be the one failure this test exists for.
        val restored = decoded.items.single().content as Coordinate
        assertEquals(42.135_098, restored.latitude)
        assertEquals(24.745_129, restored.longitude)
        assertEquals(capturedAt, restored.capturedAt)
        assertEquals(8.5f, restored.accuracyMeters)
    }

    @Test
    fun `an absent accuracy stays absent rather than becoming zero`() {
        // Zero metres is a claim of perfect precision, which is the opposite of "we do not know".
        val decoded = VaultSerializer.decode(
            VaultSerializer.encode(vault(item(fix.copy(accuracyMeters = null)))),
        )

        assertNull((decoded.items.single().content as Coordinate).accuracyMeters)
    }

    @Test
    fun `a schema-5 vault still opens, and the next save stamps the current version`() {
        // The migration, stated as the two things it has to do: read what is already on disk, and
        // hand the version gate something an older build will refuse rather than truncate.
        val onDisk = VaultSerializer.encodeAtVersion(vault(item(Note("existing"))), 5)

        val opened = VaultSerializer.decode(onDisk)
        assertEquals(5, opened.schemaVersion)
        assertEquals(Note("existing"), opened.items.single().content)

        val resaved = VaultSerializer.decode(VaultSerializer.encode(opened))
        assertEquals(Vault.CURRENT_SCHEMA_VERSION, resaved.schemaVersion)
    }

    @Test
    fun `a newer vault is refused before the polymorphic decode can throw about it`() {
        // The reason schema 6 needed a preflight at all. Up to schema 5 every bump added optional
        // fields, so an old build decoded a new vault into a complete object minus a few nulls and
        // the version gate — which ran after the decode — got to speak. A new sealed subtype fails
        // *inside* the decoder instead, and the user would be shown a serialization error naming a
        // serial name they have never heard of.
        //
        // Simulated in the only direction this build can: bytes stamped one past current. The gate
        // must fire on the stamp alone, with the payload never handed to the polymorphic decoder.
        val ahead = VaultSerializer.encodeAtVersion(vault(item(fix)), Vault.CURRENT_SCHEMA_VERSION + 1)

        val result = VaultSerializer.decodeCatching(ahead)

        assertTrue(result is Outcome.Failure, "a newer schema must not open read-write")
        assertTrue(
            "newer version" in result.error.message,
            "the refusal must tell the user to update; got: ${result.error.message}",
        )
    }

    @Test
    fun `the version preflight reads the stamp without decoding the payload`() {
        assertEquals(Vault.CURRENT_SCHEMA_VERSION, VaultSerializer.peekSchemaVersion(VaultSerializer.encode(vault(item(fix)))))
        assertEquals(1, VaultSerializer.peekSchemaVersion(VaultSerializer.encodeAtVersion(vault(), 1)))
        // Returns null rather than guessing, so anything it cannot read falls through to the real
        // decoder and the gate that already existed. Refusing early is its job; accepting early is
        // not.
        assertNull(VaultSerializer.peekSchemaVersion(ByteArray(0)))
        assertNull(VaultSerializer.peekSchemaVersion(byteArrayOf(0x12, 0x02)))
        assertNull(VaultSerializer.peekSchemaVersion(byteArrayOf(0x08, 0xFF.toByte())))
    }

    @Test
    fun `values a detail screen could not honestly render are invalid`() {
        assertTrue(fix.isValid())
        assertTrue(fix.copy(accuracyMeters = null).isValid())
        assertTrue(fix.copy(latitude = -90.0, longitude = 180.0).isValid())

        assertFalse(fix.copy(latitude = Double.NaN).isValid())
        assertFalse(fix.copy(longitude = Double.NaN).isValid())
        assertFalse(fix.copy(latitude = Double.POSITIVE_INFINITY).isValid())
        assertFalse(fix.copy(longitude = Double.NEGATIVE_INFINITY).isValid())
        assertFalse(fix.copy(latitude = 90.000_001).isValid())
        assertFalse(fix.copy(latitude = -90.000_001).isValid())
        assertFalse(fix.copy(longitude = 180.000_001).isValid())
        assertFalse(fix.copy(longitude = -180.000_001).isValid())
        assertFalse(fix.copy(accuracyMeters = -1f).isValid())
        assertFalse(fix.copy(accuracyMeters = Float.NaN).isValid())
    }

    @Test
    fun `an invalid coordinate that reached the vault is rejected on the way back out`() {
        // A decrypted vault is authenticated as *ours*, which says nothing about the values in it:
        // a merge, a past build or a hand-built fixture can all put a NaN there. Decoding succeeds —
        // it is a well-formed Double — so validity has to be asked as a question, and this is the
        // boundary that asks it.
        val decoded = VaultSerializer.decode(
            VaultSerializer.encode(vault(item(fix.copy(latitude = Double.NaN)))),
        )

        assertFalse((decoded.items.single().content as Coordinate).isValid())
    }

    @Test
    fun `a coordinate never prints its position`() {
        // The whole reason this type overrides a generated toString. Exception messages, Log calls,
        // crash reports and Compose tooling all reach for toString() on an object they were handed,
        // and none of them names the field, so none of them is a call site anyone would audit.
        val printed = fix.toString()

        assertEquals("Coordinate(redacted)", printed)
        listOf("42.135", "24.745", "8.5", capturedAt.toString()).forEach { secret ->
            assertFalse(secret in printed, "toString leaked $secret")
        }
        // The item that holds it is a data class too, and its own toString prints its content.
        assertFalse("42.135" in item(fix).toString(), "VaultItem.toString leaked the position")
    }

    @Test
    fun `redaction does not cost equality, which the merge depends on`() {
        assertEquals(fix, fix.copy())
        assertEquals(fix.hashCode(), fix.copy().hashCode())
        assertFalse(fix == fix.copy(latitude = 0.0))
    }

    @Test
    fun `a merge treats a coordinate as an opaque payload, with no invented identity`() {
        // Coordinate is deliberately not a case in ItemMerge.preserveContent: a Login's union of
        // secrets exists because an importer carries a subset of the fields the vault already has,
        // and a coordinate has no subset — it is one snapshot or another. The winner's payload
        // wins whole, which is the behaviour, not the absence of one.
        val older = item(fix).copy(updatedAt = t0)
        val newerFix = fix.copy(latitude = 1.0, longitude = 2.0, capturedAt = t0)
        val newer = item(newerFix).copy(updatedAt = t0.plusSeconds(60))

        assertEquals(newerFix, ItemMerge.preserveStructure(winner = newer, loser = older).content)
        assertEquals(fix, ItemMerge.preserveStructure(winner = older, loser = newer).content)
    }
}
