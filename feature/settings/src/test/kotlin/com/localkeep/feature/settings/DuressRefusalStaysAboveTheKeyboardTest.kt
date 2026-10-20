package com.localkeep.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.BlobGarbageCollector
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The duress "at least 12 characters" refusal is on screen while the password is typed (D3-L-15).
 *
 * The refusal appears the moment the first character is typed, below the two wells. With the keyboard
 * up and the form scrolled to its end, the line was added past the bottom of the viewport and sat
 * under the keys: only its top few pixels were above them, so the person typing saw the Set up
 * button stay grey and nothing say why.
 *
 * The keyboard is dispatched as an inset, as `SettingsFormsStayAboveTheKeyboardTest` does.
 *
 * FAILS WITHOUT THE FIX: the refusal's bottom is below the keyboard's top edge.
 * CONTROL: with a long enough password there is no refusal at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DuressRefusalStaysAboveTheKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel {
        val lockManager = mock<VaultLockManager>()
        whenever(lockManager.lockState).thenReturn(MutableStateFlow(LockState.Locked))
        val repository = mock<VaultRepository>()
        whenever(repository.presentationState)
            .thenReturn(MutableStateFlow(VaultPresentationState.Locked))
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(true))
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

    private fun settle(ms: Long = 1_000) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun raiseKeyboard(heightPx: Int) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, heightPx))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        compose.runOnUiThread {
            fun deliver(v: android.view.View) {
                if (v.javaClass.name.contains("AndroidComposeView")) {
                    ViewCompat.dispatchApplyWindowInsets(v, insets)
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) deliver(v.getChildAt(i))
            }
            deliver(compose.activity.window.decorView)
        }
        settle()
    }

    private val keyboardPx get() = (240 * compose.density.density).toInt()
    private val keyboardTop get() = (compose.activity.window.decorView.height - keyboardPx).toFloat()

    private fun scrollToEnd() {
        compose.runOnUiThread {
            val scroller = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),
            )[0].fetchSemanticsNode()
            scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f)
        }
        settle()
    }

    private fun showDuressForm(): SettingsViewModel {
        val vm = viewModel()
        // A search rather than a tap: `perform*` waits for idle, and this screen's aura never goes idle.
        vm.onQueryChanged("coercion")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme { SettingsScreen(viewModel = vm, onBack = {}, onHealthClick = {}) }
        }
        settle()
        scrollToEnd()
        raiseKeyboard(keyboardPx)
        // Where a person typing is: the first duress well, whose bottom edge is just above the
        // keyboard, which is what the keyboard's own bring-into-view leaves when that field takes
        // focus. 0 is the search field; 1 and 2 are the duress password and its confirmation.
        val first = compose.onAllNodes(hasSetTextAction())[1].fetchSemanticsNode()
        val target = keyboardTop - 8 * compose.density.density
        val dy = first.positionOnScreen.y + first.size.height - target
        compose.runOnUiThread {
            val scroller = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),
            )[0].fetchSemanticsNode()
            scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy)
        }
        settle()
        return vm
    }

    private fun refusals() = compose.onAllNodes(
        hasText(compose.activity.getString(R.string.settings_error_duress_password_too_short, 12)),
    ).fetchSemanticsNodes()

    @Test
    fun `the length refusal is above the keyboard when the first character is typed`() {
        val vm = showDuressForm()
        compose.runOnUiThread { vm.updateDuressForm(DuressFormState(password = "a")) }
        settle(2_000)

        val nodes = refusals()
        assertTrue(nodes.size == 1, "expected the refusal to be drawn, found ${nodes.size}")
        val bottom = nodes[0].positionOnScreen.y + nodes[0].size.height
        assertTrue(
            bottom <= keyboardTop,
            "the refusal ends at ${bottom}px but the keyboard starts at ${keyboardTop}px",
        )
    }

    @Test
    fun `a long enough password draws no refusal`() {
        val vm = showDuressForm()
        compose.runOnUiThread { vm.updateDuressForm(DuressFormState(password = "a".repeat(12))) }
        settle(2_000)
        assertTrue(refusals().isEmpty(), "a 12-character password was refused")
    }
}
