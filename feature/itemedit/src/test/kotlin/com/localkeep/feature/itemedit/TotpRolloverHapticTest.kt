package com.localkeep.feature.itemedit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rollover haptic must mean "the code just changed," not "you opened this item."
 *
 * Reported: `TotpCard`'s `LaunchedEffect(state.totpCurrentCode) { haptics.play(...) }` fired on the
 * very first composition, because the card only reaches that line once `totpCurrentCode` is already
 * non-blank (it returns early while blank). Opening any Login or 2FA item with a second factor felt
 * the rollover buzz at a moment nothing rolled over.
 */
class TotpRolloverHapticTest {

    @Test
    fun `does not fire on the first composition`() {
        // No previous code to roll away from — exactly the state TotpCard starts a fresh
        // composition in, once totpCurrentCode already holds a value.
        assertFalse(totpRolloverHapticShouldFire(previousCode = "", newCode = "123456"))
    }

    @Test
    fun `fires when the code actually rolls over`() {
        assertTrue(totpRolloverHapticShouldFire(previousCode = "123456", newCode = "654321"))
    }

    @Test
    fun `does not fire when the code is unchanged`() {
        // Recomposition for an unrelated reason (e.g. the ring's per-second tick) must not re-buzz.
        assertFalse(totpRolloverHapticShouldFire(previousCode = "123456", newCode = "123456"))
    }
}
