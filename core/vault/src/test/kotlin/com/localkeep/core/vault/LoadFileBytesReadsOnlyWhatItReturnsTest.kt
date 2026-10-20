package com.localkeep.core.vault

import com.localkeep.core.common.Outcome
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.crypto.Zeroizer
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * `VaultLoader.loadFileBytes` answers "give me the current container", and must read only as far as
 * the first one that parses (2026-10-02, O4-03).
 *
 * It used to be `loadCandidates(context).firstOrNull()`, and `loadCandidates` builds a
 * `listOfNotNull` of three whole-file reads — so every save, every rolling snapshot and every
 * re-authentication also read the `.bak` (present after the first save) in full and threw it away.
 * At 10,000 items a container is ~32 MiB: a save read ~64 MiB to use 32, and the extra read sits
 * before the save's only `OutOfMemoryError` translation in `VaultLockManager.commit`.
 *
 * How "was the backup read" is observed without a seam: the backup here is a real, sparse file
 * longer than any `ByteArray` can be. `kotlin.io.readBytes` checks `length()` before allocating and
 * throws `OutOfMemoryError` for such a file — the same mechanism
 * [StaleContainerSweepOutOfMemoryTest] relies on — so reading it is loud and not reading it is
 * silent. Sparse, so it costs no disk.
 *
 * The unlock path is the deliberate exception: it asks [VaultLoader.loadCandidates] for the whole
 * list, because a primary whose header parses can still fail to authenticate, and the `.bak` is
 * then the vault that opens. The last test pins that this change did not take that away.
 */
class LoadFileBytesReadsOnlyWhatItReturnsTest {

    private val dir: File = Files.createTempDirectory("vault-loader").toFile()
    private val dek = EnvelopeCrypto.generateDek()

    @AfterTest
    fun cleanUp() {
        Zeroizer.wipe(dek)
        dir.deleteRecursively()
    }

    /** A structurally valid container — all `readValid` checks — built without an Argon2 run. */
    private fun container(): ByteArray = VaultFileCodec.create(
        dek = dek,
        salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN),
        kdfParams = KdfParams.DEFAULT,
        wrappedDek = WrappedDek(
            nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
            ciphertext = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN),
        ),
        commitment = EnvelopeCrypto.slotCommitment(dek),
    )

    private fun Outcome<ByteArray>.bytes(): ByteArray = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> fail("expected a container, got $error")
    }

    private fun write(name: String, bytes: ByteArray): ByteArray = bytes.also { File(dir, name).writeBytes(it) }

    /** A regular file one byte longer than the largest array the JVM can hold. See the class KDoc. */
    private fun unreadablyLarge(name: String): File =
        File(dir, name).also { file -> RandomAccessFile(file, "rw").use { it.setLength(Int.MAX_VALUE.toLong() + 1) } }

    /** The mechanism, asserted before it is relied on — or every test below could pass for nothing. */
    @Test
    fun `the oversized fixture really does fail when read`() {
        val huge = unreadablyLarge(AtomicVaultWriter.BACKUP_FILE)
        assertFailsWith<OutOfMemoryError> { huge.readBytes() }
    }

    @Test
    fun `a healthy primary is returned without reading the backup`() {
        val primary = write(AtomicVaultWriter.VAULT_FILE, container())
        unreadablyLarge(AtomicVaultWriter.BACKUP_FILE)

        val loaded = VaultLoader.loadFileBytes(dir)

        assertContentEquals(primary, loaded.bytes())
    }

    @Test
    fun `a damaged primary still falls back to the backup`() {
        write(AtomicVaultWriter.VAULT_FILE, ByteArray(16))
        val backup = write(AtomicVaultWriter.BACKUP_FILE, container())

        assertContentEquals(backup, VaultLoader.loadFileBytes(dir).bytes())
    }

    /** The rename-window survivor still outranks the older generation. See `loadCandidates`. */
    @Test
    fun `with no primary the fsynced tmp wins over the backup`() {
        val tmp = write(AtomicVaultWriter.TEMP_FILE, container())
        write(AtomicVaultWriter.BACKUP_FILE, container())

        assertContentEquals(tmp, VaultLoader.loadFileBytes(dir).bytes())
    }

    @Test
    fun `nothing readable is still a failure`() {
        write(AtomicVaultWriter.VAULT_FILE, ByteArray(16))
        assertIs<Outcome.Failure>(VaultLoader.loadFileBytes(dir))
    }

    @Test
    fun `unlock's candidate list still carries every valid container`() {
        val primary = write(AtomicVaultWriter.VAULT_FILE, container())
        val backup = write(AtomicVaultWriter.BACKUP_FILE, container())

        val candidates = VaultLoader.loadCandidates(dir)

        assertEquals(2, candidates.size)
        assertContentEquals(primary, candidates[0])
        assertContentEquals(backup, candidates[1])
    }
}
