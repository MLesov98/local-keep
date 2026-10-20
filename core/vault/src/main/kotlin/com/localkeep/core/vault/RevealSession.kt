package com.localkeep.core.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The in-memory "the user typed the reveal phrase this session" flag. Session state, never saved UI
 * state (Decision 5): it starts concealed on every unlock and process start, and is concealed again
 * by manual lock, panic lock, idle/background auto-lock, process death and payload replacement.
 *
 * Owned by [VaultLockManager], which is the only thing that may flip it — [reveal] and [conceal] are
 * `internal` so a feature module cannot reveal itself; it goes through the lock-manager attempt gate,
 * which enforces the phrase, the unlock epoch and the non-duress precondition. A duress session never
 * reveals, so this stays false there however the decoy payload looks.
 */
class RevealSession {
    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed.asStateFlow()

    private val _expiresAtElapsedMs = MutableStateFlow<Long?>(null)

    /**
     * `SystemClock.elapsedRealtime()` at which this session ends by itself, or null when concealed.
     *
     * A reveal session used to run until the user ended it, the vault locked, or the process died,
     * and that was reported from a phone (2026-09-02): *"I enter the secret phrase, I see the
     * invisible files, but the yellow window doesn't disappear until I click, even if I go to
     * another folder"*. The banner was the visible half of the complaint; the invisible half is
     * worse, because the state behind it is a standing exception to the one feature whose whole
     * point is that something is not on screen. Concealment everywhere else in this app is a lease
     * with an end (`HiddenLease`), and the exception to it had none.
     *
     * Elapsed-realtime rather than wall clock, matching the auto-lock timer and the unlock throttle:
     * it cannot be moved by changing the device's date, and it counts while the device is asleep.
     */
    val expiresAtElapsedMs: StateFlow<Long?> = _expiresAtElapsedMs.asStateFlow()

    /** @param expiresAtElapsedMs when the session ends on its own — see [expiresAtElapsedMs]. */
    internal fun reveal(expiresAtElapsedMs: Long) {
        // Deadline first. A collector woken by `revealed` flipping true must never read a null
        // deadline and render a session that looks like it will never end.
        _expiresAtElapsedMs.value = expiresAtElapsedMs
        _revealed.value = true
    }

    internal fun conceal() {
        _revealed.value = false
        _expiresAtElapsedMs.value = null
    }

    companion object {
        /**
         * How long a reveal session lasts before it conceals itself.
         *
         * Two minutes is long enough to find the entry that was hidden and open it, and short enough
         * that walking away from an unlocked phone does not leave concealment off indefinitely.
         *
         * It is **not** shorter than every auto-lock timer, and this KDoc claimed it was until
         * 2026-09-07. Six numbers say otherwise: [AutoLockPreset.BALANCED] — the shipped default —
         * has an idle timeout of exactly 120 s, [AutoLockPreset.AGGRESSIVE]'s is 30 s, and every
         * preset's background grace (5 s, 30 s, 60 s) is shorter still. Background grace is a real
         * auto-lock timer, not a footnote: `VaultLockManager.scheduleAutoLockLocked` selects it over
         * the idle one whenever the app is not in front.
         *
         * What actually motivates a fixed window is neither of those comparisons. **The idle timer
         * restarts on interaction and this deadline does not.** Every touch re-arms auto-lock, so a
         * user who keeps working never reaches it, while the reveal deadline moves only when the
         * phrase is entered again. And auto-lock can be switched off entirely — with a timeout of
         * `0L`, `scheduleAutoLockLocked` returns without arming anything at all. That is the case
         * this constant exists for: the session where nothing else was ever going to end the reveal.
         * A reveal is a narrower, more sensitive grant than "the vault is open", so it must not be
         * the one thing left running.
         *
         * The bound that is actually enforced is `WINDOW_MS <= AutoLockPreset.DEFAULT.idleMs`, in
         * `RevealSessionWindowTest`. Raising this constant past the default preset is the change
         * that test exists to stop.
         *
         * Restarted, not extended, by a second successful reveal — typing the phrase again is the
         * user asking for the full window over.
         */
        const val WINDOW_MS: Long = 120_000L
    }
}

/**
 * The result of one reveal attempt. Deliberately two-valued: a wrong phrase, a duress session, an
 * absent or invalid verifier, and a stale epoch/generation all return [Rejected] with the same shape
 * and the same cost, so the UI can tell nothing apart from a wrong guess — no oracle for whether a
 * phrase exists, whether the session is a decoy, or whether anything is hidden at all.
 */
enum class RevealAttemptOutcome {
    Revealed,
    Rejected,
}
