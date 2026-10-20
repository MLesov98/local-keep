package com.localkeep.core.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.lkTag

/**
 * How many digits a card number carries, and where the gaps fall.
 *
 * A payment card is not an arbitrary run of digits; it is printed in groups, and everyone who has
 * ever read one back off the plastic reads it in those groups. The app already knows which network
 * a number belongs to — [com.localkeep.core.model.CardBrands.detect] resolves it from the IIN — so
 * it can group correctly rather than assuming everything is a 16-digit 4-4-4-4.
 *
 * Amex is the case that makes this worth having: 15 digits in 4-6-5. Grouping it as 4-4-4-4 puts
 * the breaks in places the card itself does not have them, which is worse than not grouping at all.
 */
data class LkCardDigitGroups(val sizes: List<Int>) {
    val total: Int = sizes.sum()

    /**
     * The group lengths for a number of [length] digits: the scheme's own groups, or fours from the
     * right once the number outgrows them (see [lkOverflowGroupSizes]). The one decision the face,
     * the field and the spoken form all read, so they cannot pair digits differently.
     */
    fun sizesFor(length: Int): List<Int> = if (length > total) lkOverflowGroupSizes(length) else sizes

    /** [digits] with a space between groups, for a place that needs the number as text. */
    fun format(digits: String): String {
        var start = 0
        return sizesFor(digits.length)
            .mapNotNull { size ->
                digits.substring(start.coerceAtMost(digits.length), (start + size).coerceAtMost(digits.length))
                    .also { start += size }
                    .takeIf(String::isNotEmpty)
            }
            .joinToString(" ")
    }

    companion object {
        val Default = LkCardDigitGroups(listOf(4, 4, 4, 4))
        val Amex = LkCardDigitGroups(listOf(4, 6, 5))
    }
}

/**
 * The grouping for a number longer than its scheme's own groups: fours, right-aligned.
 *
 * A masked card shows its last four, and the grouping has to put those four in one group. Left
 * aligned, a 19-digit number read `4444 4444 4444 4444 333`, so the visible tail was the last digit
 * of one group and the first three of the next (`•••1 234`) — four digits that look like two
 * numbers. Right-aligned, the odd digits go in a short first group and the tail is whole:
 * `123 4444 4444 4444 4444`. Only used past the scheme's length, so every 16-digit Visa/Mastercard
 * and 15-digit Amex keeps its familiar shape, and so does a 20-digit run (five fours).
 */
internal fun lkOverflowGroupSizes(length: Int): List<Int> {
    val lead = length % 4
    return buildList {
        if (lead > 0) add(lead)
        repeat(length / 4) { add(4) }
    }
}

/**
 * Groups a run of digits for display without touching the value.
 *
 * A [VisualTransformation] is the right tool precisely because it does not mutate state: the
 * ViewModel keeps the bare digits it wants to store and match on, and only the glyphs on screen
 * gain spaces. Writing the spaces into the value instead would put them in the vault, in the
 * clipboard, and in every comparison.
 *
 * Digits past [groups]`.total` keep flowing rather than being dropped — this is a display hint, not
 * a validity check, and the same field holds loyalty and membership numbers that follow no scheme.
 */
class LkCardNumberTransformation(
    private val groups: LkCardDigitGroups = LkCardDigitGroups.Default,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        // Past the scheme's own length the groups are regrouped so the last four stay together.
        val sizes = groups.sizesFor(digits.length)
        val out = StringBuilder()
        var groupIndex = 0
        var inGroup = 0
        for (i in digits.indices) {
            val size = sizes.getOrNull(groupIndex)
            if (size != null && inGroup == size) {
                out.append(' ')
                groupIndex++
                inGroup = 0
            }
            out.append(digits[i])
            inGroup++
        }
        val spacedText = out.toString()

        // Offsets have to survive the inserted spaces in both directions, or the caret lands a
        // character off every time it crosses a gap and selection ranges point at the wrong digits.
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                var transformed = 0
                var group = 0
                var inG = 0
                for (i in 0 until offset.coerceAtMost(digits.length)) {
                    val size = sizes.getOrNull(group)
                    if (size != null && inG == size) {
                        transformed++
                        group++
                        inG = 0
                    }
                    transformed++
                    inG++
                }
                return transformed
            }

            override fun transformedToOriginal(offset: Int): Int {
                val clamped = offset.coerceIn(0, spacedText.length)
                return clamped - spacedText.take(clamped).count { it == ' ' }
            }
        }
        return TransformedText(AnnotatedString(spacedText), mapping)
    }
}

