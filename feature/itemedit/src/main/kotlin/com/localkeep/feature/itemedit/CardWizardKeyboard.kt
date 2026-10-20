package com.localkeep.feature.itemedit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.localkeep.core.ui.theme.LkIconButton
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkMotion
import com.localkeep.core.ui.theme.LkShape
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LkTextButton
import com.localkeep.core.ui.theme.LkTheme
import kotlin.math.roundToInt

/*
 * The card wizard's keyboard-up ("typing") layout, as one number.
 *
 * Everything that changes when the keyboard rises — the card face folding to a strip, the stacked
 * buttons giving way to Continue alone — is driven by [CardWizardKeyboard.effective], the keyboard's
 * own progress in 0..1, read in the layout and draw phases. That is what makes it one motion with the
 * keyboard: nothing here has a timer of its own, and when the system animates the inset the layout
 * follows it frame by frame, exactly as `imePadding()` does.
 */

/** How long a step change is allowed to dip the keyboard before the typing layout lets go. */
private const val LATCH_HOLD_NANOS = LkMotion.Base * 1_000_000L

/** Pinned buttons are gone — faded, then collapsed — by the time the keyboard is this far up. */
private const val ACTIONS_FADE_END = 0.12f

/** The chevron that stands in for Back beside Continue while the keyboard is up. */
private val ChevronSize = 52.dp

/**
 * The keyboard's openness, 0 (hidden) to 1 (fully up), and whether the wizard should be showing its
 * typing layout.
 *
 * Built from the IME inset *minus what ancestors already consumed* — `MainActivity` pads for the
 * system bars, so the raw inset includes the 24dp navigation bar that `imePadding()` never sees —
 * and from the animation's source and target, so openness is a fraction of the *settled* height and
 * a numeric-to-QWERTY resize (the keyboard stays up, only its height changes) reads as fully open
 * throughout rather than as a dip.
 */
@Stable
internal class CardWizardKeyboard {
    private var consumed by mutableStateOf<WindowInsets>(WindowInsets(0, 0, 0, 0))
    private var ime: WindowInsets = WindowInsets(0, 0, 0, 0)
    private var source: WindowInsets = WindowInsets(0, 0, 0, 0)
    private var target: WindowInsets = WindowInsets(0, 0, 0, 0)

    /** True while an auto-advance is bridging the moment its focus change dips the keyboard. */
    var latched by mutableStateOf(false)
        internal set

    internal var onReview = false
    private var reviewCap = 1f

    /** Applied to the wizard's root, *before* `imePadding()`, so it sees what the ancestors consumed. */
    fun consumedInsetsModifier(): Modifier = Modifier.onConsumedWindowInsetsChanged { consumed = it }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Bind(onReview: Boolean) {
        val consumedNow = consumed
        val imeNow = WindowInsets.ime
        val sourceNow = WindowInsets.imeAnimationSource
        val targetNow = WindowInsets.imeAnimationTarget
        ime = remember(consumedNow, imeNow) { imeNow.exclude(consumedNow) }
        source = remember(consumedNow, sourceNow) { sourceNow.exclude(consumedNow) }
        target = remember(consumedNow, targetNow) { targetNow.exclude(consumedNow) }
        this.onReview = onReview
    }

    /** The keyboard's progress as the system reports it, before the wizard's own rules. */
    fun raw(density: Density): Float = imeOpenness(
        source.getBottom(density),
        target.getBottom(density),
        ime.getBottom(density),
        minKeyboard = minKeyboardPx(density),
    )

    /** Where the keyboard is heading, in px — read once per step change by the latch. */
    fun targetPx(density: Density): Int = target.getBottom(density).takeIf { it >= minKeyboardPx(density) } ?: 0

    private fun minKeyboardPx(density: Density): Int = with(density) { MIN_KEYBOARD_HEIGHT.roundToPx() }

