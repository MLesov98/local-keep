package com.localkeep.core.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.R
import com.localkeep.core.ui.decorative
import com.localkeep.core.ui.Haptic
import com.localkeep.core.ui.labelled
import com.localkeep.core.ui.lkTag
import com.localkeep.core.ui.rememberHapticClick
import com.localkeep.core.ui.rememberHaptics
import com.localkeep.core.ui.minTouchTarget

/**
 * How much room to reveal below an errored field, so the sentence explaining the red ring arrives
 * with it. One caption line plus its spacer, with slack — every inline error in the app is a single
 * short line rendered directly beneath the well.
 */
private val ErrorReveal = 48.dp

/**
 * How tall a reveal request may be: what it wants, or what the viewport can actually give.
 *
 * **A reveal request must never be larger than the viewport that has to satisfy it** (UI/UX spec
 * §2). An errored field asks for its well *plus* [ErrorReveal] of room for the caption beneath it,
 * and once the IME has shortened the viewport that rect can exceed everything left. The scroller
 * then cannot satisfy it from any position, so it re-corrects every frame and undoes each gesture
 * as it arrives: on 2026-07-29 saving with an empty title left the item editor unscrollable in
 * **both** directions, with the caption still off-screen. Asking for less than the whole story is
 * strictly better than asking for something impossible and getting none of it.
 *
 * Pure, and separate from the composable, for a specific reason. The first attempt at a regression
 * test was written against `createComposeRule` and passed identically before and after the fix — a
 * Compose test window has no IME, so the viewport never shortens and the request is always
 * satisfiable. A test that cannot fail is worse than no test. The arithmetic is the rule, so the
 * arithmetic is what gets tested, and it can fail. See `ErrorRevealPinTest`.
 *
 * @param wellHeightPx the field's own height.
 * @param errorRevealPx the extra room the caption below it wants.
 * @param availableHeightPx what is left of the window after the IME, or `0` when unknown — in which
 *   case nothing is clamped, because a wrong clamp is worse than none.
 */
internal fun errorRevealHeightPx(
    wellHeightPx: Float,
    errorRevealPx: Float,
    availableHeightPx: Float,
): Float {
    val wanted = wellHeightPx + errorRevealPx
    if (availableHeightPx <= 0f) return wanted
    // Never below the well itself. Clamping past that would scroll to a fraction of the field and
    // reintroduce the original complaint — a focused field you cannot see — while fixing the pin.
    return wanted.coerceAtMost(availableHeightPx).coerceAtLeast(wellHeightPx)
}

/**
 * Text input.
 *
 * Built on `BasicTextField` rather than Material's `TextField` because the design's input is a
 * *well*: darker than the surface it sits in, with the label outside and above it, and a focus ring
 * that brightens rather than an underline that grows. Material's outlined and filled variants both
 * reserve space for a floating label and an indicator line, neither of which exists here — fighting
 * that with `colors = …` overrides leaves a field that is subtly the wrong height on every screen.
 */
