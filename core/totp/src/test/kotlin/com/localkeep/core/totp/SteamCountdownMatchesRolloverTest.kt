package com.localkeep.core.totp

import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A countdown must end when the code it counts for is actually replaced.
 *
 * [TotpGenerator.generateSteam] hard-codes a 30-second counter and ignores [Totp.periodSec]
 * entirely, but nothing normalises that field when Steam is switched on: `onTotpSteamToggled` flips
 * the flag and leaves algorithm, digits and period exactly as they were, so an entry can be stored
 * with `steam = true` and `periodSec = 60`. `remainingSeconds` read the stored field, so it reported
 * up to 60 seconds left on a code that had already rolled at 30 — and that number is what the item
 * detail screen's ring and its "refreshes every N seconds" caption are drawn from, and what the
 * autofill row's "expires in N s" prints.
 *
 * The invariant asserted here is behavioural rather than arithmetic: whatever `remainingSeconds`
 * returns, the code must be unchanged one second before it elapses and different at it.
 */
class SteamCountdownMatchesRolloverTest {

    // RFC 6238's own key, Base32-encoded — the same secret the vector tests use.
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    /**
     * Epoch second 10 sits inside the first half of a 60-second window and the first half of a
     * 30-second one, which is exactly where the two periods disagree: the stored field says 50
     * seconds remain, the counter Steam actually uses says 20.
     */
    private val now = Instant.ofEpochSecond(10)

    private fun totp(steam: Boolean, periodSec: Int, digits: Int) = Totp(
        secretBase32 = secret,
        algorithm = TotpAlgorithm.SHA1,
        digits = digits,
        periodSec = periodSec,
        steam = steam,
    )

    @Test
    fun `a Steam entry stored with a 60 second period counts down to its real rollover`() {
        val entry = totp(steam = true, periodSec = 60, digits = 8)
        val remaining = TotpGenerator.remainingSeconds(entry, now)
        val code = TotpGenerator.generate(entry, now)

        assertEquals(
            code,
            TotpGenerator.generate(entry, now.plusSeconds(remaining - 1L)),
            "the code must still be valid one second before the countdown reaches zero",
        )
        assertNotEquals(
            code,
            TotpGenerator.generate(entry, now.plusSeconds(remaining.toLong())),
            "the code must have rolled over by the time the countdown reaches zero",
        )
    }

    @Test
    fun `a Steam entry's effective period is Steam's own, not the stored one`() {
        assertEquals(30, TotpGenerator.STEAM_PERIOD_SECONDS)
        assertEquals(30, TotpGenerator.effectivePeriodSec(totp(steam = true, periodSec = 60, digits = 8)))
    }

    /**
     * The control, and it must stay green: an ordinary 60-second entry really does count down from
     * 60, so a "fix" that clamped every period to 30 would fail here rather than passing both.
     */
    @Test
    fun `an ordinary 60 second entry still counts down over its own full period`() {
        val entry = totp(steam = false, periodSec = 60, digits = 6)
        val remaining = TotpGenerator.remainingSeconds(entry, now)
        val code = TotpGenerator.generate(entry, now)

        assertEquals(50, remaining)
        assertEquals(60, TotpGenerator.effectivePeriodSec(entry))
        assertEquals(code, TotpGenerator.generate(entry, now.plusSeconds(remaining - 1L)))
        assertNotEquals(code, TotpGenerator.generate(entry, now.plusSeconds(remaining.toLong())))
    }

    /** The second control: the ordinary 30-second case is untouched by either change. */
    @Test
    fun `an ordinary 30 second entry is unaffected`() {
        val entry = totp(steam = false, periodSec = 30, digits = 6)
        assertEquals(20, TotpGenerator.remainingSeconds(entry, now))
        assertEquals(30, TotpGenerator.effectivePeriodSec(entry))
    }
}
