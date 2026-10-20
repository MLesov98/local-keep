package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Vault
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Setting the duress password to the master password is refused, and says so.
 *
 * The guard was a bare `require`, so the UI answered with "That didn't go through. Try again." — a
 * generic failure and advice that, followed literally, reproduces itself forever. Neither the form
 * nor the confirmation dialog mentions the constraint beforehand, and both fields are wiped on the
 * way out, so the user has nothing to work from. There is no oracle argument for withholding the
 * reason: this path runs only inside an authenticated real session, and the duress rows are absent
 * entirely from a decoy one. Found by the F14 QA persona, 2026-08-30.
 *
 * The typed exception is the contract; this pins that it is what actually comes out, and that the
 * vault is untouched afterwards — a guard that threw *after* writing would pass a type assertion.
 */
@RunWith(AndroidJUnit4::class)
class DuressCollisionRefusalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager

    @BeforeTest
    fun setup() {
        cleanupFiles()
        runBlocking { VaultInitializer.create(context, MASTER_PASSWORD.toCharArray()) }
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
    }

    @AfterTest
    fun teardown() {
        lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun theMasterPasswordIsRefusedAsADuressPassword(): Unit = runBlocking {
        lockManager.unlock(MASTER_PASSWORD.toCharArray())
        assertFailsWith<DuressPasswordCollisionException> {
            DuressUnlocker.setupDuress(
                lockManager = lockManager,
                duressPassword = MASTER_PASSWORD.toCharArray(),
                decoyVault = emptyDecoy(),
            )
        }
    }

    /** And the refusal changes nothing: the master password still opens the real vault. */
    @Test
    fun aRefusedSetupLeavesTheVaultAlone(): Unit = runBlocking {
        lockManager.unlock(MASTER_PASSWORD.toCharArray())
        runCatching {
            DuressUnlocker.setupDuress(
                lockManager = lockManager,
                duressPassword = MASTER_PASSWORD.toCharArray(),
                decoyVault = emptyDecoy(),
            )
        }
        lockManager.lock()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
        assertEquals(UnlockResult.REAL, lockManager.unlock(MASTER_PASSWORD.toCharArray()))
    }

    /** The decoy's contents are irrelevant here: the refusal happens before it is ever used. */
    private fun emptyDecoy(): Vault {
        val now = Instant.now()
        return Vault(items = emptyList(), folders = emptyList(), createdAt = now, modifiedAt = now)
    }

    private fun cleanupFiles() {
        File(context.filesDir, "vault.enc").delete()
        File(context.filesDir, "vault.enc.bak").delete()
        File(context.filesDir, "vault.enc.tmp").delete()
    }

    private companion object {
        const val MASTER_PASSWORD = "test-master-password-123"
    }
}
