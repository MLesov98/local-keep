package com.localkeep.core.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The card face while the keyboard is up: one row, closed on all four edges.
 *
 * With the keyboard raised there is room for about a quarter of the full face, and the full face
 * cannot be cropped to fit — a cut card reads as a rendering bug, and on steps 2–4 it is the *only*
 * place the number is still readable (the field that took it is gone). So the wizard cross-fades to
 * this: the same card tokens, the chip and the number on one line, the network's name when it fits.
 * On the security-code step it turns over to the same shell and shows the signature panel and the
 * code box, so the flip survives.
 *
 * **It echoes only the number.** The expiry and the holder are being typed into the field directly
 * below, and a second copy would cost a row the budget does not have. (A two-row card that lights
 * the active fact is possible and costs ~34dp more — see the fix plan, decision 1.)
 *
 * **Fit rule.** The digits shrink to fit by the same method the full face uses — the mono face's own
 * advance, measured — but never below [MIN_DIGIT_FACTOR] while something else can give way first:
 * the brand name goes, then the chip. A 16-digit Visa on a 384dp phone is chip + digits; a 19-digit
 * number is digits alone.
 *
 * Height is content-driven (the number's line plus the card padding), so it grows with the system
 * font size rather than clipping. Not interactive, like the face it stands in for.
 */
@Composable
fun LkCardStrip(
    numberSlots: List<LkCardSlot>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    brandLabel: String? = null,
    showBack: Boolean = false,
    cvvDisplay: String = "",
    cvvSlots: Int = 3,
    cvvActive: Boolean = false,
) {
    val density = LocalDensity.current
    // Each face owns its own turn, exactly as LkCardPreview does: both are composed together for the
    // whole wizard and see the same `showBack` in the same frame, so they stay in step without a
    // shared animation object — and nothing is composed fresh when the keyboard rises.
    val flip by animateFloatAsState(
        if (showBack) 180f else 0f,
        lkTween(LkMotion.Flip, easing = LkMotion.Settle),
        label = "card-strip-flip",
    )
    // The number's own line plus the card's padding — the same on both sides of the flip, so the
    // shell does not change height halfway through it.
    val lineHeight = with(density) { LkText.monoValue.lineHeight.toDp() }
    val shellHeight = lineHeight + LkSpace.md * 2
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = flip
                cameraDistance = 16f * this.density
            }
            .clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        if (flip <= 90f) {
            StripFront(numberSlots, brandLabel, shellHeight)
        } else {
            Box(Modifier.fillMaxWidth().graphicsLayer { rotationY = 180f }) {
                StripBack(cvvDisplay, cvvSlots, cvvActive, shellHeight)
            }
        }
    }
}

/** The narrowest the digits are allowed to get before the brand, then the chip, are dropped. */
internal const val MIN_DIGIT_FACTOR = 0.8f

/** How the network's name sits on the strip. */
internal enum class StripBrandFit { None, OneLine, TwoLines }

/**
 * Whether the name fits, and on how many lines.
 *
 * One line first, as before: the number may shrink to [MIN_DIGIT_FACTOR] to make room. A name of
 * several words that does not fit on one line may take two ("AMERICAN" over "EXPRESS"), because the
 * strip had space for it all along: a 15-digit Amex leaves about 54dp of a 327dp row empty beside
 * the chip and the number, and the one-line name needs 129 (S5-F-08). The line break costs no
 * height - two 11sp lines are shorter than the number's own line. The two-line form keeps a smaller
 * gap to the digits ([BRAND_WRAP_GAP]), since the name is then right-aligned in whatever is left.
 * Nothing about the 19-digit case changes: with the digits at their floor there is no room for any
 * form of the name, and the rule that drops it first, then the chip, still holds.
 */
internal fun lkStripBrandFit(
    minNumber: Dp,
    maxWidth: Dp,
    chipWidth: Dp,
    gap: Dp,
    brandWidth: Dp,
    brandWrappedWidth: Dp?,
): StripBrandFit = when {
    brandWidth <= 0.dp -> StripBrandFit.None
    minNumber <= maxWidth - chipWidth - gap - brandWidth - gap -> StripBrandFit.OneLine
    brandWrappedWidth != null &&
        minNumber <= maxWidth - chipWidth - gap - brandWrappedWidth - BRAND_WRAP_GAP -> StripBrandFit.TwoLines
    else -> StripBrandFit.None
}

private val BRAND_WRAP_GAP = 8.dp

/** Correction passes after the first proportional shrink; two converge, the third is margin. */
private const val FIT_PASSES = 3