@Composable
fun LkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    /**
     * The field's name for a screen reader.
     *
     * Separate from [placeholder] because a placeholder is *not* a label: it is a sibling `Text`
     * that Compose folds into the text field's own semantics node, so a placeholder-only field
     * (the vault search, most obviously) announces as an unnamed edit box. Verified in a
     * `uiautomator` dump — the placeholder string is simply absent from the tree. Defaults to the
     * placeholder so a caller that supplies only one still gets a named field.
     */
    label: String = placeholder,
    leadingIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = LkText.body,
    /**
     * The style for [placeholder] alone, when it differs from the style of the value.
     *
     * It exists because a masked password field sets [textStyle] to `LkText.secretInput`, whose
     * 0.16em tracking is there so a row of bullets does not clump into a grey bar. The placeholder
     * is prose, not bullets, and rendering it at that tracking spelled it out letter by letter —
     * `C h o o s e   s o m e t h i n g` — for as long as the field was empty and masked.
     */
    placeholderStyle: TextStyle = textStyle,
    singleLine: Boolean = true,
    /**
     * Whether the content sits vertically centred in the well. Defaults to [singleLine]: a multiline
     * field is a page and starts at the top. A wrapping *secret* well (the card number) is not a
     * page — its value is usually one line beside centred glyphs, and top-aligning it left the line
     * hugging the top edge with an empty second line's worth of air below (R-L-06).
     */
    centerContent: Boolean = singleLine,
    /**
     * The floor for the well, not its height — a multiline well grows past it.
     *
     * 48dp because that is the touch-target floor, and because it is what the app already renders.
     * The default was 58dp until 2026-07-29, and an audit that day found **not one caller in the
     * app used it**: every single call site overrode it, at 46dp nine times over and at 52, 86, 132
     * and 200dp for the taller wells. A default nobody wants is not a default, it is a trap for
     * whoever writes the next field — they would have got a well 10dp taller than every other well
     * in the app and no way to know.
     *
     * Two reviews flagged this number on 2026-07-29 and proposed lowering it. Their reasoning was
     * wrong — both had measured the inner `BasicTextField` node rather than the well, and neither
     * had read the tap-target note below — but the number really was wrong, for a different reason,
     * and the audit that proved it only happened because they pushed.
     *
     * Lowering it does **not** re-open the dead-tap bug documented below: that was fixed by making
     * the whole well a tap target, which is height-independent, and `LkFieldTapTargetTest` pins it
     * at 200dp precisely because the tall case is the dangerous one.
     */
    minHeight: Dp = 48.dp,
    /**
     * The air above and below the well's content.
     *
     * A caller lowers it when the *content* is what sets the height — a well carrying 48dp touch
     * targets in its trailing slot is 48 + 2×12 = 72dp tall at the default, which is half again the
     * height of the plain well beside it. See [LkPasswordField.actions].
     */
    verticalPadding: Dp = 12.dp,
    shape: Shape = LkShape.xl,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }

    // Scroll a focused field out from under the keyboard, in every scrolling form at once.
    //
    // The IME arrives *after* the focus does, so the field can be perfectly visible at the moment
    // it is tapped and covered a frame later, with nothing left to tell the scroller to move: the
    // user is then typing into a field they cannot see. The whole-screen fixes elsewhere pin the
    // primary action above the keyboard, which is a different problem — this is about the field
    // currently being edited, and it is the field itself that knows when that is.
    //
    // Keyed on `focused` and on the IME height together, so it fires both when focus moves between
    // fields with the keyboard already up and when the keyboard opens under a field that already
    // had focus.
    // Asking for the well alone is not enough when the field is in error. Every inline error in the
    // app is a sibling rendered *below* the field — the confirm mismatch on onboarding and in
    // Settings, the card expiry caption, the unlock reason — so scrolling the well to the top of a
    // viewport the keyboard has already shortened puts the sentence explaining the red ring just
    // past the bottom edge. Measured on onboarding step 2: viewport [68,377][1012,1164], confirm
    // field ending at y=1152, and "These don't match yet." absent from `uiautomator dump` until the
    // user swipes. A screen reader was told (the message carries a live region); the person looking
    // at the screen was not.
    val bringIntoView = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var wellSize by remember { mutableStateOf(IntSize.Zero) }
    val errorRevealPx = with(LocalDensity.current) { ErrorReveal.toPx() }
    // What is left of the window once the keyboard has taken its share — the ceiling the request
    // below is clamped to. This is the quantity that made the request unsatisfiable in the first
    // place: the rect was fixed, and the space available to satisfy it shrank underneath it.
    val availableHeightPx = (LocalWindowInfo.current.containerSize.height - imeBottom).toFloat()
    LaunchedEffect(focused, imeBottom, isError) {
        if (focused) {
            // One frame of slack: asking during the same frame the inset changes measures against
            // the layout as it was, and scrolls to where the field used to be.
            withFrameNanos {}
            runCatching {
                if (isError && wellSize != IntSize.Zero) {
                    bringIntoView.bringIntoView(
                        Rect(
                            0f,
                            0f,
                            wellSize.width.toFloat(),
                            errorRevealHeightPx(
                                wellHeightPx = wellSize.height.toFloat(),
                                errorRevealPx = errorRevealPx,
                                availableHeightPx = availableHeightPx,
                            ),
                        ),
                    )
                } else {
                    bringIntoView.bringIntoView()
                }
            }
        }
    }

    val ring by animateColorAsState(
        targetValue = when {
            isError -> c.errorRing
            focused -> c.focusRing
            // `.09` white, not `.07`. Every text well in `Item Flows.dc.html` rests at
            // `inset 0 0 0 1px rgba(255,255,255,.09)` — [LkColors.ringStrong] to the hundredth —
            // and the quieter [LkColors.ring] is what the comp gives *rows*, which are read, not
            // typed into. A well has to advertise that it accepts input while empty, and it has
            // nothing but the ring to do it with.
            else -> c.ringStrong
        },
        animationSpec = lkTween(LkMotion.Quick),
        label = "field-ring",
    )

    // The caret and selection are jade so the one moving thing on a dark screen belongs to the
    // palette; the platform default is a bright blue that appears nowhere else in the app.
    val selection = remember(c) {
        TextSelectionColors(handleColor = c.jade, backgroundColor = c.jade.copy(alpha = 0.28f))
    }

    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .lkSurface(shape, c.field, ring)
                // The whole well is the tap target, not just the line of text inside it.
                //
                // `BasicTextField` is only as tall as its own content — one line of 16sp is about
                // 22dp — so in the 58dp well of the day it was a 22dp island with 36dp of upholstery around it
                // that swallowed taps. Measured on-device: the field drew 160px tall and only the
                // middle 61px opened the keyboard. Every field in the app had it, and it read as
                // the app being frozen rather than as a miss, because a near miss looks identical
                // to a hit. A 200dp Note field was 88% dead.
                //
                // `detectTapGestures` rather than `clickable`: the text field consumes the taps
                // that land on it (that is how the caret is placed), so this fires only for the
                // ones it did not want, and it adds no second clickable node for a screen reader
                // to stop on.
                .pointerInput(Unit) {
                    detectTapGestures { focusRequester.requestFocus() }
                }
                .bringIntoViewRequester(bringIntoView)
                // Measured on the same node the requester's rect is expressed in, so the strip
                // asked for below the well is measured from the well's own bottom edge.
                .onSizeChanged { wellSize = it }
                .padding(horizontal = 15.dp, vertical = verticalPadding),
            // A multiline field is a page: its first line belongs at the top of the well, not
            // floating in the middle of it with empty space above.
            verticalAlignment = if (centerContent) Alignment.CenterVertically else Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier.size(18.dp),
                )
            }
            // What the field actually draws — bullets for a masked value, the characters for a
            // revealed one. Measured rather than counted, because a bullet, a digit and a wide
            // Cyrillic letter are three different widths and the question is whether the *ink*
            // outruns the box.
            val measurer = rememberTextMeasurer()
            var contentWidthPx by remember { mutableStateOf(0) }
            val drawnText = remember(value, visualTransformation) {
                visualTransformation.filter(AnnotatedString(value)).text.text
            }
            // `measurer` is itself remembered against the density, the font scale and the font
            // resolver, so naming it as a key is what makes this re-measure when the user changes
            // their system text size — which is the case this defect is worst in.
            val drawnWidthPx = remember(drawnText, textStyle, measurer) {
                if (drawnText.isEmpty()) {
                    0
                } else {
                    measurer.measure(drawnText, textStyle, softWrap = false, maxLines = 1).size.width
                }
            }
            val clipped = lkValueOutrunsWell(singleLine, drawnWidthPx, contentWidthPx)
            // The platform scrolls a single-line field to the caret and keeps that offset when focus
            // leaves, and it does not say how far. Focus is the only signal this component has that
            // the offset is not zero, so a field that has never held focus shows only the trailing
            // fade (it is drawn from its start) and one that has also fades the leading edge, where
            // the scroll slices a glyph. A caret parked back at the start of a focused field dims its
            // first character a little; that is the price of not guessing the scroll position.
            val everFocused = rememberEverFocused(focused)
            Box(
                Modifier
                    .weight(1f)
                    .onSizeChanged { contentWidthPx = it.width }
                    .lkFadeLeadingEdge(lkLeadingFadeActive(clipped, everFocused))
                    .lkFadeTrailingEdge(clipped),
                contentAlignment = if (centerContent) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    // Ellipsis, not the default clip. A placeholder that outgrows the well — a
                    // narrow field, a long language, font scale 2.0 — was cut mid-word with no
                    // sign it continued: "Search titles, usernames, sites," on a dangling comma
                    // reads as broken text, not as truncated text (vault list, 2026-09-02).
                    //
                    // The line budget follows the font scale rather than sitting at 1 — see
                    // [lkPlaceholderMaxLines]. The ellipsis stays as the backstop past that budget,
                    // because the alternative to a truncation mark is a silent clip, not a whole
                    // sentence.
                    Text(
                        placeholder,
                        style = placeholderStyle,
                        color = c.textFaint,
                        maxLines = lkPlaceholderMaxLines(LocalDensity.current.fontScale),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .then(
                            if (label.isBlank()) {
                                Modifier
                            } else {
                                // `contentDescription` rather than `text`: it names the field
                                // without replacing the value the user has typed, which is still
                                // read out after it.
                                Modifier.semantics { contentDescription = label }
                            },
                        ),
                    textStyle = textStyle.copy(color = c.textStrong),
                    singleLine = singleLine,
                    cursorBrush = SolidColor(c.jade),
                    interactionSource = interaction,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                )
            }
            trailing?.invoke()
        }
    }
}

/**
 * Whether a single-line well's value is wider than the room it has to draw in.
 *
 * The question behind F-18 (2026-09-15 visual pass): a 20-character generated password in the item
 * editor had its twentieth bullet **cut in half** at the right edge of the text area — no ellipsis,
 * no scroll hint, nothing saying the value continued. At font scale 2 the same field sliced the
 * twelfth (`shots/124-fs2-editor.png`). A single-line `BasicTextField` clips its overflow at the box
 * edge and follows the caret only while it is focused, so a field the user is *reading* rather than
 * typing into shows a sliced glyph and calls it done.
 *
 * A predicate rather than an inline comparison so the rule can be tested on a JVM — the rendering
 * around it cannot be. Three things it deliberately does NOT do:
 *
 *  - It never fires for a multi-line well. Those wrap, so there is no horizontal overflow to hide.
 *  - It never fires before the box has been measured (`availableWidthPx == 0`), which is the state
 *    of the first composition. Treating an unmeasured box as zero-width would fade every field for
 *    one frame on every screen that has one.
 *  - Equal widths are not overflow. A value that exactly fills the well is whole, and dimming its
 *    last glyph would report a truncation that did not happen.
 */
internal fun lkValueOutrunsWell(
    singleLine: Boolean,
    valueWidthPx: Int,
    availableWidthPx: Int,
): Boolean = singleLine && availableWidthPx > 0 && valueWidthPx > availableWidthPx