/**
 * Renders four digits as `MM/YY` without putting the slash in the value.
 *
 * An expiry is one fact printed on the card as one thing, so it is one well — two independent
 * month and year boxes make the user decide twice and give validation two places to disagree. The
 * separator is drawn, never stored, for the same reason the card number's spaces are.
 */
class LkExpiryTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.take(4)
        val shown = if (digits.length > 2) "${digits.take(2)}/${digits.drop(2)}" else digits
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (offset > 2) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = if (offset > 2) offset - 1 else offset
        }
        return TransformedText(AnnotatedString(shown), mapping)
    }
}

/**
 * One glyph position on the card's number row.
 *
 * The face is a fixed grid of slots rather than a string, because that is what a card is: sixteen
 * printed positions, some of which you have filled in. Rendering only what has been typed makes the
 * number jump about as it grows and gives the user nothing to type *into*.
 */
data class LkCardSlot(
    val ch: Char,
    /** False for a position not yet reached — drawn as a dim bullet. */
    val filled: Boolean,
    /** True for the glyph that has just arrived; only that one animates. */
    val landing: Boolean = false,
)

/** Which fact the current step is asking for. That one lights on the face; the rest stay muted. */
enum class LkCardFacet { None, Number, Expiry, Cvv, Holder }

/** A hair space, drawn as a slot of its own so the row stays monospaced across a group break. */
private const val GROUP_GAP = ' '

/**
 * The card's number row, group by group, padded out to the network's own length.
 *
 * @param digits what the user has typed or the vault holds, digits only.
 * @param maskedBefore positions below this index render as bullets — the caller's masking rule
 *   ("all but the last four"), expressed as an index so this function needs no policy of its own.
 * @param landingIndex the position that has just been filled, or -1 for a face nothing is typing
 *   into. Only ever one, so a long number does not ripple every time it grows.
 */
fun lkCardNumberSlots(
    digits: String,
    groups: LkCardDigitGroups = LkCardDigitGroups.Default,
    maskedBefore: Int = 0,
    landingIndex: Int = -1,
): List<LkCardSlot> {
    val slots = mutableListOf<LkCardSlot>()
    var cursor = 0
    // A number longer than its scheme is regrouped in fours from the right, as the number row does,
    // so the last four a masked face leaves visible are one group. Nothing is padded: the face is
    // only a fixed grid up to the scheme's own length.
    val sizes = groups.sizesFor(digits.length)
    sizes.forEachIndexed { groupIndex, size ->
        repeat(size) { offset ->
            val index = cursor + offset
            val has = index < digits.length
            slots += LkCardSlot(
                ch = if (!has) '•' else if (index < maskedBefore) '•' else digits[index],
                filled = has,
                landing = has && index == landingIndex,
            )
        }
        cursor += size
        if (groupIndex < sizes.lastIndex) slots += LkCardSlot(GROUP_GAP, filled = false)
    }
    return slots
}

