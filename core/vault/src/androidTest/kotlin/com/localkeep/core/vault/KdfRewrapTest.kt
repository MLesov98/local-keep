package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Re-wrapping the vault under different Argon2id costs, keeping the same password and the same key.
 *
 * design.md §11.1 asks for this after a restore onto different hardware: the cost is chosen once,
 * at creation, and then carried in the header for the life of the vault — across every export,
 * restore and new phone. [KdfRecalibration] decides *whether*; this is *how*.
 *
 * The thing worth testing is what must **not** change. A re-wrap is not a rotation: the password is
 * the same and so is the key it protects, so the Recovery Kit has to stay valid and the local
 * snapshots have to go on opening. Getting that wrong in the safe-looking direction — rotating the
 * DEK "to be thorough" — would void a user's printed 24 words as a side effect of a performance
 * setting, and they would find out at the only moment those words matter.
 */
@RunWith(AndroidJUnit4::class)
class KdfRewrapTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager

    /** Deliberately not the floor, so the test can move in both directions from here. */
    private val startingParams = KdfParams(memoryKib = 24_576, iterations = 3, parallelism = 2)

    /**
     * What the re-wraps below target — **not [KdfParams.DEFAULT], and that is the point.**
     *
     * Argon2id's cost parameter is a heap allocation, and `DEFAULT` asks for 64 MiB of it. Android
     * caps an ordinary app at `dalvik.vm.heapgrowthlimit`, which on API 28 — this app's own
     * minSdk — is 48 MiB, so a test that re-wraps to `DEFAULT` is asking the device for something
     * it cannot give and dies with `OutOfMemoryError`. It passed only because it had never been run
     * below API 35, where the limit is 192 MiB.
     *
     * Nothing is lost by targeting a smaller rung. What these tests assert — the same password
     * still opens the vault, the Recovery Kit survives, the salt is redrawn, a decoy declines the
     * offer — is about what a re-wrap *preserves*, and is independent of which rung it moves to.
     * The one thing that must stay true is that this differs from [startingParams], so the re-wrap
     * is a real change.
     */
    private val targetParams = KdfParams(memoryKib = 32_768, iterations = 3, parallelism = 4)

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

    @Test
    fun theVaultOpensWithTheSamePasswordUnderTheNewParams() = runBlocking {
        lockManager.unlock(password())
        seedItem("still here")

        assertEquals(RewrapOutcome.OK, lockManager.rewrapKdfParams(password(), targetParams))
        lockManager.lock()

        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        val items = (lockManager.lockState.value as LockState.Unlocked).vault.items
        assertEquals(listOf("still here"), items.map { it.title })
        assertEquals(targetParams, storedParams())
    }

    /**
     * The whole reason this is not implemented as a rotation. A user changing a performance setting
     * must not silently invalidate the 24 words they printed and put in a drawer.
     */
    @Test
    fun aRewrapLeavesTheRecoveryKitWorking() = runBlocking {
        lockManager.unlock(password())
        val mnemonic = lockManager.prepareRecoveryKit()
        assertTrue(lockManager.commitPreparedRecoveryKit())
        lockManager.lock()

        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        assertEquals(RewrapOutcome.OK, lockManager.rewrapKdfParams(password(), targetParams))
        lockManager.lock()

        assertEquals(
            UnlockResult.REAL,
            lockManager.unlockWithRecoveryKit(requireNotNull(mnemonic)),
            "a cost change must not void the Recovery Kit",
        )
    }

    @Test
    fun aWrongPasswordChangesNothing() = runBlocking {
        lockManager.unlock(password())

        assertEquals(
            RewrapOutcome.WRONG_PASSWORD,
            lockManager.rewrapKdfParams("not-the-password".toCharArray(), targetParams),
        )

        assertEquals(startingParams, storedParams(), "a refused re-wrap must not touch the header")
        lockManager.lock()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
    }

    /**
     * A fresh salt rides along with the new parameters. Re-deriving a KEK from the same password
     * and the same salt under different costs is free to precompute against, and a salt is cheap.
     */
    @Test
    fun aRewrapDrawsAFreshSalt() = runBlocking {
        lockManager.unlock(password())
        val before = storedSalt()

        assertEquals(RewrapOutcome.OK, lockManager.rewrapKdfParams(password(), targetParams))

        assertFalse(before.contentEquals(storedSalt()), "the salt must not be reused")
    }

    /**
     * The header requires both unlock slots to carry identical KDF parameters — that is what stops
     * an attacker reading the cost fields and telling the real slot from the decoy. Slot 1 can only
     * be re-wrapped with the duress password, and asking for it would announce the feature to
     * whoever is watching. So the offer is declined, explicitly, rather than half-applied.
     */
    @Test
    fun aVaultWithADecoyDeclinesTheRewrapRatherThanBreakingIt() = runBlocking {
        lockManager.unlock(password())
        assertTrue(
            lockManager.setupDuress(
                "test-duress-password-456".toCharArray(),
                Vault(createdAt = Instant.now(), modifiedAt = Instant.now()),
            ),
        )

        assertEquals(
            RewrapOutcome.DURESS_CONFIGURED,
            lockManager.rewrapKdfParams(password(), targetParams),
        )

        assertEquals(startingParams, storedParams(), "a declined re-wrap must not touch the header")
        lockManager.lock()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        lockManager.lock()
        assertEquals(
            UnlockResult.DURESS,
            lockManager.unlock("test-duress-password-456".toCharArray()),
            "the decoy must still open after a declined re-wrap",
        )
    }

    // --- fixture -----------------------------------------------------------------------------

    private fun header() = VaultHeaderCodec.decode(
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).readBytes(),
    )

    private fun storedParams() = header().slots[0].kdfParams
    private fun storedSalt() = header().slots[0].salt

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

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, startingParams)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = startingParams,
                    wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
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
        File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR).deleteRecursively()
    }

    private fun password(): CharArray = "test-master-password-123".toCharArray()
}
