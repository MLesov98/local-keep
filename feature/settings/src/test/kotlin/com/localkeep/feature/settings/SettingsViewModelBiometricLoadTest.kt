package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.WrappedDek
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.vault.DeviceKeyStore
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.feature.files.BlobGarbageCollector
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Regression test for 16-2: `SettingsUiState.biometricEnabled` used to be read from
 * `DeviceKeyStore.load(context)` in the state's field initializer, on the ViewModel's
 * constructing thread — a synchronous disk read on the main thread, and a security-relevant flag
 * that could read as permissive (true) before the real check had run, if `load` ever grew a slow
 * path. The fix seeds `false` and loads on Dispatchers.IO in `init`.
 *
 * `KeystoreManager`/`VaultLockManager` are final classes whose real constructors touch
 * AndroidKeyStore, which is not implemented under Robolectric (confirmed empirically: a
 * `KeystoreManager(context)` call under this exact Robolectric/SDK combination throws
 * `KeyStoreException` / `NoSuchAlgorithmException`). They are mocked here rather than
 * constructed for real; `DeviceKeyStore` is a plain-file-IO object and is exercised for real via
 * the Robolectric-provided filesDir.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelBiometricLoadTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): SettingsViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        val keystoreManager = mock<KeystoreManager>()
        val biometricUnlock = mock<BiometricUnlock>()
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val breachFilter = mock<BreachFilter>()

        // A locked repository: SettingsViewModel's usage collector reads nothing while the vault
        // is shut, and an unstubbed presentationState would hand its collector a null flow.
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))

        return SettingsViewModel(
            lockManager = lockManager,
            repository = repository,
            keystoreManager = keystoreManager,
            biometricUnlock = biometricUnlock,
            entitlement = entitlement,
            breachFilter = breachFilter,
            blobGarbageCollector = mock<BlobGarbageCollector>(),
            context = context,
        )
    }

    @Test
    fun `constructor performs no disk read and seeds the non-permissive false`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // A saved device key exists on disk BEFORE construction — if the constructor itself read
        // it (the pre-fix behaviour), the very first emitted state would already read `true`.
        DeviceKeyStore.save(context, wrappedDekOfSize())

        val viewModel = buildViewModel()

        // No coroutine has been allowed to run yet (StandardTestDispatcher queues, does not run,
        // launched work) — this is the state as constructed, before `init`'s IO load lands.
        assertFalse(
            viewModel.state.value.biometricEnabled,
            "a security-relevant flag must never read as permissive while still loading",
        )
    }

    @Test
    fun `the real value arrives once the init coroutine runs`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DeviceKeyStore.save(context, wrappedDekOfSize())

        val viewModel = buildViewModel()
        assertFalse(viewModel.state.value.biometricEnabled)

        // The IO load happens on the real Dispatchers.IO thread pool (not the virtual-time test
        // dispatcher), so advancing the test scheduler alone can race it; poll briefly for the
        // continuation to post back.
        var enabled = false
        var attempts = 0
        while (!enabled && attempts < 50) {
            dispatcher.scheduler.advanceUntilIdle()
            enabled = viewModel.state.value.biometricEnabled
            if (!enabled) Thread.sleep(10)
            attempts++
        }

        assertEquals(true, enabled)
    }

    private fun wrappedDekOfSize(): WrappedDek = WrappedDek(
        nonce = ByteArray(CryptoConstants.AEAD_NONCE_LEN) { 1 },
        ciphertext = ByteArray(CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN) { 2 },
    )
}
