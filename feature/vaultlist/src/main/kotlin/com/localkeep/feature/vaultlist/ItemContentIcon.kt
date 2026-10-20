package com.localkeep.feature.vaultlist

import androidx.compose.ui.graphics.vector.ImageVector
import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.ui.theme.LkIcons

/**
 * The glyph that says what kind of thing an item is.
 *
 * The add sheet has had this mapping since it was written, but on `NewItemType` — a UI-only enum
 * that exists to describe what you are about to create and has no member for a Passkey, which can
 * only arrive from elsewhere. The vault list needs the same answer keyed on what an item actually
 * *is*, so this reads the sealed content type directly and keeps the same glyph choices, because
 * two names for one concept is how an app ends up telling you a card is a card in one place and
 * not in another.
 *
 * Lives in the feature, not in `core/model`: which picture stands for a card is a UI decision, and
 * the model should not have opinions about icons.
 */
internal fun ItemContent.typeIcon(): ImageVector = when (this) {
    is Login -> LkIcons.Key
    is Totp -> LkIcons.Clock
    is Note -> LkIcons.Note
    is Card -> LkIcons.Card
    is Identity -> LkIcons.Identity
    is File -> LkIcons.Attachment
    // Shared Login's glyph until 2026-09-16, on the reasoning that both answer "how do I sign in to
    // this". True, and not enough: the Filter sheet puts Logins and Passkeys in adjacent rows, where
    // one key for both left the control that exists to separate item types saying nothing (F-13).
    // LkIcons.Passkey is still a key — the silhouette carries the difference, not the concept.
    is Passkey -> LkIcons.Passkey
    is Coordinate -> LkIcons.Pin
    is SeedPhrase -> LkIcons.SeedPhrase
}
