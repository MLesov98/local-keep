package com.localkeep.core.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.R
import com.localkeep.core.ui.decorative

/**
 * The filled product mark — the launcher drawing at UI scale.
 *
 * This is ic_launcher_foreground's silhouette and keyhole, flattened to one evenOdd path on the 24
 * grid (divide the 108-grid geometry by 4.5). It exists because the launcher and the app were
 * rendering two different readings of the shield: the launcher a filled plate with a punched
 * keyhole, the app a stroked outline. One drawing now serves both.
 *
 * [LkIcons.Shield] stays the 24dp stroked glyph for tabs, rows and empty states — that one is an
 * *icon* meaning "your vault"; this one is the *mark* meaning "this app". Same subject, different
 * jobs, and the mark is the one that has to match what the user tapped on their home screen.
 *
 * Fill is white so `Icon(tint = …)` owns the colour, the same contract as LkIcons.
 */
private val LkMark: ImageVector = ImageVector.Builder(
    name = "LkMark",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        // One continuous outline for the keyhole — stem down, round the foot, back up, then the
        // long way over the circle. A circle subpath plus an overlapping stem would cancel where
        // they cross under evenOdd and put a jade wedge in the middle of the opening.
        pathData = PathParser().parsePathString(
            "M12 5.24 L17.8 7.71 V12.07 C17.8 15.66 15.32 18.07 12 18.76 " +
                "C8.68 18.07 6.2 15.66 6.2 12.07 V7.71 Z " +
                "M12.58 12.47 V14 A0.58 0.58 0 0 1 11.42 14 V12.47 " +
                "A1.56 1.56 0 1 1 12.58 12.47 Z",
        ).toNodes(),
        fill = SolidColor(Color.White),
        pathFillType = PathFillType.EvenOdd,
    )
}.build()

/**
 * The launcher ground's mid tone (see ic_launcher_background.xml). Deliberately a literal here
 * rather than an LkColors token while [LkLogo] is its only call site; promote it if a second
 * consumer appears.
 *
 * 2026-09-07: 0xFF0C1113 -> 0xFF0D1A19 with the 1a icon. That is the launcher ground's own middle
 * stop, not a colour picked to sit near it — the tile in a dialog header and the tile on the home
 * screen are now the same green-lifted near-black rather than two independently chosen ones.
 */
private val LauncherGround = Color(0xFF0D1A19)

/**
 * The product mark.
 *
 * The glyph is [LkMark] — the launcher plate's shield-with-punched-keyhole. The app has one shape
 * that means "this app", and it is the same shape the launcher shows.
 *
 * Two weights:
 *  - [LkLogo] filled: the app-icon reading — dark plate, jade mark, hairline ring. Mirrors the
 *    launcher tile, so the mark in a modal masthead is recognisably the icon the user just tapped.
 *  - [LkLogo] with `filled = false`: a tinted plate with the jade mark, for places where a solid
 *    dark tile would out-weigh the screen's actual primary action.
 *
 * The corner radius tracks the tile size (§LkShape's "radius grows with the element"), so the mark
 * looks like itself at 26dp in a dialog header and at 72dp on the unlock screen.
 */
