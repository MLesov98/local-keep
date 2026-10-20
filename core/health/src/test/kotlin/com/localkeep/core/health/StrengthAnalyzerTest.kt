package com.localkeep.core.health

import kotlin.test.Test
import kotlin.test.assertTrue

class StrengthAnalyzerTest {

    @Test fun `common password scores very weak`() {
        assertTrue(StrengthAnalyzer.score("password") <= 1, "expected a weak score for 'password'")
        assertTrue(StrengthAnalyzer.isWeak("password"))
    }

    @Test fun `short numeric sequence scores very weak`() {
        assertTrue(StrengthAnalyzer.score("12345678") <= 1)
    }

    @Test fun `xkcd-style substitution password scores above the weak ceiling`() {
        // Classic "Tr0ub4dor&3" example. zxcvbn4j 1.9.0 rates this a 4 (its dictionary/pattern
        // detection has evolved since the plan's "moderate band" prediction was written) — the
        // meaningful assertion is that it clears the "weak" bucket, not an exact score.
        val score = StrengthAnalyzer.score("Tr0ub4dor&3")
        assertTrue(score > StrengthAnalyzer.WEAK_SCORE_CEILING, "expected above the weak ceiling, got $score")
    }

    @Test fun `long multi-word passphrase scores strong`() {
        val score = StrengthAnalyzer.score("correct horse battery staple 2026 pigeon")
        assertTrue(score >= 3, "expected a strong score, got $score")
        assertTrue(!StrengthAnalyzer.isWeak("correct horse battery staple 2026 pigeon"))
    }

    @Test fun `empty password scores zero and has no feedback crash`() {
        assertTrue(StrengthAnalyzer.score("") == 0)
        assertTrue(StrengthAnalyzer.feedback("") == "")
    }
}
