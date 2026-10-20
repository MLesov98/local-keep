package com.localkeep.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The palette's WCAG 2.1 AA audit, as a test rather than as a one-off spreadsheet.
 *
 * C6 was carried for a long time as "audit the contrast", and an audit performed once is a fact
 * about a commit, not about the app. Every value in [LkColors] is a default argument, so a future
 * visual tweak — nudging `textFaint` a shade quieter to calm down a busy screen — can put the app
 * under AA with nothing in the build objecting. These tests are what objects.
 *
 * Thresholds, from WCAG 2.1:
 *  - 1.4.3 Contrast (Minimum), AA: 4.5:1 for body text.
 *  - 1.4.11 Non-text Contrast, AA: 3:1 for anything that indicates a component or its state —
 *    which is what the focus and error rings are.
 *
 * What is deliberately *not* asserted here is the hairline rings ([LkColors.ring] and friends), and
 * that is a judgement worth writing down rather than leaving as an omission. They measure ~1.1–1.3:1
 * against their own backgrounds, and they always will: they are 5–10% white over near-black, which
 * is the whole mechanism this design uses to make surfaces separate without lightening them into
 * grey. 1.4.11 applies to visual information *required to identify* a component — and nothing here
 * is identified by its hairline. Every button carries a label at 11:1 or better, every icon-only
 * control carries a glyph at 10:1 or better, and the focus indicator below is what marks state. The
 * rings are refinement on top of that, not the signal.
 *
 * That exemption turned into a hole, which is why [LkColors.controlIdle] exists and is asserted
 * below. It rests on a premise — "there is always a glyph at 10:1 carrying the meaning" — that an
 * *empty* control does not have. An unchecked tick box draws no glyph at all: its 1.5dp ring was
 * the only thing rendered, at 1.30:1, so the difference between off and not-a-control was
 * invisible. Same shape on the segment meter, whose unfilled bars measured 1.14:1 and took the
 * denominator with them — three of five looked exactly like three of three. The rule the two cases
 * share: a hairline that decorates something already legible is exempt; a hairline that *is* the
 * state is not.
 */
class LkContrastTest {

    private val c = LkColors()

    /** Every surface a foreground can land on. */
    private val surfaces = mapOf(
        "ground" to c.ground,
        "surface" to c.surface,
        "field" to c.field,
        "cardTop" to c.cardTop,
        "cardBottom" to c.cardBottom,
        "sheetTop" to c.sheetTop,
        "dialogTop" to c.dialogTop,
        "dialogBottom" to c.dialogBottom,
        "drawerTop" to c.drawerTop,
        "drawerBottom" to c.drawerBottom,
    )

    /** Every colour used as text or as a meaning-carrying glyph. */
    private val foregrounds = mapOf(
        "textStrong" to c.textStrong,
        "textPrimary" to c.textPrimary,
        "textSecondary" to c.textSecondary,
        "textTertiary" to c.textTertiary,
        "textMuted" to c.textMuted,
        "textFaint" to c.textFaint,
        "jade" to c.jade,
        "jadeDim" to c.jadeDim,
        "jadeLit" to c.jadeLit,
        "brass" to c.brass,
        "brassLit" to c.brassLit,
        "amber" to c.amber,
        "coral" to c.coral,
        "coralText" to c.coralText,
    )

