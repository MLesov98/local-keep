package com.localkeep.core.generator

import com.localkeep.core.health.StrengthAnalyzer
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the passphrase LocalKeep *suggests* against the strength floor LocalKeep *enforces*.
 *
 * Onboarding offers "Use a suggested passphrase" and then refuses anything scoring below
 * [StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE]. Those two live in different modules, so a change to
 * the default [PasswordGenerator.PassphraseConfig] — dropping to four words, say — would ship a
 * suggestion button that produces a password the very next screen rejects. Nothing else in the
 * build would catch that.
 */
class SuggestedPassphraseStrengthTest {

    @BeforeTest
    fun loadWordlist() {
        PasswordGenerator.setWordlist(File("src/main/assets/eff-large-wordlist.txt").readLines())
    }

    @Test
    fun `suggested passphrases clear the master-password floor`() {
        // Sampled rather than checked once: the words are random, and it is the weakest draw that
        // would strand a user, not the average one.
        repeat(SAMPLES) {
            val suggestion = PasswordGenerator.generatePassphrase().value
            val score = StrengthAnalyzer.score(suggestion)
            assertTrue(
                score >= StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE,
                "suggested passphrase scored $score, below the floor " +
                    "${StrengthAnalyzer.MIN_MASTER_PASSWORD_SCORE}",
            )
        }
    }

    @Test
    fun `suggested passphrases clear the length floor onboarding also applies`() {
        repeat(SAMPLES) {
            val suggestion = PasswordGenerator.generatePassphrase().value
            assertTrue(
                suggestion.length >= MIN_PASSWORD_LENGTH,
                "suggested passphrase was ${suggestion.length} characters",
            )
        }
    }

    private companion object {
        const val SAMPLES = 50

        /** Mirrors OnboardingViewModel's length gate, which the suggestion must also satisfy. */
        const val MIN_PASSWORD_LENGTH = 12
    }
}