@Composable
private fun StripFront(numberSlots: List<LkCardSlot>, brandLabel: String?, minHeight: Dp) {
    val c = LkTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .lkSurface(LkShape.card, c.cardFill, c.ringStrong)
            .padding(LkSpace.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        val measurer = rememberTextMeasurer()
        val numberText = remember(numberSlots) { numberSlots.joinToString("") { it.ch.toString() } }
        val numberStyled = remember(numberSlots, c) {
            buildAnnotatedString {
                numberSlots.forEach { slot ->
                    withStyle(SpanStyle(color = if (slot.filled) c.textStrong else c.ringStrong)) {
                        append(slot.ch)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val natural = with(LocalDensity.current) {
                measurer.measure(numberText, LkText.monoValue).size.width.toDp()
            }
            val brandText = brandLabel?.uppercase()
            val (brandWidth, brandWrappedWidth) = if (brandText == null) {
                0.dp to null
            } else {
                with(LocalDensity.current) {
                    val whole = measurer.measure(brandText, LkText.sectionLabel).size.width.toDp()
                    // The widest word: what the name needs when it is allowed a second line.
                    val words = brandText.split(' ').filter { it.isNotEmpty() }
                    val widest = if (words.size < 2) {
                        null
                    } else {
                        words.maxOf { measurer.measure(it, LkText.sectionLabel).size.width.toDp() }
                    }
                    whole to widest
                }
            }
            val gap = LkSpace.md
            val minNumber = natural * MIN_DIGIT_FACTOR
            val brandFit = lkStripBrandFit(minNumber, maxWidth, CHIP_WIDTH, gap, brandWidth, brandWrappedWidth)
            val withBrand = brandFit != StripBrandFit.None
            val withChip = withBrand || minNumber <= maxWidth - CHIP_WIDTH - gap
            val available = maxWidth -
                (if (withChip) CHIP_WIDTH + gap else 0.dp) -
                when (brandFit) {
                    StripBrandFit.None -> 0.dp
                    StripBrandFit.OneLine -> brandWidth + gap
                    StripBrandFit.TwoLines -> (brandWrappedWidth ?: 0.dp) + BRAND_WRAP_GAP
                }
            // One proportional step is not enough: at a large font scale sp does not convert to px
            // linearly, so the shrunk text lands wider than the ratio promised and its last digit is
            // cut. Measure what the shrunk size really takes and correct, until it fits.
            var fontSize = LkText.monoValue.fontSize
            if (natural > available && natural > 0.dp) {
                fontSize *= available / natural
                repeat(FIT_PASSES) {
                    val taken = with(LocalDensity.current) {
                        measurer.measure(numberText, LkText.monoValue.copy(fontSize = fontSize)).size.width.toDp()
                    }
                    if (taken > available) fontSize *= available / taken
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (withChip) {
                    StripChip()
                    Spacer(Modifier.width(gap))
                }
                Text(
                    numberStyled,
                    style = LkText.monoValue.copy(fontSize = fontSize),
                    maxLines = 1,
                    softWrap = false,
                )
                // The brand takes what the number leaves. A weighted spacer here would split the free
                // width with the number and clip its last digits whenever the brand fits.
                if (withBrand) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            brandText.orEmpty(),
                            style = LkText.sectionLabel,
                            color = c.textTertiary,
                            textAlign = TextAlign.End,
                            // Two lines at most, and only on a space: "AMERICAN EXPRESS" when it
                            // is given the room for one line stays on one.
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The back of the card, on the strip's shell: the magnetic band along the top, then the signature
 * panel and the code box. Same numbers as the full back ([LkCardPreview]) so the two read as one
 * object — the box in particular is 46×34 (54 for a four-digit code) in both.
 */
@Composable
private fun StripBack(cvvDisplay: String, cvvSlots: Int, active: Boolean, minHeight: Dp) {
    val c = LkTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .lkSurface(LkShape.card, c.cardFill, c.ringStrong)
            .clip(LkShape.card),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(BAND_HEIGHT)
                .background(Color(0xFF050708)),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = LkSpace.md)
                .padding(bottom = LkSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Canvas(Modifier.weight(1f).height(CODE_ROW_HEIGHT)) {
                val radius = 5.dp.toPx()
                drawRoundRect(Color(0xFFE6E9EA), cornerRadius = CornerRadius(radius, radius))
                // The hatching of a signature panel, as on the full back.
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
                    .height(CODE_ROW_HEIGHT)
                    .background(Color(0xFFF2F4F5), LkShape.xs)
                    .lkRing(
                        LkShape.xs,
                        if (active) c.jade.copy(alpha = 0.65f) else Color(0x2E000000),
                        width = 2.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Masked exactly as on the full back and in the field: a second clear copy of the
                // code on a surface that is also a screenshot target would be an unguarded one.
                Text(cvvDisplay, style = LkText.secretInput, color = Color(0xFF0C1012))
            }
        }
    }
}

/** The gold contact plate — the same drawing as the full face's, which keeps its own private. */
@Composable
private fun StripChip(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = CHIP_WIDTH, height = 28.dp)) {
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

private val CHIP_WIDTH = 38.dp
private val BAND_HEIGHT = 14.dp
private val CODE_ROW_HEIGHT = 34.dp