@Composable
fun LkLogo(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    filled: Boolean = true,
    accent: Color = LkTheme.colors.jade,
    /**
     * Announced to a screen reader. Null makes the mark decorative, which is right whenever a
     * wordmark or a title next to it already says "LocalKeep".
     */
    contentDescription: String? = null,
) {
    // Polarity matches the launcher tile in both weights: dark ground, jade mark. The filled
    // weight was a jade tile with dark ink, which is the launcher inverted — the one reading that
    // makes the same shape look like a different logo.
    val fill: Brush = if (filled) SolidColor(LauncherGround) else SolidColor(accent.copy(alpha = 0.10f))
    // 0.09, not 0.07: the 1a icon lifted its hairline ring from .06 to .09, and this ring is the
    // in-app reading of that one. 1a's jade frame stroke has no counterpart here for the same
    // reason the adaptive background has none — at 44dp it maps to half a pixel.
    val ring = if (filled) Color.White.copy(alpha = 0.09f) else accent.copy(alpha = 0.20f)

    Box(
        modifier = modifier
            .size(size)
            .lkSurface(RoundedCornerShape(size * 0.30f), fill, ring)
            .then(
                if (contentDescription == null) {
                    Modifier.decorative()
                } else {
                    Modifier.semantics { this.contentDescription = contentDescription }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 0.74, not 0.55: LkMark's drawing spans ~12dp of its 24 grid where the stroked shield
        // spanned 16, so the factor rises to keep the optical size the same.
        Icon(
            LkMark,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(size * 0.74f),
        )
    }
}

/**
 * The name.
 *
 * Set in [LkText.wordmark] — wide tracking, uppercase — because at 15sp the platform grotesque needs
 * the letter-spacing to stop reading as a UI label and start reading as a name. Two runs rather than
 * one string so "KEEP" can take the accent: it is the only piece of brand styling this app has
 * beyond the shield, and it survives being reduced to two colours with no second asset to sync.
 *
 * There is deliberately no mark-plus-name lockup composable. Both places that show the two together
 * — the onboarding hero and the drawer header — stack or caption them differently, so a shared
 * `Row` of the two would have had exactly zero call sites, which is the shape `panicLock()` had.
 */
@Composable
fun LkWordmarkText(modifier: Modifier = Modifier) {
    val c = LkTheme.colors
    val wordmarkDescription = stringResource(R.string.core_ui_wordmark_content_description)
    Row(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = wordmarkDescription }) {
        Text(stringResource(R.string.core_ui_wordmark_local), style = LkText.wordmark, color = c.textStrong)
        Text(stringResource(R.string.core_ui_wordmark_keep), style = LkText.wordmark, color = c.jade)
    }
}

/**
 * The mark stacked over a title and a line of prose — the opening of a modal that speaks as the app.
 *
 * Centred, because a dialog is a moment where the app has interrupted the user, and a centred lockup
 * reads as an address rather than as another row in a list.
 */
@Composable
fun LkModalMasthead(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    accent: Color = LkTheme.colors.jade,
) {
    val c = LkTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon == null) {
            LkLogo(size = 46.dp)
        } else {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .lkSurface(RoundedCornerShape(14.dp), accent.copy(alpha = 0.10f), accent.copy(alpha = 0.20f))
                    .decorative(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(23.dp))
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Centred like the body: the Column centres each child as a box, but a title that
            // wraps lays its lines out start-aligned inside that box.
            Text(
                title,
                style = LkText.sheetTitle,
                color = c.textStrong,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                // A blank line is a paragraph break, so a long body reads as separate sentences
                // instead of one grey block; one string key per body stays.
                //
                // Through [lkBrandedText], so a dialog that names the product gives the name its
                // own two-tone instead of flat grey. Every masthead in the app goes through here,
                // which is what makes it consistent rather than one more per-screen decision; a
                // body that does not mention the product is returned untouched.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(LkSpace.sm),
                ) {
                    for (paragraph in body.split("\n\n")) {
                        Text(
                            lkBrandedText(paragraph),
                            // Centred and short: a balanced break keeps the last line from being a
                            // single word, which the greedy default leaves on a two-line body.
                            style = LkText.bodyQuiet.copy(lineBreak = LineBreak.Heading),
                            color = c.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Body copy with the product name carrying the wordmark's own two-tone: "Local" in the text's
 * colour, "Keep" in jade.
 *
 * Reported from a phone, 2026-08-20: *"LocalKeep -> Keep is not green like everywhere else in the
 * app"*. It was right. [LkWordmarkText] gives the name its accent in the drawer and at onboarding,
 * and then a sentence in the location form said the same name in flat grey — one product, two
 * treatments, decided by which composable happened to render it.
 *
 * **Why this is safe across fr/de/es/pt, which is the objection worth taking seriously.** The span
 * is found by searching for the product name as a literal substring, never by character offset. The
 * name is never translated — it appears verbatim in all five catalogues — so a translator moving it
 * to the end of a sentence, or dropping it entirely, changes nothing here: the search either finds
 * it where it now is, or finds nothing and returns the string unstyled. There is no concatenation
 * and no positional argument, so the translation rules are untouched.
 *
 * **And it carries no information.** `quick-reference.md` §1 `color-not-only` forbids conveying
 * meaning by colour alone; a brand's own styling conveys nothing — read it in greyscale and the
 * sentence says exactly the same thing. This is the *same* jade the wordmark already uses for the
 * *same* word, so it removes a meaning the palette was carrying by accident rather than adding one.
 */
@Composable
fun lkBrandedText(text: String): AnnotatedString {
    val jade = LkTheme.colors.jade
    val local = stringResource(R.string.core_ui_wordmark_local)
    val keep = stringResource(R.string.core_ui_wordmark_keep)
    // The catalogues carry the name in title case ("LocalKeep"); the wordmark resources are the
    // uppercase display form. Rebuilt here rather than adding a sixth brand string to keep one
    // source of truth for what the two halves are.
    val brand = local.titleCaseHalf() + keep.titleCaseHalf()
    return remember(text, brand, jade) {
        buildAnnotatedString {
            var cursor = 0
            while (true) {
                val at = text.indexOf(brand, cursor)
                if (at < 0) {
                    append(text.substring(cursor))
                    break
                }
                append(text.substring(cursor, at))
                append(brand.substring(0, local.length))
                withStyle(SpanStyle(color = jade)) { append(brand.substring(local.length)) }
                cursor = at + brand.length
            }
        }
    }
}

/** "KEEP" -> "Keep". The wordmark resources are uppercase; prose is not. */
private fun String.titleCaseHalf(): String =
    if (isEmpty()) this else first() + drop(1).lowercase()
