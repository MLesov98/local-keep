package com.localkeep.core.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True while the open vault is the decoy opened by the duress password.
 *
 * Duress only works if the decoy session is indistinguishable from a real one. Any surface that
 * names the feature — a "Duress / decoy" drawer link, a Settings row offering to configure one,
 * copy reassuring the reader that "your real vault is untouched" — tells a coercer holding the
 * phone that a second vault exists, which is the single thing the feature is built to conceal.
 * Handing over the decoy password is then worse than useless: it proves there is something else to
 * demand.
 *
 * Provided once, by the activity that owns [com.localkeep.core.vault.VaultLockManager], so every
 * consumer reads the same fact rather than each deciding for itself. Defaults to `false` so a
 * composable rendered outside that scope (previews, the autofill activities) behaves like an
 * ordinary session instead of silently hiding real settings.
 */
val LocalDuressSession = staticCompositionLocalOf { false }
