package com.localkeep.core.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The scopes a shared-element transition needs, published rather than threaded through every
 * composable signature.
 *
 * A shared element (UI/UX spec §8.1: a list row's tile morphing into the detail screen) needs two
 * things from far up the tree — the [SharedTransitionScope] from the `SharedTransitionLayout` that
 * wraps the `NavHost`, and the [AnimatedVisibilityScope] of the specific destination it is animating
 * into. Passing both down through `VaultListScreen → VaultItemRow → LkMonogramTile` and the mirror
 * path on the detail side would widen four public signatures across three modules for one cosmetic
 * effect. A leaf reads these instead; when either is null (any screen that is not part of a
 * shared-element pair) the element simply renders in place, so nothing else has to know they exist.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The destination being animated into. Provided by each `composable(...)` that hosts a shared element. */
val LocalNavAnimatedVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }
