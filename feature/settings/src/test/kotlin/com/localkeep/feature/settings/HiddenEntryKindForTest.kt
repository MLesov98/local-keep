package com.localkeep.feature.settings

import com.localkeep.core.model.Card
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.PasskeyAlgorithm
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [hiddenEntryKindFor], the content-to-row-glyph mapping `HiddenItemsViewModel.refreshEntries`
 * uses to label a hidden record without un-hiding it.
 *
 * [SeedPhrase] is the case this exists to cover: added as a ninth [com.localkeep.core.model.ItemContent]
 * subtype after [HiddenEntryKind] was written, it fell through the mapping's `else` branch and drew
 * the login key glyph on a hidden recovery phrase. [Passkey] falling through that same `else` is
 * deliberate — see the comment on [hiddenEntryKindFor] — and the control below pins that it stays
 * that way.
 */
class HiddenEntryKindForTest {

    @Test
    fun hiddenSeedPhraseMapsToSeedPhraseKindNotLogin() {
        assertEquals(HiddenEntryKind.SEEDPHRASE, hiddenEntryKindFor(SeedPhrase(words = listOf("abandon"))))
    }

    @Test
    fun passkeyStillMapsToLoginKind() {
        val passkey = Passkey(
            rpId = "example.com",
            algorithm = PasskeyAlgorithm.ES256,
            createdAt = Instant.parse("2026-08-18T12:00:00Z"),
        )
        assertEquals(HiddenEntryKind.LOGIN, hiddenEntryKindFor(passkey))
    }

    @Test
    fun everyOtherKnownSubtypeMapsToItsOwnKind() {
        assertEquals(HiddenEntryKind.LOGIN, hiddenEntryKindFor(Login()))
        assertEquals(HiddenEntryKind.TOTP, hiddenEntryKindFor(Totp()))
        assertEquals(HiddenEntryKind.NOTE, hiddenEntryKindFor(Note()))
        assertEquals(HiddenEntryKind.CARD, hiddenEntryKindFor(Card()))
        assertEquals(HiddenEntryKind.IDENTITY, hiddenEntryKindFor(Identity()))
        assertEquals(HiddenEntryKind.FILE, hiddenEntryKindFor(File()))
        assertEquals(
            HiddenEntryKind.COORDINATE,
            hiddenEntryKindFor(Coordinate(capturedAt = Instant.parse("2026-08-18T12:00:00Z"))),
        )
    }
}
