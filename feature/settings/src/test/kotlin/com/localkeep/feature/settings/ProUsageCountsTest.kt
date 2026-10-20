package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.model.Card
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AttachmentQuota
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.ProTypeQuota
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultStorageInventory
import com.localkeep.core.vault.VaultVisibilitySnapshot
import com.localkeep.feature.files.BlobGarbageCollector
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * What the Pro row says the vault holds must be what the vault holds.
 *
 * Two failures are guarded here, and they fail in opposite directions.
 *
 * **Clamping.** An implementation that reached for `coerceAtMost(FREE_LIMIT)` — the obvious way to
 * make a fraction "look right" — passes every at-or-below fixture and is wrong only above the cap.
 * Above the cap is a supported state: import is never charged against the cap, so a KDBX vault with
 * nine files and three cards imports whole and every one of them is kept. That is why the over-cap
 * fixture is here and why it is the half of this test that earns its keep.
 *
 * **Reading a locked vault.** Both quota functions route through `VaultRepository.requireVault`,
 * whose first line is `check(state is LockState.Unlocked) { "vault is locked" }`. An unguarded
 * collector throws there — on construction in the six sibling tests that stub a locked vault, and
 * on-device the moment auto-lock fires with Settings open. `usageIsAbsentRatherThanZeroWhenLocked`
 * is red for that reason, not for an assertion, which is what makes it worth having.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProUsageCountsTest {

    /**
     * Unconfined rather than Standard, and an explicit await below rather than `advanceUntilIdle`.
     *
     * The collector computes inside `withContext(Dispatchers.Default)`, which leaves the test
     * scheduler for a real thread, so `advanceUntilIdle` returns before the result has come back
     * and the assertion reads a null that is an artifact of the harness rather than of the code.
     * Waiting on the state itself is the honest wait.
     */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Suspends until the collector has published counts, rather than assuming it already has. */
    private suspend fun SettingsViewModel.awaitUsage(): ProUsageCounts =
        state.first { it.proUsage != null }.proUsage!!

    private fun login() = item(Login())
    private fun file() = item(File(filename = "passport.jpg"))
    private fun card() = item(Card(holder = "Jane Doe"))
    private fun identity() = item(Identity(firstName = "Jane"))

    private fun item(content: com.localkeep.core.model.ItemContent) = VaultItem(
        id = UUID.randomUUID(),
        title = "x",
        content = content,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun buildViewModel(
        items: List<VaultItem>?,
    ): SettingsViewModel {
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))

        val repository = mock<VaultRepository>()
        if (items == null) {
            whenever(repository.presentationState)
                .thenReturn(MutableStateFlow(VaultPresentationState.Locked))
        } else {
            whenever(repository.presentationState).thenReturn(
                MutableStateFlow(VaultPresentationState.Unlocked(mock<VaultVisibilitySnapshot>())),
            )
            whenever(repository.getCompleteStorageInventory())
                .thenReturn(VaultStorageInventory(allItems = items, allFolders = emptyList()))
        }

        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        whenever(entitlement.displayPrice).thenReturn(MutableStateFlow(null))

        return SettingsViewModel(
            lockManager = lockManager,
            repository = repository,
            keystoreManager = mock<KeystoreManager>(),
            biometricUnlock = mock<BiometricUnlock>(),
            entitlement = entitlement,
            breachFilter = mock<BreachFilter>(),
            blobGarbageCollector = mock<BlobGarbageCollector>(),
            context = ApplicationProvider.getApplicationContext(),
        )
    }

    @Test
    fun countsAreWhatTheQuotaObjectsSay() = runTest {
        val items = List(3) { file() } + card() + List(5) { login() }
        val viewModel = buildViewModel(items)
        val usage = viewModel.awaitUsage()

        assertEquals(3, usage.files, "files must count File items")
        assertEquals(1, usage.cards)
        assertEquals(0, usage.identities, "a category holding nothing still reports zero")

        // Tied to the quota objects rather than to literals, so a cap change cannot leave this
        // test asserting a number the app no longer enforces.
        assertEquals(AttachmentQuota.used(items), usage.files)
        assertEquals(ProTypeQuota.used(items, ProTypeQuota.Kind.CARD), usage.cards)
        assertEquals(ProTypeQuota.used(items, ProTypeQuota.Kind.IDENTITY), usage.identities)
    }

    @Test
    fun anImportedVaultReportsWhatItActuallyHoldsRatherThanTheCap() {
        // The load-bearing case. 9 > 7 and 3 > 1: a clamping implementation returns 7 and 1 here
        // and passes every other assertion in this file.
        runTest {
            val items = List(9) { file() } + List(3) { card() } + List(2) { identity() }
            val viewModel = buildViewModel(items)
            val usage = viewModel.awaitUsage()

            assertEquals(9, usage.files, "clamped to the cap instead of reporting what is held")
            assertEquals(3, usage.cards, "clamped to the cap instead of reporting what is held")
            assertEquals(2, usage.identities, "clamped to the cap instead of reporting what is held")
        }
    }

    @Test
    fun usageIsAbsentRatherThanZeroWhenLocked() = runTest {
        // No exception is the assertion as much as the null is: an unguarded read throws
        // IllegalStateException("vault is locked") before this line is reached.
        val viewModel = buildViewModel(items = null)
        advanceUntilIdle()

        assertNull(
            viewModel.state.value.proUsage,
            "a locked vault must say nothing; 0 of 7 is a false statement about a vault with seven",
        )
    }
}
