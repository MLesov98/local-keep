package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The empty vault's copy ends clear of the resting FAB (SCL-037).
 *
 * Device pass 2026-10-19, 308x548dp at the default text size: the panel reserves `gutterFab` below
 * itself, which keeps its button off the FAB's row but does nothing for a window short enough that
 * the copy is *beside* the FAB. Body copy at x 30..278dp, y 367..429; FAB at x 230..288, y 348..406,
 * so the FAB sat over the last ~48dp of the first two lines ("anyth…", "enc…"). The list's rows and
 * the search row already give up their end to a FAB on top of them; this panel did not.
 *
 * Composed from [EmptyVault] itself with the modifier the screen gives it, because the screen is
 * Hilt-backed and never reaches idle under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w308dp-h548dp-xhdpi", fontScale = 1.0f)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmptyVaultClearsTheFabTest {

    @get:Rule
    val compose = createComposeRule()

    private var fab by mutableStateOf<Rect?>(null)

    private val body: String
        get() = RuntimeEnvironment.getApplication().getString(R.string.vaultlist_empty_body_default)

    private fun show() {
        fab = null
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxSize()) {
                    EmptyVault(
                        query = "",
                        filtered = false,
                        onClear = {},
                        modifier = Modifier.padding(top = 48.dp, bottom = LkFrame.gutterFab),
                        fab = { fab },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun copy() = compose.onNodeWithText(body, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow

    /** A FAB over the last ~48dp of the copy's first lines, as captured. */
    private fun parkTheFabOverTheCopy(): Rect {
        val d = compose.density.density
        val c = copy()
        return Rect(c.right - 48f * d, c.top - 20f * d, c.right + 10f * d, c.top + 38f * d).also {
            fab = it
            compose.waitForIdle()
        }
    }

    @Test
    fun theCopyEndsClearOfTheFab() {
        show()
        val parked = parkTheFabOverTheCopy()
        val after = copy()
        assertTrue(
            after.right <= parked.left + 1f,
            "the copy spans ..${after.right}px against a FAB starting at ${parked.left}px: it is still under it",
        )
    }

    /** Control: with no FAB over the panel the copy keeps the width it had. */
    @Test
    fun controlWithoutAFabTheCopyKeepsItsWidth() {
        show()
        val before = copy()
        val d = compose.density.density
        fab = Rect(before.left, before.bottom + 300f * d, before.left + 58f * d, before.bottom + 358f * d)
        compose.waitForIdle()
        assertEquals(before.right, copy().right, 0.5f)
    }
}
