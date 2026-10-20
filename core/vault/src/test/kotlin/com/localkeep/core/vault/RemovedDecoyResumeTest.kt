package com.localkeep.core.vault

import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finishing an interrupted decoy sweep must never strip a decoy the user still has.
 *
 * `removeDuress` commits the live write and then walks the stale containers one at a time. Killed
 * at file 3 of 9 it leaves some clean and some still carrying the decoy, and nothing retried it —
 * the trigger was a one-shot user action (fixed 2026-09-13).
 *
 * **Two retries were designed and rejected before this one, and the controls below are the reason.**
 * Both read as correct and both destroy data:
 *
 * 1. **Sweep unconditionally at unlock.** `sweepRemovedDecoy` recognises a container holding a decoy
 *    as "region 0 authenticates under the real key, region 1 does not" — which is *identical* to
 *    every backup of a vault whose duress vault is configured right now. That retry erases a live,
 *    wanted decoy from `vault.enc.bak` and all five snapshots.
 * 2. **Gate it on the live container saying duress is off.** That excludes case 1 and walks into a
 *    second: the live file also says "off" after the user restores a **pre-duress snapshot** over a
 *    vault that had duress. The pass would then strip the decoys out of every remaining snapshot,
 *    destroying a setup nobody removed.
 *
 * What ships requires **both** signals — a journal written by an actual removal, and a live
 * container that still reflects it. `a configured decoy survives` and `a restored pre-duress vault
 * does not trigger` below are the two rejected designs, written as tests.
 */
class RemovedDecoyResumeTest {
    private val dek = EnvelopeCrypto.generateDek()
    private val encKey = VaultSubkeys.encKey(dek)
    private val decoyDek = EnvelopeCrypto.generateDek()
    private val decoyKey = VaultSubkeys.encKey(decoyDek)
    private val dir: File = Files.createTempDirectory("removed-decoy-resume").toFile()

    @AfterTest
    fun cleanUp() {
        Zeroizer.wipe(dek)
        Zeroizer.wipe(encKey)
        Zeroizer.wipe(decoyDek)
        Zeroizer.wipe(decoyKey)
        dir.deleteRecursively()
    }

    // --- the two rejected designs, as controls ------------------------------------------------

    /**
     * **The headline control.** Duress is configured and working. Every backup of that vault has
     * exactly the shape the sweep reads as "a leftover decoy", so a resume that looks only at the
     * containers wipes the user's decoy out of all of them while the live vault keeps it.
     */
    @Test
    fun `a configured decoy survives a resume untouched`() {
        val bak = container("vault.enc.bak", decoyContainer())
        val snapshot = container("vault-20260913-000000.enc", decoyContainer())
        val before = listOf(bak, snapshot).map { it.readBytes() }
        // The live vault has a decoy too — this IS a configured duress vault.
        val live = decoyContainer()

        val outcome = StaleContainerSweeper.resumeRemovedDecoySweep(live, listOf(bak, snapshot), encKey)

        // The data first, deliberately: a resume that swept would fail on the enum below too, and
        // the failure worth reading is "the decoy is gone", not "the enum is wrong".
        listOf(bak, snapshot).forEachIndexed { index, file ->
            assertEquals(
                "decoy",
                VaultFileCodec.decryptRegion(file.readBytes(), decoyKey, regionIndex = 1)
                    .second.items.single().title,
                "${file.name}: the user must still be able to restore their duress setup",
            )
            assertContentEquals(
                before[index],
                file.readBytes(),
                "${file.name}: a configured decoy must survive byte for byte",
            )
        }
        assertEquals(StaleContainerSweeper.ResumeOutcome.SUPERSEDED, outcome)
    }

    /**
     * The restore edge. The user never removed duress; they restored a **pre-duress** snapshot over
     * a vault that had it. The live container now says "no decoy" for a reason that is not a
     * removal, and the remaining snapshots still hold the decoy they were always meant to hold.
     *
     * With no journal there is nothing to resume, which is what the production caller checks first.
     * This test drives the layer below that to state what the container-only signal would have done
     * — swept — so the journal requirement is recorded as load-bearing rather than decorative.
     */
    @Test
    fun `a restored pre-duress vault leaves the snapshots alone because no journal exists`() {
        val bak = container("vault.enc.bak", decoyContainer())
        val snapshot = container("vault-20260913-000000.enc", decoyContainer())
        val before = listOf(bak, snapshot).map { it.readBytes() }

        // No removal happened, so `removeDuress` never wrote a journal — and the production path
        // (`VaultLockManager.resumeContainerMaintenance`) reads the journal first and returns.
        assertNull(
            ContainerMaintenanceJournal.readFrom(File(dir, "container-maintenance.journal"), dek),
            "no removal, no journal — nothing may be resumed",
        )
        listOf(bak, snapshot).forEachIndexed { index, file ->
            assertContentEquals(before[index], file.readBytes())
        }

        // And this is what the rejected gate would have done with the same files: the live vault
        // says duress is off, so a container-only resume sweeps, and the decoy is gone from both.
        val liveWithNoDecoy = noDecoyContainer()
        StaleContainerSweeper.resumeRemovedDecoySweep(liveWithNoDecoy, listOf(bak, snapshot), encKey)
        listOf(bak, snapshot).forEach { file ->
            assertFalse(
                VaultFileCodec.authenticatesRegion(file.readBytes(), decoyKey, regionIndex = 1),
                "${file.name}: recorded, not desired — this is why the journal is required",
            )
        }
    }

