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
import java.util.concurrent.ConcurrentLinkedQueue
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
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Arming or disarming the duress vault must prove the vault password first.
 *
 * Neither did (device, 2026-09-20): the two most security-critical rows in Settings were the only
 * ones with no re-authentication at all, while the Recovery Kit and invisible-items gates both ask.
 * Anyone holding the unlocked phone for a minute could set a decoy password of their own, or remove
 * the decoy the owner is relying on, and the owner would find out at the unlock screen.
 *
 * Observed through the mock's default answer, which records every `VaultLockManager` call by name:
 * the duress entry points are `internal` to `:core:vault` and cannot be named from this module, but
 * the answer intercepts at the JVM level, so "did the operation reach the vault at all" is
 * answerable either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuressChangesRequireTheVaultPasswordTest {

    private val dispatcher = StandardTestDispatcher()

    /** Names of the lock-manager methods each operation reached, in order. */
    private val calls = ConcurrentLinkedQueue<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(reauthenticates: Boolean): SettingsViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lockManager = mock<VaultLockManager>(
            defaultAnswer = { invocation ->
                val name = invocation.method.name
                if (name.startsWith("setupDuress") ||
                    name.startsWith("removeDuress") ||
                    name.startsWith("reauthenticate")
                ) {
                    calls += name.substringBefore('$')
                }
                // A suspend function's erased return type is Object, so the empty-value default is
                // null — which Kotlin would unbox into an exception rather than a refusal. The gate
                // has to answer with a real Boolean.
                if (name.startsWith("reauthenticate")) {
                    reauthenticates
                } else {
                    Mockito.RETURNS_DEFAULTS.answer(invocation)
                }
            },
        )
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(true))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))

        return SettingsViewModel(
            lockManager = lockManager,
            repository = repository,
            keystoreManager = mock<KeystoreManager>(),
            biometricUnlock = mock<BiometricUnlock>(),
            entitlement = entitlement,
            breachFilter = mock<BreachFilter>(),
            blobGarbageCollector = mock<BlobGarbageCollector>(),
            context = context,
        )
    }

    /**
     * The gate runs on `Dispatchers.Default`, a real pool, so advancing virtual time alone can
     * return while it is in flight. `isWorking` is the operation's own completion flag.
     */
    private fun awaitOperation(viewModel: SettingsViewModel) {
        var attempts = 0
        while (attempts < 200) {
            dispatcher.scheduler.advanceUntilIdle()
            if (!viewModel.state.value.isWorking) return
            Thread.sleep(10)
            attempts++
        }
    }

    @Test
    fun `removal is refused when the vault password is wrong, and never reaches the vault`() =
        runTest(dispatcher) {
            val viewModel = buildViewModel(reauthenticates = false)

            viewModel.removeDuress("not-the-vault-password".toCharArray())
            awaitOperation(viewModel)

            assertEquals(
                SettingsMessage.Simple(R.string.settings_error_wrong_current_password),
                viewModel.state.value.error,
            )
            assertTrue(
                calls.none { it == "removeDuress" },
                "the decoy was removed without the vault password: $calls",
            )
        }

    @Test
    fun `setup is refused when the vault password is wrong, and never reaches the vault`() =
        runTest(dispatcher) {
            val viewModel = buildViewModel(reauthenticates = false)

            viewModel.setupDuress(
                "not-the-vault-password".toCharArray(),
                "DuressPassword99".toCharArray(),
            )
            awaitOperation(viewModel)

            assertEquals(
                SettingsMessage.Simple(R.string.settings_error_wrong_current_password),
                viewModel.state.value.error,
            )
            assertTrue(
                calls.none { it == "setupDuress" },
                "a decoy was armed without the vault password: $calls",
            )
        }

    @Test
    fun `the right vault password lets both changes through`() = runTest(dispatcher) {
        val removal = buildViewModel(reauthenticates = true)
        removal.removeDuress("TestVaultAbc99".toCharArray())
        awaitOperation(removal)
        assertEquals(
            SettingsMessage.Simple(R.string.settings_message_duress_removed),
            removal.state.value.message,
        )

        calls.clear()
        val setup = buildViewModel(reauthenticates = true)
        setup.setupDuress("TestVaultAbc99".toCharArray(), "DuressPassword99".toCharArray())
        awaitOperation(setup)
        assertEquals(
            SettingsMessage.Simple(R.string.settings_message_duress_configured),
            setup.state.value.message,
        )
    }
}
