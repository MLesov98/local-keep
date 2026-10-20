package com.localkeep.feature.vaultlist

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Passkey type-filter chip must draw the same glyph as a Passkey row — one concept, one icon —
 * and that glyph must not be the one Logins use.
 *
 * A 2026-09-13 fix established the first half: `typeIcon()`
 * drew every Passkey row with `LkIcons.Key` while `ItemTypeFilter.icon` drew the type-filter chip
 * with `LkIcons.Fingerprint`, a glyph `LkIcons.kt` reserves for "a biometric prompt will actually be
 * raised". That fix settled both sides on `LkIcons.Key`.
 *
 * F-38-era visual pass, F-13 (2026-09-16), is the second half: the Filter sheet lists Logins
 * directly above Passkeys, and with both on `LkIcons.Key` the two rows were pixel-identical — a
 * control whose whole job is to separate item types, saying nothing at the one place it is asked to.
 * Both sides moved together to `LkIcons.Passkey`, which exists for nothing else. So this test now
 * pins three things: the two sides agree, they are on the Passkey glyph, and neither has drifted
 * back onto `Key` (Logins) or `Fingerprint` (a promise a chip cannot keep).
 *
 * Source-scan, bounded to `ItemTypeFilter.icon`'s own `when` body (brace-matched) and then to the
 * `PASSKEY` branch's own line — a whole-file search would be too broad, and the anchor keeps the
 * assertion tied to the actual finding rather than an accident of what else the file contains.
 */
class PasskeyFilterIconTest {

    private val filterBarSource = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultFilterBar.kt")
    private val itemContentIconSource = File("src/main/kotlin/com/localkeep/feature/vaultlist/ItemContentIcon.kt")

    /** `ItemTypeFilter.icon`'s whole `when` body, braces balanced from its opening `{`. */
    private fun filterIconBody(): String {
        val text = filterBarSource.readText()
        val anchor = text.indexOf("internal val ItemTypeFilter.icon: ImageVector")
        assertTrue(anchor >= 0, "VaultFilterBar.kt no longer declares ItemTypeFilter.icon — update this test")
        val open = text.indexOf('{', anchor)
        assertTrue(open >= 0, "could not find the opening brace of ItemTypeFilter.icon's when block")
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        fail("closing brace of ItemTypeFilter.icon's when block was never found — update this test")
    }

    private fun lineAt(text: String, anchor: Int): String {
        val lineEnd = text.indexOf('\n', anchor).let { if (it >= 0) it else text.length }
        return text.substring(anchor, lineEnd)
    }

    @Test
    fun `the Passkey filter draws the same glyph as a Passkey row`() {
        val body = filterIconBody()
        val branch = body.indexOf("ItemTypeFilter.PASSKEY ->")
        assertTrue(branch >= 0, "ItemTypeFilter.icon no longer has a PASSKEY branch — update this test")
        val line = lineAt(body, branch)
        assertTrue(
            "LkIcons.Passkey" in line,
            "ItemTypeFilter.icon's PASSKEY branch reads \"$line\" — it must be LkIcons.Passkey, matching " +
                "ItemContentIcon.kt's typeIcon() (findings 22-3 and F-13).",
        )
        assertFalse(
            "LkIcons.Fingerprint" in line,
            "ItemTypeFilter.icon's PASSKEY branch still uses LkIcons.Fingerprint — LkIcons.kt reserves " +
                "that glyph for a real biometric prompt, which a filter chip tap never raises.",
        )
    }

    /** The row-icon side of the same concept. It moved with the chip, and must keep agreeing. */
    @Test
    fun `ItemContentIcon draws a Passkey item with the Passkey glyph`() {
        val text = itemContentIconSource.readText()
        val branch = text.indexOf("is Passkey ->")
        assertTrue(branch >= 0, "ItemContentIcon.kt no longer has an \"is Passkey ->\" branch — update this test")
        val line = lineAt(text, branch)
        assertTrue(
            "LkIcons.Passkey" in line,
            "ItemContentIcon.kt's Passkey branch reads \"$line\", not LkIcons.Passkey — the two concepts " +
                "this test pins together have drifted apart again",
        )
    }

    /**
     * F-13 itself: Logins and Passkeys may not share a glyph.
     *
     * Asserted on both surfaces rather than on one, because the Filter sheet and the list rows read
     * from different `when` blocks and only the sheet was in the screenshot.
     */
    @Test
    fun `Logins and Passkeys do not share a glyph`() {
        val body = filterIconBody()
        val passkeyLine = lineAt(body, body.indexOf("ItemTypeFilter.PASSKEY ->"))
        val loginLine = lineAt(body, body.indexOf("ItemTypeFilter.LOGIN ->"))
        assertTrue(
            loginLine.substringAfter("->").trim() != passkeyLine.substringAfter("->").trim(),
            "ItemTypeFilter.icon draws Logins and Passkeys with one glyph: \"$loginLine\" / \"$passkeyLine\" " +
                "— the Filter sheet lists them as adjacent rows (F-13)",
        )

        val icons = itemContentIconSource.readText()
        val rowPasskey = lineAt(icons, icons.indexOf("is Passkey ->")).substringAfter("->").trim()
        val rowLogin = lineAt(icons, icons.indexOf("is Login ->")).substringAfter("->").trim()
        assertTrue(
            rowLogin != rowPasskey,
            "ItemContentIcon.kt draws a Login row and a Passkey row with one glyph ($rowLogin)",
        )
    }
}
