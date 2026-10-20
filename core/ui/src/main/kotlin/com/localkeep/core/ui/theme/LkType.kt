package com.localkeep.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * The type scale from the visual pass.
 *
 * **On the typeface.** The mockup specifies Geist. It is not in the repository and cannot be
 * fetched — this app makes no network calls by construction (`checkNoNetworkingDeps`), and adding
 * ~1 MB of woff2 for a visual nicety is a poor trade in an app whose whole pitch is that it is
 * small and auditable. So the *shape* of the scale is reproduced with the platform grotesque
 * instead: the sizes, the weights, the negative tracking on display sizes and the wide positive
 * tracking on the uppercase micro-labels are all what actually make the mockup read the way it
 * does. Drop a Geist `FontFamily` into [LkSans] and the rest of the scale is already correct.
 *
 * Numerals and secrets use [LkMono]. That is not a style choice: a masked password, a TOTP code, a
 * countdown and a bit-count all need characters that cannot be confused (`l`/`1`, `O`/`0`) and
 * columns that do not jitter as digits change.
 */
val LkSans: FontFamily = FontFamily.SansSerif
val LkMono: FontFamily = FontFamily.Monospace

/**
 * Text sits optically centred in its line box rather than baseline-biased. Without this, the
 * mockup's tight 1.0–1.2 line heights leave every heading looking a pixel high inside its row.
 */
private val Centered = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * [lineBreak] is set only on prose (Paragraph) and headings (Heading), so a sentence does not end on
 * a one-word last line. It is deliberately NOT set on [LkText.body]: text fields default to that
 * style, and an optimised or balanced break re-wraps the whole paragraph on every keystroke, so words
 * jump lines under the caret. Mono and secret styles stay on the platform breaker for the same reason.
 *
 * [hyphens] is `Auto` on the same header and prose styles, plus the card title and the drawer label
 * (SCL-010, SCL-018, SCL-019): a German compound longer than its column broke at the last letter that
 * fit ("Passw/ord", "Gener/ator"), because nothing told the breaker where a word may be split. Auto
 * hyphenation uses the text's own locale, so English, French, Spanish and Portuguese get it too and
 * a word that fits is untouched. It is never set on [LkText.body] or on the mono, masked and secret
 * styles: a hyphen drawn inside a value the user is typing or must copy exactly is a lie about it.
 * Vault row titles are the user's own text and stay unhyphenated for the same reason.
 *
 * Hyphenation needs an optimising [LineBreak]: Android's simple strategy (what `Unspecified` resolves
 * to) respects only soft hyphens and never adds one, so every style that sets [hyphens] sets a
 * [lineBreak] with it.
 */
private fun lk(
    size: Int,
    weight: FontWeight,
    lineHeight: Float,
    tracking: Float = 0f,
    family: FontFamily = LkSans,
    lineBreak: LineBreak = LineBreak.Unspecified,
    hyphens: Hyphens = Hyphens.Unspecified,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
    letterSpacing = tracking.em,
    lineHeightStyle = Centered,
    lineBreak = lineBreak,
    hyphens = hyphens,
)

/**
 * LocalKeep's own scale, addressed by role rather than by Material's size names.
 *
 * Material's roles do not map cleanly here — this design has three distinct "small uppercase label"
 * treatments and two monospace value sizes, which `labelSmall`/`bodySmall` cannot carry. Screens
 * use these; [lkTypography] exists only so stock components still look right.
 */
object LkText {
    /** Onboarding and Pro headline. */
    val display = lk(28, FontWeight.Medium, 1.15f, -0.03f, lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto)
    /** Unlock's "Welcome back". */
    val title = lk(27, FontWeight.Medium, 1.15f, -0.028f, lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto)
    /** Item detail title. */
    val heading = lk(22, FontWeight.Medium, 1.2f, -0.028f, lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto)
    /**
     * Screen header ("Vault", "Generator", "Security").
     *
     * SemiBold, not Medium. `quick-reference.md` §6 `weight-hierarchy` splits the ramp
     * "bold headings 600-700, medium labels 500", and this is the one text on a screen that is
     * a heading rather than a label. The micro-labels below it deliberately stay at 500 —
     * bolding those would be applying half of one rule to the wrong half of the ramp.
     */
    val screenTitle = lk(21, FontWeight.SemiBold, 1.1f, -0.026f, lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto)
    /** Sheet title. */
    val sheetTitle = lk(18, FontWeight.Medium, 1.2f, -0.024f, lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto)
    /** Card headline inside a screen. */
    val cardTitle = lk(15, FontWeight.Medium, 1.3f, -0.015f, lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto)
    /** A vault row's title. */
    val rowTitle = lk(15, FontWeight.Medium, 1.25f, -0.012f)
    /** A drawer entry. */
    val navLabel = lk(14, FontWeight.Medium, 1.3f, -0.01f, lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto)
    /** Default body. */
    val body = lk(14, FontWeight.Normal, 1.5f)
    /** Supporting prose — lighter than [body] so descriptions recede. */
    val bodyQuiet = lk(13, FontWeight.Light, 1.55f, lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto)
    /** A row's subtitle. */
    val subtitle = lk(12, FontWeight.Normal, 1.25f, lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto)
    /** Captions and footnotes. */
    val caption = lk(11, FontWeight.Light, 1.55f, lineBreak = LineBreak.Paragraph, hyphens = Hyphens.Auto)
    /** Button text. */
    val button = lk(15, FontWeight.SemiBold, 1f, -0.01f)
    val buttonSmall = lk(13, FontWeight.Medium, 1f)
    /** Chip / small control text. */
    val chip = lk(12, FontWeight.Medium, 1f)