/**
 * A saved card drawn as the object it is, rather than as rows of label/value text.
 *
 * A payment card is one of the few things in a vault with a shape everyone already recognises: a
 * 1.586:1 rectangle (ISO/IEC 7810 ID-1), grouped digits along the bottom, the network's name in a
 * corner, expiry and holder beneath. Rendered as six generic grey rows it is indistinguishable from
 * an identity or a login, and the one fact the app computes for free — the brand — ends up as an
 * 11sp caption nobody reads.
 *
 * Nothing here is fetched. The brand is drawn as its own name, from the enum, because the app makes
 * no network calls at all (`checkNoNetworkingDeps`) and a bundled logo set would be the wrong trade
 * for a fact that fits in a word. The gradient is the existing [LkColors.cardFill], the same lift
 * the TOTP block and the health score already use.
 *
 * Not interactive, deliberately, and not styled to look it: a brand mark that reads as a button
 * invites taps from people expecting a network picker. Reveal stays where it already lives — the
 * caller decides which slots are masked, and the CVV is on the *back* and only while the step that
 * asks for it is open.
 *
 * **It turns over.** `Card Flow.dc.html` promotes this face to the top of every wizard step and
 * rotates it on Y when the flow reaches the security code, so the signature strip and the code box
 * are what you are looking at while you read them off the plastic in your hand. Amex skips the flip,
 * because an Amex code is printed on the front — [showBack] is the caller's decision for that
 * reason, not a function of the step alone.
 *
 * @param active the fact the current step is asking for; its label and its rule turn jade. The card
 *   is then the progress indicator, which a five-segment rail can only abstract.
 */
@Composable
fun LkCardPreview(
    numberSlots: List<LkCardSlot>,
    expiryDisplay: String,
    holder: String,
    modifier: Modifier = Modifier,
    brandLabel: String? = null,
    expiryCaption: String,
    holderPlaceholder: String,
    contentDescription: String,
    active: LkCardFacet = LkCardFacet.None,
    showBack: Boolean = false,
    cvvDisplay: String = "",
    cvvSlots: Int = 3,
    cvvHint: String = "",
) {
    val flip by animateFloatAsState(
        if (showBack) 180f else 0f,
        lkTween(LkMotion.Flip, easing = LkMotion.Settle),
        label = "card-flip",
    )
    // Measured, not `aspectRatio`: the face is 1.586:1 while its content fits, and grows past that
    // when it does not (SCL-035). At 308dp and font scale 2.0 the chip, the number's line and the
    // expiry block need more height than a 256dp-wide ID-1 rectangle has, and the bottom-aligned
    // number row rose over the chip. The aspect height is the floor; the front reports the height
    // it really needed and the back takes the same, so the turn does not change the card's size.
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = flip
                // Without a camera distance the rotation reads as a horizontal squash rather than
                // as an object turning over: the default is close enough to exaggerate the
                // perspective into a fisheye at this size.
                cameraDistance = 16f * density
            }
            // One node, one sentence. Read as four siblings it comes out as a brand, then a run of
            // bullets, then two dates with nothing tying them to the card they belong to.
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        val fontScale = LocalDensity.current.fontScale
        var grown by remember(maxWidth, fontScale) { mutableStateOf(0.dp) }
        val minHeight = maxOf(maxWidth / CARD_ASPECT, grown)
        if (flip <= 90f) {
            LkCardFront(
                numberSlots, expiryDisplay, holder, holderPlaceholder, brandLabel, expiryCaption, active,
                minHeight = minHeight,
                onHeight = { if (it > grown + 0.5.dp) grown = it },
            )
        } else {
            // Counter-rotated, or the back would be drawn mirror-imaged by the parent's own turn.
            Box(Modifier.fillMaxWidth().height(minHeight).graphicsLayer { rotationY = 180f }) {
                LkCardBack(cvvDisplay, cvvSlots, cvvHint, active == LkCardFacet.Cvv)
            }
        }
    }
}

/** ISO/IEC 7810 ID-1: width over height. */
private const val CARD_ASPECT = 1.586f

/** The contact plate's height, which the front keeps clear above the number row. */
private val CHIP_HEIGHT = 28.dp

/** The contact plate's width: the brand label's room starts after it. */
private val CHIP_WIDTH = 38.dp

