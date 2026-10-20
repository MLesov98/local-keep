package com.localkeep.feature.itemedit

/**
 * Whether the rollover haptic should play for a change from [previousCode] to [newCode].
 *
 * [TotpCard]'s `LaunchedEffect` used to be keyed on the code alone and play unconditionally, so the
 * very first composition — which already holds a code, because the card returns early while
 * `totpCurrentCode` is blank — fired the haptic too. That buzzed on simply opening a 2FA item, at a
 * moment nothing had rolled over, diluting the one signal spec §8.2 relies on to mean "the code you
 * are mid-way through typing just expired."
 *
 * [previousCode] blank means there is nothing to roll away from — the card's own first composition,
 * or a recomposition-from-scratch such as a rotation — so this returns `false` there even though the
 * code did technically change from "" to a real value.
 */
internal fun totpRolloverHapticShouldFire(previousCode: String, newCode: String): Boolean =
    previousCode.isNotBlank() && previousCode != newCode