/**
 * The font scale up to which a placeholder is a one-line string.
 *
 * 1.3 rather than [LkText.stackAboveFontScale]'s 1.5: that number is about a *graphic* losing its
 * share of a row to text beside it, and it is tuned to the narrowest window the app supports. This
 * one is about a sentence losing its own words, and the measurement says it starts earlier — at
 * 360dp the note placeholder is whole at 1.0 in all five languages and cut in all five at 2.0.
 */
private const val PlaceholderSingleLineFontScale = 1.3f

/**
 * How many lines a placeholder may occupy at [fontScale].
 *
 * F-1 (device pass, 2026-09-17): the secure note's placeholder is a *sentence*, and the well drew it
 * on one line at any text size. At 360dp and font scale 2.0 every shipped language was cut —
 * en `Too sensitive for a plai…`, fr `Trop sensible pour un …`, de `Zu sensibel für eine N…`,
 * es `Muy sensible para una…`, pt `Muito sensível para u…` (`shots/022-note-placeholder-*-font2.png`).
 * German is the shortest string in the catalogue at 31 characters and still clipped, so shortening
 * the translations is not the fix: doubling the text size halves the characters a line holds, so a
 * fixed `maxLines = 1` is a shrinking character budget — the same arithmetic [lkScaledMaxLines]
 * exists for, stated there in full.
 *
 * Two rather than a scaled count, and stepped rather than continuous. A placeholder is app-authored
 * prose of a known length, not a user-named string with no bound, so it needs no ceiling against a
 * pathological value and no third line: two lines hold every note placeholder in the catalogue at
 * 2.0. The step keeps font scales 1.0 through 1.3 rendering exactly the layout the comps specify —
 * a well that is empty and one line tall stays one line tall.
 *
 * The well grows to meet it on its own: a placeholder is a sibling of the text field inside a [Box],
 * and the [Row] around it carries a `defaultMinSize` floor rather than a fixed height.
 */
internal fun lkPlaceholderMaxLines(fontScale: Float): Int =
    if (fontScale <= PlaceholderSingleLineFontScale) 1 else 2

/**
 * How wide the ramp is that a clipped value fades out across.
 *
 * Not an [LkSpace] rung: those are the form's *spacing* vocabulary — field to field, label to field
 * — and this is neither a gap nor a padding. It is the length of a gradient, and the only thing it
 * has to be is long enough that the fade reads as a fade rather than as a hard edge, and short
 * enough that it does not dim characters a user is still trying to read. At the app's 16sp body text
 * that is roughly a character and a half.
 */
private val LkClipFadeWidth = 20.dp

/**
 * Fades the trailing edge of a well whose value does not fit, instead of slicing a glyph in half.
 *
 * Chosen over the two alternatives the finding allowed. An **ellipsis** is not available: the well
 * is a live `BasicTextField`, not a `Text`, and `TextOverflow` does not apply to one — and capping
 * what is drawn is forbidden outright for a masked value, since the bullets would then desync from
 * the caret and from the real character count. **Scrolling to the end when unfocused** answers the
 * wrong question for the field this was found in: the end of a row of bullets is exactly as
 * informative as the start, and the user would still be shown a sliced glyph, just at the other
 * edge.
 *
 * What a fade buys is the thing the finding says is missing — *"no ellipsis, no scroll hint"*. A
 * value that ends by dissolving is visibly a value that continues; one that ends in half a bullet is
 * a rendering fault. It also holds at any font scale for free, because the trigger is a measurement
 * rather than a character count.
 *
 * `DstIn` over an offscreen layer, so the alpha ramp multiplies the text's own pixels rather than
 * painting a coloured veneer over them — the well's fill, its ring and whatever sits behind it stay
 * exactly as they were, which a gradient drawn in the background colour would not manage on the
 * lifted-card surfaces this component also appears on. The layer is created only while the value
 * actually overflows, so an ordinary field pays nothing.
 */
private fun Modifier.lkFadeTrailingEdge(active: Boolean): Modifier =
    if (!active) {
        this
    } else {
        this
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val fade = LkClipFadeWidth.toPx().coerceAtMost(size.width)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Black, Color.Transparent),
                        startX = size.width - fade,
                        endX = size.width,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
    }

/**
 * Whether this field has ever held focus, kept across a recreation.
 *
 * Saveable, because the platform's scroll offset is: `BasicTextField` restores it with the rest of
 * the saved state after a rotation or a return to the screen, and a plain `remember` forgot that
 * focus had ever happened. The restored well then sat scrolled to its caret with the leading glyph
 * sliced at full opacity (SCL-034).
 */
@Composable
internal fun rememberEverFocused(focused: Boolean): Boolean {
    var everFocused by rememberSaveable { mutableStateOf(false) }
    if (focused) everFocused = true
    return everFocused
}

/**
 * Whether the leading edge of a well needs the fade as well as the trailing one.
 *
 * A value that outruns its well and has been focused has been scrolled to the caret, so the glyph at
 * the left edge is the one sliced in half (a masked 24-character password showed a 1-2 px full-bright
 * sliver there, and kept it after focus moved on). A value that has never been focused is drawn from
 * its start and has nothing sliced at the left. See the call site for why focus stands in for scroll.
 */
internal fun lkLeadingFadeActive(clipped: Boolean, everFocused: Boolean): Boolean = clipped && everFocused

/** The mirror of [lkFadeTrailingEdge]: dissolves the left edge so a scrolled value does not start on half a glyph. */
private fun Modifier.lkFadeLeadingEdge(active: Boolean): Modifier =
    if (!active) {
        this
    } else {
        this
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val fade = LkClipFadeWidth.toPx().coerceAtMost(size.width)
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color.Black),
                        startX = 0f,
                        endX = fade,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
    }

/**
 * A password well: monospace, masked by default, with an eye toggle and a live character count.
 *
 * The count is not decoration. Typing a long passphrase blind into bullets is the single most
 * common way to fail an unlock, and a number that ticks up is enough feedback to tell "the keyboard
 * dropped a character" apart from "I mistyped it" — without ever showing the secret.
 */