    @Test
    fun `every text colour clears AA on every surface`() {
        val failures = buildList {
            foregrounds.forEach { (fgName, fg) ->
                surfaces.forEach { (bgName, bg) ->
                    val r = contrast(fg, bg)
                    if (r < AA_TEXT) add("$fgName on $bgName = ${"%.2f".format(r)}:1")
                }
            }
        }
        assertTrue(failures.isEmpty(), "below AA 4.5:1:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `ink on an accent fill clears AA`() {
        // Both fills are gradients, so both stops have to pass — a label that is readable at the top
        // of a button and not at the bottom is still an unreadable button.
        val pairs = listOf(
            "onJade/jade" to (c.onJade to c.jade),
            "onJade/jadeDim" to (c.onJade to c.jadeDim),
            "onJade/jadeFillTop" to (c.onJade to Color(0xFF4BE0B6)),
            "onBrass/brass" to (c.onBrass to c.brass),
            "onBrass/brassFillTop" to (c.onBrass to Color(0xFFDCC189)),
            "onBrass/brassFillBottom" to (c.onBrass to Color(0xFFC09A52)),
        )
        val failures = pairs.mapNotNull { (name, p) ->
            val r = contrast(p.first, p.second)
            if (r < AA_TEXT) "$name = ${"%.2f".format(r)}:1" else null
        }
        assertTrue(failures.isEmpty(), "below AA 4.5:1:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `focus and error rings clear the 3 to 1 that 1_4_11 asks of a state indicator`() {
        // These are semi-transparent, so what matters is the composite against what they are drawn
        // over — the ring's own nominal colour says nothing about whether it can be seen.
        //
        // `controlIdle` is here rather than in a test of its own because it answers the same clause
        // for the same reason: it is the only thing drawn when a tick box is unchecked or a meter
        // segment unfilled, so if it cannot be seen, neither can the state. It is the token that
        // regresses silently — every wash in the palette is a plausible-looking substitute for it,
        // and the two it replaced (`washStrong` at 1.30:1, `wash` at 1.14:1) both shipped.
        val rings = mapOf(
            "focusRing" to c.focusRing,
            "focusRingSoft" to c.focusRingSoft,
            "errorRing" to c.errorRing,
            "controlIdle" to c.controlIdle,
        )
        val failures = buildList {
            rings.forEach { (ringName, ring) ->
                surfaces.forEach { (bgName, bg) ->
                    val r = contrast(ring.compositeOver(bg), bg)
                    if (r < AA_NON_TEXT) add("$ringName on $bgName = ${"%.2f".format(r)}:1")
                }
            }
        }
        assertTrue(failures.isEmpty(), "below 3:1:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `the keyboard focus ring is legible on every surface it can land on`() {
        // lkFocusRing draws full-strength jade, and on the primary button it draws onJade instead
        // because jade on a jade fill would be invisible. Both readings are checked.
        val failures = buildList {
            surfaces.forEach { (bgName, bg) ->
                val r = contrast(c.jade, bg)
                if (r < AA_NON_TEXT) add("jade focus ring on $bgName = ${"%.2f".format(r)}:1")
            }
            listOf(c.jade, c.jadeDim, Color(0xFF4BE0B6)).forEachIndexed { i, fill ->
                val r = contrast(c.onJade, fill)
                if (r < AA_NON_TEXT) add("onJade focus ring on jade fill stop $i = ${"%.2f".format(r)}:1")
            }
        }
        assertTrue(failures.isEmpty(), "below 3:1:\n" + failures.joinToString("\n"))
    }

    /**
     * A sheet that floats over list rows has to have an edge of its own.
     *
     * Not a WCAG clause — 1.4.11 exempts this for the reason the class header gives, and no sheet is
     * *identified* by its edge. It is here because the token it guards is the sort that regresses
     * silently: `sheetTop` sat at `#141A1C` against `surface`'s `#0E1214`, a 1.07:1 boundary carried
     * entirely by a hairline ring, and every neighbouring value in the palette is a plausible-looking
     * substitute for the one that fixes it.
     *
     * Expressed as a luminance gap rather than as a ratio, because that is what the eye is reading at
     * this end of the ramp: two near-black surfaces one rung apart differ by hundredths of a ratio
     * point and the ratio stops being a useful number. The floor is set below the value the fix
     * produces (0.005183) and above the value it replaced (0.003949), so it fails on a walk-back of
     * one rung and does not fail on a further tweak in the right direction.
     */
    @Test
    fun `a floating sheet has a visible edge over the surface it floats on`() {
        val gap = luminance(c.sheetTop) - luminance(c.surface)
        assertTrue(
            gap >= SHEET_EDGE_MIN_LUMINANCE_GAP,
            "sheetTop is only ${"%.4f".format(gap)} above surface in relative luminance — " +
                "a sheet floating over list rows needs at least $SHEET_EDGE_MIN_LUMINANCE_GAP " +
                "or its edge is the ring alone",
        )
    }

    @Test
    fun `monogram tiles are readable, since they stand in for a favicon`() {
        // A tile whose initials can't be read is worse than no tile: the whole point is that you
        // match an item by colour-and-letter at a glance down a list.
        val failures = TileRamp.mapIndexedNotNull { i, t ->
            val r = contrast(t.content, t.container)
            if (r < AA_TEXT) "tile $i = ${"%.2f".format(r)}:1" else null
        }
        assertTrue(failures.isEmpty(), "below AA 4.5:1:\n" + failures.joinToString("\n"))
    }

    // ── WCAG relative luminance ───────────────────────────────────────────

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    /** Straight-alpha composite of a translucent colour over an opaque one. */
    private fun Color.compositeOver(background: Color): Color = Color(
        red = red * alpha + background.red * (1 - alpha),
        green = green * alpha + background.green * (1 - alpha),
        blue = blue * alpha + background.blue * (1 - alpha),
    )

    private companion object {
        const val AA_TEXT = 4.5
        const val AA_NON_TEXT = 3.0

        /** See `a floating sheet has a visible edge over the surface it floats on`. */
        const val SHEET_EDGE_MIN_LUMINANCE_GAP = 0.005
    }
}
