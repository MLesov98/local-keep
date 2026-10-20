package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * **After a rotation, no container under `filesDir` opens under a retired credential.**
 *
 * That is the whole of [StaleContainerSweeper], and this file asserts it the only way worth
 * asserting it: by sweeping the directory afterwards and trying the old key against everything
 * found there, rather than by checking that the sweeper was called.
 *
 * The bug it guards: `vault.enc` was the only container a password change rewrote. The previous
 * generation kept as `vault.enc.bak`, and the newest five rolling snapshots under `backups/`, all
 * went on opening under the old password — holding a vault at most a few saves old — while Settings
 * told the user "Your old Recovery Kit no longer works". Someone who changed their password
 * *because it leaked* had closed nothing and been told they had.
 */
@RunWith(AndroidJUnit4::class)
class StaleContainerSweepTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager

    @BeforeTest
    fun setup() {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    // --- the invariant -----------------------------------------------------------------------

    @Test
    fun afterAPasswordChange_theOldPasswordOpensNothingLeftOnDisk() = runBlocking {
        lockManager.unlock(password())
        seedItem("before rotation")
        val snapshots = seedSnapshots(3)
        assertTrue(File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).isFile,
            "the fixture must leave a .bak behind, or this test proves nothing")

        val result = lockManager.changeMasterPassword(password(), newPassword())
        assertTrue(result.success)

        val oldEncKey = encKeyFor(password())
        try {
            val stillOpen = containersOnDisk().filter {
                runCatching {
                    VaultFileCodec.authenticatesRegion(it.readBytes(), oldEncKey, regionIndex = 0)
                }.getOrDefault(false)
            }
            assertTrue(
                stillOpen.isEmpty(),
                "the retired password still opens: ${stillOpen.map { it.name }}",
            )
        } finally {
            Zeroizer.wipe(oldEncKey)
        }
        assertEquals(snapshots.size, result.staleContainersReKeyed - 1, "the .bak is re-keyed too")
    }

    /**
     * The other half, and the reason this converts rather than deletes: a rotation is a terrible
     * moment to take away someone's rollback. If the sweep simply removed the snapshots, this test
     * would pass on the invariant above while quietly destroying the safety net.
     */
    @Test
    fun theSnapshotsSurviveTheRotationAndStillRestore() = runBlocking {
        lockManager.unlock(password())
        seedItem("survives the rotation")
        val snapshots = seedSnapshots(2)

        assertTrue(lockManager.changeMasterPassword(password(), newPassword()).success)
        lockManager.lock()

        snapshots.forEach { snapshot ->
            assertTrue(snapshot.isFile, "${snapshot.name} must not have been deleted")
            assertEquals(
                RestoreOutcome.OK,
                lockManager.restoreVaultFile(snapshot.readBytes()),
                "${snapshot.name} must still be restorable after the rotation",
            )
            // And the restored copy opens under the *new* password, which is the point.
            assertEquals(UnlockResult.REAL, lockManager.unlock(newPassword()))
            val items = (lockManager.lockState.value as LockState.Unlocked).vault.items
            assertEquals(listOf("survives the rotation"), items.map { it.title })
            lockManager.lock()
        }
    }

    /**
     * A container this build can no longer read is either damaged or keyed to a credential retired
     * even earlier — which is the hole itself. It is destroyed rather than skipped, and the count
     * comes back so the UI can say so instead of implying nothing happened.
     */
    @Test
    fun anUnreadableContainerIsDestroyedRatherThanLeftBehind() = runBlocking {
        lockManager.unlock(password())
        val orphan = File(backupsDir(), "vault-19700101-000000.enc")
        backupsDir().mkdirs()
        orphan.writeBytes(SecureRandomProvider.nextBytes(4096))

        val result = lockManager.changeMasterPassword(password(), newPassword())

        assertTrue(result.success)
        assertTrue(!orphan.exists(), "an unreadable container must not survive a rotation")
        assertEquals(1, result.staleContainersDestroyed)
        assertEquals(0, result.staleContainersLeftBehind)
    }

    /**
     * A DEK rotation retires the key without changing the password, so the same sweep has to run —
     * the snapshots are keyed to the DEK, not to the password, and that is easy to get wrong in the
     * direction that leaves them behind.
     */
    @Test
    fun aKeyRotationSweepsTheSameContainersAsAPasswordChange() = runBlocking {
        lockManager.unlock(password())
        seedItem("before key rotation")
        seedSnapshots(2)
        val oldEncKey = encKeyFor(password())

        assertTrue(lockManager.rotateRealDek(password(), biometricCipher = null).success)

        try {
            val stillOpen = containersOnDisk().filter {
                runCatching {
                    VaultFileCodec.authenticatesRegion(it.readBytes(), oldEncKey, regionIndex = 0)
                }.getOrDefault(false)
            }
            assertTrue(stillOpen.isEmpty(), "retired DEK still opens: ${stillOpen.map { it.name }}")
        } finally {
            Zeroizer.wipe(oldEncKey)
        }
    }

    // --- fixture -----------------------------------------------------------------------------

    /** Every vault container this app writes, live one included. */
    private fun containersOnDisk(): List<File> = buildList {
        listOf(AtomicVaultWriter.VAULT_FILE, AtomicVaultWriter.BACKUP_FILE)
            .map { File(context.filesDir, it) }
            .filter(File::isFile)
            .let(::addAll)
        backupsDir().listFiles { f: File -> f.isFile }?.let(::addAll)
    }

    /** What [com.localkeep.core.vault.VaultLockManager] would derive for [password] right now. */
    private fun encKeyFor(password: CharArray): ByteArray {
        val header = VaultHeaderCodec.decode(
            File(context.filesDir, AtomicVaultWriter.VAULT_FILE).readBytes(),
        )
        var kek: ByteArray? = null
        var dek: ByteArray? = null
        return try {
            kek = EnvelopeCrypto.deriveKek(password, oldSalt, oldKdfParams(header))
            dek = EnvelopeCrypto.unwrapDek(kek, oldWrappedDek)
            VaultSubkeys.encKey(dek)
        } finally {
            Zeroizer.wipe(kek)
            Zeroizer.wipe(dek)
        }
    }

    /**
     * Slot 0 as it stood *before* the rotation. Captured at fixture time because the rotation
     * overwrites it, and the whole question is what the pre-rotation credential still opens.
     */
    private lateinit var oldSalt: ByteArray
    private lateinit var oldWrappedDek: com.localkeep.core.crypto.WrappedDek

    private fun oldKdfParams(@Suppress("UNUSED_PARAMETER") header: VaultHeader) = KdfParams.FLOOR

    /** Copies the live vault into `backups/`, exactly as RollingBackupManager does on each save. */
    private fun seedSnapshots(count: Int): List<File> {
        val dir = backupsDir().apply { mkdirs() }
        val live = File(context.filesDir, AtomicVaultWriter.VAULT_FILE).readBytes()
        return (1..count).map { n ->
            File(dir, "vault-2026072%d-00000%d.enc".format(n, n)).apply { writeBytes(live) }
        }
    }

    private suspend fun seedItem(title: String) {
        VaultRepository(lockManager).mutate { mutator ->
            mutator.items.add(
                VaultItem(
                    id = UUID.randomUUID(),
                    title = title,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                    content = Login(username = "me"),
                ),
            )
        }
    }

    private fun backupsDir() = File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR)

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, KdfParams.FLOOR)
        try {
            val wrapped = EnvelopeCrypto.wrapDek(kek, dek)
            oldSalt = salt.copyOf()
            oldWrappedDek = wrapped
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = KdfParams.FLOOR,
                    wrappedDek = wrapped,
                    commitment = EnvelopeCrypto.slotCommitment(dek),
                ),
            )
        } finally {
            Zeroizer.wipe(password)
            Zeroizer.wipe(dek)
            Zeroizer.wipe(kek)
        }
    }

    private fun cleanupFiles() {
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.TEMP_FILE).delete()
        File(context.filesDir, DeviceKeyStore.FILE_NAME).delete()
        backupsDir().deleteRecursively()
    }

    private fun password(): CharArray = "test-master-password-123".toCharArray()
    private fun newPassword(): CharArray = "test-new-master-password-789".toCharArray()
}