/**
 * The height the brand label takes in [space] - or zero when it does not fit there.
 *
 * It fits on one line or on two, and only if no word has to be broken to do it. Measured with the
 * face's own text style and the same two-line cap the label is drawn with, so what is measured is
 * what is drawn.
 */
internal fun lkCardBrandHeight(measurer: TextMeasurer, density: Density, text: String, space: Dp): Dp {
    if (space <= 0.dp || text.isEmpty()) return 0.dp
    val limit = with(density) { space.roundToPx() }
    val widest = text.split(' ').filter { it.isNotEmpty() }.maxOfOrNull {
        measurer.measure(it, LkText.sectionLabel, softWrap = false).size.width
    } ?: return 0.dp
    if (widest > limit) return 0.dp
    val r = measurer.measure(
        text,
        LkText.sectionLabel,
        maxLines = 2,
        constraints = Constraints(maxWidth = limit),
    )
    if (r.didOverflowHeight || r.didOverflowWidth) return 0.dp
    return with(density) { r.size.height.toDp() }
}

/**
 * The font size at which the whole number row fits [limitPx], measured as the row is built: a Text
 * per slot, each rounded up to a whole pixel. A proportional step lands a pixel or two over because
 * of that rounding, so it is corrected until the sum fits - and once more over the limit is never
 * accepted, since the row gives a child with no width left none at all.
 */
internal fun lkCardGlyphSize(
    measurer: TextMeasurer,
    density: Density,
    slots: List<LkCardSlot>,
    limitPx: Int,
): TextUnit {
    val base = LkText.monoValue
    // A mono face has one advance, so a few distinct glyphs cover the whole row.
    fun rowWidth(size: TextUnit): Int {
        val style = base.copy(fontSize = size)
        val advance = HashMap<Char, Int>()
        return slots.sumOf { advance.getOrPut(it.ch) { measurer.measure(it.ch.toString(), style).size.width } }
    }
    var size = base.fontSize
    var width = rowWidth(size)
    var pass = 0
    while (width > limitPx && width > 0 && pass < GLYPH_FIT_PASSES) {
        size *= minOf(limitPx.toFloat() / width, 0.99f)
        width = rowWidth(size)
        pass++
    }
    // Margin of last resort: if the corrections have not converged, step down until it fits.
    while (width > limitPx && pass < GLYPH_FIT_PASSES + 40) {
        size *= 0.97f
        width = rowWidth(size)
        pass++
    }
    return size
}

private const val GLYPH_FIT_PASSES = 6

