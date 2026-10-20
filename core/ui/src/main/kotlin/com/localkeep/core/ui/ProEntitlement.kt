package com.localkeep.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when this install is entitled to Pro.
 *
 * For surfaces that sit outside any feature ViewModel and still have to describe the entitlement —
 * the command drawer's padlocks, most obviously. The drawer decided from the item type alone, so
 * after paying you still saw a padlock on Cards, Identities and Files & media: the app kept
 * advertising a purchase you had already made (found in the 2026-07-29 hand-test, second round).
 *
 * A gate must not read this. Gating belongs to [ProGate] and to the ViewModels that own the actual
 * decision — this is for *describing* the state, so a composable outside that scope rendering with
 * the `false` default under-promises (a padlock too many) rather than opening something it should
 * not. Provided once, by the activity that injects `Entitlement`.
 */
val LocalProEntitled = staticCompositionLocalOf { false }