    // --- the resume itself --------------------------------------------------------------------

    @Test
    fun `an interrupted sweep finishes on the next unlock`() {
        val swept = container("vault.enc.bak", decoyContainer())
        StaleContainerSweeper.sweepRemovedDecoy(listOf(swept), encKey)
        val leftBehind = container("vault-20260913-000000.enc", decoyContainer())
        val live = noDecoyContainer()

        val outcome = StaleContainerSweeper.resumeRemovedDecoySweep(
            live,
            listOf(swept, leftBehind),
            encKey,
        )

        assertEquals(StaleContainerSweeper.ResumeOutcome.SWEPT, outcome)
        assertFalse(
            VaultFileCodec.authenticatesRegion(leftBehind.readBytes(), decoyKey, regionIndex = 1),
            "the container the interrupted walk never reached must be swept now",
        )
        assertTrue(
            VaultFileCodec.authenticatesRegion(leftBehind.readBytes(), encKey, regionIndex = 1),
        )
    }

    /** Idempotent: a resume that finds nothing left rewrites nothing. */
    @Test
    fun `a completed sweep resumed again changes no bytes`() {
        val bak = container("vault.enc.bak", decoyContainer())
        StaleContainerSweeper.sweepRemovedDecoy(listOf(bak), encKey)
        val after = bak.readBytes()

        val outcome = StaleContainerSweeper.resumeRemovedDecoySweep(noDecoyContainer(), listOf(bak), encKey)

        assertEquals(StaleContainerSweeper.ResumeOutcome.SWEPT, outcome)
        assertContentEquals(after, bak.readBytes(), "a second resume must be a no-op")
    }

    @Test
    fun `an unreadable live container keeps the journal for the next unlock`() {
        val bak = container("vault.enc.bak", decoyContainer())
        val before = bak.readBytes()

        val outcome = StaleContainerSweeper.resumeRemovedDecoySweep(
            SecureRandomProvider.nextBytes(64),
            listOf(bak),
            encKey,
        )

        assertEquals(
            StaleContainerSweeper.ResumeOutcome.UNKNOWN,
            outcome,
            "neither answer is known, so the walk must be retried rather than abandoned",
        )
        assertContentEquals(before, bak.readBytes(), "and nothing may be touched meanwhile")
    }

    // --- the journal --------------------------------------------------------------------------

    @Test
    fun `the journal round-trips under the real key and says nothing in plaintext`() {
        val file = File(dir, "container-maintenance.journal")

        ContainerMaintenanceJournal.writeTo(file, dek, ContainerMaintenanceJournal.Pass.REMOVED_DECOY)

        val raw = file.readText()
        assertFalse(
            raw.contains("DECOY", ignoreCase = true) || raw.contains("duress", ignoreCase = true),
            "the pass name must be sealed — a file that names duress in plaintext undoes the " +
                "deniability the whole feature rests on; found:\n$raw",
        )
        assertEquals(
            ContainerMaintenanceJournal.Pass.REMOVED_DECOY,
            ContainerMaintenanceJournal.readFrom(file, dek),
        )
    }

    @Test
    fun `a decoy session cannot read the journal and the file is spent rather than kept`() {
        val file = File(dir, "container-maintenance.journal")
        ContainerMaintenanceJournal.writeTo(file, dek, ContainerMaintenanceJournal.Pass.REMOVED_DECOY)

        assertNull(
            ContainerMaintenanceJournal.readFrom(file, decoyDek),
            "the decoy DEK derives a different subkey, so it opens nothing",
        )
        assertFalse(file.isFile, "a journal that will not open under the reader's key is dropped")
    }

    @Test
    fun `no journal is not an error`() {
        assertNull(ContainerMaintenanceJournal.readFrom(File(dir, "absent.journal"), dek))
    }

    // --- fixture ------------------------------------------------------------------------------

    private fun container(name: String, bytes: ByteArray): File =
        File(dir, name).also { it.writeBytes(bytes) }

    private fun noDecoyContainer(): ByteArray =
        VaultFileCodec.encrypt(vault("real"), encKey, initialHeader(), VaultFileCodec.revokedRecoveryBlock())

    private fun decoyContainer(): ByteArray = VaultFileCodec.encryptBothRegions(
        realVault = vault("real"),
        encKeyReal = encKey,
        decoyVault = vault("decoy"),
        encKeyDecoy = decoyKey,
        prevHeader = initialHeader(),
        recoveryBlock = VaultFileCodec.revokedRecoveryBlock(),
    )

    private fun initialHeader(): VaultHeader = VaultHeaderCodec.create(
        salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
        kdfParams = KdfParams.FLOOR,
        wrappedDek = WrappedDek(
            nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
            ciphertext = SecureRandomProvider.nextBytes(
                CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
            ),
        ),
        commitment = EnvelopeCrypto.slotCommitment(dek),
    )

    private fun vault(title: String): Vault {
        val timestamp = Instant.parse("2026-09-13T00:00:00Z")
        return Vault(
            items = listOf(
                VaultItem(
                    id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"),
                    title = title,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                    content = Note("secret note"),
                ),
            ),
            createdAt = timestamp,
            modifiedAt = timestamp,
        )
    }
}
