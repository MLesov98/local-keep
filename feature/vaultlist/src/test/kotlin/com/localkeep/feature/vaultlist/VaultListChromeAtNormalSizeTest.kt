package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkLockButton
import com.localkeep.core.ui.theme.LocalKeepTheme
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The control for [VaultListChromeScalingTest]: a roomy phone at normal text keeps the single header
 * row the comps draw. The controls only get a row of their own when [com.localkeep.core.ui.theme.LkText.stacks]
 * says the title would be starved — a 411dp window at font scale 1.0 is nowhere near that.
 *
 * Its own class because Robolectric lets a class-level `fontScale` win over a method-level
 * `fontScale = 1.0f` (1.0 is the "unset" value), so the control cannot live beside the 2.0 tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VaultListChromeAtNormalSizeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theControlsShareTheTitleRow() {
        compose.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    VaultTitleBar(
                        title = "Tresor",
                        subtitle = null,
                        leading = { LkIconButton(icon = LkIcons.Menu, label = "Menü", onClick = {}) },
                        trailing = { LkLockButton() },
                        modifier = Modifier.testTag("bar"),
                    )
                }
            }
        }
        compose.waitForIdle()
        val title = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("bar").fetchSemanticsNode().boundsInRoot
        assertTrue(
            title.top - bar.top < 20f * compose.density.density,
            "the title starts ${title.top - bar.top}px below the bar's top: the controls were given a row of their own",
        )
    }
}
