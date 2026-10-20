package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The countdown boundaries, which are a design decision and therefore worth pinning.
 *
 * They also used to exist twice — once in the detail card and once in the editor's live preview —
 * so half the value of this file is that there is now only one place for it to test.
 */
class TotpToneTest {

    @Test
    fun theBoundariesAreWhereTheDesignPutsThem() {
        assertEquals(TotpTone.CALM, totpToneFor(9))
        assertEquals(TotpTone.CALM, totpToneFor(8))
        assertEquals(TotpTone.NEAR, totpToneFor(7))
        assertEquals(TotpTone.NEAR, totpToneFor(5))
        assertEquals(TotpTone.EXPIRING, totpToneFor(4))
        assertEquals(TotpTone.EXPIRING, totpToneFor(1))
    }

    /**
     * 8 was amber until 2026-08-10. The boundary moving is the whole point of this change, so the
     * old value is named explicitly — a silent drift back would otherwise read as a fix.
     */
    @Test
    fun eightSecondsIsCalmNotNear() {
        assertEquals(TotpTone.CALM, totpToneFor(8))
    }

    /**
     * `remainingSeconds` never reports 0 — it is `period - epochSecond % period`, so it lands on
     * `period` at the roll. The clamp is here anyway because a caller passing a defaulted or stale
     * 0 must not fall through to CALM, which would paint an expired code jade.
     */
    @Test
    fun zeroAndBelowAreNeverCalm() {
        assertEquals(TotpTone.EXPIRING, totpToneFor(0))
        assertEquals(TotpTone.EXPIRING, totpToneFor(-1))
    }

    /**
     * The thresholds are absolute seconds, not a fraction of the period. A 60-second code is CALM
     * far longer than a 30-second one, because the time it takes to type six digits is the same.
     */
    @Test
    fun theBoundariesDoNotScaleWithThePeriod() {
        assertEquals(TotpTone.CALM, totpToneFor(30))
        assertEquals(TotpTone.CALM, totpToneFor(60))
        assertEquals(TotpTone.NEAR, totpToneFor(7))
    }
}
