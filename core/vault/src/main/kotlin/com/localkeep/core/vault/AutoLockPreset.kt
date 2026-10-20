package com.localkeep.core.vault

/**
 * Named auto-lock profiles (UI/UX spec §10.2).
 *
 * Two independent timers, because "how long may the vault stay open?" has two different answers:
 *
 * - **Idle** — the app is in front and the user simply hasn't touched it. They can see the screen,
 *   so this can be generous.
 * - **Background grace** — the app is *not* in front. The phone may be face-down on a desk, handed
 *   to someone, or the user may have switched to the site they're logging into. This must be much
 *   shorter, but not zero: bouncing out to an authenticator app or the camera and straight back is
 *   normal use, and re-entering a master password every time would train people to pick a weak one.
 *
 * A single flat timeout could not express that, so the previous model had to be wrong in one
 * direction or the other — either it lingered too long in the pocket or it nagged during ordinary
 * app-switching.
 */
enum class AutoLockPreset(
    // Resource IDs, not @StringRes-annotated: this module has no dependency that provides the
    // annotation, and adding one means editing the shared version catalog, which is out of scope
    // for a single-module string extraction (other agents are extracting concurrently).
    val labelRes: Int,
    val descriptionRes: Int,
    val idleSeconds: Int,
    val backgroundGraceSeconds: Int,
) {
    AGGRESSIVE(
        labelRes = R.string.core_vault_autolock_aggressive_label,
        descriptionRes = R.string.core_vault_autolock_aggressive_description,
        idleSeconds = 30,
        backgroundGraceSeconds = 5,
    ),
    BALANCED(
        labelRes = R.string.core_vault_autolock_balanced_label,
        descriptionRes = R.string.core_vault_autolock_balanced_description,
        idleSeconds = 120,
        backgroundGraceSeconds = 30,
    ),
    RELAXED(
        labelRes = R.string.core_vault_autolock_relaxed_label,
        descriptionRes = R.string.core_vault_autolock_relaxed_description,
        idleSeconds = 300,
        backgroundGraceSeconds = 60,
    ),
    ;

    val idleMs: Long get() = idleSeconds * 1_000L
    val backgroundGraceMs: Long get() = backgroundGraceSeconds * 1_000L

    companion object {
        val DEFAULT = BALANCED

        fun byName(raw: String?): AutoLockPreset? =
            raw?.let { name -> entries.firstOrNull { it.name == name } }

        /**
         * Maps a legacy flat timeout onto the closest preset, so upgrading doesn't silently reset
         * a choice the user made — someone who deliberately picked a short timeout should not
         * quietly end up on a longer one.
         */
        fun closestTo(idleSeconds: Int): AutoLockPreset =
            entries.minBy { kotlin.math.abs(it.idleSeconds - idleSeconds) }
    }
}
