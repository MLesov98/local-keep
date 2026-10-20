package com.localkeep.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.test.device.DeviceReadyRule
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Both bottom overlays can be up at once — copying a password from a row and then trashing it
 * raises a clipboard countdown *and* a confirmation. As two independently bottom-aligned siblings
 * the second drew straight over the first, so `AppShell` stacks them in a Column instead.
 *
 * This reproduces that arrangement and checks the claim, rather than trusting the reasoning: the
 * two must not overlap, and the countdown must not move when a notice appears above it.
 */
@RunWith(AndroidJUnit4::class)
class OverlayStackingTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    @get:Rule
    val rule = createComposeRule()

    @Before
    @After
    fun reset() {
        LkNotices.clear()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { ClipboardHelper.clearNow(instrumentation.targetContext) }
    }

    /** The same arrangement AppShell uses, minus the nav host. */
    private fun setShellOverlays() {
        rule.setContent {
            LocalKeepTheme {
                Box(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LkNoticeHost()
                        ClipboardCountdown()
                    }
                }
            }
        }
    }

    @Test
    fun aNoticeAndACountdownDoNotOverlap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        rule.mainClock.autoAdvance = false
        setShellOverlays()

        instrumentation.runOnMainSync {
            ClipboardHelper.copy(instrumentation.targetContext, "Password", "hunter2")
        }
        rule.runOnUiThread { LkNotices.post("Moved to trash") }
        rule.mainClock.advanceTimeBy(1_000)

        val notice = rule.onNodeWithText("Moved to trash").fetchSemanticsNode().boundsInRoot
        val countdown = rule.onNodeWithText(copiedLabel()).fetchSemanticsNode().boundsInRoot

        assertTrue(
            notice.bottom <= countdown.top,
            "the confirmation (bottom ${notice.bottom}) overlaps the countdown (top ${countdown.top})",
        )
    }

    /**
     * Weaker than it looks, and recorded as such: this also passes against the pre-fix arrangement,
     * because two independently bottom-aligned overlays do not move each other either — they simply
     * overlap. [aNoticeAndACountdownDoNotOverlap] is the one that discriminates; checked by
     * reproducing the old layout, which fails it with a 102px overlap. This one guards a different
     * regression — a future stack that pushes the countdown up off its resting place.
     */
    @Test
    fun theCountdownDoesNotMoveWhenANoticeAppearsAboveIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        rule.mainClock.autoAdvance = false
        setShellOverlays()

        instrumentation.runOnMainSync {
            ClipboardHelper.copy(instrumentation.targetContext, "Password", "hunter2")
        }
        rule.mainClock.advanceTimeBy(1_000)
        val before = rule.onNodeWithText(copiedLabel()).fetchSemanticsNode().boundsInRoot.bottom

        rule.runOnUiThread { LkNotices.post("Moved to trash") }
        rule.mainClock.advanceTimeBy(1_000)
        val after = rule.onNodeWithText(copiedLabel()).fetchSemanticsNode().boundsInRoot.bottom

        assertTrue(
            kotlin.math.abs(before - after) < 1f,
            "the countdown moved from $before to $after when a confirmation appeared",
        )
    }

    /**
     * The rendered "copied" notice, resolved rather than hardcoded.
     *
     * The instrumentation process runs under the SYSTEM locale, which is not the app's per-app
     * locale. On this machine the system is fr-FR, where this string is "Copié : %1$s" — a different
     * word order, so the literal "Password copied" could never match and all three assertions failed
     * for a reason unrelated to overlay stacking.
     */
    private fun copiedLabel(): String =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.core_ui_clipboard_copied_label, "Password")
}