@Composable
fun LkPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    /** Never defaults to [placeholder] here: a row of bullets is not a field name. */
    label: String = stringResource(R.string.core_ui_field_password_label),
    revealed: Boolean = false,
    onToggleReveal: (() -> Unit)? = null,
    showCount: Boolean = false,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    /**
     * Nullable, and null means *the platform default*, not "do nothing".
     *
     * This used to default to `{}` and be installed unconditionally, which is worse than having no
     * handler at all: supplying any `KeyboardActions` lambda suppresses Compose's own default
     * action, so every caller that did not pass one got an IME key that neither hid the keyboard on
     * Done nor moved focus on Next. It was inert on the Settings password fields, the three backup
     * fields, and the onboarding master password, whose Next never reached Confirm.
     */
    onImeAction: (() -> Unit)? = null,
    /**
     * [LkIcons.Key], not [LkIcons.Lock]. The icon set's own KDoc reserves `Lock` for "this field is
     * gated behind re-auth", which is a specific promise — a padlock on every ordinary password well
     * spends that meaning on fields that make no such promise, and leaves the ones that do with
     * nothing to distinguish them (G16, 2026-09-11).
     */
    leadingIcon: ImageVector? = LkIcons.Key,
    /**
     * Overridable because not every secret is a password. A CVV, a card PIN and a card number are
     * digits, and `KeyboardType.Password` puts a full QWERTY in front of them — which is both a
     * slower way to type four numbers and an invitation to type something that is not a number.
     */
    keyboardType: KeyboardType = KeyboardType.Password,
    /**
     * Further controls for this secret, drawn inside the well beside the reveal toggle.
     *
     * `Item Flows.dc.html` puts them there: its password well is full width with the eye and the die
     * at its right-hand edge. They used to sit *outside*, in a Row that gave the well `weight(1f)` —
     * three 44dp buttons and their gaps took 156dp of a ~360dp form, so the well showed about 45% of
     * the screen and a generated 20-character password did not fit in it. Inside, they cost the
     * well's trailing padding and nothing else, because [LkGlyphButton]'s plate is 30dp against a
     * 48dp well and so cannot push it open — which is the actual concern behind the repo rule about
     * trailing buttons, and it is satisfied here rather than sidestepped.
     */
    /**
     * False lets a revealed secret wrap instead of scrolling horizontally inside the well.
     *
     * Default true, because a password well that grows as you type is noise for the ordinary case
     * and every existing caller wants one line. The case it exists for is a value the user is being
     * asked to *read* rather than type: onboarding's suggested passphrase is six words and about 52
     * characters, and in a single-line well it showed 25 of them, with no ellipsis, directly above
     * copy telling the user to write it down. Nothing on that path re-checks the value — the Confirm
     * field is deliberately absent for a generated password — so a transcription cut short there
     * ends in a vault nobody can open.
     *
     * Declared before [actions] deliberately: [actions] is a trailing lambda at several call sites,
     * and a parameter added after it silently captures that lambda instead.
     */
    singleLine: Boolean = true,
    /**
     * What the well shows while masked. Null is the plain `•` mask every existing caller gets; a
     * value replaces it (e.g. a mask that keeps the bullets grouped like the digits they hide).
     * Ignored while [revealed]. Declared before [actions] for the same trailing-lambda reason as
     * [singleLine].
     */
    maskTransformation: VisualTransformation? = null,
    /**
     * What the well shows while [revealed], so a value the mask groups can be read back grouped
     * (a card number masked `••• •••• ••••` must not reveal as one ungapped run). Null shows the
     * plain value, which is every existing caller. Ignored while masked. Declared before [actions]
     * for the same trailing-lambda reason as [singleLine].
     */
    revealedTransformation: VisualTransformation? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LkTheme.colors
    // Every copy of a secret in this app goes through ClipboardHelper, which marks the clip
    // sensitive (so it stays out of the clipboard preview and out of Android 13's paste history)
    // and wipes it after 30s. The one hole was the platform's own selection menu: reveal a password
    // in an editor, long-press it, and the system's Copy put unmarked plaintext on the clipboard
    // forever, with the app never told it happened.
    //
    // So the menu loses Copy and Cut on secret fields, and keeps everything else — Paste and Select
    // all still work, which is what actually matters for typing a password in. The app's own Copy
    // button is unaffected and is now the only way out.
    //
    // The toolbar swap alone stopped doing that once Compose's newer context menu became the
    // default, and never covered a hardware keyboard; see [secretClipboardGuard] for both. That
    // modifier goes on the field's own modifier rather than a wrapper, so a caller's `weight` or
    // other parent data still lands on the well itself.
    val toolbar = LocalTextToolbar.current
    val guardedToolbar = remember(toolbar) { SecretTextToolbar(toolbar) }
    CompositionLocalProvider(LocalTextToolbar provides guardedToolbar) {
    LkTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.secretClipboardGuard(),
        placeholder = placeholder,
        label = label,
        leadingIcon = leadingIcon,
        isError = isError,
        // The comp's secret well is 56px with 40px glyphs inside it. Ours keeps the repo's 48dp
        // touch floor instead of the comp's 40 — so the well is the comp's 56 and the air around
        // the content is 4dp, which is the arithmetic that makes a 48dp target fit a 56dp well.
        // Without it the same well is 72dp and stands half again as tall as the Username field
        // above it, which is how it looked on the emulator the first time these moved inside.
        minHeight = if (actions == null) 48.dp else 56.dp,
        verticalPadding = if (actions == null) 12.dp else 4.dp,
        singleLine = singleLine,
        // A wrapping well stays centred beside its glyph column; see [LkTextField.centerContent].
        centerContent = true,
        textStyle = if (revealed) LkText.monoField.copy(fontSize = LkText.secretInput.fontSize) else LkText.secretInput,
        // Prose, always — never the secret style. See LkTextField.placeholderStyle.
        placeholderStyle = LkText.body,
        visualTransformation = if (revealed) revealedTransformation ?: VisualTransformation.None else maskTransformation ?: PasswordVisualTransformation('•'),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = if (onImeAction == null) {
            KeyboardActions.Default
        } else {
            KeyboardActions(
                onDone = { onImeAction() },
                onGo = { onImeAction() },
                onNext = { onImeAction() },
            )
        },
        trailing = {
            // The comp puts `gap:2px` between these glyphs (`Item Flows.dc.html`, the password
            // well's `right:8px` cluster) on 40x40 plates that are also its targets, giving 42dp
            // between icon centres. Ours are 40dp plates grown to the 48dp touch floor.
            //
            // Superseded 2026-09-09, and the numbers above it are kept only as the history they
            // are: the arrangement is 0dp now, not 4dp, and the ink gap on the login password
            // field measured 24dp rather than 36.6dp — because the glyph inside grew to 30dp and
            // because the row holds two controls instead of three. The residual over the comp is
            // 6dp, not 10.6: the plate stays 40dp and only the touch box is 48dp. Regenerate left
            // this row entirely; see SecretField and PasswordStrengthRow for why.
            // No arrangement spacing between the trailing glyphs. Each one already carries its own
            // touch box, so `spacedBy` here adds a gap on top of ~29dp of dead margin the boxes
            // contribute — it was buying separation that was already there several times over.
            // The count chip below still needs air, so it keeps its own padding.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (showCount && value.isNotEmpty()) {
                    Text(value.length.toString(), style = LkText.monoCaption, color = c.textFaint)
                }
                if (onToggleReveal != null) {
                    LkGlyphButton(
                        icon = if (revealed) LkIcons.EyeOff else LkIcons.Eye,
                        label = stringResource(
                            if (revealed) R.string.core_ui_field_password_hide else R.string.core_ui_field_password_show,
                        ),
                        onClick = onToggleReveal,
                        size = 40.dp,
                        iconSize = 30.dp,
                        shape = LkShape.sm,
                        // Amber once the secret is on screen, grey while it is masked.
                        //
                        // `LkColors.kt` defines amber as *caution — something is worth looking at
                        // but nothing is wrong*, which is a plaintext secret currently visible,
                        // exactly. This component took `LkGlyphButton`'s default grey for both
                        // states, so the unlock screen drew the identical toggle in a different
                        // colour from the item editor and the detail screen, which both already
                        // paint it amber (F-17, 2026-09-15 visual pass: `shots/059` against
                        // `shots/006-unlock-reveal-on.png`). Three call sites, one state, one
                        // colour — and the KDoc, not the majority, is what picks which.
                        //
                        // Not jade: `LkColors` reserves jade for primary action, and a state toggle
                        // is not an action. Not coral either — coral is a failure or a compromised
                        // environment, and choosing to look at your own password is neither.
                        tint = if (revealed) c.amber else c.textMuted,
                    )
                }
                actions?.invoke(this)
            }
        },
    )
    }
}

/**
 * A [TextToolbar] that drops Copy and Cut from the platform selection menu.
 *
 * Passing `null` for an action is how the framework is told the action does not apply, so the menu
 * simply renders without those items — there is no need to build a menu of our own, and Paste,
 * Select all and the text handles keep behaving exactly as the platform intends.
 *
 * This covers only the legacy menu, the one Compose builds when
 * `ComposeFoundationFlags.isNewContextMenuEnabled` is false. That flag is true by default from
 * foundation 1.11, and the newer menu never calls [showMenu] at all, so on its own this guarded
 * nothing. It stays because it is what still works if the flag is ever turned off;
 * [secretClipboardGuard] is what covers the default.
 */
