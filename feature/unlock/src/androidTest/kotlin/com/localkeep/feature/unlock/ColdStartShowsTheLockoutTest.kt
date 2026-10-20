package com.localkeep.feature.unlock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultLockedException
import com.localkeep.feature.backup.VaultImporter
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * An unlock screen opened during a lockout says so before anyone types.
 *
 * Found 2026-10-03: the backoff is persisted and survives a force-stop, but a cold start showed
 * an ordinary lock screen with Unlock enabled; the countdown appeared only after a submit that was
 * always going to be refused.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartShowsTheLockoutTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var keystoreManager: KeystoreManager
    private lateinit var lockManager: VaultLockManager

    @BeforeTest
    fun setUp() {
        clearState()
        createVault()
        keystoreManager = KeystoreManager(context)
        lockManager = VaultLockManager(context, keystoreManager)
    }

    @AfterTest
    fun tearDown() = clearState()

    @Test
    fun aViewModelBuiltDuringALockoutShowsTheCountdownAtOnce() = runBlocking {
        // Seven failures: the fifth starts the backoff at one second and each after it doubles, so
        // the last leaves a lockout of a few seconds — long enough to outlive building the screen.
        var failures = 0
        while (failures < FAILURES) {
            try {
                lockManager.unlock("not the password".toCharArray())
            } catch (locked: VaultLockedException) {
                delay(locked.retryAfterMs + 50)
                continue
            } catch (_: Exception) {
            }
            failures++
        }

        val viewModel = UnlockViewModel(
            context = context,
            lockManager = lockManager,
            keystoreManager = keystoreManager,
            biometricUnlock = BiometricUnlock(keystoreManager),
            vaultImporter = VaultImporter(context, FileBlobStore(context), lockManager),
        )

        // FAILS WITHOUT THE FIX: nothing was shown until a submit.
        val shown = withTimeoutOrNull(2_000L) {
            while (viewModel.state.value.error !is UnlockError.RetryAfter) delay(25L)
            true
        }
        assertTrue(shown == true, "the lockout countdown was not on the screen as it opened")
    }

    private fun createVault() {
        val password = "correct horse battery staple".toCharArray()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, KdfParams.FLOOR)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = KdfParams.FLOOR,
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

    /** The backoff is persisted on purpose; a test that builds one must not leave it for the next. */
    private fun clearState() {
        context.getSharedPreferences(THROTTLE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }

    private companion object {
        const val FAILURES = 7
        /** `PrefsThrottleStore.FILE`, which is internal to `:core:vault`. */
        const val THROTTLE_PREFS = "unlock_throttle"
    }
}
