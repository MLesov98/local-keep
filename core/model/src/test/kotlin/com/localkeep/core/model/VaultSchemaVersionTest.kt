package com.localkeep.core.model

import com.localkeep.core.common.Outcome
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The schema version has to actually gate something, or bumping it is decoration.
 *
 * The failure it prevents is silent and permanent: protobuf drops fields it does not recognise, so
 * a build that happily opened a newer vault would decode it minus those fields and write the
 * truncated version back on the next save. Nothing would report an error at any point.
 */
class VaultSchemaVersionTest {

    private val now = Instant.parse("2026-07-25T00:00:00Z")

    private val liveFolder = Folder(
        id = UUID.fromString("00000000-0000-0000-0000-0000000000f0"),
        name = "Work",
    )

    // The version passed to Vault() no longer decides anything on the wire — encode() stamps
    // CURRENT_SCHEMA_VERSION and encodeAtVersion() takes the version as an argument — so the
    // fixture carries only its contents.
    private fun vault(folders: List<Folder> = emptyList()) = Vault(
        folders = folders,
        createdAt = now,
        modifiedAt = now,
    )

    @Test
    fun `a vault written by this build round-trips`() {
        val decoded = VaultSerializer.decode(VaultSerializer.encode(vault()))
        assertEquals(Vault.CURRENT_SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun `an older but still supported vault opens`() {
        val decoded = VaultSerializer.decode(
            VaultSerializer.encodeAtVersion(vault(), Vault.MIN_SUPPORTED_SCHEMA_VERSION),
        )
        assertEquals(Vault.MIN_SUPPORTED_SCHEMA_VERSION, decoded.schemaVersion)
    }

    @Test
    fun `saving an older vault re-stamps it, so a rollback refuses it instead of truncating it`() {
        // The one that matters. Nothing used to write schemaVersion at all: it was a constructor
        // default that every mutation copied through, so a vault stamped 3 stayed stamped 3 no
        // matter what this build wrote into it.
        //
        // That is fine while every unknown field is also an unneeded one, and stops being fine at
        // schema 4. Trash a folder on a schema-4 build, and fields 6-8 go into a payload still
        // stamped 3. Roll the APK back: the old gate reads 3 <= 3 and opens it, protobuf drops the
        // fields it does not know, the folder decodes live, and the next save writes that
        // truncation back. The user's trash silently un-happens and nothing reports an error.
        //
        // Stamping on save turns that into the refusal the gate was built to give.
        val loadedFromDisk = VaultSerializer.decode(
            VaultSerializer.encodeAtVersion(vault(folders = listOf(liveFolder)), 3),
        )
        assertEquals(3, loadedFromDisk.schemaVersion, "fixture must really be an old vault")

        val trashed = loadedFromDisk.copy(
            folders = listOf(liveFolder.copy(deletedAt = now, trashUpdatedAt = now, trashRootId = liveFolder.id)),
        )
        val saved = VaultSerializer.decode(VaultSerializer.encode(trashed))

        assertEquals(Vault.CURRENT_SCHEMA_VERSION, saved.schemaVersion)
        // What the schema-3 build's gate would now do with these bytes, spelled out rather than
        // implied: its CURRENT_SCHEMA_VERSION was 3, and this payload declares 4.
        assertTrue(
            saved.schemaVersion > 3,
            "a payload carrying fields a schema-3 build cannot represent must not pass its version gate",
        )
    }

    @Test
    fun `a vault from a newer build is refused rather than silently truncated`() {
        val bytes = VaultSerializer.encodeAtVersion(vault(), Vault.CURRENT_SCHEMA_VERSION + 1)
        val result = VaultSerializer.decodeCatching(bytes)
        assertTrue(result is Outcome.Failure, "a newer schema must not open read-write")
        assertTrue(
            "newer version" in result.error.message,
            "the message must tell the user to update, not read as corruption; got: ${result.error.message}",
        )
    }

    /**
     * The refusal has to be *identifiable*, not merely worded well.
     *
     * `decodeCatching` is not the path an unlock takes. `VaultLockManager.tryPasswordSlot` calls
     * `VaultFileCodec.decryptRegion` and catches `Exception`, so while this refusal was a plain
     * `IllegalArgumentException` it was indistinguishable from a corrupt payload — and the unlock
     * screen answered a healthy newer vault with "the vault file couldn't be read... try restoring
     * a backup from Settings", the single instruction that destroys it. Matching on the message
     * text was never an option: the message is translated at the UI and this string is not.
     */
    @Test
    fun `the newer-build refusal is its own exception type`() {
        val bytes = VaultSerializer.encodeAtVersion(vault(), Vault.CURRENT_SCHEMA_VERSION + 1)
        val failure = assertFailsWith<VaultSchemaTooNewException> {
            VaultSerializer.decode(bytes)
        }
        assertEquals(Vault.CURRENT_SCHEMA_VERSION + 1, failure.stamped)
        assertEquals(Vault.CURRENT_SCHEMA_VERSION, failure.supported)
    }

    /**
     * The other two refusals must NOT be the too-new type, or the unlock path would tell a user
     * with a genuinely unreadable file to go and update the app.
     */
    @Test
    fun `an unreadable payload is not reported as a newer build`() {
        assertFalse(
            assertFailsWith<Exception> {
                VaultSerializer.decode(byteArrayOf(0x08, 0x01, 0x7F, 0x7F, 0x7F))
            } is VaultSchemaTooNewException,
            "a corrupt payload must stay distinguishable from one written by a newer build",
        )
    }
}
