package com.localkeep.feature.settings

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.model.Vault
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.BlobGarbageCollector
import java.time.Instant
import javax.crypto.Cipher
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Biometric unlock can be turned back on after a fingerprint change (S5-03, 2026-10-02).
 *
 * The Keystore key is minted `setInvalidatedByBiometricEnrollment(true)`, so adding or removing a
 * fingerprint kills it, and `getOrCreateBioKey` hands the dead alias back forever: `Cipher.init`
 * throws `KeyPermanentlyInvalidatedException` on every Enable, which `runOperation` answered with
 * "That didn't go through. Try again." The only code that deleted the alias was the unlock
 * screen's, reachable only while a wrap file exists — so after Disable there was no way back short
 * of clearing app data. Disable now deletes the alias too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricReEnableAfterKeyInvalidationTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Fixture(
        val viewModel: SettingsViewModel,
        val keystoreManager: KeystoreManager,
    )

    /** The key is dead on the first `encryptCipher()` and fresh on the next, as after a delete. */
    private fun fixture(lockState: LockState = LockState.Locked): Fixture {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(lockState))
        lockManager.stub { onBlocking { enableBiometricUnlock(any()) } doReturn true }
        val keystoreManager = mock<KeystoreManager>()
        whenever(keystoreManager.isStrongBiometricAvailable()).thenReturn(true)
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val biometricUnlock = mock<BiometricUnlock>()
        // `thenAnswer`, not `thenThrow`: the exception is checked and `encryptCipher` declares no
        // `throws`, which Mockito's stubbing validator rejects.
        whenever(biometricUnlock.encryptCipher())
            .thenAnswer { throw KeyPermanentlyInvalidatedException() }
            .thenReturn(cipher)
        biometricUnlock.stub {
            onBlocking { authenticate(any(), any(), any(), any(), any()) } doReturn cipher
        }
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))
        val viewModel = SettingsViewModel(
            lockManager = lockManager,
            repository = repository,
            keystoreManager = keystoreManager,
            biometricUnlock = biometricUnlock,
            entitlement = entitlement,
            breachFilter = mock<BreachFilter>(),
            blobGarbageCollector = mock<BlobGarbageCollector>(),
            context = ApplicationProvider.getApplicationContext(),
        )
        return Fixture(viewModel, keystoreManager)
    }

    private fun Fixture.awaitOperation() {
        var attempts = 0
        while (attempts < 200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (!viewModel.state.value.isWorking) return
            Thread.sleep(10)
            attempts++
        }
    }

    @Test
    fun `enable recovers from a key a fingerprint change invalidated`() = runTest(dispatcher) {
        val f = fixture()

        f.viewModel.enableBiometric(mock<FragmentActivity>())
        f.awaitOperation()

        assertNull(
            f.viewModel.state.value.error,
            "the dead key was handed back again and Enable failed with 'try again', for ever",
        )
        // The message, not `biometricEnabled`: init re-reads DeviceKeyStore on IO, and with the
        // enrolment mocked nothing is written there, so that read can land late and say "off".
        assertEquals(
            SettingsMessage.Simple(R.string.settings_message_biometric_enabled),
            f.viewModel.state.value.message,
        )
        verify(f.keystoreManager).deleteKey(KeystoreManager.BIO_DEK_ALIAS)
    }

    @Test
    fun `disable deletes the key along with the wrap`() = runTest(dispatcher) {
        val f = fixture()

        f.viewModel.disableBiometric()
        f.awaitOperation()

        assertFalse(f.viewModel.state.value.biometricEnabled)
        verify(f.keystoreManager).deleteKey(KeystoreManager.BIO_DEK_ALIAS)
    }

    /** Both stores are device-global and belong to the real vault; a decoy session leaves them be. */
    @Test
    fun `a decoy session never deletes the real vault's key`() = runTest(dispatcher) {
        val decoy = LockState.Unlocked(
            dek = ByteArray(32),
            vault = Vault(createdAt = Instant.EPOCH, modifiedAt = Instant.EPOCH),
            isDuress = true,
        )
        val f = fixture(decoy)
        dispatcher.scheduler.advanceUntilIdle()

        f.viewModel.enableBiometric(mock<FragmentActivity>())
        f.awaitOperation()

        verify(f.keystoreManager, never()).deleteKey(any())
    }
}
