package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * A panic lock keeps biometrics away from the vault's own step-up gates, across a restart and past
 * the master-password unlock that follows it.
 *
 * Found 2026-10-03: after "Lock now", the Recovery Kit and invisible-items gates still offered
 * "Use biometrics" and accepted a fingerprint — the forced finger the panic lock exists to refuse,
 * opening the 24 words that reopen the vault for good. [VaultLockManager.isBiometricUnlockBlocked]
 * lifts at the next master unlock, so it could not be what the gates read.
 */
@RunWith(AndroidJUnit4::class)
class PanicSuspendsBiometricGatesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager

    @BeforeTest
    fun setup() = runBlocking {
        clear()
        FolderTestVault.create(context)
        lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        lockManager.unlock(FolderTestVault.password())
        Unit
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        clear()
    }

    @Test
    fun aPanicLockSuspendsTheGatesUntilBiometricUnlockIsSetUpAgain() = runBlocking {
        assertFalse(lockManager.isBiometricSuspendedByPanic, "control: nothing has panicked yet")

        lockManager.panicLock()
        assertTrue(lockManager.isBiometricSuspendedByPanic)

        // A new manager is a new process: the suspension is on disk, not in a field.
        val restarted = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        assertTrue(restarted.isBiometricSuspendedByPanic, "a force-stop must not lift it")

        // FAILS WITHOUT THE FIX's distinction: the master password ends the unlock block, not this.
        restarted.unlock(FolderTestVault.password())
        assertTrue(restarted.isBiometricSuspendedByPanic, "the master unlock after a panic must not lift it")
        restarted.lock()
    }

    private fun clear() {
        context.getSharedPreferences(PANIC_BIOMETRIC_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FolderTestVault.cleanup(context)
    }
}
