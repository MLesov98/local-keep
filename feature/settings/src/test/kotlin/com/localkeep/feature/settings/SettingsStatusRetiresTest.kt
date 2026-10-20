package com.localkeep.feature.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.vault.AutoLockPreset
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * Settings' status lines go away when they have said what they had to say.
 *
 * Device, 2026-10-04 (API 36, foss release): "Auto-lock updated" was still on screen more than two
 * minutes after the chip was tapped; "That password is too easy to guess…" stayed beside a form
 * the user had already corrected. Both lived in `SettingsUiState` and nothing but the next
 * operation, or leaving Settings, ever cleared them — while every other confirmation in the app
 * goes through `LkNotices`, floats, and retires after four seconds.
 *
 * Two different rules, because the two channels mean different things:
 *
 *  - **A confirmation of a change that already happened** is handed to `LkNotices`, like the
 *    app's other confirmations. [isTransientConfirmation] says which ones those are.
 *  - **An error persists until it is dealt with** (`LkNotices`' own rule), and editing the form it
 *    was about is dealing with it.
 *
 * The password-change result is neither, and stays: it carries the Recovery Kit and biometric
 * instructions and the counted backup-copy clauses tagged security copy, which a four-second
 * timer would retire before they were read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStatusRetiresTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Which messages retire ------------------------------------------------------------------

    @Test
    fun `every confirmation of a finished change is transient`() {
        listOf(
            R.string.settings_message_autolock_updated,
            R.string.settings_message_biometric_enabled,
            R.string.settings_message_biometric_disabled,
            R.string.settings_message_duress_configured,
            R.string.settings_message_duress_removed,
            R.string.settings_message_pro_restored,
        ).forEach { res ->
            assertTrue(
                SettingsMessage.Simple(res).isTransientConfirmation(),
                "${context.resources.getResourceEntryName(res)} confirms something already done",
            )
        }
    }

    @Test
    fun `a message that still asks something of the user stays`() {
        assertFalse(
            SettingsMessage.PasswordChanged(
                recoveryKitVoided = true,
                biometricDisabled = true,
                reKeyedCount = 2,
                destroyedCount = 0,
                leftBehindCount = 1,
                blobsPendingCount = 0,
            ).isTransientConfirmation(),
            "make a new kit, enable biometrics again, a copy the old password still opens",
        )
        assertFalse(
            SettingsMessage.Simple(com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_pending)
                .isTransientConfirmation(),
            "a pending purchase has not happened yet",
        )
        assertFalse(
            SettingsMessage.Simple(com.localkeep.core.ui.R.string.core_ui_pro_gate_purchase_already_owned)
                .isTransientConfirmation(),
            "names an action on this screen",
        )
    }

    // --- The hand-over to LkNotices -------------------------------------------------------------

    @Test
    fun `a relayed confirmation leaves the screen's state`() {
        val viewModel = buildViewModel()
        viewModel.setAutoLockPreset(AutoLockPreset.RELAXED)
        val posted = assertNotNull(viewModel.state.value.message)

        viewModel.consumeMessage(posted)

        assertNull(viewModel.state.value.message, "the confirmation stayed on screen indefinitely")
    }

    @Test
    fun `relaying never clears a message that replaced it`() {
        val viewModel = buildViewModel()
        viewModel.setAutoLockPreset(AutoLockPreset.RELAXED)
        val stale = assertNotNull(viewModel.state.value.message)
        // Something else took the slot before the relay for the first one ran.
        viewModel.disableBiometric()
        dispatcher.scheduler.advanceUntilIdle()
        val replacement = SettingsMessage.Simple(R.string.settings_message_biometric_disabled)
        assertEquals(replacement, viewModel.state.value.message, "control: the slot was replaced")

        viewModel.consumeMessage(stale)

        assertEquals(replacement, viewModel.state.value.message)
    }

    @Test
    fun `the screen relays transient confirmations instead of drawing them`() {
        val screen = File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "LkNotices.post(" in screen && "viewModel.consumeMessage(" in screen,
            "transient confirmations must go to the app-wide notice host and leave the state",
        )
        assertTrue(
            "isTransientConfirmation()" in screen,
            "the inline note must skip what the notice host is already showing",
        )
    }

    // --- Errors clear when the form they were about changes -------------------------------------

    @Test
    fun `editing the password form clears the error about it`() {
        val viewModel = buildViewModel()
        viewModel.changePassword("current-password".toCharArray(), "short".toCharArray())
        assertNotNull(viewModel.state.value.error, "control: the short password was refused")

        viewModel.updatePasswordForm(viewModel.passwordForm.value.copy(newPassword = "a much longer one"))

        assertNull(
            viewModel.state.value.error,
            "the refusal stayed beside a form the user had already corrected",
        )
    }

    @Test
    fun `re-sending an unchanged form leaves the error alone`() {
        // The control. Nothing the user typed changed, so nothing has been dealt with.
        val viewModel = buildViewModel()
        viewModel.changePassword("current-password".toCharArray(), "short".toCharArray())
        val error = assertNotNull(viewModel.state.value.error)

        viewModel.updatePasswordForm(viewModel.passwordForm.value)

        assertEquals(error, viewModel.state.value.error)
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
