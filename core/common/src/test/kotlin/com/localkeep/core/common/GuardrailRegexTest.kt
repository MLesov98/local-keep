package com.localkeep.core.common

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Mirrors the `params` list and `paramLiteral` regex that `checkNoHardcodedUiStrings` builds in
 * root `build.gradle.kts` (~1166-1179). There is no buildSrc in this repo to share that closure's
 * code with a test module, so this is a literal copy kept in sync by convention — the Gradle task
 * itself is the authoritative check, this is a fast characterization test for the regex construction.
 *
 * Until 2026-09-13 the params list omitted onClickLabel,
 * onLongClickLabel and stateDescription. Because the regex is `\b`-anchored on a whole parameter
 * name, "Label" / "Description" appearing only as a *suffix* of those three names never matched —
 * a hardcoded literal on any of them passed the gate silently. Before this fix the three assertions
 * below failed (confirmed by regex simulation matching the production semantics); they pass now that
 * the three names are in `params`, without changing the existing ten (control below).
 */
class GuardrailRegexTest {

    private val params = listOf(
        "text", "label", "title", "body", "contentDescription", "placeholder",
        "hint", "message", "subtitle", "actionLabel", "dismissLabel",
        "onClickLabel", "onLongClickLabel", "stateDescription",
    )

    private val paramLiteral =
        Regex("""\b(${params.joinToString("|")})\s*(:\s*[\w.]+\??\s*)?=\s*"[^"]{2,}"""")

    @Test
    fun `onClickLabel literal is caught`() {
        assertTrue(paramLiteral.containsMatchIn("""onClickLabel = "Skip""""))
    }

    @Test
    fun `onLongClickLabel literal is caught`() {
        assertTrue(paramLiteral.containsMatchIn("""onLongClickLabel = "Skip""""))
    }

    @Test
    fun `stateDescription literal is caught`() {
        assertTrue(paramLiteral.containsMatchIn("""stateDescription = "Skip""""))
    }

    @Test
    fun `pre-existing contentDescription literal still caught (control)`() {
        assertTrue(paramLiteral.containsMatchIn("""contentDescription = "Delete item""""))
    }
}
