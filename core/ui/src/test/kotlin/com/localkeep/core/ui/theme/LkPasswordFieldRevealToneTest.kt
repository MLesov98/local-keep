package com.localkeep.core.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One state, one colour: a revealed secret is amber wherever the toggle is drawn.
 *
 * 2026-09-15: `shots/059` drew the editor's EyeOff amber; `shots/006-unlock-reveal-on.png` drew the
 * identical toggle, in the identical state, neutral grey (F-17). The two are not different controls
 * — the unlock screen simply took `LkPasswordField`'s built-in toggle, which took `LkGlyphButton`'s
 * default tint, while the item editor passes `onToggleReveal = null` and draws its own.
 *
 * The KDoc picks the winner, not the majority. `LkColors.kt` defines amber as *caution — something
 * is worth looking at but nothing is wrong*, which is a plaintext secret currently on screen,
 * exactly. Jade is reserved for primary action and a state toggle is not an action; coral means a
 * failure or a compromised environment, and choosing to look at your own password is neither.
 *
 * FAILS WITHOUT THE FIX: the `LkGlyphButton` in `LkPasswordField`'s trailing row passed no `tint` at
 * all, so both of its states were `LkGlyphButton`'s default `textMuted`.
 */
class LkPasswordFieldRevealToneTest {

    private val fields = File("src/main/kotlin/com/localkeep/core/ui/theme/LkFields.kt").readText()

    /** The component's own toggle — the one the unlock screen renders. */
    private val builtInToggle = fields
        .substringAfter("if (onToggleReveal != null) {", "")
        .substringBefore("actions?.invoke(this)")

    @Test
    fun `the built-in toggle is amber while the secret is on screen and grey while it is masked`() {
        assertTrue(
            "tint = if (revealed) c.amber else c.textMuted" in builtInToggle,
            "the unlock screen's reveal toggle no longer agrees with the item editor's",
        )
    }

    @Test
    fun `it is still the eye pair, so only the colour changed`() {
        assertTrue("icon = if (revealed) LkIcons.EyeOff else LkIcons.Eye" in builtInToggle)
    }

    /**
     * The control: the two call sites that already had it right are unchanged, and the assertion is
     * that all three now say the same thing. `SecretField` (the item editor) and `SecretReadField`
     * plus `TotpCard` (the detail screen) all live in `:feature:itemedit`, which this module cannot
     * see — so what is pinned here is the rule itself, stated once where the shared component is.
     */
    @Test
    fun `jade is not borrowed for a state toggle`() {
        assertTrue(
            "tint = if (revealed) c.jade" !in builtInToggle,
            "jade is reserved for primary action; Copy beside this toggle already owns it",
        )
    }
}