@Composable
private fun LkCardFront(
    numberSlots: List<LkCardSlot>,
    expiryDisplay: String,
    holder: String,
    holderPlaceholder: String,
    brandLabel: String?,
    expiryCaption: String,
    active: LkCardFacet,
    minHeight: Dp,
    onHeight: (Dp) -> Unit,
) {
    val c = LkTheme.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    // BoxWithConstraints, so the brand label can be given exactly the room the chip leaves it.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .onSizeChanged { onHeight(with(density) { it.height.toDp() }) }
            .lkSurface(LkShape.card, c.cardFill, c.ringStrong)
            .padding(LkSpace.md),
    ) {
        LkCardChip(Modifier.align(Alignment.TopStart).lkTag("card-chip"))
        // The label owns only the space after the chip. It used to be laid out across the whole face
        // and, when the name needed two lines ("AMERICAN" / "EXPRESS" at 308dp and font scale 2.0),
        // its first line began at the chip's own x and the chip covered "AM" (S-W4-F1). Two lines in
        // that space, or nothing: a label that even two lines cannot hold is dropped, as it is on
        // the wizard's strip, rather than drawn over the chip or broken inside a word.
        val brandText = brandLabel?.uppercase()
        val brandSpace = maxWidth - CHIP_WIDTH - LkSpace.md
        val brandHeight: Dp = brandText?.let {
            lkCardBrandHeight(measurer, density, it, brandSpace)
        } ?: 0.dp
        if (brandText != null && brandHeight > 0.dp) {
            Text(
                brandText,
                style = LkText.sectionLabel,
                color = c.textTertiary,
                textAlign = TextAlign.End,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(max = brandSpace)
                    .lkTag("card-brand"),
            )
        }
        Column(
            // The taller of the chip and the label, and a gap, above the column, inside the column's
            // own measure: the card is as tall as this needs, so the number row can never rise over
            // either.
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(top = maxOf(CHIP_HEIGHT, brandHeight) + LkSpace.sm),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The rule under the number is half of what tells you which fact the screen is asking
            // for; the other half is the "Expires" label below going jade at its own step.
            val underline by animateColorAsState(
                if (active == LkCardFacet.Number) c.jade.copy(alpha = 0.55f) else c.ring,
                lkTween(LkMotion.Crossfade),
                label = "card-number-rule",
            )
            // Shrink to fit rather than clip. The number is one Text per slot in a Row exactly as
            // wide as the card, and the card is a fixed-proportion object — `aspectRatio(1.586f)`
            // above — whose contents scale with the system font size while its width does not. At
            // font scale 2.0 the sixteen glyphs are wider than the face and the tail is cut off by
            // the card's own clip, so the number stopped mid-group and took the last four digits
            // with it. They are the only part of a masked number that identifies the card.
            //
            // Shrinking beats wrapping, the same call the bottom bar's labels and the generator's
            // stage already make: a card number reflowed onto two lines is not a card number. The
            // factor is measured rather than guessed — the mono face's own advance for this string
            // against this constraint — so it is exactly 1.0, and nothing moves, at every font
            // scale where the number already fits.
            val numberText = remember(numberSlots) { numberSlots.joinToString("") { it.ch.toString() } }
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .lkTag("card-number")
                    .drawBehind {
                        val stroke = 1.5.dp.toPx()
                        drawRect(
                            underline,
                            topLeft = Offset(0f, size.height - stroke),
                            size = Size(size.width, stroke),
                        )
                    }
                    .padding(bottom = 5.dp),
            ) {
                // Measured the way the row is laid out: one Text per slot, each as wide as its own
                // advance rounded up to a whole pixel. Fitting the one-string width left the sum of
                // the roundings over the edge, and a Row hands a child that no longer fits zero width -
                // the number lost its tail ("•••• •••• •••• 1", S-W4-L2) however little it overran.
                val glyph = remember(numberText, maxWidth, density) {
                    lkCardGlyphSize(measurer, density, numberSlots, with(density) { maxWidth.roundToPx() })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    numberSlots.forEachIndexed { i, slot ->
                        LkCardSlotGlyph(
                            slot, glyph,
                            if (i == numberSlots.lastIndex) Modifier.lkTag("card-number-last") else Modifier,
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LkSpace.md),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(LkSpace.xs)) {
                    Text(
                        expiryCaption.uppercase(),
                        style = LkText.fieldLabel,
                        color = if (active == LkCardFacet.Expiry) c.jade else c.textFaint,
                    )
                    Text(
                        expiryDisplay,
                        style = LkText.monoField,
                        color = if (expiryDisplay.any(Char::isDigit)) c.textSecondary else c.ringStrong,
                    )
                }
                Text(
                    holder.ifBlank { holderPlaceholder }.uppercase(),
                    style = LkText.chip,
                    color = if (holder.isBlank()) c.ringStrong else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End),
                )
            }
        }
    }
}

/**
 * One glyph of the number.
 *
 * Drawn per character rather than as one string so the newest digit can land on its own — the
 * comp's `cwLand`, 180ms from −7px at 1.16×. Animating the whole row instead would make a
 * sixteen-digit number ripple on every keystroke, which is the thing this is trying not to be.
 */
