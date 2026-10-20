package com.localkeep.feature.unlock

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The "N of 24 words" counter must not congratulate a kit that cannot open anything.
 *
 * It did. The colour was `count == RECOVERY_KIT_WORDS`, so the twenty-fourth keystroke turned the
 * line jade whatever that word was — and `recoveryKitChecksumInvalid` (2026-09-16) exists precisely
 * because a full 24 words can still be nothing. The screen then said three contradictory things at
 * once: a jade verdict, a dead "Open with Recovery Kit", and an inline error naming the checksum.
 * Jade is the only one a user reads as the answer.
 *
 * Amber for that state, per `LkColors`: amber is "nothing is broken, but look".
 */
class RecoveryCountToneTest {

    @Test
    fun `a full kit that fails the checksum is not congratulated`() {
        assertEquals(
            RecoveryCountTone.CHECKSUM_INVALID,
            recoveryCountTone(RECOVERY_KIT_WORDS, checksumInvalid = true),
        )
    }

    /** The control: a whole, well-formed kit keeps the jade it has always had. */
    @Test
    fun `a whole valid kit reads as complete`() {
        assertEquals(
            RecoveryCountTone.COMPLETE,
            recoveryCountTone(RECOVERY_KIT_WORDS, checksumInvalid = false),
        )
    }

    /**
     * V3-01. Past twenty-four words the counter fell back to the neutral grey of a phrase still
     * being typed — "32 of 24 words" in the colour that means *keep going* — because the only
     * branch above COUNTING was `count == RECOVERY_KIT_WORDS`. Amber, like the checksum case: a
     * kit is exactly twenty-four words, so thirty-two is a state to look at, not a state to
     * continue.
     */
    @Test
    fun `more than twenty-four words is not still counting`() {
        assertEquals(
            RecoveryCountTone.TOO_MANY,
            recoveryCountTone(RECOVERY_KIT_WORDS + 1, checksumInvalid = false),
        )
        assertEquals(
            RecoveryCountTone.TOO_MANY,
            recoveryCountTone(32, checksumInvalid = false),
        )
    }

    /** The other control: a part-typed phrase is neither wrong nor finished, it is just short. */
    @Test
    fun `a part-typed kit is still counting`() {
        assertEquals(RecoveryCountTone.COUNTING, recoveryCountTone(0, checksumInvalid = false))
        assertEquals(RecoveryCountTone.COUNTING, recoveryCountTone(3, checksumInvalid = false))
        assertEquals(
            RecoveryCountTone.COUNTING,
            recoveryCountTone(RECOVERY_KIT_WORDS - 1, checksumInvalid = false),
        )
    }
}
