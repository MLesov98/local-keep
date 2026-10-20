package com.localkeep.feature.vaultlist

import com.localkeep.core.ui.theme.LkIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The glyph on an empty vault list, and the one state it was lying about.
 *
 * F-09 (device pass 2026-09-15): the Passkeys empty state drew a large "+" dead centre — the biggest
 * thing on the screen, where a button would be — and it is absent from the accessibility tree
 * entirely, because there is nothing to add: passkeys arrive from the credential provider and
 * `EmptyStateLabelTest` already pins Passkey as the only filter with no add-sheet type. The real
 * control was `fab-add-item` in the corner.
 */
class EmptyStateIconTest {

    @Test
    fun `a type with no add flow does not draw an add glyph`() {
        val icon = emptyStateIcon(addableType = null, emptyOfType = ItemTypeFilter.PASSKEY, narrowed = true)
        assertNotEquals(
            LkIcons.Plus,
            icon,
            "the Passkeys empty state may not draw a plus: there is no add flow behind it (F-09)",
        )
        assertEquals(
            ItemTypeFilter.PASSKEY.icon,
            icon,
            "with nothing to add, the type's own glyph is what the screen is about",
        )
    }

    /** Control: where the plate really is an add button, it keeps saying so. */
    @Test
    fun `an addable type still draws the add glyph`() {
        assertEquals(
            LkIcons.Plus,
            emptyStateIcon(addableType = NewItemType.LOGIN, emptyOfType = ItemTypeFilter.LOGIN, narrowed = true),
        )
    }

    /** Control: the two states that were never about a type are untouched. */
    @Test
    fun `a search with no matches and an empty vault are unchanged`() {
        assertEquals(
            LkIcons.Search,
            emptyStateIcon(addableType = null, emptyOfType = null, narrowed = true),
        )
        assertEquals(
            LkIcons.Shield,
            emptyStateIcon(addableType = null, emptyOfType = null, narrowed = false),
        )
    }
}