@Composable
private fun LkCardSlotGlyph(slot: LkCardSlot, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    val reduceMotion = LocalLkReduceMotion.current
    val drop = remember { Animatable(if (slot.landing) 0f else 1f) }
    LaunchedEffect(slot.landing, slot.ch) {
        if (slot.landing) {
            drop.snapTo(0f)
            if (reduceMotion) {
                drop.snapTo(1f)
            } else {
                drop.animateTo(1f, tween(LkMotion.Quick, easing = LkMotion.Overshoot))
            }
        } else {
            drop.snapTo(1f)
        }
    }
    Text(
        slot.ch.toString(),
        style = LkText.monoValue.copy(fontSize = fontSize),
        color = if (slot.filled) c.textStrong else c.ringStrong,
        modifier = modifier.graphicsLayer {
            val scale = 1.16f - 0.16f * drop.value.coerceIn(0f, 1f)
            scaleX = scale
            scaleY = scale
            translationY = (1f - drop.value) * -7.dp.toPx()
            alpha = drop.value.coerceIn(0f, 1f)
        },
    )
}

/** The gold contact plate, drawn rather than fetched — this app ships no card artwork. */
@Composable
private fun LkCardChip(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = CHIP_WIDTH, height = CHIP_HEIGHT)) {
        val radius = 5.dp.toPx()
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color(0xFFC9A96A), Color(0xFF8E7538)),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
            cornerRadius = CornerRadius(radius, radius),
        )
        val line = Color(0x80231A08)
        val stroke = 1.dp.toPx()
        listOf(9.dp.toPx(), 18.dp.toPx()).forEach { y ->
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
        listOf(13.dp.toPx(), 25.dp.toPx()).forEach { x ->
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
        }
    }
}

/**
 * The back: signature strip, code box, and the sentence saying which is which.
 *
 * The code is shown here masked exactly as the field above it is — the box on a real card is where
 * the digits are printed, and drawing them in clear on a face that is also a screenshot target
 * would be a second, unguarded copy of the one secret this step exists to take.
 */
@Composable
private fun LkCardBack(cvvDisplay: String, cvvSlots: Int, cvvHint: String, active: Boolean) {
    val c = LkTheme.colors
    // Clipped to the card's own radius, or the magnetic stripe's square corners hang past the
    // rounded edge — the comp gets this from `overflow:hidden` on the face and a Compose background
    // only clips itself, not the children drawn over it.
    Box(
        Modifier
            .fillMaxSize()
            .lkSurface(LkShape.card, c.cardFill, c.ringStrong)
            .clip(LkShape.card),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .height(44.dp)
                .background(Color(0xFF050708)),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 92.dp)
                .padding(horizontal = LkSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Canvas(Modifier.weight(1f).height(34.dp)) {
                val radius = 5.dp.toPx()
                drawRoundRect(Color(0xFFE6E9EA), cornerRadius = CornerRadius(radius, radius))
                // The hatching of a signature panel, at the comp's -58°, clipped to the panel.
                val step = 14.dp.toPx()
                val stroke = 7.dp.toPx()
                clipRect {
                    var x = -size.height
                    while (x < size.width + size.height) {
                        drawLine(
                            Color(0xFFCFD5D7),
                            Offset(x, size.height),
                            Offset(x + size.height * 0.6f, 0f),
                            strokeWidth = stroke,
                        )
                        x += step
                    }
                }
            }
            Box(
                Modifier
                    .width(if (cvvSlots > 3) 54.dp else 46.dp)
                    .height(34.dp)
                    .background(Color(0xFFF2F4F5), LkShape.xs)
                    .lkRing(
                        LkShape.xs,
                        if (active) c.jade.copy(alpha = 0.65f) else Color(0x2E000000),
                        width = 2.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    cvvDisplay,
                    // The comp's own 16/1.45 mono at .16em, which this repo already carries as the
                    // style a secret is *typed* in — the same characters, in the same rhythm.
                    style = LkText.secretInput,
                    color = Color(0xFF0C1012),
                )
            }
        }
        if (cvvHint.isNotBlank()) {
            Text(
                cvvHint,
                style = LkText.caption,
                color = c.textFaint,
                modifier = Modifier.align(Alignment.BottomStart).padding(LkSpace.md),
            )
        }
    }
}
