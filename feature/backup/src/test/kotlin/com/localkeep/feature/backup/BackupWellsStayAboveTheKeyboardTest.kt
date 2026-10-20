package com.localkeep.feature.backup

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The export passphrase well is not left under the keyboard it opens.
 *
 * `MainActivity` is edge-to-edge and keeps the IME out of the shell's padding, so a screen that
 * should sit above the keyboard has to say so itself. This one's scroll region kept the full height
 * of the window with the keyboard up, so a well in the lower half of the page was typed into from
 * under the keys, and `LkTextField`'s bring-into-view had nothing to scroll because the viewport
 * never shrank.
 *
 * The keyboard is simulated by dispatching an IME inset to the composition, as
 * `PasswordStepImeScrollTest` does: Robolectric raises none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupWellsStayAboveTheKeyboardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        BackupFocus.clear()
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun show() {
        val viewModel = BackupSettingsViewModel(
            application = app,
            vaultExporter = mock(),
            vaultImporter = mock(),
            rollingBackupManager = mock(),
            vaultLockManager = mock<VaultLockManager>(),
            vaultRepository = mock<VaultRepository>(),
        )
        compose.setContent {
            LocalKeepTheme {
                BackupSettingsScreen(
                    viewModel = viewModel,
                    onBack = {},
                    recoveryKitVoidedNotice = R.string.backup_status_import_complete,
                )
            }
        }
        settle(2_000)
        compose.onNodeWithText(app.getString(R.string.backup_export_section)).performScrollTo().performClick()
        settle()
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

    private val keyboardPx get() = (300 * compose.density.density).toInt()
    private val windowHeight get() = compose.activity.window.decorView.height
    private val keyboardTop get() = (windowHeight - keyboardPx).toFloat()

    private fun lastWell() = compose.onAllNodes(hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1] }

    private fun bottomOfLastWell(): Float =
        lastWell().fetchSemanticsNode().let { it.positionOnScreen.y + it.size.height }

    /** Scrolls the last well to the foot of the window, where a keyboard would land on it. */
    private fun parkLastWellLow(): Float {
        lastWell().performScrollTo()
        settle()
        val spare = windowHeight - bottomOfLastWell() - 8f
        compose.runOnUiThread {
            val scroller = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy),
            )[0].fetchSemanticsNode()
            scroller.config[SemanticsActions.ScrollBy].action!!.invoke(0f, -spare)
        }
        settle()
        return bottomOfLastWell()
    }

    /**
     * Control: with the export section open the passphrase well exists and can be parked in the
     * zone a keyboard covers, so the test below has something to lift.
     */
    @Test
    fun `without a keyboard the well can sit where the keyboard will be`() {
        show()
        val before = parkLastWellLow()
        assertTrue(
            before > keyboardTop && before <= windowHeight,
            "the well ends at ${before}px; the keyboard will start at ${keyboardTop}px of $windowHeight" +
                "px, so this window proves nothing",
        )
    }

    @Test
    fun `the focused export well ends above the keyboard`() {
        show()
        parkLastWellLow()
        raiseKeyboard(keyboardPx)
        compose.runOnUiThread {
            lastWell().fetchSemanticsNode().config[SemanticsActions.RequestFocus].action!!.invoke()
        }
        settle(2_000)
        val bottom = bottomOfLastWell()
        assertTrue(
            bottom <= keyboardTop,
            "the well ends at ${bottom}px but the keyboard starts at ${keyboardTop}px, so the " +
                "passphrase is being typed from under the keyboard",
        )
    }
}
