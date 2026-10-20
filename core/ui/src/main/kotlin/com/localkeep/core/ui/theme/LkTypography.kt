package com.localkeep.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * Its own file, and that is the whole point of this file existing.
 *
 * This lived at the bottom of `LkType.kt`, which made the two classes initialise in a cycle.
 * `object LkText` builds every style by calling `lk()`, a top-level function in `LkType.kt`, and
 * touching it triggers that file class's static initialiser — which evaluated `lkTypography`, which
 * reads `LkText.display`. So `LkText` was asked for its fields while its own initialiser was still
 * running, and got nulls.
 *
 * Whether that threw depended entirely on which of the two was touched first. Reach `lkTypography`
 * first — as `LocalKeepTheme` does — and everything resolves. Reach `LkText` first, as any
 * composable styling its own text does, and `TextStyle.copy` is called on null:
 *
 *     java.lang.NullPointerException: Attempt to read from field
 *     'androidx.compose.ui.text.SpanStyle androidx.compose.ui.text.TextStyle.spanStyle'
 *     on a null object reference
 *         at com.localkeep.core.ui.theme.LkTypeKt.<clinit>(LkType.kt:154)
 *
 * That is a process-killing ExceptionInInitializerError, not a styling glitch, and it was found by
 * an instrumented test whose subject was reduced motion — it rendered a card preview in isolation,
 * which touches `LkText.monoValue` before anything has asked for the Material typography.
 *
 * Moving it here breaks the cycle in the only direction that cannot come back: `LkType.kt` now
 * refers to nothing in this file, so its initialiser can no longer reach into a half-built object.
 */
/**
 * Material's typography, filled from [LkText] so that any component we have not restyled — dialogs,
 * snackbars, the odd stock control inside a feature screen — still lands in the same voice.
 */
val lkTypography = Typography(
    displaySmall = LkText.display,
    headlineMedium = LkText.title,
    headlineSmall = LkText.heading,
    titleLarge = LkText.screenTitle,
    titleMedium = LkText.sheetTitle,
    titleSmall = LkText.cardTitle,
    bodyLarge = LkText.body,
    bodyMedium = LkText.bodyQuiet.copy(fontWeight = FontWeight.Normal),
    bodySmall = LkText.subtitle,
    labelLarge = LkText.button,
    labelMedium = LkText.chip,
    labelSmall = LkText.fieldLabel,
)