    /**
     * The progress every layout reads.
     *
     * Held at 1 while [latched] (an auto-advance briefly dips the keyboard; the face must not fold
     * and unfold across it). On Review it can only fall: the Name-it sheet raises a keyboard of its
     * own, and letting that rise re-fold the layout behind the sheet — or snap it open before the
     * keyboard has finished hiding on the way in — would move Continue the wrong way.
     */
    fun effective(density: Density): Float {
        var f = raw(density)
        if (latched) f = 1f
        if (onReview) {
            reviewCap = minOf(reviewCap, f)
            f = reviewCap
        }
        return f.coerceIn(0f, 1f)
    }

    internal fun resetReviewCap() {
        reviewCap = 1f
    }
}

@Composable
internal fun rememberCardWizardKeyboard(step: Int, reviewStep: Int): CardWizardKeyboard {
    val kb = remember { CardWizardKeyboard() }
    kb.Bind(onReview = step == reviewStep)
    val density = LocalDensity.current
    LaunchedEffect(step) {
        kb.resetReviewCap()
        if (step == reviewStep) {
            kb.latched = false
            return@LaunchedEffect
        }
        // Armed only if the keyboard was already coming up at the step change: the latch bridges a
        // dip, it never creates the typing state on its own (Back from Review, a hardware keyboard).
        val before = kb.targetPx(density)
        if (before <= 0) {
            kb.latched = false
            return@LaunchedEffect
        }
        kb.latched = true
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val target = kb.targetPx(density)
            if (now - start >= LATCH_HOLD_NANOS || (target > 0 && target != before)) break
        }
        kb.latched = false
    }
    return kb
}

/** Whether the typing layout is on at all — flips once per show and once per hide. */
@Composable
internal fun CardWizardKeyboard.rememberTyping(): State<Boolean> {
    val density = LocalDensity.current
    return remember(this, density) { derivedStateOf { raw(density) > 0f || latched } }
}

/** The shortest inset that is a soft keyboard — the smallest real one (voice typing) is well over this. */
internal val MIN_KEYBOARD_HEIGHT = 100.dp

/**
 * Keyboard openness from the inset animation: the current inset as a fraction of the larger of where
 * it started and where it is going.
 *
 * If both ends are above zero the keyboard is staying up and only changing height (numeric to
 * QWERTY), so it counts as fully open for as long as it is at least as high as the lower end.
 */
internal fun imeOpenness(source: Int, target: Int, current: Int, minKeyboard: Int = 0): Float {
    val peak = maxOf(source, target)
    // With a hardware keyboard the system still reports an inset: the keyboard's own navigation
    // row, about 48dp. It is not a keyboard, and folding the card for it would take room for nothing.
    if (peak <= 0 || peak < minKeyboard) return 0f
    if (source > 0 && target > 0 && current >= minOf(source, target)) return 1f
    return (current.toFloat() / peak).coerceIn(0f, 1f)
}

internal fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * The progress at which the full face has finished fading out — the point where the region may start
 * to shrink without ever cutting the card's outline.
 *
 * The full face is clipped from the bottom as the region shrinks, so it is cut as soon as the
 * region is shorter than it. Its lowest drawn thing is the card's own bottom edge and corners, one
 * [marginPx] above the bottom of the card; so the face must be invisible before the region has lost
 * that margin. Hence the region holds its full height until [faceRegionFadeEnd], and only then
 * shrinks.
 */
internal fun faceRegionFadeEnd(fullHeight: Int, stripHeight: Int, marginPx: Int): Float =
    if (fullHeight <= stripHeight) 1f else (marginPx.toFloat() / (fullHeight - stripHeight)).coerceIn(0.01f, 1f)

internal fun faceRegionHeight(progress: Float, fullHeight: Int, stripHeight: Int, fadeEnd: Float): Int {
    if (progress <= fadeEnd || fullHeight <= stripHeight) return fullHeight
    val t = ((progress - fadeEnd) / (1f - fadeEnd)).coerceIn(0f, 1f)
    return (fullHeight + (stripHeight - fullHeight) * t).roundToInt()
}

