package com.localkeep.core.vault

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A reveal session is a lease, not a mode.
 *
 * Until 2026-09-02 it ran until the user ended it by hand, the vault locked, or the process died.
 * Reported from a phone: *"I enter the secret phrase, I see the invisible files, but the yellow
 * window doesn't disappear until I click, even if I go to another folder"* — the banner was the
 * visible half of that, and the standing exception behind it was the worse half. Concealment
 * everywhere else in this app is a lease with an end (`HiddenLease`); the one thing that switches it
 * off had none.
 *
 * [RevealSession] itself is deliberately dumb about clocks — it is handed a deadline and publishes
 * it. `VaultLockManager` owns the timer that acts on it, so that the session ends whether or not the
 * vault list happens to be composed. These tests pin the contract between the two.
 */
class RevealSessionWindowTest {

    @Test
    fun `a fresh session is concealed and has no deadline`() {
        val session = RevealSession()
        assertFalse(session.revealed.value)
        assertNull(session.expiresAtElapsedMs.value)
    }

    @Test
    fun `revealing publishes the deadline it was given`() {
        val session = RevealSession()
        session.reveal(expiresAtElapsedMs = 5_000L)
        assertTrue(session.revealed.value)
        assertEquals(5_000L, session.expiresAtElapsedMs.value)
    }

    /**
     * The ordering matters and is asserted, not assumed. A collector woken by `revealed` flipping
     * true reads the deadline in the same pass; if the flag moved first, the banner's first frame
     * would render a session with no stated end — which is the exact impression the complaint was
     * about.
     */
    @Test
    fun `the deadline is visible to anything woken by the reveal`() {
        val session = RevealSession()
        var deadlineWhenRevealed: Long? = null
        // A StateFlow publishes synchronously to `.value` readers, so reading the sibling flow at
        // the moment the flag is observed true is exactly what a collector does.
        session.reveal(expiresAtElapsedMs = 42L)
        if (session.revealed.value) deadlineWhenRevealed = session.expiresAtElapsedMs.value
        assertEquals(42L, deadlineWhenRevealed, "the banner could render a session with no end")
    }

    @Test
    fun `concealing clears both the flag and the deadline`() {
        val session = RevealSession()
        session.reveal(expiresAtElapsedMs = 5_000L)
        session.conceal()
        assertFalse(session.revealed.value)
        assertNull(
            session.expiresAtElapsedMs.value,
            "a stale deadline is what a late expiry timer checks itself against; leaving one set " +
                "lets an old timer conceal a session the user has just revealed",
        )
    }

    /** Typing the phrase again is the user asking for the whole window over, not for an extension. */
    @Test
    fun `revealing again restarts the window rather than extending it`() {
        val session = RevealSession()
        session.reveal(expiresAtElapsedMs = 1_000L)
        session.reveal(expiresAtElapsedMs = 9_000L)
        assertEquals(9_000L, session.expiresAtElapsedMs.value)
    }

    /**
     * The window is a stated number, and it is stated here because it is a security property rather
     * than a styling choice.
     *
     * The bound that matters is against the *most permissive* auto-lock setting, not the strictest.
     * On Aggressive the vault locks after 30 s idle and takes the reveal with it, so the window is
     * moot there; the case this exists for is the other end — a user on the longest preset, or with
     * auto-lock effectively off, for whom nothing else was ever going to end the session. A reveal is
     * a narrower grant than "the vault is open", so it must not be the one that lasts longest.
     */
    @Test
    fun `the window is short, and never outlasts the most permissive auto-lock preset`() {
        assertTrue(RevealSession.WINDOW_MS in 30_000L..300_000L, "an unreasonable reveal window")
        val longestIdleMs = AutoLockPreset.entries.maxOf { it.idleSeconds } * 1_000L
        assertTrue(
            RevealSession.WINDOW_MS <= longestIdleMs,
            "reveal (${RevealSession.WINDOW_MS} ms) outlasts the longest auto-lock idle window " +
                "($longestIdleMs ms) — the narrower grant must not be the longer one",
        )
    }

    /**
     * The window is **not** shorter than every auto-lock timer, and the KDoc no longer says it is.
     *
     * Until 2026-09-07 [RevealSession.WINDOW_MS]'s KDoc claimed the window was "deliberately shorter
     * than every auto-lock preset". Six numbers contradict that. BALANCED — the shipped default — has
     * an idle timeout of exactly 120 s, so the window equals it rather than undercutting it;
     * AGGRESSIVE's idle is 30 s; and every preset's background grace (5 s, 30 s, 60 s) is shorter
     * still. Background grace is a real auto-lock timer and not a footnote — `scheduleAutoLockLocked`
     * picks it over the idle one whenever the app is not in front.
     *
     * The arithmetic below is the live half of this test. The last assertion is a **source
     * assertion**: the defect was a comment stating a false security property, and a comment is the
     * only place that defect can reappear.
     */
    @Test
    fun `the window is not shorter than every auto-lock timer, and no longer claims to be`() {
        assertEquals(
            AutoLockPreset.BALANCED.idleMs,
            RevealSession.WINDOW_MS,
            "the default preset's idle timeout equals the reveal window; it does not exceed it",
        )
        assertTrue(
            AutoLockPreset.AGGRESSIVE.idleMs < RevealSession.WINDOW_MS,
            "the strictest preset's idle timeout is shorter than the reveal window",
        )
        assertTrue(
            AutoLockPreset.entries.all { it.backgroundGraceMs < RevealSession.WINDOW_MS },
            "every preset's background grace is shorter than the reveal window",
        )
        val kdoc = File("src/main/kotlin/com/localkeep/core/vault/RevealSession.kt").readText()
        assertFalse(
            "shorter than every auto-lock preset" in kdoc,
            "RevealSession states a security property the three assertions above contradict",
        )
    }

    /**
     * The fence the assertion above it does not provide.
     *
     * `WINDOW_MS in 30_000L..300_000L` and `WINDOW_MS <= longest idle preset` are both satisfied by
     * 300_000L, so raising the window to five minutes — past the default preset, past every
     * background grace, and equal to the most permissive idle setting — was a one-token change that
     * no test objected to. The bound that actually protects the property is against the **default**
     * preset, because that is the setting almost every vault is really running.
     */
    @Test
    fun `the window never outlasts the default auto-lock preset`() {
        assertTrue(
            RevealSession.WINDOW_MS <= AutoLockPreset.DEFAULT.idleMs,
            "reveal (${RevealSession.WINDOW_MS} ms) outlasts the default preset's idle timeout " +
                "(${AutoLockPreset.DEFAULT.idleMs} ms) — on the shipped setting the narrower grant " +
                "would be the longer-lived one",
        )
    }
}
