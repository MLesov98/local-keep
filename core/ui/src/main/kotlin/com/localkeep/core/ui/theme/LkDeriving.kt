package com.localkeep.core.ui.theme

import android.icu.math.BigDecimal
import android.icu.text.NumberFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.localkeep.core.ui.R
import com.localkeep.core.ui.announce
import java.util.Locale

/**
 * The derivation overlay: what the app shows while Argon2id is running.
 *
 * ### The percentage, and what it is allowed to be
 *
 * Given a [progress] this draws a ring and a count-up. **The number steps; it never creeps.** It
 * moves when, and only when, the pipeline reaches a checkpoint — see `UnlockProgress` in
 * `:core:vault`, which owns both the checkpoints and the reason each one is safe to report — and it
 * sits perfectly still in between. A smooth count-up would be a lie: Argon2id exposes no progress
 * callback (BouncyCastle's `generateBytes` is one opaque call), the payload is decoded by a single
 * `decodeFromByteArray`, and an interpolated 0→100% would be a timer wearing the costume of work.
 * `LkIndicators`' rule — "a bar that represents real work is never eased and never padded" — is the
 * same rule, and the KDF ring is the one place it matters most.
 *
 * The dwell is therefore the feature, not a defect to smooth over. Sitting at 60% for eight seconds
 * is the app showing you what an attacker pays per guess. And because the steps land when the work
 * lands, the wait is never the same length twice: a small vault on a fast phone runs the sequence
 * through in a fraction of the time a large one on a slow phone takes.
 *
 * With [progress] null it falls back to the indeterminate halo — which is right for callers that
 * cannot report checkpoints, and is what every caller did before 2026-08-10.
 *
 * Lives here, not in `:feature:unlock`, because creating a vault costs exactly what opening one
 * does and needs exactly the same overlay. Onboarding had the `isWorking` flag and rendered
 * nothing for it: tapping Continue blanked both password fields — correct zeroisation — and then
 * sat inert for ~80 seconds on an emulator with no spinner, no disabled state and no message.
 * Everything the user could see said it had failed and they should type it again.
 */
