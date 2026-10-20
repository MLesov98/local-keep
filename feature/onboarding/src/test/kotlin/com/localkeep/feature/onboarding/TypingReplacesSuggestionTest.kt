package com.localkeep.feature.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * After "Suggest one", the first keystroke replaces the suggestion instead of landing inside it
 * (S1-F-04). A string-valued password well has no select-all-on-focus, so the same effect is had
 * where the edit arrives: an insertion into a value that is still the untouched suggestion is the
 * user typing over it.
 */
class TypingReplacesSuggestionTest {

    private val suggestion = "alpha bravo charlie delta echo foxtrot"

    private val suggested = OnboardingUiState(
        password = suggestion,
        confirmation = suggestion,
        passwordWasGenerated = true,
    )

    @Test
    fun `a character typed into the untouched suggestion replaces it`() {
        val next = suggested.withPasswordEntered(suggestion + "x")
        assertEquals("x", next.password)
        assertFalse(next.passwordWasGenerated)
    }

    @Test
    fun `a character typed mid-suggestion replaces it`() {
        assertEquals("q", suggested.withPasswordEntered(suggestion.substring(0, 5) + "q" + suggestion.substring(5)).password)
    }

    @Test
    fun `a paste replaces it`() {
        assertEquals("pasted secret", suggested.withPasswordEntered(suggestion + "pasted secret").password)
    }

    @Test
    fun `deleting from the suggestion keeps the rest as the user's own edit`() {
        val trimmed = suggestion.dropLast(1)
        val next = suggested.withPasswordEntered(trimmed)
        assertEquals(trimmed, next.password)
        assertFalse(next.passwordWasGenerated)
    }

    /** The control: a password the user typed is never replaced by what they type next. */
    @Test
    fun `typing into a typed password appends as usual`() {
        val typed = OnboardingUiState(password = "hunter", confirmation = "hunter")
        assertEquals("hunter2", typed.withPasswordEntered("hunter2").password)
    }

    @Test
    fun `confirmation is still dropped when the suggestion is replaced`() {
        assertTrue(suggested.withPasswordEntered(suggestion + "x").confirmation.isEmpty())
    }
}