    /** Uppercase section label above a group ("ITEMS", "WEBSITES"). */
    val sectionLabel = lk(11, FontWeight.Medium, 1f, 0.13f)
    /** Uppercase field label inside a card. */
    val fieldLabel = lk(10, FontWeight.Medium, 1f, 0.12f)
    /** The wordmark. */
    val wordmark = lk(15, FontWeight.SemiBold, 1f, 0.16f)
    /** Pro / 2FA style badge. */
    val badge = lk(9, FontWeight.SemiBold, 1.3f, 0.09f)

    /** Big monospace readout: the KDF percentage. */
    val numeralHuge = lk(44, FontWeight.Medium, 1f, -0.03f, LkMono)
    /** A TOTP code. */
    val numeralLarge = lk(33, FontWeight.Medium, 1f, 0.06f, LkMono)
    /** The health score. */
    val numeralMedium = lk(27, FontWeight.Medium, 1f, 0f, LkMono)
    /** Generator output, and a revealed slider value. */
    val monoValue = lk(21, FontWeight.Medium, 1.42f, 0.01f, LkMono)
    /** A revealed secret, or a website. */
    val monoField = lk(14, FontWeight.Normal, 1.35f, 0f, LkMono)
    /** Bit counts, countdowns, tallies. */
    val monoCaption = lk(11, FontWeight.Normal, 1.3f, 0f, LkMono)

    /** A masked secret: bullets need the extra tracking or they clump into a grey bar. */
    val masked = lk(14, FontWeight.SemiBold, 1.35f, 0.1f)
    /** A password being typed. */
    val secretInput = lk(16, FontWeight.Normal, 1.45f, 0.16f, LkMono)

    /**
     * The font scale at or above which a graphic and the caption beside it stop sharing a line.
     *
     * A dp-sized graphic next to sp-sized text is a layout with no floor: the text takes its
     * intrinsic width first and the graphic gets the remainder, which at a large accessibility
     * scale is nothing. Two components hit it - the strength meter, whose four bands came out 19dp
     * wide beside "Strong 127 bits" against 107dp at the default size, and the health score ring,
     * whose numerals grew across its own stroke. Both answer it by stacking, and they share this
     * number so the two cannot drift apart.
     *
     * 1.5 is where the caption stops leaving the graphic a majority of the narrowest window the app
     * supports - 308dp, the width at the system's largest display size.
     *
     * **This is no longer the only trigger.** Below 1.5 the same shortfall still happens whenever
     * the *width* is small (SCL-012: the strength meter at 308dp and 1.0 was zero wide; SCL-021: the
     * health score card, still side by side at 308dp and 1.3, dropped its ring under the fold). A
     * font scale is a proxy for the room the caption leaves, and it is the wrong proxy at the narrow
     * end. Ask [stacks] rather than comparing this constant on its own: it adds the width rule, and
     * 360dp and up at 1.0 through 1.4 still render exactly the layout the comps specify.
     */
    const val stackAboveFontScale = 1.5f

    /**
     * The width of a row's own container below which it stacks at any font scale.
     *
     * Measured on the health score card, whose content is the window minus the page frame and the
     * card's padding: 228dp at 308dp, 280dp at 360dp. 260dp sits between the two, so the 308dp floor
     * stacks and the 360dp phone keeps the layout the comps draw. It is a width for the *row*, not
     * the window - pass what the row is actually given, from `BoxWithConstraints` or `onSizeChanged`.
     * Components that can measure the thing that is being squeezed (the strength meter measures its
     * own verdict) should do that instead of using this number.
     */
    val stackBelowWidth: Dp = 260.dp

    /** One rule for "does this graphic-beside-caption row stack": big type, or a narrow row. */
    fun stacks(fontScale: Float, availableWidth: Dp): Boolean =
        fontScale >= stackAboveFontScale || availableWidth < stackBelowWidth
}
