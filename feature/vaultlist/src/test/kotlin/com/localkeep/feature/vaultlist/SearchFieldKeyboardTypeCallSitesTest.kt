package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F44, 2026-09-13: exactly one of `LkSearchField`'s four call sites should ask for
 * `KeyboardType.Password` — the vault list's, which is also the make-invisible reveal-phrase entry
 * point. Settings' search is the buy/upgrade route and must keep the ordinary keyboard; Trash and
 * Health's category dialog have no reveal-phrase concern either. This is the control half of
 * [com.localkeep.core.ui.theme.LkSearchFieldKeyboardTypeSourceScanTest]: that test proves the
 * parameter is wired at all, this one proves it was applied at exactly the right call site and
 * nowhere else — a fix that defaulted every field to `Password` would break Settings' search
 * keyboard and would still pass a test that only checked the vault list.
 */
class SearchFieldKeyboardTypeCallSitesTest {

    /** The whole `LkSearchField(...)` call, found by counting parens from the opening one rather
     * than guessing a fixed window — the real calls carry long inline comments, so a naive
     * fixed-size slice cut one of them off mid-parameter in an earlier draft of this test. */
    private fun callSiteBody(relativePath: String, marker: String): String {
        val file = File(relativePath)
        assertTrue(file.exists(), "$relativePath not found at ${file.absolutePath}")
        val text = file.readText()
        val start = text.indexOf(marker)
        assertTrue(start > 0, "$marker not found in $relativePath — did the call site move?")
        var depth = 0
        var i = start + marker.length - 1 // marker ends in "(" — start depth counting there
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced parens scanning $marker in $relativePath")
    }

    @Test
    fun `the vault list's search field asks for the Password keyboard type`() {
        val body = callSiteBody(
            "src/main/kotlin/com/localkeep/feature/vaultlist/VaultListChrome.kt",
            "LkSearchField(",
        )
        assertTrue(
            "keyboardType = KeyboardType.Password" in body,
            "the vault list's LkSearchField call does not pass keyboardType = KeyboardType.Password",
        )
    }

    @Test
    fun `the trash search field is unaffected`() {
        val body = callSiteBody(
            "src/main/kotlin/com/localkeep/feature/vaultlist/TrashScreen.kt",
            "LkSearchField(",
        )
        assertFalse("KeyboardType.Password" in body, "TrashScreen's search field must not be gated")
    }

    @Test
    fun `the settings search field — the buy route — is unaffected`() {
        val body = callSiteBody(
            "../settings/src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt",
            "LkSearchField(",
        )
        assertFalse(
            "KeyboardType.Password" in body,
            "Settings' search is the buy/upgrade route and must keep the ordinary keyboard",
        )
    }

    @Test
    fun `the health dashboard's category search field is unaffected`() {
        val body = callSiteBody(
            "../health/src/main/kotlin/com/localkeep/feature/health/HealthDashboardScreen.kt",
            "LkSearchField(",
        )
        assertFalse("KeyboardType.Password" in body, "HealthDashboardScreen's search field must not be gated")
    }
}
