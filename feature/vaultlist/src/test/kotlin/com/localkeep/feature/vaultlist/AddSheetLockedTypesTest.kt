package com.localkeep.feature.vaultlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the add sheet marks must be exactly what selecting it would refuse.
 *
 * [spentAllowances] decides the marking for ten cells while the sheet composes; [addDestination]
 * decides what one tap does. They are separate functions answering at different moments, and until
 * 2026-09-04 they disagreed about File: `addDestination` sent a File tap to `QuotaBlocked` once
 * `AttachmentQuota` was full, while the sheet's marking was keyed on [NewItemType.requiresPro],
 * which File is not. The result was the one type a free vault is most likely to exhaust wearing the
 * cell that promised it would work.
 *
 * The last test here is the one that matters: it drives both functions over every type and every
 * quota combination and asserts they never disagree. A future type that gets a quota but is left out
 * of one of the two fails it, which is the whole point — the previous version of this file could
 * only have caught the defect if someone had thought to write a File case by hand.
 */
class AddSheetLockedTypesTest {

    @Test
    fun `nothing is marked while every allowance stands`() {
        assertEquals(
            emptyMap(),
            spentAllowances(cards = null, identities = null, attachments = null),
            "a free vault that has spent nothing must render exactly like a Pro one — every cell " +
                "works, so no cell is marked",
        )
    }

    @Test
    fun `a full attachment quota marks File and nothing else`() {
        // The 2026-09-04 fix. Card and Identity are untouched: their own slots are still there.
        assertEquals(
            mapOf(NewItemType.FILE to 7),
            spentAllowances(cards = null, identities = null, attachments = 7),
            "seven files and photos is the cap; the eighth is refused, so the File cell has to say so",
        )
    }

    @Test
    fun `each quota marks its own type only`() {
        assertEquals(
            mapOf(NewItemType.CARD to 1),
            spentAllowances(cards = 1, identities = null, attachments = null),
        )
        assertEquals(
            mapOf(NewItemType.IDENTITY to 1),
            spentAllowances(cards = null, identities = 1, attachments = null),
        )
    }

    @Test
    fun `all three spent marks all three`() {
        assertEquals(
            mapOf(NewItemType.CARD to 1, NewItemType.IDENTITY to 1, NewItemType.FILE to 7),
            spentAllowances(cards = 1, identities = 1, attachments = 7),
        )
    }

    @Test
    fun `no uncapped type is ever marked`() {
        // Six of the nine types have no quota at all. Whatever the three flags say, none of them
        // may acquire a mark — that would be the "UNLIMITED on eight cells" design, arrived at by
        // accident.
        val uncapped = NewItemType.entries -
            setOf(NewItemType.CARD, NewItemType.IDENTITY, NewItemType.FILE)
        val everything = spentAllowances(cards = 1, identities = 1, attachments = 7)
        uncapped.forEach { type ->
            assertTrue(
                type !in everything,
                "$type has no quota, so nothing about a spent allowance may mark it",
            )
        }
    }

    /**
     * The invariant the previous design broke, stated so that it cannot be broken silently again.
     *
     * For every type and every combination of the three quota flags, being marked and being refused
     * are the same thing. Marked-but-not-refused is a cell that warns about nothing; refused-but-
     * unmarked is the File defect.
     */
    @Test
    fun `marking and refusing agree for every type in every quota state`() {
        val flags = listOf(true, false)
        flags.forEach { card ->
            flags.forEach { identity ->
                flags.forEach { attachment ->
                    val marked = spentAllowances(
                        cards = 1.takeIf { card },
                        identities = 1.takeIf { identity },
                        attachments = 7.takeIf { attachment },
                    )
                    NewItemType.entries.forEach { type ->
                        val typeQuotaFull = when (type) {
                            NewItemType.CARD -> card
                            NewItemType.IDENTITY -> identity
                            else -> false
                        }
                        val refused = type.addDestination(
                            isPro = false,
                            quotaFull = attachment,
                            typeQuotaFull = typeQuotaFull,
                        ) is AddDestination.QuotaBlocked
                        assertEquals(
                            refused,
                            type in marked,
                            "$type disagrees at card=$card identity=$identity attachment=" +
                                "$attachment: refused=$refused, marked=${type in marked}. The " +
                                "sheet must mark exactly the cells that will turn the user away.",
                        )
                    }
                }
            }
        }
    }

    /**
     * The control for the test above. It compares two functions, so it would pass if both were
     * broken the same way — in particular if `addDestination` stopped refusing anything at all.
     */
    @Test
    fun `a spent allowance really does refuse`() {
        listOf(
            NewItemType.CARD to Triple(true, false, false),
            NewItemType.IDENTITY to Triple(false, true, false),
            NewItemType.FILE to Triple(false, false, true),
        ).forEach { (type, flags) ->
            val (card, identity, attachment) = flags
            val typeQuotaFull = if (type == NewItemType.CARD) card else identity
            assertTrue(
                type.addDestination(
                    isPro = false,
                    quotaFull = attachment,
                    typeQuotaFull = typeQuotaFull,
                ) is AddDestination.QuotaBlocked,
                "$type must be refused when its own quota is full, or the agreement test above " +
                    "is comparing two functions that both say nothing",
            )
        }
    }
}
