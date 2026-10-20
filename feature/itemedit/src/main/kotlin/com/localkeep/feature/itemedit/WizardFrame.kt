package com.localkeep.feature.itemedit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.localkeep.core.ui.theme.LkFrame
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LocalLkChromeUnpinned
import com.localkeep.core.ui.theme.rememberLkKeyboardUnpinsChrome

/**
 * The frame the card and identity wizards share: a progress rail, a scrolling body, and the
 * Continue stack, inside the keyboard-aware root both used to build by hand.
 *
 * **Keyboard up on a window too short for it, the rail and the Continue stack stop being pinned**
 * and ride in the scroll with the body — the app's one keyboard-up rule (see
 * [rememberLkKeyboardUnpinsChrome]), published to the body as [LocalLkChromeUnpinned] so the card
 * face can stop pinning itself too. Pinned, the rail, the 24dp under the body and Continue claimed
 * the window first and the scroll region got whatever was left; at 308dp wide and 2.0 text with the
 * keyboard up that left the field being typed into a sliver (SCL-001's class, reached through the
 * wizards). Pinned again the moment the keyboard closes.
 *
 * The body keeps its place in the tree in both arrangements — only the rail and [actions] move — so
 * a focused field survives the keyboard's arrival. Moving the body would dispose the field the
 * keyboard was raised for, which closes the keyboard, which re-pins the chrome: a loop.
 *
 * Every step change also puts the scroll back at the top ([step]): one [ScrollState] outlives the
 * step it was scrolled for, so a step used to arrive half-scrolled with its heading cut under the
 * rail (SCL-031). And then shows the step's primary field ([arrival]) — on a window of 640dp or
 * less the card face, the heading and the rail left step one's number well below the fold with no
 * hint that it was there (SCL-029). Arriving still takes no focus, on purpose: the first tap is the
 * user saying they are ready for the keyboard.
 */
@Composable
internal fun WizardFrame(
    keyboard: CardWizardKeyboard,
    scroll: ScrollState,
    step: Int,
    /** The gap between a pinned rail and the scroll region below it. */
    railGap: Dp,
    rail: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    /** The step's primary field, to be shown on arrival. Null where the step opens on screen. */
    arrival: BringIntoViewRequester? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val unpinned = rememberLkKeyboardUnpinsChrome()
    LaunchedEffect(step) {
        scroll.scrollTo(0)
        if (arrival != null) {
            // Two frames, as the focus request that follows an advance waits: the step being entered
            // has to be laid out before its field has a rectangle to bring. A no-op where the field
            // is already showing, so a window the step fits in does not move.
            withFrameNanos {}
            withFrameNanos {}
            try {
                arrival.bringIntoView()
            } catch (_: IllegalStateException) {
                // A step with no primary field on it (Review): nothing to show.
            }
        }
    }
    CompositionLocalProvider(LocalLkChromeUnpinned provides unpinned) {
        Column(
            Modifier
                .fillMaxSize()
                // Read what the ancestors consumed before imePadding, so the keyboard's progress is
                // measured on the inset imePadding really applies — see CardWizard.
                .then(keyboard.consumedInsetsModifier())
                .imePadding()
                .padding(horizontal = LkFrame.horizontal)
                // The 46dp above and 32dp below are chrome too: pinned, they are 78dp the scroll
                // region never gets, so with the rule on they move into the scroll with the rest.
                .then(if (unpinned) Modifier else Modifier.padding(top = LkFrame.top, bottom = LkFrame.bottom)),
        ) {
            if (!unpinned) {
                rail()
                Spacer(Modifier.height(railGap))
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .then(if (unpinned) Modifier.padding(top = LkFrame.top, bottom = LkFrame.bottom) else Modifier),
                verticalArrangement = Arrangement.spacedBy(LkSpace.lg),
            ) {
                if (unpinned) rail()
                body()
                if (unpinned) actions()
            }
            if (!unpinned) {
                Spacer(Modifier.height(LkSpace.lg))
                actions()
            }
        }
    }
}