private class SecretTextToolbar(private val delegate: TextToolbar) : TextToolbar {
    override val status: TextToolbarStatus get() = delegate.status

    override fun hide() = delegate.hide()

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = delegate.showMenu(
        rect = rect,
        onCopyRequested = null,
        onPasteRequested = onPasteRequested,
        onCutRequested = null,
        onSelectAllRequested = onSelectAllRequested,
    )
}

/**
 * Takes every platform copy and cut away from the text fields this modifier's node encloses, and
 * every menu route that hands the selection to another app.
 *
 * Two routes, and the toolbar swap above reached neither of them. Proven on a device: a revealed
 * password, a note body, a hidden custom field, a card CVV and PIN, identity numbers and a
 * recovery-phrase word could all be copied in plaintext, with no `EXTRA_IS_SENSITIVE` and no 30s
 * clear.
 *
 * - **The selection menu.** Compose 1.11 builds it from text-context-menu components, both the
 *   floating toolbar a long-press raises and the dropdown a right-click raises.
 *   [filterTextContextMenuComponents] is a traversable modifier, and the text field collects its
 *   menu by walking *up* its ancestors, so a filter here applies to every field below it. Copy and
 *   Cut go; Paste, Select all and Autofill stay, because typing a secret in needs them.
 * - **Other apps, through that same menu.** Every app answering `ACTION_PROCESS_TEXT` gets a row
 *   (each keyed by a `ProcessTextKey`) — Google Translate, Read aloud, a dictionary — and tapping
 *   one starts that app with the selection in `EXTRA_PROCESS_TEXT`: the plaintext leaves the app
 *   with no clipboard involved. The smart-selection actions ("Call", "Open", "Email" when a
 *   selection looks like a number or an address) do the same through an intent, so both go. Seen
 *   on a device on a revealed hidden field and a passport number. Hence [SecretMenuKeys].
 * - **A hardware keyboard.** Ctrl+C and friends go straight to the field's key handling and consult
 *   no menu and no toolbar. [onPreviewKeyEvent] runs on the way *down* to the focused field, so an
 *   ancestor sees the chord first and consuming it means the field never does. That covers Ctrl+C,
 *   Ctrl+X, Ctrl+Insert, Shift+Delete and the dedicated Copy and Cut keys some keyboards carry.
 *   Paste chords (Ctrl+V, Shift+Insert) and everything else pass through.
 *
 * The copies are blocked, not rerouted through [com.localkeep.core.ui.ClipboardHelper]: the app's
 * own Copy button sits behind the re-auth gate for high-value fields, and a keyboard shortcut that
 * bypassed that gate would be a second way out, however well it marked the clip.
 *
 * Masking, the reveal gate and TalkBack are untouched: nothing here changes semantics or drawing.
 */
private fun Modifier.secretClipboardGuard(): Modifier = this
    .filterTextContextMenuComponents { component -> component.key in SecretMenuKeys }
    .onPreviewKeyEvent { event -> event.isSecretCopyOrCut() }

/**
 * The only selection-menu items a secret field keeps: each brings text *in*, none sends it out.
 * An allowlist rather than a list of what to drop, because the smart-selection key is internal to
 * foundation and cannot be named here, and because a menu item a later Compose adds should have
 * to be let in, not remembered to be kept out. Separators go with the rest; the platform's sit
 * between groups this leaves no longer present.
 */
private val SecretMenuKeys = setOf(
    TextContextMenuKeys.PasteKey,
    TextContextMenuKeys.SelectAllKey,
    TextContextMenuKeys.AutofillKey,
)

private fun androidx.compose.ui.input.key.KeyEvent.isSecretCopyOrCut(): Boolean = when (key) {
    Key.Copy, Key.Cut -> true
    Key.C, Key.X, Key.Insert -> isCtrlPressed
    Key.Delete -> isShiftPressed
    else -> false
}

/**
 * Drops Copy, Cut and the hand-to-another-app items from the platform selection menu for any text
 * field inside [content], and swallows the keyboard's copy and cut chords for it (see [secretClipboardGuard]).
 *
 * [LkPasswordField] installs this itself. A *hidden custom field* is a secret too — a recovery
 * code, a security answer, an API key — but is rendered through a plain [LkTextField], which has
 * no such guard; a long-press Copy there put unmarked plaintext on the clipboard forever, with no
 * `EXTRA_IS_SENSITIVE` and no 30s auto-clear, exactly the hole [LkPasswordField]'s own comment
 * names. Wrap the field in this composable to get the same menu without pulling in the password
 * well's reveal toggle and secret typography.
 */
@Composable
fun GuardedSecretField(content: @Composable () -> Unit) {
    val toolbar = LocalTextToolbar.current
    val guardedToolbar = remember(toolbar) { SecretTextToolbar(toolbar) }
    CompositionLocalProvider(LocalTextToolbar provides guardedToolbar) {
        // A Box only because a modifier needs a node to sit on. Every caller wraps a single field,
        // so it sizes to that field and changes no layout; a caller passing several siblings would
        // see them stacked, which is the reason to keep this to one field per guard.
        Box(Modifier.secretClipboardGuard()) {
            content()
        }
    }
}

/** The vault search well: shorter than an input field, with a clear button that pops in. */
@Composable
fun LkSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.core_ui_field_search_placeholder),
    /**
     * The field's name for a screen reader, when it should say more than the placeholder shows.
     *
     * The vault list's field used to show its whole scope as the placeholder — "Search titles,
     * usernames, sites, notes, tags, folders" — which fit a full-width well and stopped fitting
     * once the star and the filter button moved in beside it. German was the case that decided
     * this: its verb comes last ("… Ordner durchsuchen"), so the clipped placeholder held no word
     * meaning *search* at all. Now the visible placeholder is the one-word default, and the full
     * scope lives here, where a screen reader still gets every word of it.
     */
    label: String = placeholder,
    /**
     * `KeyboardType.Text` everywhere except the vault list's own field (F44, 2026-09-13), which
     * passes `Password`: that field is also where a make-invisible reveal phrase gets typed, and
     * `Text` left it with no anti-learning treatment at all — [LkFormField]'s own KDoc already names
     * `KeyboardType.Password` as "what suppresses autocorrect and the IME's learning of the value".
     * Verified against Android's `InputType.TYPE_TEXT_VARIATION_PASSWORD` semantics before wiring
     * this through: the variation suppresses the IME's personal-dictionary learning and its
     * suggestion/prediction strip, and it does that independently of visual masking — Compose does
     * not apply a `PasswordVisualTransformation` merely from this `KeyboardType`, so typed text stays
     * visible here exactly as a search field needs it to. It does not defeat a coercer reading the
     * screen live or a forensic dump of the IME's own state, both explicitly out of scope for this
     * feature (`2026-08-18-make-invisible-design.md`); what it closes is the narrower, in-scope
     * residual — a suggestion-strip tell to someone holding the unlocked phone after the fact.
     * Settings' search (the buy/upgrade route) must keep the ordinary keyboard, so this defaults to
     * `Text` and only the vault-list call site overrides it.
     */
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val c = LkTheme.colors
    val focusManager = LocalFocusManager.current
    LkTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        label = label,
        leadingIcon = LkIcons.Search,
        textStyle = LkText.body.copy(fontSize = LkText.body.fontSize),
        shape = LkShape.lg,
        // The Clear button carries a 48dp touch target, and the well pads its content by 12dp above
        // and below, so the well grew from 48dp to 72dp the moment the first character was typed and
        // the whole list under it jumped. No vertical padding: the 48dp minimum is the well, and the
        // target fills it exactly, so the height is the same with and without Clear.
        verticalPadding = 0.dp,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Search,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
        ),
        // Compose's default keyboard action handles Next, Previous and Done and nothing else, so a
        // field asking for Search gets a key that does nothing at all: the list filters as you type,
        // but the keyboard stays up over the results the search was for. Filtering is already live,
        // so the action is simply "I'm done typing".
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        trailing = {
            AnimatedVisibility(
                visible = value.isNotEmpty(),
                enter = fadeIn(lkTween(LkMotion.Quick)) + scaleIn(lkTween(LkMotion.Quick), 0.6f),
                exit = fadeOut(lkTween(LkMotion.Instant)) + scaleOut(lkTween(LkMotion.Instant), 0.6f),
            ) {
                LkGlyphButton(
                    icon = LkIcons.Close,
                    label = stringResource(R.string.core_ui_field_search_clear),
                    // Drops focus as well as the text, for the same reason `onSearch` above does:
                    // clearing is "I'm done searching", and leaving the keyboard up puts it back
                    // over the full list the user just asked to see. Measured on the vault list,
                    // 2026-08-29: after Clear, `dumpsys input_method` still read `mInputShown=true`
                    // and the keyboard covered the bottom rows — which are then not just hidden but
                    // untappable, because the taps land on the IME instead.
                    onClick = {
                        onValueChange("")
                        focusManager.clearFocus()
                    },
                    size = 24.dp,
                    iconSize = 16.dp,
                )
            }
        },
    )
}

