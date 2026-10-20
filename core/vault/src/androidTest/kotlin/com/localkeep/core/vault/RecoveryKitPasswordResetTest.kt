package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The Recovery Kit has to give the vault back, not only open it.
 *
 * Recovering unlocked the vault and left the user there: `changeMasterPassword` asks for the
 * current password, which is the one thing someone who used the Kit does not have, so every cold
 * start needed the 24 words again and there was no route to anything else. The design calls the Kit
 * "the only forgotten-master-password recovery path" (§12.5, MUST), and recovery that cannot
 * restore password unlock does not meet that. Found by the F14 QA persona, 2026-08-30.
 *
 * These are known-answer tests in the strict sense that matters here: each one ends by locking,
 * restarting the manager, and proving which credentials do and do not open the vault afterwards —
 * a re-key that "succeeded" but left the file openable by the old password would pass a return-value
 * assertion and fail these.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryKitPasswordResetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    @BeforeTest
    fun setup() {
        cleanupFiles()
        runBlocking {
            VaultInitializer.create(context, MASTER_PASSWORD.toCharArray())
        }
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
        repository = VaultRepository(lockManager)
    }

    @AfterTest
    fun teardown() {
        lockManager.lock()
        cleanupFiles()
    }

    /**
     * The whole point. Recover with the words, set a password, and the vault opens with the
     * password on a cold start — with every item still readable.
     */
    @Test
    fun recoveredUserCanSetANewPasswordAndUseIt() = runBlocking {
        lockManager.unlock(password())
        repository.addItem(item("real", "Bank"))
        val mnemonic = assertNotNull(saveANewRecoveryKit())

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(mnemonic))

        val result = MasterPasswordChanger.setPasswordWithRecoveryKit(
            lockManager,
            mnemonic,
            NEW_MASTER_PASSWORD.toCharArray(),
        )
        assertTrue(result.success, "setting a password with the kit that just opened the vault must work")

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlock(NEW_MASTER_PASSWORD.toCharArray()))
        assertEquals("Bank", repository.getAllItems().single().title)
    }

    /** The retired password must stop opening the vault, or the reset closed nothing. */
    @Test
    fun theForgottenPasswordStopsWorking() = runBlocking {
        lockManager.unlock(password())
        val mnemonic = assertNotNull(saveANewRecoveryKit())
        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(mnemonic))
        assertTrue(
            MasterPasswordChanger.setPasswordWithRecoveryKit(
                lockManager,
                mnemonic,
                NEW_MASTER_PASSWORD.toCharArray(),
            ).success,
        )

        restartManager()
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlock(password()))
    }

    /**
     * The kit used for the reset dies with it, because the data key rotates. That is the intended
     * end state — the user is back on a password and owes themselves a new kit — and it is the half
     * of this change most likely to be broken by a "simplification" that only re-wraps the key.
     */
    @Test
    fun theKitUsedForTheResetIsRevoked() = runBlocking {
        lockManager.unlock(password())
        val mnemonic = assertNotNull(saveANewRecoveryKit())
        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(mnemonic))
        val result = MasterPasswordChanger.setPasswordWithRecoveryKit(
            lockManager,
            mnemonic,
            NEW_MASTER_PASSWORD.toCharArray(),
        )
        assertTrue(result.success)
        assertTrue(result.oldRecoveryKitVoided, "the user has to be told the kit no longer works")

        restartManager()
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlockWithRecoveryKit(mnemonic))
    }

    /**
     * Wrong words change nothing. Asserted after a restart rather than on the return value alone:
     * a partial write that failed on its way out would still report `success = false`.
     */
    @Test
    fun wrongWordsAreRefusedAndLeaveTheVaultAlone() = runBlocking {
        lockManager.unlock(password())
        assertNotNull(saveANewRecoveryKit())

        val result = MasterPasswordChanger.setPasswordWithRecoveryKit(
            lockManager,
            "these are definitely not the twenty four words of this vault kit",
            NEW_MASTER_PASSWORD.toCharArray(),
        )
        assertFalse(result.success)

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
    }

    /**
     * A kit belonging to a *different* vault must not re-key this one. The commitment alone would
     * not catch it — the second check, that the unwrapped key is the one this session is holding,
     * is what does.
     */
    @Test
    fun anotherVaultsKitCannotReKeyThisOne() = runBlocking {
        lockManager.unlock(password())
        val mine = assertNotNull(saveANewRecoveryKit())

        // A second vault, made and thrown away, purely for its words.
        lockManager.lock()
        cleanupFiles()
        VaultInitializer.create(context, MASTER_PASSWORD.toCharArray())
        restartManager()
        lockManager.unlock(password())
        val theirs = assertNotNull(saveANewRecoveryKit())
        assertFalse(theirs == mine, "the two vaults must not have produced the same words")

        val result = MasterPasswordChanger.setPasswordWithRecoveryKit(
            lockManager,
            mine,
            NEW_MASTER_PASSWORD.toCharArray(),
        )
        assertFalse(result.success, "a kit for another vault must not set this vault's password")

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
    }

    /** A locked vault has no session to re-key, and this must refuse rather than half-run. */
    @Test
    fun aLockedVaultRefusesTheReset() = runBlocking {
        lockManager.unlock(password())
        val mnemonic = assertNotNull(saveANewRecoveryKit())
        lockManager.lock()

        runCatching {
            MasterPasswordChanger.setPasswordWithRecoveryKit(
                lockManager,
                mnemonic,
                NEW_MASTER_PASSWORD.toCharArray(),
            )
        }.onSuccess { assertFalse(it.success, "a locked vault must not report a successful re-key") }

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
    }

    private suspend fun saveANewRecoveryKit(): String? {
        val mnemonic = lockManager.prepareRecoveryKit() ?: return null
        return if (lockManager.commitPreparedRecoveryKit()) mnemonic else null
    }

    private fun restartManager() {
        lockManager.lock()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
        repository = VaultRepository(lockManager)
    }

    private fun item(seed: String, title: String): VaultItem {
        val now = Instant.now()
        return VaultItem(
            id = UUID.nameUUIDFromBytes(seed.toByteArray()),
            title = title,
            createdAt = now,
            updatedAt = now,
            content = Login(username = "ada@example.com", password = "hunter2", passwordChangedAt = now),
        )
    }

    private fun cleanupFiles() {
        File(context.filesDir, "vault.enc").delete()
        File(context.filesDir, "vault.enc.bak").delete()
        File(context.filesDir, "vault.enc.tmp").delete()
    }

    private fun password(): CharArray = MASTER_PASSWORD.toCharArray()

    private companion object {
        const val MASTER_PASSWORD = "test-master-password-123"
        const val NEW_MASTER_PASSWORD = "test-new-master-password-789"
    }
}
