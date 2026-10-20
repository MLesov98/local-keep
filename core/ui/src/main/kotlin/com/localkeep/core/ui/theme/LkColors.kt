package com.localkeep.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The LocalKeep palette, transcribed from the visual pass (`LocalKeep.html`).
 *
 * Material's [androidx.compose.material3.ColorScheme] is still populated (see [lkColorScheme]) so
 * that stock components and any screen not yet converted stay coherent, but it cannot express what
 * this design actually needs: four *semantic* accents that mean specific things, a five-step text
 * ramp, and hairline rings that are alpha over the ground rather than an opaque colour. Those live
 * here and reach composables through [LocalLkColors].
 *
 * The accents are load-bearing, not decorative:
 *  - [jade] — safe, encrypted, done. The only colour used for primary action.
 *  - [brass] — Pro, and anything key-shaped (recovery kit, favourites).
 *  - [amber] — caution. Something is worth looking at but nothing is wrong.
 *  - [coral] — attention. A failure, a breach match, an expiring code — and a compromised
 *    environment. The root/tamper banner is coral rather than amber because "nothing is wrong" is
 *    untrue on a rooted device: it is the one condition that weakens every other guarantee the app
 *    makes. The test for coral is not "did the app fail", it is "is the user's data less safe than
 *    they believe" — and an unmodified OS is the belief everything else here rests on.
 *
 * Colour is never the sole signal for any of these — every place one is used also carries a label,
 * a count or a shape (UI/UX spec §7, and the mockup's own note "colour is never the only signal").
 */
@Immutable
data class LkColors(
    // ── grounds & surfaces ────────────────────────────────────────────────
    /** The vault floor. Everything else sits on this. */
    val ground: Color = Color(0xFF0A0C0E),
    /** A raised block: option groups, field cards, list cards. */
    val surface: Color = Color(0xFF0E1214),
    /** An input well — deliberately darker than [surface] so it reads as recessed. */
    val field: Color = Color(0xFF101416),
    /** Top and bottom stops of the "lifted card" gradient (score card, TOTP block, generator out). */
    val cardTop: Color = Color(0xFF12171A),
    val cardBottom: Color = Color(0xFF0C1012),
    /**
     * Modal surfaces.
     *
     * One rung above where it used to sit (`#141A1C`, 2026-09-17).
     *
     * A sheet is the one modal surface with no scrim under its own edge: the multi-select bar and
     * the add sheet float straight over list rows drawn on [surface], and at `#141A1C` that edge
     * measured 1.07:1 against them — a boundary carried entirely by a 1px ring. The rung above,
     * taking the ladder's own +2-per-channel step, measures 1.09:1: relative luminance 0.010948
     * against surface's 0.005765, a gap 31% wider than the 0.003949 it replaces. Still quiet, and
     * deliberately one rung rather than two — this is a surface separation, not a contrast
     * requirement. `LkContrastTest` pins the gap so a future palette tweak cannot walk it back.
     *
     * It lands a shade above [dialogTop], which is the right way round rather than an accident: a
     * dialog has a scrim beneath it and does not need its own edge to separate it from anything.
     */
    val sheetTop: Color = Color(0xFF161C1E),
    val dialogTop: Color = Color(0xFF151B1D),
    val dialogBottom: Color = Color(0xFF0D1113),
    val drawerTop: Color = Color(0xFF111618),
    val drawerBottom: Color = Color(0xFF0B0E10),
    /** Scrim behind drawer/sheet, and the heavier one behind the re-auth gate. */
    val scrim: Color = Color(0xB8040607),
    val scrimStrong: Color = Color(0xCC040607),

    // ── text ramp ─────────────────────────────────────────────────────────
    /** Screen titles and revealed values. */
    val textStrong: Color = Color(0xFFF4F7F8),
    /** Body-level primary: row titles, field values. */
    val textPrimary: Color = Color(0xFFEDF1F2),
    /** Secondary controls and drawer labels. */
    val textSecondary: Color = Color(0xFFC4CDD1),
    /** Supporting prose. */
    val textTertiary: Color = Color(0xFFA8B2B7),
    /** Subtitles, descriptions. */
    val textMuted: Color = Color(0xFF8B979E),
    /** Captions, hints, counters — the quietest legible step. */
    val textFaint: Color = Color(0xFF7A858B),

    // ── accents ───────────────────────────────────────────────────────────
    val jade: Color = Color(0xFF3ED9AE),
    val jadeDim: Color = Color(0xFF2FBD93),
    val jadeLit: Color = Color(0xFF7BEFD0),
    /** Text/icon colour *on* a jade fill. Near-black green, not white — jade is a light accent. */
    val onJade: Color = Color(0xFF04211A),
    val brass: Color = Color(0xFFC9A96A),
    val brassLit: Color = Color(0xFFD9BE86),
    val onBrass: Color = Color(0xFF231A08),
    val amber: Color = Color(0xFFE8B04B),
    val coral: Color = Color(0xFFF2705B),
    /** Coral is too saturated to read as body text; error prose uses this instead. */
    val coralText: Color = Color(0xFFF2A392),

    // ── hairlines & washes ────────────────────────────────────────────────
    /** The 1px inset ring that separates every raised surface from the ground. */
    val ring: Color = Color(0x12FFFFFF),
    /** A slightly brighter ring for interactive chrome (buttons, chips). */
    val ringStrong: Color = Color(0x17FFFFFF),
    /** Row separator inside a grouped card. */
    val separator: Color = Color(0x0EFFFFFF),
    /** Neutral pressed/hover wash. */
    val wash: Color = Color(0x0DFFFFFF),
    /**
     * A wash one step quieter, for a control that should read as recessed rather than raised.
     *
     * `LocalKeep.dc.html` draws the vault list's unselected filter chips at
     * `background:rgba(255,255,255,.035)` — 0.035 x 255 = 8.9, so 0x09. [wash] is 5.1% and is
     * right for a plate that sits *on* something; this is right for one cut *into* it.
     */
    val washFaint: Color = Color(0x09FFFFFF),
    val washStrong: Color = Color(0x1AFFFFFF),
    /**
     * The visible half of a control that is *off*: an unchecked tick box's ring, a meter's
     * unfilled segments.
     *
     * Measured, like [focusRing] and [errorRing], and for the same reason. The washes above are
     * refinement drawn on top of a signal — a pressed tint, a hairline separating two surfaces —
     * and 1.4.11 does not ask anything of them because nothing is identified by them. An empty
     * control is the opposite case: the ring *is* the whole control, and an unfilled segment is
     * what makes "three of five" different from "three of three". Drawn in [washStrong] the tick
     * box measured 1.30:1 against [surface] and the meter track 1.14:1 against [cardTop], so the
     * off state had no rendered pixels a reader could be sure were there.
     *
     * 0.40 alpha, not a rounder 0.35: 0.35 lands at 3.15:1 on [ground], and a ring that clears the
     * bar by fifteen hundredths on the app's darkest surface is one palette tweak from failing.
     * 0.40 measures 3.79:1 at worst across every surface in [LkContrastTest].
     */
    val controlIdle: Color = Color(0x66FFFFFF),
) {
    /**
     * Focus ring for a field that has the caret.
     *
     * Measured, not chosen by eye. At the 0.40 alpha this started at, jade over the field composites
     * to 2.58:1 against it — under the 3:1 WCAG 1.4.11 asks of anything that indicates a component's
     * state, so the one visual cue telling a user where the caret is was itself too quiet to find.
     * 0.65 measures 5.1:1. [focusRingSoft] is deliberately the quieter of the two but still clears
     * the bar at 3.9:1.
     */
    val focusRing: Color get() = jade.copy(alpha = 0.65f)
    /** Focus ring for search, which is a lighter-weight commitment than a password field. */
    val focusRingSoft: Color get() = jade.copy(alpha = 0.55f)
    /**
     * Likewise raised to clear 3:1 — an error ring nobody can see is not an error signal.
     *
     * 0.65 rather than a rounder 0.60, because coral is darker than jade and 0.60 lands at 2.94:1 on
     * the dialog surface. That is the sort of two-hundredths miss that only a measurement finds;
     * [LkContrastTest] found exactly it.
     */
    val errorRing: Color get() = coral.copy(alpha = 0.68f)

    /** Tinted container + ring pairs, used by chips, icon tiles and quiet buttons. */
    fun tint(accent: Color, alpha: Float = 0.10f): Color = accent.copy(alpha = alpha)

    /** The primary action fill. A gradient, not a flat colour — it is the app's one bright object. */
    val jadeFill: Brush
        get() = Brush.linearGradient(
            colors = listOf(Color(0xFF4BE0B6), jadeDim),
            // ~168deg in CSS terms: almost vertical, tipped slightly right.
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(60f, 400f),
        )

    /** The Pro action fill. Brass reads as "paid" without ever looking like an alert. */
    val brassFill: Brush
        get() = Brush.linearGradient(
            colors = listOf(Color(0xFFDCC189), Color(0xFFC09A52)),
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(60f, 400f),
        )

    /** The lifted-card gradient shared by the score card, TOTP block and generator output. */
    val cardFill: Brush
        get() = Brush.linearGradient(
            colors = listOf(cardTop, cardBottom),
            start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(240f, 620f),
        )
}

val LocalLkColors = staticCompositionLocalOf { LkColors() }

/**
 * Deterministic monogram tile colours: `[container, content, glow]`.
 *
 * Identity has to come from the item's own name because the app never fetches a favicon — that
 * would be a network call, which this app does not make at all (`checkNoNetworkingDeps`). Hashing
 * the title means the same entry keeps the same colour forever, across devices and reinstalls,
 * with no stored state.
 *
 * `glow` is the container at half alpha. It is what [LkMonogramTile] casts behind itself, so the
 * tile sits in its own colour rather than directly on the ground — declared and populated for all
 * eight entries but read by nothing at all until 2026-08-10.
 */
@Immutable
data class LkTileColors(val container: Color, val content: Color, val glow: Color)

/** `internal` rather than private so [com.localkeep.core.ui.theme.LkContrastTest] audits the real
 *  ramp — a copy of these values in the test would pass happily while the app shipped unreadable
 *  tiles. */
internal val TileRamp = listOf(
    LkTileColors(Color(0xFF2E3A6B), Color(0xFFA8B8F0), Color(0x802E3A6B)),
    LkTileColors(Color(0xFF1E4A45), Color(0xFF8DE0CF), Color(0x801E4A45)),
    LkTileColors(Color(0xFF4A3628), Color(0xFFE0BE9A), Color(0x804A3628)),
    LkTileColors(Color(0xFF3A2C5A), Color(0xFFC7B0F0), Color(0x803A2C5A)),
    LkTileColors(Color(0xFF5A2740), Color(0xFFF0A8C4), Color(0x805A2740)),
    LkTileColors(Color(0xFF1A4553), Color(0xFF8CD4EA), Color(0x801A4553)),
    LkTileColors(Color(0xFF333E44), Color(0xFFB6C4CC), Color(0x80333E44)),
    LkTileColors(Color(0xFF5A3320), Color(0xFFF0B392), Color(0x805A3320)),
)

/**
 * Picks the tile colours for a title.
 *
 * The hash is the plain FNV-ish `h * 31 + char` the mockup uses, kept deliberately simple: it is
 * only choosing between eight swatches, and any change to it would silently recolour every item in
 * every existing vault.
 */
fun lkTileColorsFor(title: String): LkTileColors {
    var h = 0
    for (c in title) h = h * 31 + c.code
    // `h` overflows; `and Int.MAX_VALUE` gives the unsigned-style positive index the mockup gets
    // from JavaScript's `>>> 0` without depending on how Kotlin's `%` treats negatives.
    return TileRamp[(h and Int.MAX_VALUE) % TileRamp.size]
}

/**
 * Up to two letters for the tile: initials of the first two words, or the first two letters of a
 * single-word title. "Bank of Ireland" → "BO", "Google" → "GO".
 *
 * The first two words literally, including a short one: "of" is the second word above, so the tile
 * reads BO and not BI. Skipping stopwords would read better and is a change to what the tiles say,
 * not a fix — left alone deliberately.
 *
 * **Punctuation separates words; it is never an initial.** Splitting on whitespace alone made the
 * bracket in "Licence (BW)" the whole second half of the tile — `L(` — and did the same to
 * Mastercard, Passport and Visa, while a hyphen inside a filename left every imported `t-*.png`
 * rendering the identical `T-`. A stray bracket in an avatar reads as a rendering fault, and a
 * column of identical tiles stops distinguishing anything (1B-F10 / 2A-F9 / 4C-19, 2026-09-20).
 * With punctuation separating, "Licence (BW)" is LB and "t-archive.zip" is TA.
 *
 * **A word that starts with a digit is skipped while any other word remains**, so the picker's
 * opaque `323.png` reads PN rather than `32`. If digits are all there is — "1Password" — they
 * are still better than "?", so the filter gives way rather than emptying the tile.
 *
 * Symbols are *not* punctuation: an emoji is a perfectly good initial and the surrogate-pair
 * handling below exists to keep it whole.
 */
fun lkMonogram(title: String): String {
    val split = title.split(WordSeparators).filter { it.isNotEmpty() }
    val words = split.filterNot { it[0].isDigit() }.ifEmpty { split }
    return when {
        words.isEmpty() -> "?"
        // By code point, not by Char, for the same reason as the two-word branch below: take(2)
        // could keep a lone high surrogate and render a replacement box.
        words.size == 1 -> {
            val cp1 = words[0].firstCodePoint()
            val rest = words[0].substring(cp1.length)
            val cp2 = if (rest.isEmpty()) "" else rest.firstCodePoint()
            (cp1 + cp2).uppercase()
        }
        // By code point, not by Char: an emoji outside the BMP is a surrogate pair, and `first()`
        // returns half of one — a lone high surrogate rendered as a replacement box. "💰 Savings"
        // put that box in the tile instead of the emoji.
        else -> (words[0].firstCodePoint() + words[1].firstCodePoint()).uppercase()
    }
}

/**
 * What [lkMonogram] treats as the gap between two words: whitespace, or any punctuation.
 *
 * The classes are the Unicode general categories `P` (every punctuation category) and `Z` (every
 * separator), plus `\s`, so an em dash or a full-width bracket is a gap and not an initial.
 * Written as single-letter categories on purpose: the previous `(?U)[\p{Punct}\s]+` passed
 * `LkMonogramTest` on OpenJDK and threw `PatternSyntaxException` from this file's static
 * initialiser on Android, whose ICU-backed `java.util.regex` rejects the `(?U)` flag — which
 * crashed `LocalKeepTheme`, and with it every launch, on the 2026-09-20 device re-verification.
 * `\p{P}` and `\p{Z}` are accepted by both engines. Symbols (`So` — every emoji) are
 * deliberately outside category `P` and so are kept.
 */
private val WordSeparators = Regex("""[\p{P}\p{Z}\s]+""")

/** The first whole code point of [this], so a surrogate pair survives as the pair it is. */
private fun String.firstCodePoint(): String {
    val first = this[0]
    return if (first.isHighSurrogate() && length > 1 && this[1].isLowSurrogate()) substring(0, 2)
    else first.toString()
}
