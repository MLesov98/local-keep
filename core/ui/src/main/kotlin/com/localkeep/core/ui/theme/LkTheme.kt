package com.localkeep.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Corner radii.
 *
 * The scale is unusually generous — a 46dp-tall chip has an 11dp radius, a card has 18–20dp — and
 * that is deliberate: rounding is what stops a screen of stacked dark rectangles from reading as a
 * spreadsheet. The rule the mockup follows is that radius grows with the element's size, so a
 * small control never looks like a shrunken card.
 */
object LkShape {
    /** Badges and the smallest tick boxes. */
    val xs = RoundedCornerShape(7.dp)
    /** Chips, icon buttons, list-row copy buttons. */
    val sm = RoundedCornerShape(11.dp)
    /** Header buttons, monogram tiles, drawer rows. */
    val md = RoundedCornerShape(13.dp)
    /** Search fields, secondary buttons. */
    val lg = RoundedCornerShape(15.dp)
    /** Input wells, primary buttons, list rows. */
    val xl = RoundedCornerShape(16.dp)
    /** Grouped cards. */
    val xxl = RoundedCornerShape(18.dp)
    /** Lifted cards (score, TOTP, generator output). */
    val card = RoundedCornerShape(20.dp)
    /** The re-auth dialog. */
    val dialog = RoundedCornerShape(26.dp)
    /** A bottom sheet: top corners only. */
    val sheet = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
    /** The drawer: trailing corners only. */
    val drawer = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp)
    /** The FAB. */
    val fab = RoundedCornerShape(20.dp)
    /** The floating bottom bar. */
    val bar = RoundedCornerShape(22.dp)
}

private val lkShapes = Shapes(
    extraSmall = LkShape.xs,
    small = LkShape.sm,
    medium = LkShape.lg,
    large = LkShape.card,
    extraLarge = LkShape.dialog,
)

/**
 * Material's scheme, derived from [LkColors].
 *
 * Kept in sync by hand rather than generated, because the mapping is a judgement call in a few
 * places: `error` is coral but `onErrorContainer` is [LkColors.coralText] (coral itself is
 * unreadable as prose), and `surfaceVariant` is the *field* well rather than a lighter surface,
 * because in this design "variant" always means recessed, never raised.
 */
private fun lkColorScheme(c: LkColors) = darkColorScheme(
    primary = c.jade,
    onPrimary = c.onJade,
    primaryContainer = c.jade.copy(alpha = 0.12f),
    onPrimaryContainer = c.jade,
    secondary = c.brass,
    onSecondary = c.onBrass,
    secondaryContainer = c.brass.copy(alpha = 0.12f),
    onSecondaryContainer = c.brass,
    tertiary = c.amber,
    onTertiary = c.onJade,
    background = c.ground,
    onBackground = c.textPrimary,
    surface = c.surface,
    onSurface = c.textPrimary,
    surfaceVariant = c.field,
    onSurfaceVariant = c.textMuted,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.cardTop,
    surfaceContainerHighest = c.dialogTop,
    surfaceContainerLow = c.field,
    surfaceContainerLowest = c.ground,
    error = c.coral,
    onError = c.onJade,
    errorContainer = c.coral.copy(alpha = 0.10f),
    onErrorContainer = c.coralText,
    outline = c.textFaint,
    outlineVariant = c.ringStrong,
    scrim = c.scrim,
    inverseSurface = c.textPrimary,
    inverseOnSurface = c.ground,
)

/**
 * The app theme.
 *
 * **Dark only, on purpose.** Every screen in the visual pass is dark, and the palette's meaning
 * depends on it: jade at 8% alpha over `#0A0C0E` is a quiet tint, and the same value over white is
 * invisible. A light scheme would not be this design at a different brightness — it would be a
 * second design, with its own contrast ratios to verify. Until one is drawn, honouring the system
 * setting would mean shipping a half-finished second theme, so the parameter is gone rather than
 * ignored.
 */
@Composable
fun LocalKeepTheme(content: @Composable () -> Unit) {
    val colors = remember { LkColors() }
    // Tracked, not sampled once. See [rememberSystemAnimationsDisabled] — this composable is
    // entered once by `setContent`, so a single reading stayed frozen for the life of the process
    // no matter what the user changed in system settings afterwards.
    val reduceMotion = rememberSystemAnimationsDisabled()

    CompositionLocalProvider(
        LocalLkColors provides colors,
        LocalLkReduceMotion provides reduceMotion,
        LocalContentColor provides colors.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = lkColorScheme(colors),
            typography = lkTypography,
            shapes = lkShapes,
            content = content,
        )
    }
}

/** Shorthand for the palette. `LkTheme.colors.jade` reads better than the CompositionLocal. */
object LkTheme {
    val colors: LkColors
        @Composable get() = LocalLkColors.current
}