/**
 * A small, unringed glyph button for *inside* another control.
 *
 * Distinct from [LkIconButton] because a button nested in a field must not carry its own ring —
 * two concentric hairlines 3dp apart read as a rendering bug.
 */
@Composable
fun LkGlyphButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    iconSize: Dp = 18.dp,
    tint: Color = LkTheme.colors.textMuted,
    container: Color = LkTheme.colors.wash,
    /**
     * The plate's corner, defaulting to the 8dp [LkShape.xs] every 30dp and 24dp caller was drawn
     * with. `Item Flows.dc.html` gives the glyphs inside a secret well `border-radius:11px` on a
     * 40x40 plate, which is [LkShape.sm] — a corner that only reads as right at that larger size,
     * so it is a parameter rather than a new default.
     */
    shape: Shape = LkShape.xs,
    haptic: Haptic? = Haptic.SELECT,
) {
    val interaction = remember { MutableInteractionSource() }
    val click = rememberHapticClick(haptic, onClick)
    // The visual plate stays 30dp (24dp for the search clear), which is what the design wants inside
    // a field; the *target* is grown to the 48dp floor. These are the smallest controls in the app —
    // reveal, clear — and they sit immediately beside a text field, where a near-miss puts the caret
    // somewhere unexpected instead of doing nothing.
    //
    // The floor has to live on its own node. `minTouchTarget()` is `defaultMinSize`, which raises a
    // dimension only when the incoming minimum constraint is exactly zero, so the `.size(size)` that
    // used to sit above it in this chain made it inert: the target was 30dp, not 48dp, and a comment
    // right here said otherwise.
    // 48dp square, the Material floor, and it stays that way.
    //
    // On 2026-09-09 the width was cut to 40dp to close a visual gap: a glyph draws its ink centred
    // in its touch box, so between two adjacent glyphs sits (box - ink) of dead margin, and with
    // 48dp boxes around ~15dp of ink that measured 36.6 and 37.3dp on emulator-5554 — a hole two
    // and a half times the glyph beside it, reported five times.
    //
    // Growing the GLYPH fixed that and was kept. Shrinking the BOX was reversed the same day, and
    // the reason is worth writing down because it is not a matter of taste: with three controls in
    // a password well, the cluster is competing with the value for width. Three 48dp targets need
    // 144dp, a 20-character generated password already runs to within ~21dp of the cluster, and no
    // arrangement of a three-control row fits. The geometry could not be tuned into safety, so the
    // *destructive* control left the row instead — see PasswordStrengthRow, which now carries
    // regenerate. Two controls at 48dp fit with room to spare, and the resulting ink gap is 24dp:
    // still far from the 37dp that was complained about, and a pair with one gap does not read as
    // the repeated-hole pattern that three did.
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .lkFocusRing(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = click,
            )
            .labelled(label),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .lkPressScale(interaction, pressedScale = 0.9f, shape = shape)
                .background(container, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * A label above one field, in the app's uppercase micro-label treatment.
 *
 * [LkText] has always carried two label volumes — [LkText.sectionLabel] for a header over a group of
 * unrelated things, and the quieter [LkText.fieldLabel] for a label sitting directly over the one
 * well it names. This function rendered `sectionLabel`, so a field's own caption spoke at section
 * volume: 11sp of tracked uppercase over the 14sp sentence-case value it was naming, which inverts
 * the hierarchy on every row in the app at once. `fieldLabel` had exactly one caller and was
 * otherwise dead.
 *
 * The split is already expressed one level up — [LkSectionLabel] is the section header, and every
 * caller of *this* function labels a single field — so the role needs no parameter. Reach for
 * [LkSectionLabel] when heading a group.
 *
 * [maxLines]/[overflow] are part of the contract, not decoration, and the contract is **two lines,
 * then an ellipsis**. It was one line until 2026-10-19. A German compound label runs 2-3x the English
 * string, so an unbounded label wraps into the well below - hence a bound at all - but one line cut
 * "CARD NUMBER (REQUIRED)" to "CARD NUMBER (REQU...", "NATIONAL ID NUMBER" to "NATIONAL ID NUMB..." and
 * a recovery-phrase label to a lone ellipsis (RG-41, SCL-014). A label names the field, so losing its
 * tail loses the part that says what to enter. The principal's call: wrap to a second line first.
 * A third line is still never granted, and the ellipsis still marks a label that did not fit; those
 * two things are what keep it from running into the well.
 *
 * A label in a row that shares its width with buttons (Paste all, Reveal) is a caller problem the
 * label cannot solve: two lines of a 20dp column are still no letters. Such a row must stack, or
 * give the label the room, rather than rely on this.
 */
@Composable
fun LkFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    Text(
        text.uppercase(),
        style = LkText.fieldLabel,
        color = LkTheme.colors.textFaint,
        maxLines = maxLines,
        overflow = overflow,
        modifier = modifier,
    )
}

/**
 * A labelled field: the label, the gap, and the well, as one thing.
 *
 * The label sits *above* the well rather than floating inside it. Material's floating label is
 * elegant on a light form and unreadable here — at rest it occupies the same space as the value, in
 * the same tone family, on a surface with only a hairline to separate it from the page. Stacked,
 * the label is always legible and the field height never changes as the user types.
 *
 * It lives in `core/ui` rather than in the one screen that needed it first because every form was
 * re-assembling the same three pieces by hand, and hand-assembly is why they differed. The
 * label-to-well gap measured 8dp in the item editor and 44dp on onboarding step 2 on 2026-07-29 —
 * not because anyone decided onboarding should breathe more, but because the two Columns were
 * written months apart. Owning the gap here makes the request that behaviour be identical across
 * features enforceable by construction rather than by everyone remembering.
 *
 * The IME action is part of that contract: Next moves down the form, so a whole item can be filled
 * in without reaching back to the screen between every field. Single-line fields only — in a Notes
 * or Websites well the Enter key belongs to the text, and taking it away to move focus would make a
 * field labelled "one per line" impossible to use as labelled.
 */
@Composable
fun LkFormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Dp = 48.dp,
    /** Caps a growing well so a pasted wall of text cannot push the form's actions off the end. */
    maxHeight: Dp = Dp.Unspecified,
    /**
     * The keyboard a field asks for is part of what the field *is*: an email well that opens on a
     * layout with no `@` within reach, or a phone well that opens on letters, makes the user do the
     * app's work.
     */
    keyboardType: KeyboardType = KeyboardType.Text,
    /**
     * How the value is drawn — the seam for masking a field that is not a password well.
     *
     * [LkTextField] has always taken one; this wrapper simply did not pass it through, so anything
     * built with [LkFormField] was unmaskable and a caller needing a mask had to reach for
     * `SecretField` instead. That is not a free swap: `SecretField` also routes reveal through the
     * re-auth gate, which is right for disclosing a *stored* secret and wrong on a capture screen,
     * where the user is typing the value in and has nothing to be re-authenticated against. The
     * BIP-39 recovery-phrase fields are exactly that case, and were left in clear because neither
     * option fitted. Set this together with `keyboardType = KeyboardType.Password`, which is what
     * suppresses autocorrect and the IME's learning of the value.
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    /**
     * The well's corner.
     *
     * 16dp for every ordinary well, which is what `Item Flows.dc.html` draws. The one exception the
     * comp itself makes is the secure note's body: a `200px` textarea at `border-radius:20px`, so a
     * well that is a *block* rather than a line reads as a card. [LkShape.card] is that 20dp.
     */
    shape: Shape = LkShape.xl,
    trailing: @Composable (() -> Unit)? = null,
    /**
     * A readout on the label's own baseline, right-aligned — `Item Flows.dc.html` puts the secure
     * note's character count there, opposite the label, rather than under the well. It belongs to
     * the label row and not to [trailing], which lives *inside* the well: a counter placed there
     * would sit on top of the text it is counting.
     */
    labelTrailing: @Composable (() -> Unit)? = null,
    /** Overrides the IME key. Null keeps the default: Next on a single-line well, none otherwise. */
    imeAction: ImeAction? = null,
    /**
     * What the IME key does. Null means the platform default (move focus down).
     *
     * Nullable rather than defaulting to `{}` for the reason [LkPasswordField] records: supplying
     * any `KeyboardActions` lambda suppresses Compose's own default action, so a no-op default
     * would leave every caller with an IME key that neither hides the keyboard nor moves focus.
     */
    onImeAction: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(LkSpace.sm)) {
        if (labelTrailing == null) {
            LkFieldLabel(label)
        } else {
            // A flow, not a [Row], so the readout drops under the label instead of into it.
            //
            // F-2 (device pass, 2026-09-17): at 360dp and font scale 2.0 the secure note's label and
            // its character counter were drawn on top of one another — en `SECURE NOTEcharacters`
            // with a stray `0` above, fr `NOTE SÉCURISÉEe` over `0 caractèr`, es
            // `NOTA SEGURAcaracteres` (`shots/022-note-placeholder-*-font2.png`). A `Row` measures
            // its children in order against what is left, so the label took the width it wanted and
            // the counter was handed the remainder and wrapped inside it, mid-word. Both strings
            // then sat on one line because `Bottom` aligns a two-line caption by its last line.
            //
            // Measured rather than thresholded: [FlowRow] measures each child whole and moves the
            // one that does not fit onto its own line, so the stack appears exactly when the two
            // strings stop fitting — at whichever font scale, language and width that happens — and
            // never before. At 1.0 both fit, `SpaceBetween` pins them to the two edges, and this is
            // the row the comp draws.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                // Label to its own wrapped readout — the tightest rung, because the two are one
                // caption rather than two things.
                verticalArrangement = Arrangement.spacedBy(LkSpace.xs),
                // Baselines, not centres: a 10sp label and an 11sp mono caption centred against
                // each other sit visibly off one another. The comp aligns them on `baseline`.
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                // `SpaceBetween` guarantees no minimum gap, so two strings that *just* fit would
                // touch and read as one word — which is the defect above, one pixel short of
                // wrapping. Carrying the gap on the label makes it part of what has to fit: the
                // pair wraps 8dp early rather than abutting. Invisible at 1.0, where the
                // arrangement pushes them to opposite edges regardless.
                LkFieldLabel(label, Modifier.padding(end = LkSpace.sm))
                labelTrailing()
            }
        }
        LkTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            isError = isError,
            modifier = if (maxHeight == Dp.Unspecified) Modifier else Modifier.heightIn(max = maxHeight),
            singleLine = singleLine,
            minHeight = minHeight,
            shape = shape,
            trailing = trailing,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                autoCorrectEnabled = keyboardType == KeyboardType.Text,
                imeAction = imeAction ?: if (singleLine) ImeAction.Next else ImeAction.Default,
            ),
            // The default moves focus down, which is right for a column of ordinary wells. A field
            // whose IME key has to *commit* something rather than move on overrides it - the seed
            // phrase's word entry is the case this exists for, where the typed word lives in a
            // draft until something writes it into the grid, and Next moving focus wrote nothing.
            keyboardActions = if (onImeAction == null) {
                KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
            } else {
                KeyboardActions(
                    onDone = { onImeAction() },
                    onGo = { onImeAction() },
                    onNext = { onImeAction() },
                )
            },
        )
    }
}

