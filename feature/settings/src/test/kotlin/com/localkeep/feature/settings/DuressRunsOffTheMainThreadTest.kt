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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.resetMain
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
 * Setting up and removing the duress vault must not run on the main thread.
 *
 * Removing it ANRed the app for minutes on a device (2026-09-20): `runOperation` launches in
 * `viewModelScope`, which is `Dispatchers.Main.immediate`, and `removeDuress` had no
 * `withContext`, so `StaleContainerSweeper.sweepRemovedDecoy` re-authenticated and re-sealed
 * `vault.enc`, its `.bak` and five snapshots on the thread that draws the UI. `setupDuress` had
 * the identical shape and an Argon2 derivation besides.
 *
 * Observed without a device by making `Dispatchers.Main` a real single-threaded dispatcher with a
 * known thread name and recording, through the mock's default answer, the thread each
 * `VaultLockManager` call arrives on. The lock manager's duress entry points are `internal` to
 * `:core:vault` and cannot be stubbed by name from here; the default answer sees them anyway,
 * because it intercepts at the JVM level.
 *
 * `KeystoreManager`/`VaultLockManager` are mocked for the reason given in
 * `SettingsViewModelBiometricLoadTest`: their real constructors reach AndroidKeyStore, which
 * Robolectric does not implement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuressRunsOffTheMainThreadTest {

    private val mainThreadName = "lk-test-main"
    private val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, mainThreadName)
    }
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    /**
     * Thread names the lock manager's duress calls arrived on, in order. Compared with
     * `startsWith`, not equality: the coroutines debug agent appends " @coroutine#N" to whatever
     * thread it is running on, so an exact match silently never fires.
     */
    private val duressCallThreads = ConcurrentLinkedQueue<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        // Drained before `resetMain`, and `shutdown` rather than `shutdownNow`: this class puts a
        // *real* thread behind `Dispatchers.Main`, and a continuation still posting to it while the
        // next test class calls `setMain` is reported as "Dispatchers.Main is used concurrently
        // with setting it" — a failure in an unrelated class, seen once before this was added.
        mainExecutor.shutdown()
        mainExecutor.awaitTermination(5, TimeUnit.SECONDS)
        Dispatchers.resetMain()
    }

    private fun buildViewModel(isPro: Boolean): SettingsViewModel {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lockManager = mock<VaultLockManager>(
            defaultAnswer = { invocation ->
                if (invocation.method.name.startsWith("setupDuress") ||
                    invocation.method.name.startsWith("removeDuress")
                ) {
                    duressCallThreads += Thread.currentThread().name
                }
                // Both operations are behind a vault-password re-auth. Answered here rather than
                // with `whenever` because this mock already carries a default answer, and a
                // suspend function's erased return type is Object — the empty-value default is
                // null, which Kotlin would unbox into an exception before the gate could pass.
                if (invocation.method.name.startsWith("reauthenticate")) {
                    true
                } else {
                    Mockito.RETURNS_DEFAULTS.answer(invocation)
                }
            },
        )
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        val keystoreManager = mock<KeystoreManager>()
        val biometricUnlock = mock<BiometricUnlock>()
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(isPro))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))
        val breachFilter = mock<BreachFilter>()
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

    /** Waits for the operation to finish, so a recorded thread is a completed call, not a race. */
    private fun awaitIdle(viewModel: SettingsViewModel) {
        var attempts = 0
        while (attempts < 300) {
            if (duressCallThreads.isNotEmpty() && !viewModel.state.value.isWorking) return
            Thread.sleep(10)
            attempts++
        }
    }

    @Test
    fun `removing the duress vault does not run on the main thread`() {
        val viewModel = buildViewModel(isPro = true)

        viewModel.removeDuress(GATE_PASSWORD.toCharArray())
        awaitIdle(viewModel)

        assertEquals(1, duressCallThreads.size, "the removal never reached the lock manager")
        assertTrue(
            duressCallThreads.none { it.startsWith(mainThreadName) },
            "duress removal ran on the main thread: $duressCallThreads",
        )
    }

    @Test
    fun `setting up the duress vault does not run on the main thread`() {
        val viewModel = buildViewModel(isPro = true)

        viewModel.setupDuress(GATE_PASSWORD.toCharArray(), "DuressPassword99".toCharArray())
        awaitIdle(viewModel)

        assertEquals(1, duressCallThreads.size, "the setup never reached the lock manager")
        assertTrue(
            duressCallThreads.none { it.startsWith(mainThreadName) },
            "duress setup ran on the main thread: $duressCallThreads",
        )
    }

    private companion object {
        /** Whatever the re-auth gate is handed; the default answer above accepts anything. */
        const val GATE_PASSWORD = "TestVaultAbc99"
    }
}