@Composable
fun LkDerivingOverlay(
    kdfSummary: String? = null,
    title: String = stringResource(R.string.core_ui_deriving_default_title),
    /** 0f..1f, or null for the indeterminate halo. */
    progress: Float? = null,
    /**
     * Whether the "this wait is the point" Argon2id copy applies to what is actually running.
     * Defaults to `true` — every caller before 2026-09-07 raised this overlay only around a real
     * KDF derivation (onboarding's vault creation, the password unlock path), so the default keeps
     * their behaviour unchanged. The biometric and Recovery Kit unlock paths raise this same
     * overlay (`UnlockScreen.kt`) with no KDF running at all — Tier G copy claiming a per-guess
     * Argon2id cost the wait does not represent. Pass `false` there.
     */
    showRationale: Boolean = true,
) {
    val c = LkTheme.colors
    // Nothing under this overlay is editable while it is up, and a keyboard left open takes a third
    // of the window from it: caught at 308dp with the ring cut at the keyboard's edge and the caption
    // off screen (SCL-004). Released here, once, so every caller gets it; onboarding's own
    // `ReleaseFocusWhileWorking` does the same for the wait before this overlay is composed.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.ground.copy(alpha = 0.94f))
            // Swallows taps so the form underneath cannot be edited or re-submitted mid-derivation.
            .pointerInput(Unit) { detectTapGestures { } }
            .announce(stringResource(R.string.core_ui_deriving_announce, title)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(38.dp),
            // Scrolls, so a window shorter than the column shows the top of it and can reach the
            // rest, instead of centring an over-tall column and clipping both ends. Under the API 29
            // root banner at 2.0 text the ring, the title, the KDF line and the Tier G rationale
            // are taller than the window: "Reading the vault" lost its descenders at the top edge
            // and the rationale was never on screen (SCL-004). A column that fits is unaffected —
            // the Box centres it exactly as before, because the scroll takes only the height the
            // content needs when that is less than the window.
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 44.dp, vertical = 24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (progress == null) {
                    LkPulseHalo(size = 172.dp)
                    Icon(
                        LkIcons.Shield,
                        contentDescription = null,
                        tint = c.jade,
                        modifier = Modifier.size(48.dp),
                    )
                } else {
                    // `animated = false` is the whole point: the arc jumps to each checkpoint the
                    // instant it lands. Easing it would put a tween between the work and the
                    // number, which is the interpolation this overlay exists not to do.
                    LkProgressRing(
                        progress = progress,
                        size = derivingRingSize(LocalDensity.current.fontScale),
                        strokeWidth = 6.dp,
                        brush = Brush.sweepGradient(listOf(c.jadeDim, c.jade, c.jadeLit, c.jadeDim)),
                        animated = false,
                    ) {
                        val locale = LocalConfiguration.current.locales[0]
                        Text(
                            // Floored, never rounded up. 99% must not appear before the work that
                            // earns it, and a percentage that reaches 100 while something is still
                            // running is the exact dishonesty this file argues against. Locale-aware
                            // rather than a hardcoded "N%" concatenation, the same reason
                            // BreachCorpusFootnote formats with ICU instead of string-building: digit
                            // glyphs and the number/percent-sign order both vary by locale.
                            remember(progress, locale) { lkFlooredPercent(progress, locale) },
                            style = LkText.numeralHuge,
                            color = c.textStrong,
                        )
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(title, style = LkText.cardTitle, color = c.textSecondary)
                if (kdfSummary != null) {
                    Text(kdfSummary, style = LkText.monoCaption, color = c.textFaint)
                }
            }
            if (showRationale) {
                Text(
                    stringResource(R.string.core_ui_deriving_rationale),
                    style = LkText.caption,
                    color = c.textFaint,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The KDF ring's diameter — same defect class, same fix, as `ScoreCard`'s health-score ring in
 * `HealthDashboardScreen.kt`: [LkText.numeralHuge] is 44sp with a tight 1.0 line height, so at
 * fontScale 2.0 the numeral this ring frames is an effective 88sp, and a ring pinned at 172dp does
 * not grow to meet it.
 *
 * Unlike that ring, this one cannot buy width by stacking — the overlay's [Column] is already
 * vertical (ring, then title, then rationale), so there is no row to break out of. What bounds it
 * instead is the overlay's own layout: [LkDerivingOverlay] pads its content 44dp on each side, a
 * dp value that does not itself scale with type, on the narrowest window this app supports — 308dp
 * (see [LkText.stackAboveFontScale]'s KDoc) — leaving 220dp of content width. An unclamped
 * `172.dp * fontScale` reaches that ceiling at fontScale ~1.28, well below the 2.0 the accessibility
 * slider allows, and the ring would draw into its own padding rather than past a screen edge nobody
 * would see. [OverlayRingScaleCap] holds growth at 1.25× — 215dp, 5dp inside that ceiling — rather
 * than let the multiply run past what the layout around it can hold.
 */
internal fun derivingRingSize(fontScale: Float, base: Dp = 172.dp): Dp =
    base * fontScale.coerceAtMost(OverlayRingScaleCap)

private const val OverlayRingScaleCap = 1.25f

/**
 * A `0f..1f` progress fraction, formatted as a floored percentage for [locale] rather than
 * concatenated as `"$n%"`.
 *
 * Shared between this overlay's KDF ring and `HealthDashboardScreen`'s scan ring — both draw a
 * percentage inside an `LkProgressRing` with the identical "floored, never rounded up" contract
 * (see the doc above this overlay's call site), so the two call sites had the same hardcoded
 * concatenation and the same fix. `NumberFormat.getPercentInstance` scaling by 100 combined with
 * `ROUND_DOWN` on a non-negative value is exactly that floor — the same rounding mode
 * `BreachCorpusFootnote.formatCorpusSize` already uses for the same reason. `android.icu`, not
 * `java.text`, for the locale data: digit glyphs and the number/percent-sign order both vary by
 * locale (Arabic-Indic digits, "N %" with a leading space in `fr`/`de`, "‎%N" in RTL scripts).
 */
fun lkFlooredPercent(progress: Float, locale: Locale): String =
    NumberFormat.getPercentInstance(locale).apply {
        roundingMode = BigDecimal.ROUND_DOWN
        maximumFractionDigits = 0
    }.format(progress.toDouble())
