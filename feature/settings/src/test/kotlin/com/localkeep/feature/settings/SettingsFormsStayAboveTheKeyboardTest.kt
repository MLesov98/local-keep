package com.localkeep.feature.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * The change-master-password form is not left under the keyboard it opens.
 *
 * `MainActivity` is edge-to-edge and keeps the IME out of the shell's padding, so a screen that
 * should sit above the keyboard has to say so itself. Settings never did: its scroll column kept
 * the full window height with the keyboard up, and `LkTextField`'s bring-into-view had nothing to
 * scroll because the viewport never shrank. The Current, New and Confirm wells and the Change
 * password button ended up under the keys, so the master password was typed blind (device,
 * S10-F-11).
 *
 * The keyboard is simulated by dispatching an IME inset to the composition, as
 * `PasswordStepImeScrollTest` does: Robolectric raises none.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsFormsStayAboveTheKeyboardTest {

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
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun settle(ms: Long = 1_000) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private val keyboardPx get() = (240 * compose.density.density).toInt()
    private val keyboardTop get() = (compose.activity.window.decorView.height - keyboardPx).toFloat()

    /** Shows Settings narrowed to the change-password row, which a search draws open. */
    private fun showChangePasswordForm() {
        val vm = viewModel()
        // A search rather than a tap: `perform*` waits for idle, and this screen's aura never goes idle.
        vm.onQueryChanged("rotate credentials")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                SettingsScreen(viewModel = vm, onBack = {}, onHealthClick = {})
            }
        }
        settle()
    }

    private fun scrollToEnd() {
        compose.runOnUiThread {
            val scroller = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),
            )[0].fetchSemanticsNode()
            scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 100_000f)
        }
        settle()
    }

    private fun bottomOf(node: androidx.compose.ui.semantics.SemanticsNode): Float =
        node.positionOnScreen.y + node.size.height

    /**
     * Control: with no keyboard the form is laid out, its three wells and the button are in the
     * tree, and the last of them reaches below where the keyboard will start once scrolled to the
     * end — so the two tests below have something to lift.
     */
    @Test
    fun `without a keyboard the form is present and reaches the keyboard's zone`() {
        showChangePasswordForm()
        assertTrue(
            compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 4,
            "expected the search field and the three password wells to be in the tree",
        )
        scrollToEnd()
        val submit = compose.onNodeWithText(
            compose.activity.getString(R.string.settings_password_submit),
        ).fetchSemanticsNode()
        assertTrue(
            bottomOf(submit) > keyboardTop,
            "the submit button ends at ${bottomOf(submit)}px, above where the keyboard will start " +
                "($keyboardTop px), so this window proves nothing",
        )
    }

    @Test
    fun `the confirm well stays above the keyboard when it takes focus`() {
        showChangePasswordForm()
        scrollToEnd()
        raiseKeyboard(keyboardPx)
        // 0 is the search field; 1..3 are Current, New and Confirm.
        val confirm = compose.onAllNodes(hasSetTextAction())[3]
        compose.runOnUiThread {
            confirm.fetchSemanticsNode().config[SemanticsActions.RequestFocus].action!!.invoke()
        }
        settle(2_000)
        val bottom = bottomOf(compose.onAllNodes(hasSetTextAction())[3].fetchSemanticsNode())
        assertTrue(
            bottom <= keyboardTop,
            "the confirm well ends at ${bottom}px but the keyboard starts at ${keyboardTop}px, so " +
                "the master password is being typed from under the keyboard",
        )
    }

    @Test
    fun `the change password button can be scrolled above the keyboard`() {
        showChangePasswordForm()
        raiseKeyboard(keyboardPx)
        scrollToEnd()
        val submit = compose.onNodeWithText(
            compose.activity.getString(R.string.settings_password_submit),
        ).fetchSemanticsNode()
        assertTrue(
            bottomOf(submit) <= keyboardTop,
            "the button ends at ${bottomOf(submit)}px at the end of the scroll, but the keyboard " +
                "starts at ${keyboardTop}px, so it cannot be brought out from under it",
        )
    }
}
