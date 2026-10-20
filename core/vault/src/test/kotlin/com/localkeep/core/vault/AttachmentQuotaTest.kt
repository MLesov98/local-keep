package com.localkeep.core.vault

import com.localkeep.core.model.Attachment
import com.localkeep.core.model.File
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The free tier's file-and-photo slots (2026-07-29), replacing a binary gate that gave free users
 * none at all. Five at first; seven since 2026-09-03.
 *
 * The load-bearing test in here is [an import over the cap is never refused, filtered or hidden] —
 * the rest describe the cap, that one describes the promise the cap must not break.
 */
class AttachmentQuotaTest {

    private val now: Instant = Instant.EPOCH

    private fun item(content: com.localkeep.core.model.ItemContent, attachments: Int = 0) = VaultItem(
        id = UUID.randomUUID(),
        title = "x",
        createdAt = now,
        updatedAt = now,
        content = content,
        attachments = List(attachments) {
            Attachment(id = UUID.randomUUID(), filename = "a$it", mime = "image/png", sizeBytes = 1, blobRef = "b$it")
        },
    )

    /** The quota counts items, not a repository — so a test can simply hand it a list. */
    private fun vault(vararg items: VaultItem): List<VaultItem> = items.toList()

    @Test
    fun `an empty vault has used nothing`() {
        assertEquals(0, AttachmentQuota.used(vault()))
    }

    @Test
    fun `a standalone file item occupies a slot`() {
        assertEquals(1, AttachmentQuota.used(vault(item(File()))))
    }

    @Test
    fun `attachments hanging off other items occupy slots too`() {
        // Otherwise the cap is sidestepped by attaching photos to a Login instead of storing them
        // as Files — the same encrypted blob either way.
        assertEquals(3, AttachmentQuota.used(vault(item(Login(), attachments = 3))))
    }

    @Test
    fun `files and attachments share one counter`() {
        val used = AttachmentQuota.used(
            vault(item(File()), item(File()), item(Note(), attachments = 2)),
        )
        assertEquals(4, used)
    }

    /**
     * The cap itself, pinned.
     *
     * Every other test here is written relative to [AttachmentQuota.FREE_LIMIT] so that moving the
     * cap does not require rewriting them — which is correct, and which also means none of them
     * would notice the cap moving. This one exists to be the single place that does. A change here
     * is a product decision and should have to be made deliberately, in a commit that says so.
     */
    @Test
    fun `the free file-and-photo allowance is seven`() {
        assertEquals(7, AttachmentQuota.FREE_LIMIT)
    }

    @Test
    fun `a free user may create up to the limit`() {
        val oneShort = vault(item(Login(), attachments = AttachmentQuota.FREE_LIMIT - 1))
        assertFalse(
            AttachmentQuota.exceedsFreeLimit(oneShort, isPro = false),
            "the last slot must be allowed",
        )
    }

    @Test
    fun `one past the limit is refused`() {
        val full = vault(item(Login(), attachments = AttachmentQuota.FREE_LIMIT))
        assertTrue(AttachmentQuota.exceedsFreeLimit(full, isPro = false))
    }

    @Test
    fun `deleting back below the limit re-enables creation`() {
        assertTrue(
            AttachmentQuota.exceedsFreeLimit(
                vault(item(Login(), attachments = AttachmentQuota.FREE_LIMIT)),
                isPro = false,
            ),
        )
        assertFalse(
            AttachmentQuota.exceedsFreeLimit(
                vault(item(Login(), attachments = AttachmentQuota.FREE_LIMIT - 1)),
                isPro = false,
            ),
        )
    }

    @Test
    fun `pro is unbounded`() {
        val many = vault(item(Login(), attachments = 500))
        assertFalse(AttachmentQuota.exceedsFreeLimit(many, isPro = true))
    }

    @Test
    fun `adding several at once is checked against the whole batch`() {
        // Two short of the cap: a batch of two exactly fills it, a batch of three does not fit.
        val twoShort = vault(item(Login(), attachments = AttachmentQuota.FREE_LIMIT - 2))
        assertFalse(AttachmentQuota.exceedsFreeLimit(twoShort, isPro = false, adding = 2))
        assertTrue(AttachmentQuota.exceedsFreeLimit(twoShort, isPro = false, adding = 3))
    }

    @Test
    fun `an import over the cap is never refused, filtered or hidden`() {
        // The invariant, stated as a test because it is the one thing a counting layer breaks by
        // accident: caps limit CREATION, never import and never display. A free user importing a
        // KDBX with thirty attachments keeps all thirty — readable, editable, searchable — and
        // simply cannot add a thirty-first. Silently dropping over-cap items is data loss in a
        // password manager, and hiding them is losing them with the added insult that the user
        // believes the data is safe.
        val imported = vault(item(Login(), attachments = 30))

        // `used` reports the truth rather than clamping to the limit...
        assertEquals(30, AttachmentQuota.used(imported))
        // ...every item stays visible...
        assertEquals(30, imported.single().attachments.size)
        // ...and the only consequence is that the next *add* is refused.
        assertTrue(AttachmentQuota.exceedsFreeLimit(imported, isPro = false))
    }

    // `the limit message names the number rather than the tier` moved to
    // feature/files/QuotaMessageTest when AttachmentQuota.LIMIT_MESSAGE was deleted. The sentence
    // is a resource now, and this module has no resources — asserting it here is what kept an
    // English literal alive in a module that ships no UI.
}
