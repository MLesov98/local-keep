package com.localkeep.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/**
 * How many lines a user-named string gets at the current font scale.
 *
 * **The arithmetic this exists to stop people getting wrong.** Doubling the text size halves the
 * characters a line holds, so a *fixed* `maxLines` is a shrinking character budget: `maxLines = 1`
 * at font scale 2.0 shows roughly a quarter of what it showed at 1.0. That is why a hard cap reads
 * as a safe choice and is not one — the cap was usually added to stop a long name breaking a row's
 * rhythm, and at a large accessibility scale it silently starts cutting short names too.
 *
 * Scaling the count keeps the budget in characters roughly flat instead.
 *
 * **Why it is capped rather than uncapped.** These strings are user-named — item titles, filenames,
 * folder names — and nothing bounds their length, so a pathological title would otherwise push the
 * rest of the row off the screen. An app-authored string (a settings summary, a fixed warning) can
 * be allowed to wrap freely; anything the user typed cannot.
 *
 * The expression is the one already reasoned out and shipped inline at
 * `feature/vaultlist/.../VaultItemRow.kt:365`, generalised here because three more surfaces needed
 * the identical calculation — the trash row, the attachment list and the import wizard's collision
 * rows — and three copies of a formula is three chances for them to drift. That call site keeps its
 * own inline copy deliberately: it is covered by its own tests and rewriting working code to route
 * through a new helper buys nothing. If this formula changes, change it there too.
 *
 * @param base lines at font scale 1.0 — the design's intent at the default size.
 * @param max ceiling for the pathological string.
 */
@Composable
@ReadOnlyComposable
fun lkScaledMaxLines(base: Int = 1, max: Int = 4): Int =
    (base * LocalDensity.current.fontScale).roundToInt().coerceIn(base, max)
