package com.localkeep.feature.unlock

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.vault.DeviceKeyStore
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.feature.backup.VaultImporter
import com.localkeep.feature.files.FileBlobStore
import java.security.ProviderException
import javax.crypto.Cipher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith

/**
 * Regresses the residual the QA verifier flagged on top of the `KeyPermanentlyInvalidatedException`
 * finding: after the biometric key can no longer be used, the old generic catch-all left the stale
 * device-wrapped key on disk, so `biometricAvailable()` kept offering a button that would fail
 * identically on every retry, and reported the vaguer `unlock_error_biometric_incomplete`.
 *
 * **Why instrumented, not a plain unit test.** `KeystoreManager` opens `AndroidKeyStore` in its
 * constructor, which is unimplemented on a bare JVM and under Robolectric — `:feature:itemedit`
 * hit the identical wall (see `ItemEditViewModelTest`) and answered it the same way.
 *
 * **Why the failure is injected rather than provoked.** Both ways a biometric key ends for good are
 * thrown from inside `BiometricUnlock.decryptCipher`: `KeyPermanentlyInvalidatedException` from
 * `Cipher.init` after an enrolment change, and `IllegalStateException` from
 * `KeystoreManager.getOrCreateBioKey`'s `check()`s once the device has no lock or no strong
 * biometric. Neither can be produced on demand without changing the device: the first needs a
 * fingerprint enrolled or removed, the second needs the lock screen or every enrolment removed —
 * and the instrumented suite requires the emulator's PIN (see `VaultLockManagerInstrumentedTest`).
 *
 * This test used to rely on the second happening by accident: it assumed "this emulator has no
 * secure lock screen / no enrolled strong biometric". That was true of whichever emulator it was
 * written on, and false on `LocalKeep_API_36` once a fingerprint was enrolled there (two prints,
 * 2026-10-04). On that device `getOrCreateBioKey` succeeded and minted a real key, a real
 * BiometricPrompt opened (`BiometricService: handleAuthenticate` in logcat) and waited for a finger
 * nobody supplied, and the test failed as "unlockWithBiometric never finished" — pass or fail
 * decided by the emulator's enrolment, not by the code. It also only ever reached the
 * `IllegalStateException` branch; the `KeyPermanentlyInvalidatedException` branch its name refers
 * to was never executed on any device.
 *
 * So a [BiometricUnlock] whose `decryptCipher` throws the platform's own exception type stands in
 * for the platform, and everything downstream of it — the view model's classification, the real
 * `DeviceKeyStore` file, the real Keystore delete, `biometricAvailable()` — is the shipped code.
 * That `getOrCreateBioKey` really does throw `IllegalStateException` on an ineligible device is
 * asserted where it lives, by `KeystoreInstrumentedTest.strongBiometricGate_matchesKeyCreation`.
 */
@RunWith(AndroidJUnit4::class)
class UnlockViewModelBiometricInvalidationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var keystoreManager: KeystoreManager
    private lateinit var lockManager: VaultLockManager

    @BeforeTest
    fun setUp() {
        keystoreManager = KeystoreManager(context)
        lockManager = VaultLockManager(context, keystoreManager)
        // A fake device-wrapped key — its bytes never get read, because decryptCipher throws
        // before decryption is attempted. Its file existing is what the fix must clear.
        DeviceKeyStore.save(
            context,
            WrappedDek(nonce = ByteArray(12), ciphertext = ByteArray(48)),
        )
        assertNotNull(DeviceKeyStore.load(context), "precondition: the wrap to be cleared exists")
    }

    @AfterTest
    fun tearDown() {
        DeviceKeyStore.delete(context)
        keystoreManager.deleteKey(KeystoreManager.BIO_DEK_ALIAS)
    }

    /**
     * Fails without the fix on two counts: the old code left `DeviceKeyStore`'s file in place
     * (`DeviceKeyStore.load` still returns non-null), and it reported
     * `unlock_error_biometric_incomplete` instead of `unlock_error_biometric_unavailable` — which
     * is exactly what [aFailureThatIsNotTheKeysEndKeepsTheWrap] below still gets, through the same
     * harness, for an exception the fix does not classify.
     */
    @Test
    fun anInvalidatedKeyClearsTheStaleWrapAndNamesTheRemedy() {
        val viewModel = unlockWithBiometricFailing(KeyPermanentlyInvalidatedException())

        assertTheWrapIsClearedAndTheRemedyNamed(viewModel)
    }

    /** The other half of the same fix: biometrics removed outright, so the key cannot be made. */
    @Test
    fun aDeviceThatLostItsStrongBiometricClearsTheStaleWrapToo() {
        val viewModel = unlockWithBiometricFailing(
            // The message `getOrCreateBioKey`'s own `check()` carries.
            IllegalStateException("BIOMETRIC_STRONG unavailable; use master password"),
        )

        assertTheWrapIsClearedAndTheRemedyNamed(viewModel)
    }

    /**
     * The control. Clearing the wrap answers "this key can never work again", not "something
     * failed": a Keystore hiccup that clears it would turn biometric unlock off behind the user's
     * back, and only re-enrolling in Settings would bring it back. So a failure the fix does not
     * classify keeps the wrap and keeps the retryable wording.
     */
    @Test
    fun aFailureThatIsNotTheKeysEndKeepsTheWrap() {
        val viewModel = unlockWithBiometricFailing(ProviderException("Keystore operation failed"))

        assertNotNull(DeviceKeyStore.load(context), "a retryable failure must not discard the wrap")
        val error = viewModel.state.value.error
        check(error is UnlockError.Simple) { "expected a simple error, got $error" }
        assertEquals(R.string.unlock_error_biometric_incomplete, error.messageRes)
    }

    private fun unlockWithBiometricFailing(failure: Exception): UnlockViewModel {
        val viewModel = UnlockViewModel(
            context = context,
            lockManager = lockManager,
            keystoreManager = keystoreManager,
            biometricUnlock = FailingDecryptCipher(keystoreManager, failure),
            // Constructed but never reached on this path: nothing in the biometric flow touches the
            // importer. It exists here only because the constructor names it (F34).
            vaultImporter = VaultImporter(context, FileBlobStore(context), lockManager),
        )
        ActivityScenario.launch(UnlockTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> viewModel.unlockWithBiometric(activity) }

            val deadline = System.currentTimeMillis() + 5_000
            while (viewModel.state.value.isWorking && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
        }
        assertFalse(viewModel.state.value.isWorking, "unlockWithBiometric never finished")
        return viewModel
    }

    private fun assertTheWrapIsClearedAndTheRemedyNamed(viewModel: UnlockViewModel) {
        assertNull(
            DeviceKeyStore.load(context),
            "the stale device-wrapped key must be cleared once it can never be used again",
        )
        // The user-visible half of the residual: the button that would fail identically on every
        // retry is no longer offered.
        assertFalse(
            viewModel.state.value.biometricAvailable,
            "biometric unlock is still offered over a key that cannot work",
        )
        val error = viewModel.state.value.error
        check(error is UnlockError.Simple) { "expected a simple error, got $error" }
        assertEquals(R.string.unlock_error_biometric_unavailable, error.messageRes)
    }

    /**
     * Throws [failure] where the platform throws it — from `decryptCipher`, before any prompt —
     * so no real key is minted and no real prompt is raised on a device with a finger enrolled.
     */
    private class FailingDecryptCipher(
        keystoreManager: KeystoreManager,
        private val failure: Exception,
    ) : BiometricUnlock(keystoreManager) {
        override fun decryptCipher(iv: ByteArray): Cipher = throw failure
    }
}
