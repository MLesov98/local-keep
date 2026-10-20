package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.BlobGarbageCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Editing the duress form clears the refusal that was about the form's previous contents.
 *
 * D1-F-01 (functional pass, 2026-10-15): a too-short duress password was refused only after the
 * vault-password gate, and the coral banner then stayed above a form the user had already started
 * correcting. The change-password form got this rule first ([SettingsViewModel.updatePasswordForm]);
 * the duress form is the same case. An unchanged form is not an edit and leaves the error alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuressFormEditClearsStaleErrorTest {

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
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(true))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))
        val viewModel = SettingsViewModel(
            lockManager = lockManager,
            repository = repository,
            keystoreManager = mock<KeystoreManager>(),
            biometricUnlock = mock<BiometricUnlock>(),
            entitlement = entitlement,
            breachFilter = mock<BreachFilter>(),
            blobGarbageCollector = mock<BlobGarbageCollector>(),
            context = context,
        )
        // The Pro flag arrives through a collector launched on Main, which the test dispatcher
        // queues; without this the setup call below is refused for being unentitled, silently.
        dispatcher.scheduler.advanceUntilIdle()
        return viewModel
    }

    /** A refusal the ViewModel raises synchronously: the length check, before any derivation. */
    private fun SettingsViewModel.refuseShortDuressPassword() {
        setupDuress("vault-password-12345".toCharArray(), "short".toCharArray())
    }

    @Test
    fun `editing the form drops the standing refusal`() {
        val viewModel = buildViewModel()
        viewModel.refuseShortDuressPassword()
        assertNotNull(viewModel.state.value.error, "the control: the refusal was raised")

        viewModel.updateDuressForm(viewModel.duressForm.value.copy(password = "shorter-no-more"))

        assertNull(
            viewModel.state.value.error,
            "the too-short banner stayed above a duress form that had already been edited",
        )
    }

    @Test
    fun `an unchanged form is not an edit and leaves the refusal`() {
        val viewModel = buildViewModel()
        viewModel.refuseShortDuressPassword()
        val before = viewModel.state.value.error

        viewModel.updateDuressForm(viewModel.duressForm.value)

        assertEquals(before, viewModel.state.value.error)
    }
}