/**
 * An inline error: coral tint, coral ring, an alert glyph, and prose in [LkColors.coralText].
 *
 * Announced politely by the caller via `Modifier.announce` — an error that only exists visually is,
 * to a screen reader, a button that did nothing.
 */
@Composable
fun LkInlineError(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = LkTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(LkShape.md, c.coral.copy(alpha = 0.09f), c.coral.copy(alpha = 0.20f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            LkIcons.Warning,
            contentDescription = null,
            tint = c.coral,
            modifier = Modifier.size(15.dp).padding(top = 1.dp),
        )
        Text(
            // Through [lkBrandedText]: a sentence that names the product gives the name its jade
            // "Keep" here as it does in a dialog (reported again 2026-10-12). One component, so a
            // call site cannot forget.
            lkBrandedText(message),
            style = LkText.subtitle.copy(lineHeight = LkText.body.lineHeight),
            color = c.coralText,
            modifier = Modifier.weight(1f),
        )
        // Only for errors that leave the user with nothing to press. Where the control that failed
        // is still on screen — a row's trash icon, an enabled Save button — a second control here
        // is a longer route to the same thing, and the message says to try again instead.
        if (actionLabel != null && onAction != null) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .minTouchTarget()
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onAction,
                    )
                    .labelled(actionLabel),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .lkPressScale(interaction, shape = LkShape.xs)
                        .lkSurface(LkShape.xs, c.coral.copy(alpha = 0.14f), c.coral.copy(alpha = 0.24f))
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                ) {
                    Text(actionLabel, style = LkText.chip, color = c.coralText)
                }
            }
        }
    }
}

