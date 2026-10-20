package com.localkeep.core.model

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schema 5 contract for presentation concealment: the nullable lease/stamp pairs on [VaultItem] and
 * [Folder], the vault-level [RevealPhraseVerifier], and the guarantees a schema bump has to keep —
 * old vaults decode with everything null, the indefinite sentinel survives the millisecond wire, and
 * a verifier compares by content rather than array identity.
 */
class HiddenSchemaTest {
    private val t0 = Instant.ofEpochMilli(1_700_000_000_000L)
    private val t1 = Instant.ofEpochMilli(1_700_000_100_000L)
    private val itemId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val folderId = UUID.fromString("00000000-0000-0000-0000-0000000000f0")

    private fun verifier(
        salt: ByteArray = ByteArray(16) { it.toByte() },
        memoryKib: Int = 19_456,
        iterations: Int = 2,
        parallelism: Int = 1,
        hash: ByteArray = ByteArray(32) { (it + 1).toByte() },
    ) = RevealPhraseVerifier(salt, memoryKib, iterations, parallelism, hash)

    @Test
    fun `a hidden item and folder round-trip their lease and stamp`() {
        val vault = Vault(
            folders = listOf(
                Folder(folderId, "Secret", hiddenUntil = t1, hiddenUpdatedAt = t0),
            ),
            items = listOf(
                VaultItem(
                    id = itemId,
                    createdAt = t0,
                    updatedAt = t0,
                    content = Note("body"),
                    hiddenUntil = HiddenLease.INDEFINITE,
                    hiddenUpdatedAt = t0,
                ),
            ),
            createdAt = t0,
            modifiedAt = t1,
            revealPhrase = verifier(),
        )

        val decoded = VaultSerializer.decode(VaultSerializer.encode(vault))

        assertEquals(t1, decoded.folders.single().hiddenUntil)
        assertEquals(t0, decoded.folders.single().hiddenUpdatedAt)
        assertEquals(HiddenLease.INDEFINITE, decoded.items.single().hiddenUntil)
        assertEquals(t0, decoded.items.single().hiddenUpdatedAt)
        assertEquals(vault.revealPhrase, decoded.revealPhrase)
    }

    @Test
    fun `a schema-4 vault decodes with every concealment field null`() {
        // What every existing install looks like on the first unlock after schema 5 ships: none of
        // fields 14/15 (item), 9/10 (folder) or 6 (vault) is on the wire, and each must default to
        // null rather than a decode failure or an all-zero lease.
        val legacy = VaultSerializer.encodeAtVersion(
            Vault(
                folders = listOf(Folder(folderId, "Root")),
                items = listOf(VaultItem(id = itemId, createdAt = t0, updatedAt = t0, content = Note("n"))),
                createdAt = t0,
                modifiedAt = t1,
            ),
            4,
        )

        val decoded = VaultSerializer.decode(legacy)

        assertEquals(4, decoded.schemaVersion)
        assertNull(decoded.items.single().hiddenUntil)
        assertNull(decoded.items.single().hiddenUpdatedAt)
        assertNull(decoded.folders.single().hiddenUntil)
        assertNull(decoded.folders.single().hiddenUpdatedAt)
        assertNull(decoded.revealPhrase)
    }

    @Test
    fun `the indefinite sentinel survives millisecond serialization exactly`() {
        // InstantSerializer stores toEpochMilli(); INDEFINITE is whole-second so it round-trips with
        // no drift. A sentinel that decoded to a slightly different instant would silently stop being
        // "forever" after one save.
        assertEquals(
            HiddenLease.INDEFINITE,
            Instant.ofEpochMilli(HiddenLease.INDEFINITE.toEpochMilli()),
        )
    }

    @Test
    fun `an unset lease is absent from the wire, not encoded as a zero`() {
        // encodeDefaults = false: a present-but-null hiddenUntil on every item would grow every
        // re-saved vault and make "null means visible" unobservable.
        val hidden = VaultSerializer.encode(
            Vault(
                items = listOf(
                    VaultItem(id = itemId, createdAt = t0, updatedAt = t0, content = Note("n"), hiddenUntil = t1, hiddenUpdatedAt = t0),
                ),
                createdAt = t0,
                modifiedAt = t1,
            ),
        )
        val live = VaultSerializer.encode(
            Vault(
                items = listOf(VaultItem(id = itemId, createdAt = t0, updatedAt = t0, content = Note("n"))),
                createdAt = t0,
                modifiedAt = t1,
            ),
        )
        assertTrue(hidden.size > live.size)
    }

    @Test
    fun `a custom lease converts a positive amount and rejects the rest`() {
        val now = t1
        assertEquals(now.plusSeconds(3600), HiddenLease.customLeaseOrNull(now, 60, HiddenDurationUnit.MINUTES))
        assertEquals(now.plus(java.time.Duration.ofDays(2)), HiddenLease.customLeaseOrNull(now, 2, HiddenDurationUnit.DAYS))
        assertNull(HiddenLease.customLeaseOrNull(now, 0, HiddenDurationUnit.HOURS), "zero is not a lease")
        assertNull(HiddenLease.customLeaseOrNull(now, -5, HiddenDurationUnit.HOURS), "negative is not a lease")
        assertNull(
            HiddenLease.customLeaseOrNull(now, 3_654, HiddenDurationUnit.DAYS),
            "past MAX_CUSTOM (3653 days) is rejected",
        )
        assertNull(
            HiddenLease.customLeaseOrNull(now, Long.MAX_VALUE, HiddenDurationUnit.DAYS),
            "overflow is rejected, not thrown",
        )
        assertEquals(
            now.plus(HiddenLease.MAX_CUSTOM),
            HiddenLease.customLeaseOrNull(now, 3_653, HiddenDurationUnit.DAYS),
            "exactly MAX_CUSTOM is allowed",
        )
    }

    @Test
    fun `isHidden treats the expiry boundary as the visible side`() {
        val now = t1
        assertTrue(HiddenLease.isHidden(now.plusMillis(1), now), "until > now is hidden")
        assertFalse(HiddenLease.isHidden(now, now), "until == now is visible")
        assertFalse(HiddenLease.isHidden(now.minusMillis(1), now), "expired is visible")
        assertFalse(HiddenLease.isHidden(null, now), "no lease is visible")
    }

    @Test
    fun `two verifiers with equal contents are equal and hash equally`() {
        val a = verifier()
        val b = verifier()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `verifiers differing in any byte or parameter are unequal`() {
        val base = verifier()
        assertFalse(base == verifier(salt = ByteArray(16) { 9 }))
        assertFalse(base == verifier(hash = ByteArray(32) { 9 }))
        assertFalse(base == verifier(memoryKib = 65_536))
        assertFalse(base == verifier(iterations = 3))
        assertFalse(base == verifier(parallelism = 2))
    }

    @Test
    fun `a verifier validates only in-range parameters and correct byte sizes`() {
        assertTrue(verifier().isValid)
        assertFalse(verifier(salt = ByteArray(15)).isValid, "short salt")
        assertFalse(verifier(hash = ByteArray(31)).isValid, "short hash")
        assertFalse(verifier(memoryKib = 1_024).isValid, "below floor memory")
        assertFalse(verifier(memoryKib = 4_000_000).isValid, "oversized memory would OOM the KDF")
        assertFalse(verifier(iterations = 1).isValid, "below floor iterations")
        assertFalse(verifier(parallelism = 0).isValid, "zero parallelism")
    }
}
