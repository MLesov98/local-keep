package com.localkeep.feature.settings

import android.content.Context
import android.provider.Settings
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Reduce haptics" follows the system's touch-feedback switch while Settings stays open.
 *
 * Device, 2026-10-04 (API 36, foss release): with Settings open, switching the system's touch
 * feedback off left the row unchecked and enabled; leaving Settings and entering it again showed
 * it CHECKED and DISABLED, "Already off: touch feedback is disabled system-wide". The row read the
 * system value in a `remember {}`, once per composition — the same defect the autofill row had —
 * and the system Settings app covers this one without disposing it, so coming back recomposes
 * nothing. It now rides the one resume re-read the autofill and biometric rows already use
 * (see [AutofillRowRefreshesOnReturnTest]), rather than a second mechanism.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HapticsRowRefreshesOnReturnTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        Dispatchers.resetMain()
    }

    @Test
    fun `turning system touch feedback off is picked up by the refresh`() {
        touchFeedback(enabled = true)
        val viewModel = buildViewModel()
        assertFalse(viewModel.state.value.systemHapticsOff, "control: on at entry")

        // The user leaves for the system's sound & vibration page, switches it off, comes back.
        touchFeedback(enabled = false)
        viewModel.refreshSystemCapabilities()

        assertTrue(
            viewModel.state.value.systemHapticsOff,
            "the row must turn CHECKED + DISABLED on return, as it does on a fresh entry",
        )
    }

    @Test
    fun `turning it back on is picked up too`() {
        touchFeedback(enabled = false)
        val viewModel = buildViewModel()
        assertTrue(viewModel.state.value.systemHapticsOff, "control: off at entry")

        touchFeedback(enabled = true)
        viewModel.refreshSystemCapabilities()

        assertFalse(viewModel.state.value.systemHapticsOff)
    }

    /** And the row reads the refreshed value instead of its own one-shot copy. */
    @Test
    fun `the haptics row reads the refreshed state`() {
        val screen = File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertFalse(
            "remember { A11yPreferences.systemHapticsDisabled(context) }" in screen,
            "the row reads the system value once per composition, so it goes stale while open",
        )
        assertTrue("state.systemHapticsOff" in screen, "the row must read the refreshed state")
    }

    private fun touchFeedback(enabled: Boolean) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            if (enabled) 1 else 0,
        )
    }

    private fun buildViewModel(): SettingsViewModel {
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
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
}
