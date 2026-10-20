package com.localkeep.feature.vaultlist

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.model.Folder
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Hardware ENTER on the folder rename field has to commit, exactly like the on-screen Done action.
 *
 * Found driving a device: a physical keyboard's ENTER cancelled the rename instead of saving it,
 * while the on-screen checkmark worked. `performKeyInput` sends a real down/up `KeyEvent` at the
 * focused node rather than a touch, which is the one thing `uiautomator`'s coordinate taps cannot
 * isolate from the on-screen path — this test exercises hardware input specifically. The field is
 * put into focus explicitly rather than relying on the dialog's own `LaunchedEffect` — the key
 * event goes nowhere from an unfocused node, and this way a future focus regression fails loudly
 * here instead of silently passing a test of nothing.
 *
 * The label is read from the string resource rather than hardcoded: this suite runs under whatever
 * locale the instrumentation process picks up (French, on this machine), and matching literal
 * English text against a French tree is exactly the "present control reads as absent" failure this
 * repo has already chased down three times for IME occlusion.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RenameFolderHardwareEnterTest {

    @get:Rule
    val rule = createComposeRule()

    private val folder = Folder(id = UUID.randomUUID(), name = "Alpha")

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun hardwareEnterCommitsRatherThanCancels() {
        var confirmed = 0
        var dismissed = 0
        var text = "Renamed"
        rule.setContent {
            LocalKeepTheme {
                RenameFolderDialog(
                    folder = folder,
                    value = text,
                    onValueChange = { text = it },
                    onDismiss = { dismissed++ },
                    onConfirm = { confirmed++ },
                )
            }
        }

        val label = context.getString(R.string.vaultlist_folders_rename_label, folder.name)
        val field = rule.onNodeWithContentDescription(label)
        field.requestFocus()
        field.assertIsFocused()

        field.performKeyInput { pressKey(Key.Enter) }

        assertEquals(1, confirmed, "hardware ENTER should call onConfirm, the same as the on-screen Done action")
        assertEquals(0, dismissed, "hardware ENTER must not cancel the rename")
    }
}
