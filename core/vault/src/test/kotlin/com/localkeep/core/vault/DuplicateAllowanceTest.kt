package com.localkeep.core.vault

import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Card
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Folders > Duplicate is creation, and was the one creation path no allowance reached
 * (2026-10-02, S5-04).
 *
 * Every other way to make a File, Card or Identity asks [AttachmentQuota] / [ProTypeQuota] first;
 * duplicating a folder minted fresh copies of everything inside it with no question asked, so a
 * free vault's seven files, one card and one identity could be doubled with each tap — in both
 * flavours. The other half of the invariant is pinned here too: **the cap limits creation, never
 * what the vault already holds**, so a vault imported over its cap can still duplicate a folder
 * that adds nothing metered.
 */
class DuplicateAllowanceTest {

    private val now: Instant = Instant.EPOCH

    private fun item(content: ItemContent, attachments: Int = 0) = VaultItem(
        id = UUID.randomUUID(),
        title = "x",
        createdAt = now,
        updatedAt = now,
        content = content,
        attachments = List(attachments) {
            Attachment(id = UUID.randomUUID(), filename = "a$it", mime = "image/png", sizeBytes = 1, blobRef = "b$it")
        },
    )

    private fun exceeded(
        copies: List<VaultItem>,
        files: Int = 0,
        cards: Int = 0,
        identities: Int = 0,
        isPro: Boolean = false,
    ) = DuplicateAllowance.exceeded(copies, files, cards, identities, isPro)

    @Test
    fun `a copy that would hold a second card is refused on the free tier`() {
        assertTrue(exceeded(listOf(item(Card())), cards = 1))
    }

    @Test
    fun `a copy that brings the vault exactly to the card allowance is allowed`() {
        assertFalse(exceeded(listOf(item(Card())), cards = 0))
    }

    @Test
    fun `identities are their own allowance`() {
        assertTrue(exceeded(listOf(item(Identity())), identities = 1))
        assertFalse(exceeded(listOf(item(Identity())), cards = 1), "a card already held spends nothing here")
    }

    /** File items and attachments share one counter, exactly as [AttachmentQuota.used] counts them. */
    @Test
    fun `files and attachments in the copy are counted together against the file allowance`() {
        val copies = listOf(item(File()), item(Login(), attachments = 1))
        assertFalse(exceeded(copies, files = AttachmentQuota.FREE_LIMIT - 2))
        assertTrue(exceeded(copies, files = AttachmentQuota.FREE_LIMIT - 1))
    }

    @Test
    fun `pro is never refused`() {
        assertFalse(exceeded(List(5) { item(Card()) }, files = 50, cards = 50, identities = 50, isPro = true))
    }

    /**
     * The invariant. A vault imported with twenty cards is a supported state, and duplicating a
     * folder of logins creates no card — refusing it would make the cap limit something other than
     * creation.
     */
    @Test
    fun `a vault already over a cap may still copy what that cap does not meter`() {
        assertFalse(exceeded(listOf(item(Login())), files = 30, cards = 20, identities = 20))
    }

    @Test
    fun `an over-cap vault is still refused a copy that adds to that cap`() {
        assertTrue(exceeded(listOf(item(Login()), item(Card())), cards = 20))
    }
}
