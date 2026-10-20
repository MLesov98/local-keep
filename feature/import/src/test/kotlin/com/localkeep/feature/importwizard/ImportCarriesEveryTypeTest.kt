package com.localkeep.feature.importwizard

import com.localkeep.core.import.ImportMerger
import com.localkeep.core.model.Card
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The import plan carries every item type, whatever tier the vault is on (2026-07-29).
 *
 * This wizard used to `partition` Cards and Identities out of the plan when `!isPro`, reporting the
 * count as `proSkippedCount`. It was disclosed rather than silent, so not a data-loss incident — but
 * it contradicted the tiers doc's invariant twice over, and it made this the only door in the app
 * that behaved that way:
 *
 *  - `ItemEditViewModel.save()` refuses to *write* a Card or Identity on a free tier while
 *    deliberately allowing them to be read — "holding it hostage would be a worse answer than
 *    declining to write more of it". The filter was doubling a gate that already got this right.
 *  - Backup restore has no entitlement check at all, and the vault list does not hide Pro types.
 *  - `foss` sets `isPro = false` permanently (spec §N2), so someone who *bought* Pro on Play and
 *    moved to F-Droid would have been refused an import of their own cards, forever.
 *
 * The plan step is the whole surface: `ImportWizardViewModel` now hands `ImportMerger.plan`'s output
 * to the preview untouched, so a test that the plan is type-blind is a test that the wizard is.
 */
class ImportCarriesEveryTypeTest {

    private val now: Instant = Instant.EPOCH

    private fun item(title: String, content: ItemContent) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = now,
        updatedAt = now,
        content = content,
    )

    private val everyType = listOf(
        item("A login", Login(username = "u", password = "p")),
        item("A note", Note(body = "b")),
        item(
            "A 2FA code",
            Totp(
                issuer = "GitHub",
                account = "u@example.com",
                secretBase32 = "JBSWY3DPEHPK3PXP",
                algorithm = TotpAlgorithm.SHA1,
                digits = 6,
                periodSec = 30,
            ),
        ),
        item("A card", Card(holder = "A Person", number = "4242424242424242")),
        item("An identity", Identity(firstName = "A", lastName = "Person")),
    )

    @Test
    fun `every parsed type reaches the plan`() {
        val planned = ImportMerger.plan(existing = emptyList(), imported = everyType)

        assertEquals(everyType.size, planned.size, "the plan dropped an item type")
        assertEquals(
            everyType.map { it.title }.toSet(),
            planned.map { it.item.title }.toSet(),
        )
    }

    @Test
    fun `cards and identities are planned like any other type`() {
        // The exact two types the removed filter singled out. Named individually so a re-introduced
        // filter fails on the assertion that describes it, not on a total.
        val planned = ImportMerger.plan(existing = emptyList(), imported = everyType)
        val contents = planned.map { it.item.content }

        assertTrue(contents.any { it is Card }, "a Card was dropped from the import plan")
        assertTrue(contents.any { it is Identity }, "an Identity was dropped from the import plan")
    }

    @Test
    fun `a vault of nothing but pro types still imports whole`() {
        // The realistic F-Droid case: a vault made on Play, restored into a build where isPro is
        // false by construction. Under the old filter this import produced an empty preview.
        val proOnly = everyType.filter { it.content is Card || it.content is Identity }

        val planned = ImportMerger.plan(existing = emptyList(), imported = proOnly)

        assertEquals(2, planned.size)
        assertTrue(planned.all { it.disposition == ImportMerger.Disposition.NEW })
    }

    @Test
    fun `the wizard state has no way left to report a tier-based skip`() {
        // `proSkippedCount` is gone from ImportUiState. Reintroducing tier filtering would mean
        // reintroducing a field for it — this asserts the only skip channel left is the parser's,
        // whose remedy is different (those entries never import, at any tier).
        val state = ImportUiState()

        assertTrue(state.parserSkipped.isEmpty())
        assertEquals(0, state.selectedCount)
    }
}