internal fun fullFaceAlpha(progress: Float, fadeEnd: Float): Float = 1f - smoothstep(progress / fadeEnd)

internal fun stripAlpha(progress: Float, fadeEnd: Float): Float = smoothstep(progress / fadeEnd)

/**
 * The card face's region: the full face and the typing strip, stacked top-aligned, cross-faded and
 * folded by keyboard progress.
 *
 * Both are composed for the whole wizard (the strip invisible at rest). Composing the strip only
 * while the keyboard is up would build it fresh each time the keyboard rises, and a fresh face
 * starts its turn already at its target — so the security-code step would appear flipped with no
 * flip, and the newest digit would re-land. One region, one semantics node: the face's sentence is
 * set here and cleared on both faces.
 */
@Composable
internal fun CardWizardFaceRegion(
    keyboard: CardWizardKeyboard,
    scroll: ScrollState,
    contentDescription: String,
    full: @Composable (Modifier) -> Unit,
    strip: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * False when the keyboard-up rule has put the rail and the actions in the scroll
     * ([WizardFrame]): the strip then scrolls with them instead of holding the top of the viewport,
     * which on a window that short is room the field being typed into needs.
     */
    pinned: Boolean = true,
) {
    val fade = remember { FadeEnd() }
    val ground = LkTheme.colors.ground
    Layout(
        content = {
            full(Modifier.graphicsLayer { alpha = fullFaceAlpha(keyboard.effective(this), fade.value) })
            strip(
                Modifier.graphicsLayer {
                    alpha = stripAlpha(keyboard.effective(this), fade.value)
                },
            )
        },
        // Pinned to the top of the viewport while the keyboard is up: the form scrolls beneath it, so
        // a screen too short for the strip *and* the focused field still keeps the card in view. The
        // strip is opaque and drawn above its siblings; at rest it scrolls away with the content.
        modifier = modifier
            .fillMaxWidth()
            .zIndex(1f)
            .graphicsLayer {
                translationY = if (pinned) scroll.value * stripAlpha(keyboard.effective(this), fade.value) else 0f
            }
            // The strip is a rounded shape in a rectangular region, so its four corner gutters are
            // transparent and the form scrolling beneath showed through them - the foot of a heading
            // peeking out beside the strip's corner. A ground plate over the whole region, faded in
            // with the strip, closes them; at rest it is invisible and the full face keeps the
            // screen's aura behind it.
            .drawBehind {
                drawRect(ground, alpha = stripAlpha(keyboard.effective(this), fade.value))
            }
            .clipToBounds()
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val fixedWidth = Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = Constraints.Infinity)
        val fullPlaceable = measurables[0].measure(fixedWidth)
        val stripPlaceable = measurables[1].measure(fixedWidth)
        fade.value = faceRegionFadeEnd(fullPlaceable.height, stripPlaceable.height, LkSpace.md.roundToPx())
        val height = faceRegionHeight(
            keyboard.effective(this),
            fullPlaceable.height,
            stripPlaceable.height,
            fade.value,
        )
        layout(width, height) {
            fullPlaceable.placeRelative(0, 0)
            stripPlaceable.placeRelative(0, 0)
        }
    }
}

/** Written during measure, read during draw — the fade point depends on the measured heights. */
private class FadeEnd {
    var value: Float = 1f
}

/**
 * Continue, and — while the keyboard is up — nothing else but a chevron for Back.
 *
 * At rest this is the stack it always was: Continue, then the quieter buttons below. As the keyboard
 * rises the quieter buttons fade and fold away, and on steps 2–4 a chevron grows in to the left of
 * Continue. Continue is laid out first and top-aligned, so as the stack collapses by exactly the
 * amount the keyboard rises it stays put and then rises with the keyboard — it only ever moves up.
 *
 * Their semantics are cleared while the keyboard is up (Back is the chevron; Discard and
 * "Enter everything" are the row at the end of the scroll column), so a screen reader never meets
 * two of the same control.
 */
