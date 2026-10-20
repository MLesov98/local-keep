package com.localkeep.feature.generator

import com.localkeep.core.generator.PasswordGenerator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Generator screen opens on the same length the dice produces everywhere else.
 *
 * `GeneratorUiState.length` and `PasswordGenerator.RandomConfig.length` are two copies of one
 * decision — the slider's starting position and the value `ItemEditViewModel`'s dice draws with —
 * and 4A-F1 was only visible on the second of them. Left apart, the screen would open on 20 while
 * the in-form dice produced 21, and the entropy readout would differ between two controls a user
 * reads as the same one.
 */
class GeneratorDefaultLengthTest {

    @Test
    fun `the screen starts at the generator's own default`() {
        assertEquals(
            PasswordGenerator.RandomConfig().length,
            GeneratorUiState().length,
            "the Generator screen's starting length has drifted from the generator's default",
        )
    }

    @Test
    fun `and that default is 21`() {
        assertEquals(21, GeneratorUiState().length)
    }
}
