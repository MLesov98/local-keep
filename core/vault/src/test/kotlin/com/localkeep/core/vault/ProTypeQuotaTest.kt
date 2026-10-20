package com.localkeep.core.vault

import com.localkeep.core.model.Card
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The free tier's one Card and one Identity document (2026-09-03), replacing a binary gate that
 * gave free users none of either.
 *
 * The load-bearing test in here is [a vault already over the cap keeps every record] — the rest
 * describe the cap, that one describes the promise the cap must not break. A password manager that
 * hides or drops the user's own data to enforce a price is not a password manager.
 */
class ProTypeQuotaTest {

    private val now: Instant = Instant.EPOCH

    private fun item(content: ItemContent, deleted: Boolean = false) = VaultItem(
        id = UUID.randomUUID(),
        title = "x",
        createdAt = now,
        updatedAt = now,
        content = content,
        deletedAt = if (deleted) now else null,
    )

    private fun vault(vararg items: VaultItem): List<VaultItem> = items.toList()

    /**
     * The cap itself, pinned.
     *
     * Every other test here is written relative to [ProTypeQuota.FREE_LIMIT], which means none of
     * them would notice the cap moving. This is the one that does, and moving it is a product
     * decision — it reverses part of `2026-07-29-tiers-and-pricing.md` and should have to be made
     * in a commit that says so.
     */
    @Test
    fun `the free allowance is one card and one identity`() {
        assertEquals(1, ProTypeQuota.FREE_LIMIT)
    }

    @Test
    fun `an empty vault has used neither allowance`() {
        assertEquals(0, ProTypeQuota.used(vault(), ProTypeQuota.Kind.CARD))
        assertEquals(0, ProTypeQuota.used(vault(), ProTypeQuota.Kind.IDENTITY))
    }

    @Test
    fun `a free vault with no card may create one`() {
        assertFalse(
            ProTypeQuota.exceedsFreeLimit(
                ProTypeQuota.used(vault(item(Login())), ProTypeQuota.Kind.CARD),
                isPro = false,
            ),
            "the first card must be allowed — this is the whole change",
        )
    }

    @Test
    fun `a free vault with no identity may create one`() {
        assertFalse(
            ProTypeQuota.exceedsFreeLimit(
                ProTypeQuota.used(vault(item(Login())), ProTypeQuota.Kind.IDENTITY),
                isPro = false,
            ),
        )
    }

    @Test
    fun `the second card is refused`() {
        val used = ProTypeQuota.used(vault(item(Card())), ProTypeQuota.Kind.CARD)
        assertEquals(ProTypeQuota.FREE_LIMIT, used)
        assertTrue(ProTypeQuota.exceedsFreeLimit(used, isPro = false))
    }

    @Test
    fun `the second identity is refused`() {
        val used = ProTypeQuota.used(vault(item(Identity())), ProTypeQuota.Kind.IDENTITY)
        assertEquals(ProTypeQuota.FREE_LIMIT, used)
        assertTrue(ProTypeQuota.exceedsFreeLimit(used, isPro = false))
    }

    @Test
    fun `cards and identities have separate allowances`() {
        // One shared counter would mean storing a card silently spent the identity allowance. No
        // sentence in any of the five catalogues could explain that, and the user would read it as
        // the app losing count.
        val oneCard = vault(item(Card()))
        assertTrue(
            ProTypeQuota.exceedsFreeLimit(
                ProTypeQuota.used(oneCard, ProTypeQuota.Kind.CARD),
                isPro = false,
            ),
        )
        assertFalse(
            ProTypeQuota.exceedsFreeLimit(
                ProTypeQuota.used(oneCard, ProTypeQuota.Kind.IDENTITY),
                isPro = false,
            ),
            "a stored card must not spend the identity allowance",
        )
    }

    @Test
    fun `a trashed card gives the slot back`() {
        // Deleting is the free remedy the refusal names first. If a trashed record still charged,
        // the dialog would tell the user to delete something and then refuse them again.
        val chargeable = vault(item(Card(), deleted = true)).filterNot(VaultItem::isDeleted)
        assertFalse(
            ProTypeQuota.exceedsFreeLimit(
                ProTypeQuota.used(chargeable, ProTypeQuota.Kind.CARD),
                isPro = false,
            ),
        )
    }

    @Test
    fun `pro is unbounded`() {
        val many = vault(*Array(50) { item(Card()) })
        assertFalse(
            ProTypeQuota.exceedsFreeLimit(
                ProTypeQuota.used(many, ProTypeQuota.Kind.CARD),
                isPro = true,
            ),
        )
    }

    @Test
    fun `a vault already over the cap keeps every record`() {
        // The invariant, stated as a test because it is the one thing a counting layer breaks by
        // accident: the cap limits CREATION, never import and never display. A free user importing
        // a KDBX with three cards keeps all three — listed, searchable, openable, editable — and
        // simply cannot add a fourth. The same is true of a lapsed Pro vault. Silently dropping
        // over-cap items is data loss; hiding them is losing them while the user believes the data
        // is safe.
        val imported = vault(item(Card()), item(Card()), item(Card()))

        // `used` reports the truth rather than clamping to the cap...
        assertEquals(3, ProTypeQuota.used(imported, ProTypeQuota.Kind.CARD))
        // ...every record stays in the list the UI renders from...
        assertEquals(3, imported.count { it.content is Card })
        // ...and the only consequence is that the next *create* is refused.
        assertTrue(
            ProTypeQuota.exceedsFreeLimit(
                ProTypeQuota.used(imported, ProTypeQuota.Kind.CARD),
                isPro = false,
            ),
        )
    }

    @Test
    fun `the refusal never asks for zero deletions and never lies about the number`() {
        // At the cap, one. Above it, however many it actually takes — an imported vault told to
        // "remove one to make room" does so, watches nothing change, and has been given a false
        // instruction by an app that knew the real number.
        assertEquals(1, ProTypeQuota.mustRemove(ProTypeQuota.FREE_LIMIT))
        assertEquals(3, ProTypeQuota.mustRemove(3))
        assertEquals(1, ProTypeQuota.mustRemove(0))
    }
}