/** A tick box row inside an [LkGroup] — the generator's character-class options. */
@Composable
fun LkCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    first: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val c = LkTheme.colors
    val box by animateColorAsState(
        if (checked) c.jade else Color.Transparent,
        lkTween(LkMotion.Quick),
        label = "check-box",
    )
    val boxRing by animateColorAsState(
        if (checked) c.jade else c.controlIdle,
        lkTween(LkMotion.Quick),
        label = "check-ring",
    )
    LkGroupRow(
        first = first,
        modifier = modifier
            .labelled(
                stringResource(
                    if (checked) R.string.core_ui_check_row_enabled
                    else R.string.core_ui_check_row_disabled,
                    label,
                ),
            )
            // A minimum, not a height. The label below takes `weight(1f)` and sets no `maxLines`,
            // so at a large font scale a longer option name — "Use a separate export passphrase" in
            // Backup, "Avoid ambiguous" in the generator's character classes — wraps to a second
            // line. Pinned at 52dp that second line had nowhere to go and drew over the next row in
            // the group. [LkSwitchRow] below passes no height at all and was never affected; this
            // is the one row in the file that disagreed with its own sibling.
            .heightIn(min = 52.dp),
        onClick = onToggle,
        horizontalPadding = 18.dp,
    ) {
        // The tick fades rather than being added/removed: with AnimatedVisibility the box would
        // resize as the icon leaves, and 21dp of chrome popping on every toggle is very visible.
        val tick by animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec = lkTween(LkMotion.Quick),
            label = "check-tick",
        )
        Box(
            modifier = Modifier
                .size(21.dp)
                .lkSurface(LkShape.xs, box, boxRing, ringWidth = 1.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                LkIcons.Check,
                contentDescription = null,
                tint = c.onJade,
                modifier = Modifier.size(12.dp).alpha(tick),
            )
        }
        val labelStyle = LkText.body.copy(fontSize = LkText.rowTitle.fontSize)
        if (hint == null) {
            Text(
                label,
                style = labelStyle,
                color = c.textSecondary,
                modifier = Modifier.weight(1f).padding(start = 13.dp),
            )
        } else {
            // SCL-011: beside the label the hint takes its intrinsic width first, so at P1 (308dp,
            // 2.0) "Avoid ambiguous" was left ~110dp and broke mid-word. Where [LkText.stacks] says
            // the row is squeezed, the hint goes under the label and the label gets the full width.
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(Modifier.weight(1f).padding(start = 13.dp)) {
                if (LkText.stacks(fontScale, maxWidth)) {
                    Column {
                        Text(label, style = labelStyle, color = c.textSecondary)
                        Text(hint, style = LkText.monoCaption, color = c.textFaint)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = labelStyle, color = c.textSecondary, modifier = Modifier.weight(1f))
                        Text(hint, style = LkText.monoCaption, color = c.textFaint)
                    }
                }
            }
        }
    }
}

/**
 * A switch.
 *
 * Material's `Switch` cannot be brought into this palette: its thumb carries an elevation shadow and
 * its unchecked track is an outlined pill, both of which look like a different app's control on a
 * near-black surface. This is a track and a thumb, and the checked state is carried by fill *and*
 * thumb position — never by colour alone.
 */
@Composable
fun LkSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    haptic: Haptic? = Haptic.SELECT,
) {
    val c = LkTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    val track by animateColorAsState(
        when {
            !enabled -> c.wash
            checked -> c.jadeDim
            else -> c.washStrong
        },
        lkTween(LkMotion.Quick),
        label = "switch-track",
    )
    val offset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = lkTween(LkMotion.Quick, easing = LkMotion.Emphasized),
        label = "switch-thumb",
    )
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 28.dp)
            .lkPressScale(interaction, pressedScale = 0.94f, shape = LkShape.sm)
            .background(track, LkShape.sm)
            .lkRing(LkShape.sm, if (checked) Color.Transparent else c.ring)
            .lkFocusRing(LkShape.sm)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    haptic?.let(haptics::play)
                    onCheckedChange(!checked)
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                // 46 − 2×3 padding − 22 thumb = 18dp of travel.
                .offset(x = (18 * offset).dp)
                .size(22.dp)
                .background(if (enabled) c.textStrong else c.textFaint, LkShape.xs),
        )
    }
}

/**
 * A labelled switch row for inside an [LkGroup].
 *
 * One toggleable node, not a label beside a control: as two nodes a screen reader reads the sentence
 * and the switch separately, and the user has to guess which switch the sentence belonged to.
 */
@Composable
fun LkSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    first: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    val c = LkTheme.colors
    LkGroupRow(
        first = first,
        // Default inset (16dp), and the chevron rows' own faces: [LkText.body] over [LkText.subtitle],
        // 3dp apart, title in `textStrong`. This was `rowTitle` over `caption` with no gap, picked on
        // the belief that Settings' chevron rows use rowTitle — they do not (a vault row does) — so on
        // a device the title measured 50px beside their 56px, a Medium weight against their Normal,
        // and the subtitle was smaller and lighter, which made a switch row read as a different list.
        modifier = modifier.toggleableRow(label, checked, enabled) { onCheckedChange(!checked) },
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                label,
                style = LkText.body,
                color = if (enabled) c.textStrong else c.textFaint,
            )
            if (hint != null) {
                Text(hint, style = LkText.subtitle, color = c.textFaint)
            }
        }
        // The switch itself is decorative here: the row above owns the toggleable semantics, and a
        // second focusable control inside it would make TalkBack stop twice on one setting.
        Box(Modifier.decorative()) {
            // haptic = null: the row above already plays on toggle, and a tap that lands
            // on the switch itself hits both nodes.
            LkSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                haptic = null,
            )
        }
    }
}

@Composable
private fun Modifier.toggleableRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
): Modifier {
    // Its own source, so the wash follows a press anywhere on the strip. Tapping the label of a
    // settings row used to do the thing and show nothing at all — the switch's own animation is
    // 46dp wide at the far end of the row and easy to miss on a phone held one-handed.
    val interaction = remember { MutableInteractionSource() }
    val toggle = rememberHapticClick(Haptic.SELECT, onToggle)
    return this
        .semantics(mergeDescendants = true) { contentDescription = label }
        .lkPressWash(interaction, RectangleShape)
        // Square, because a settings row is a full-bleed strip inside a group — a rounded ring would
        // float free of the row's actual edges.
        .lkFocusRing(RectangleShape)
        .toggleable(
            value = checked,
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            onValueChange = { toggle() },
        )
}

/**
 * A quiet inline note — the counterpart to [LkInlineError] for an outcome that went *right*, or for
 * a neutral status line. Same shape, so success and failure occupy the same slot without the layout
 * shifting as one replaces the other.
 */
@Composable
fun LkInlineNote(
    message: String,
    modifier: Modifier = Modifier,
    accent: Color = LkTheme.colors.jade,
    icon: ImageVector = LkIcons.Check,
) {
    // One body line, at the current font scale. The glyph is centred on it rather than hung from the
    // top of the row: a top-aligned 14dp glyph sat about 2.5dp above the centre of a 21sp line
    // (reported 2026-10-09: "the tick is not aligned with the text"), and the gap widens with font
    // scale because the line grows in sp while the glyph stays in dp. For a note that wraps this is
    // the *first* line, which is where a leading glyph belongs — centring on the whole row would
    // float it between lines.
    val firstLine = with(LocalDensity.current) { LkText.body.lineHeight.toDp() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .lkSurface(LkShape.md, accent.copy(alpha = 0.08f), accent.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) {
            // Tagged on the glyph, not the slot: the slot is one line tall whatever is inside it, so a
            // test reading the slot cannot tell a centred glyph from a top-aligned one.
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp).lkTag("inline-note-icon"))
        }
        // Through [lkBrandedText], as [LkInlineError] does — see there.
        Text(
            lkBrandedText(message),
            style = LkText.subtitle.copy(lineHeight = LkText.body.lineHeight),
            color = LkTheme.colors.textSecondary,
        )
    }
}

/** [lkSurface] with a settable ring width — the tick box needs a heavier 1.5dp outline. */
private fun Modifier.lkSurface(
    shape: Shape,
    color: Color,
    ring: Color,
    ringWidth: Dp,
): Modifier = background(color, shape).lkRing(shape, ring, ringWidth)