@Composable
internal fun CardWizardActionsFrame(
    keyboard: CardWizardKeyboard,
    typing: Boolean,
    withChevron: Boolean,
    onBack: () -> Unit,
    backEnabled: Boolean,
    primary: @Composable () -> Unit,
    secondaries: @Composable () -> Unit,
) {
    val chevronShown = withChevron && typing
    val back = stringResource(R.string.itemedit_card_wizard_back)
    val c = LkTheme.colors
    Layout(
        content = {
            Box(Modifier.graphicsLayer { alpha = keyboard.effective(this) }) {
                if (chevronShown) {
                    LkIconButton(
                        icon = LkIcons.ChevronLeft,
                        label = back,
                        onClick = onBack,
                        enabled = backEnabled,
                        size = ChevronSize,
                        container = c.wash,
                        ring = c.ringStrong,
                        shape = LkShape.xl,
                    )
                }
            }
            Box { primary() }
            Box(
                Modifier
                    .graphicsLayer { alpha = 1f - smoothstep(keyboard.effective(this) / ACTIONS_FADE_END) }
                    .then(if (typing) Modifier.clearAndSetSemantics { } else Modifier),
            ) { secondaries() }
        },
        modifier = Modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = LkSpace.sm.roundToPx()
        val f = keyboard.effective(this)
        val chevronWidth = if (chevronShown) ChevronSize.roundToPx() else 0
        val shown = ((chevronWidth + if (chevronShown) gap else 0) * f).roundToInt()
        val primaryPlaceable = measurables[1].measure(
            Constraints(minWidth = (width - shown).coerceAtLeast(0), maxWidth = (width - shown).coerceAtLeast(0), minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val chevronPlaceable = measurables[0].measure(Constraints.fixed(chevronWidth, if (chevronShown) primaryPlaceable.height else 0))
        val secondariesPlaceable = measurables[2].measure(
            Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val folded = if (secondariesPlaceable.height > 0) {
            ((secondariesPlaceable.height + gap) * (1f - f)).roundToInt()
        } else {
            0
        }
        layout(width, primaryPlaceable.height + folded) {
            // Slides in from the left edge as the keyboard rises; at full progress it sits at x = 0.
            chevronPlaceable.placeRelative(shown - (chevronWidth + if (chevronShown) gap else 0), 0)
            primaryPlaceable.placeRelative(shown, 0)
            secondariesPlaceable.placeRelative(0, primaryPlaceable.height + gap)
        }
    }
}

/**
 * Step 1's two quiet exits while the keyboard is up: "Discard" and "Enter everything at once", as a
 * row at the end of the scroll column rather than two stacked buttons pinned above the keyboard.
 *
 * Pinned, they were 116dp of the little room there is. Here they cost nothing until the content is
 * short enough that they are visible — and a screen-reader user, for whom the keyboard is up on every
 * step, still reaches both by swiping on. Faded in by keyboard progress, composed only while typing,
 * so there is never a second copy beside the pinned ones.
 */
@Composable
internal fun CardWizardTypingExits(
    keyboard: CardWizardKeyboard,
    scroll: ScrollState,
    enabled: Boolean,
    onDiscard: () -> Unit,
    onEnterFullForm: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().graphicsLayer {
            // Whole or not drawn. When the content overflows, a button whose top few pixels peeked
            // above the keyboard read as stray marks over Continue; it is drawn once the content
            // fits or the user has scrolled to the end — and is in the tree, for a screen reader,
            // the whole time.
            val whole = scroll.maxValue == 0 || scroll.value >= scroll.maxValue
            alpha = smoothstep(keyboard.effective(this)) * (if (whole) 1f else 0f)
        },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LkTextButton(
            text = stringResource(R.string.itemedit_edit_discard_confirm),
            onClick = onDiscard,
            enabled = enabled,
            fillWidth = false,
        )
        if (onEnterFullForm != null) {
            LkTextButton(
                text = stringResource(R.string.itemedit_card_wizard_manual),
                onClick = onEnterFullForm,
                enabled = enabled,
                fillWidth = false,
            )
        }
    }
}
