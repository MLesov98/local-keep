package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
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
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression test for 37-4: a vault that has a decoy configured never reclaimed an orphaned blob.
 *
 * `BlobGarbageCollector.sweep()` declines outright while a decoy exists, because a session holds
 * the key for exactly one payload region and sweeping from one region's denominator would delete
 * the other region's media irrecoverably. That decline is correct and stays. What was missing is
 * that the app's only sweep trigger is the on-unlock one, so for these users the decline was
 * permanent: every deleted File's ciphertext stayed on disk for the life of the install, unbounded
 * in size, while the vault list told them the item was "permanently deleted".
 *
 * The two moments where a sweep IS safe are the transitions: just before the decoy is created (no
 * decoy exists yet, so the real region is the whole denominator) and just after it is destroyed
 * (same). Both live in [SettingsViewModel], so both are asserted here.
 *
 * What these tests do NOT pin is the *placement* within each operation — sweeping after
 * `setupDuress` rather than before it would reclaim nothing, but `VaultLockManager.setupDuress` is
 * `internal` to `:core:vault` and `DuressUnlocker` is an object, so neither is observable from this
 * module. Placement is held by the comments at the two call sites.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuressTransitionReclaimsBlobsTest {

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
        val blobGarbageCollector: BlobGarbageCollector,
    )

    private fun fixture(isPro: Boolean): Fixture {
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        // Both duress changes are behind a vault-password re-auth; this fixture is about the blob
        // sweep, so the gate is satisfied.
        lockManager.stub { onBlocking { reauthenticateForCurrentVault(any()) } doReturn true }
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(isPro))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val blobGarbageCollector = mock<BlobGarbageCollector>()

        // A locked repository: SettingsViewModel's usage collector reads nothing while the vault
        // is shut, and an unstubbed presentationState would hand its collector a null flow.
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
            blobGarbageCollector = blobGarbageCollector,
            context = ApplicationProvider.getApplicationContext(),
        )
        return Fixture(viewModel, blobGarbageCollector)
    }

    /**
     * The sweep runs off the test dispatcher (`Dispatchers.IO`), so advancing virtual time alone
     * can return while it is still in flight. `isWorking` is the operation's own completion flag —
     * set true synchronously by `runOperation` and cleared only once the whole block has finished.
     */
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
    fun `configuring the decoy reclaims orphaned blobs while a sweep is still safe`() =
        runTest(dispatcher) {
            val f = fixture(isPro = true)

            f.viewModel.setupDuress(GATE_PASSWORD.toCharArray(), "correcthorsebattery".toCharArray())
            f.awaitOperation()

            verifyBlocking(f.blobGarbageCollector) { sweep() }
            assertEquals(
                SettingsMessage.Simple(R.string.settings_message_duress_configured),
                f.viewModel.state.value.message,
            )
        }

    @Test
    fun `removing the decoy reclaims what could not be reclaimed while it existed`() =
        runTest(dispatcher) {
            val f = fixture(isPro = true)

            f.viewModel.removeDuress(GATE_PASSWORD.toCharArray())
            f.awaitOperation()

            verifyBlocking(f.blobGarbageCollector) { sweep() }
            assertEquals(
                SettingsMessage.Simple(R.string.settings_message_duress_removed),
                f.viewModel.state.value.message,
            )
        }

    @Test
    fun `a failed reclaim never turns a completed duress change into an error`() =
        runTest(dispatcher) {
            val f = fixture(isPro = true)
            f.blobGarbageCollector.stub {
                onBlocking { sweep() } doThrow IllegalStateException("blob dir unreadable")
            }

            f.viewModel.removeDuress(GATE_PASSWORD.toCharArray())
            f.awaitOperation()

            // Reclaiming disk is opportunistic; the duress change itself succeeded and must be
            // reported as such. `runOperation` catches everything, so an unwrapped sweep would
            // have told the user their decoy was still configured when it was not.
            assertNull(f.viewModel.state.value.error)
            assertEquals(
                SettingsMessage.Simple(R.string.settings_message_duress_removed),
                f.viewModel.state.value.message,
            )
        }

    private companion object {
        /** Whatever the re-auth gate is handed; the stub above accepts anything. */
        const val GATE_PASSWORD = "TestVaultAbc99"
    }
}
