package com.localkeep.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the shell's floating overlays — the clipboard countdown and the notice host — are
 * taking at the bottom of the screen right now.
 *
 * The two of them are mounted once by the shell and float above whatever screen is showing, which
 * is what keeps a copy from shoving the whole list up by 60dp. The cost is that they know nothing
 * about the screen underneath: copying a password from the vault list put the countdown card and
 * its "Clear now" button directly on top of the Add FAB, covering it completely for the 30 seconds
 * the countdown runs (measured 2026-08-10 — the FAB's node disappeared from the accessibility tree
 * entirely while the card was up).
 *
 * Rather than have every bottom-anchored control guess, the shell reports what it actually measured
 * and they lift by exactly that. Zero when nothing is showing, so a screen with no overlay is
 * unaffected.
 */
object LkShellOverlays {
    internal var heightPx by mutableIntStateOf(0)
        private set

    /** Applied by the shell to the container holding the overlays. */
    fun measureModifier(): Modifier = Modifier.onSizeChanged { heightPx = it.height }
}

/**
 * The gap a bottom-anchored control needs to clear the shell's overlays. See [LkShellOverlays].
 *
 * Not animated here: the overlays animate in and out themselves, and a control that slid on its own
 * schedule would arrive at a different time than the card it is avoiding. Call sites that want the
 * movement smoothed should animate this value.
 */
@Composable
fun shellOverlayInset(): Dp {
    val px = LkShellOverlays.heightPx
    return if (px == 0) 0.dp else with(LocalDensity.current) { px.toDp() }
}
