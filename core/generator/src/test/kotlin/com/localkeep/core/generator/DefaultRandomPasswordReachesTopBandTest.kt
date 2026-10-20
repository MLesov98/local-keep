package com.localkeep.core.generator

import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generator's own default output has to be able to reach the app's top strength band.
 *
 * 4A-F1, device 2026-09-20: four consecutive taps of the dice all reported `Strong` / `127 bits`,
 * while a hand-typed `abcdefgQ9%xZmNop2!kL` reported `Excellent`. `LkStrength` states the rule it
 * was breaking in its own KDoc — *"Same four bands, same words, no new vocabulary"* — and the two
 * scales it folds together disagree at exactly the point a user compares them: a generated value is
 * scored by `ofBits` (Excellent at >= 128) and a typed one by zxcvbn's `ofScore`. The default draw
 * was 20 characters from an 83-character pool, which is 127.5 bits: half a bit short, every time.
 *
 * Asserted in bits rather than in bands, because `LkStrength` lives in `:core:ui` and this module
 * does not depend on it. 128 is transcribed from `LkStrength.ofBits`, and the third assertion below
 * pins the pool size the arithmetic rests on so a later change to the alphabet cannot quietly take
 * the default back under the line.
 */
class DefaultRandomPasswordReachesTopBandTest {

    /** `LkStrength.ofBits`: `bits < 128 -> Strong`, `else -> Excellent`. */
    private val excellentBits = 128

    @Test
    fun `the default configuration clears the top band`() {
        val bits = PasswordGenerator.entropy(PasswordGenerator.RandomConfig())
        assertTrue(
            floor(bits) >= excellentBits,
            "the default generate reports ${floor(bits).toInt()} bits, which the app calls " +
                "\"Strong\" — its own output cannot reach the band a typed password can.",
        )
    }

    @Test
    fun `the default length is what produces that`() {
        assertEquals(21, PasswordGenerator.RandomConfig().length)
        assertEquals(21, PasswordGenerator.generateRandom().value.length)
    }

    /**
     * The control: one character less is under the line. Without it the first assertion would keep
     * passing for a default raised far past what the finding asked for, and would say nothing about
     * the boundary actually being where it is.
     */
    @Test
    fun `one character shorter falls back under it`() {
        val bits = PasswordGenerator.entropy(PasswordGenerator.RandomConfig(length = 20))
        assertTrue(
            floor(bits) < excellentBits,
            "20 characters now reports ${floor(bits).toInt()} bits — the alphabet moved, and this " +
                "test's arithmetic no longer describes the generator.",
        )
    }
}
