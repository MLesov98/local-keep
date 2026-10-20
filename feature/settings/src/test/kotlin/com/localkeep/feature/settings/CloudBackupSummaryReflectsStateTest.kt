package com.localkeep.feature.settings

import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.vault.LocalKeepSettings
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.BlobGarbageCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * F-41: the "Cloud backup" row in Settings showed one static sentence — "Keep the vault file out
 * of Android Auto Backup" — regardless of the live preference, so it read as a claim of state that
 * could contradict the switch on the Backup screen whenever that switch was on.
 *
 * Covers both halves of the fix: [SettingsViewModel.refreshCloudBackupState] re-reading the same
 * preference the Backup screen's switch writes ([LocalKeepSettings]), and [cloudBackupSummaryRes],
 * the pure selection between the two summaries that state drives.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CloudBackupSummaryReflectsStateTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(context: android.content.Context): SettingsViewModel {
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))

        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))

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
            context = context,
        )
    }

    @Test
    fun stateFollowsThePreferenceTheBackupScreenWrites() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        LocalKeepSettings.setCloudBackupDisabled(context, true)
        val excluded = buildViewModel(context)
        assertEquals(
            false,
            excluded.state.value.cloudBackupEnabled,
            "a disabled preference must read as not included",
        )

        LocalKeepSettings.setCloudBackupDisabled(context, false)
        val included = buildViewModel(context)
        assertEquals(
            true,
            included.state.value.cloudBackupEnabled,
            "an enabled preference must read as included",
        )

        // Re-read on re-entry, not just at construction: the Backup screen is one row away and can
        // flip this preference while the same ViewModel instance is still alive.
        LocalKeepSettings.setCloudBackupDisabled(context, true)
        included.refreshCloudBackupState()
        assertEquals(
            false,
            included.state.value.cloudBackupEnabled,
            "must reflect a change made after construction, not just the value it was seeded with",
        )
    }

    @Test
    fun summarySelectionMatchesTheState() {
        assertEquals(
            R.string.settings_cloud_backup_summary_included,
            cloudBackupSummaryRes(cloudBackupEnabled = true),
        )
        assertEquals(
            R.string.settings_cloud_backup_summary,
            cloudBackupSummaryRes(cloudBackupEnabled = false),
        )
    }
}
