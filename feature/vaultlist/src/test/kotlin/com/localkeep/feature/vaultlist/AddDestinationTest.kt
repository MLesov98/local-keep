package com.localkeep.feature.vaultlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The add sheet and the empty-state button must make the same routing decision. They did not:
 * the empty state called onAddItem directly, so "Add a file" on the empty Files & Media list
 * opened the LOGIN editor (2026-07-29), and the pro gate was skipped entirely.
 */
class AddDestinationTest {

    @Test
    fun `a file add goes to the file editor, never the shared item editor`() {
        assertEquals(AddDestination.FileEditor, NewItemType.FILE.addDestination(isPro = true))
    }

    // ---------------------------------------------------------------------------------------
    // Cards and Identity documents: one free each (2026-09-03), where they were gated outright.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a free user who has not used the allowance reaches the editor`() {
        // The whole point of the change. A free vault holding no card opens the ordinary form —
        // no dialog, no padlock, no detour through an upsell for something they are entitled to.
        NewItemType.entries.filter { it.requiresPro }.forEach { type ->
            assertEquals(
                AddDestination.Editor(type),
                type.addDestination(isPro = false, typeQuotaFull = false),
                "$type must be creatable once on the free tier",
            )
        }
    }

    @Test
    fun `a free user who has spent the allowance is quota-blocked, not pro-blocked`() {
        // Not ProBlocked, for exactly the reason files stopped being ProBlocked: that dialog is
        // titled "Cards — part of Pro", which is false said to someone who is storing a card.
        NewItemType.entries.filter { it.requiresPro }.forEach { type ->
            assertEquals(
                AddDestination.QuotaBlocked(type),
                type.addDestination(isPro = false, typeQuotaFull = true),
                "$type must refuse on the count, not on the tier",
            )
        }
    }

    @Test
    fun `a spent allowance never blocks a pro user`() {
        NewItemType.entries.filter { it.requiresPro }.forEach { type ->
            assertEquals(
                AddDestination.Editor(type),
                type.addDestination(isPro = true, typeQuotaFull = true),
            )
        }
    }

    @Test
    fun `the type allowance only ever affects cards and identities`() {
        // A card count has nothing to say about whether a Login, a Note or a File may be created.
        NewItemType.entries.filterNot { it.requiresPro }.forEach { type ->
            assertEquals(
                type.addDestination(isPro = false, typeQuotaFull = false),
                type.addDestination(isPro = false, typeQuotaFull = true),
                "$type changed destination because of the card/identity allowance",
            )
        }
    }

    @Test
    fun `every refusal is a spent allowance, never a tier`() {
        // This test used to assert that no route produced `AddDestination.ProBlocked`, the "this
        // tier may not create X at all" case. It passed for a day, then the case was deleted
        // outright on 2026-09-04 along with its dialog, the locked empty state and five strings —
        // an unreachable branch that still said "part of Pro" is a lie waiting for a caller.
        //
        // What survives it is the stronger claim: across every combination of inputs, the only way
        // this router refuses anything is QuotaBlocked. If a tier-shaped refusal is ever added
        // back, this fails on the count rather than on a type name that would no longer compile.
        val everyCombination = NewItemType.entries.flatMap { type ->
            listOf(true, false).flatMap { pro ->
                listOf(true, false).flatMap { quota ->
                    listOf(true, false).map { typeQuota ->
                        type.addDestination(isPro = pro, quotaFull = quota, typeQuotaFull = typeQuota)
                    }
                }
            }
        }
        val refusals = everyCombination.filterNot {
            it is AddDestination.Editor || it is AddDestination.FileEditor
        }
        assertTrue(
            refusals.isNotEmpty(),
            "no input refuses anything, so this test is comparing nothing — the quota routing has " +
                "been removed, not merely simplified",
        )
        assertTrue(
            refusals.all { it is AddDestination.QuotaBlocked },
            "a route refuses on something other than a spent allowance: " +
                "${refusals.filterNot { it is AddDestination.QuotaBlocked }.distinct()}",
        )
    }

    @Test
    fun `free types open the shared editor on their own type`() {
        NewItemType.entries.filterNot { it.requiresPro || it.isFile }.forEach { type ->
            assertEquals(AddDestination.Editor(type), type.addDestination(isPro = false))
        }
    }

    @Test
    fun `the pro filters are exactly the pro add types`() {
        // One source of truth: the drawer padlock, the upsell empty state and the add gate must
        // never disagree about what is Pro.
        ItemTypeFilter.entries.forEach { filter ->
            val addType = filter.addable()
            if (addType != null) {
                assertEquals(addType.requiresPro, filter.requiresPro, "$filter disagrees with $addType")
            }
        }
        // FILE is deliberately absent: files are quota-limited, not gated, so the drawer must not
        // padlock the category. See ItemTypeFilter.requiresPro and AttachmentQuota. CARD and
        // IDENTITY still answer true here — `requiresPro` now means "Pro is what lifts this type's
        // cap", which is still exactly right; what changed is that the cap is one rather than zero,
        // and the gates read a count beside it. See ProTypeQuota.
        assertEquals(
            setOf(ItemTypeFilter.CARD, ItemTypeFilter.IDENTITY),
            ItemTypeFilter.entries.filter { it.requiresPro }.toSet(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // The free-tier attachment quota (2026-07-29). Files stopped being Pro-or-nothing.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a free user with slots left reaches the file editor`() {
        assertEquals(
            AddDestination.FileEditor,
            NewItemType.FILE.addDestination(isPro = false, quotaFull = false),
        )
    }

    @Test
    fun `a free user out of slots is quota-blocked, not pro-blocked`() {
        // Two failures this prevents. One: dropping someone into the file editor, letting them pick
        // a photo, encrypt it, name it, and only then refusing at Save. Two, subtler: reusing
        // ProBlocked here made the dialog say "Files & media — part of Pro" to someone who had just
        // stored five files, which is simply untrue since files became quota-limited.
        assertEquals(
            AddDestination.QuotaBlocked(NewItemType.FILE),
            NewItemType.FILE.addDestination(isPro = false, quotaFull = true),
        )
    }

    @Test
    fun `a full quota never blocks a pro user`() {
        assertEquals(
            AddDestination.FileEditor,
            NewItemType.FILE.addDestination(isPro = true, quotaFull = true),
        )
    }

    @Test
    fun `the quota only ever affects files`() {
        // A count of attachments has nothing to say about whether a Login or a Note may be created.
        NewItemType.entries.filterNot { it.isFile }.forEach { type ->
            assertEquals(
                type.addDestination(isPro = true, quotaFull = false),
                type.addDestination(isPro = true, quotaFull = true),
                "$type changed destination because of the attachment quota",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // The "Password" row (2026-07-29) — a Login by a different door, not a sixth item type.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the password row is free and opens the shared editor`() {
        assertEquals(
            AddDestination.Editor(NewItemType.PASSWORD),
            NewItemType.PASSWORD.addDestination(isPro = false),
        )
    }

    @Test
    fun `the password row files itself under Logins, not a category of its own`() {
        // The drawer must not grow a "Passwords" section for a kind of item that does not exist.
        // Compared as resource ids: the same id is the same word in every language, where two
        // equal English literals were only ever the same word in English.
        assertEquals(NewItemType.LOGIN.categoryLabelRes, NewItemType.PASSWORD.categoryLabelRes)
    }

    @Test
    fun `the login row is the first thing offered`() {
        // Login is the dominant real-world use case for a password manager — users create logins
        // most frequently, so it comes first in the add sheet. Password is a route into the login
        // form (generating a password while creating a login), not a standalone item type.
        assertEquals(NewItemType.LOGIN, NewItemType.entries.first())
    }

    @Test
    fun `the pro rows are one contiguous block at the end`() {
        // Brass is doing the work of a section heading in the sheet, so the Pro rows have to be
        // adjacent and last. They were not: FILE went free but stayed after CARD and IDENTITY,
        // rendering four jade rows, two brass, then one jade again.
        val pro = NewItemType.entries.map { it.requiresPro }
        assertEquals(
            pro.sortedBy { it },
            pro,
            "Pro rows must all come after the free ones: $pro",
        )
        assertTrue(NewItemType.entries.last().requiresPro, "the last row should be a Pro row")
    }

    @Test
    fun `every add type has a category label the drawer already knows`() {
        // categoryLabelRes is what the Pro prompt and the drawer say out loud. A label naming a
        // category with no filter behind it sends the user looking for a section that isn't there.
        // Sharing the resource, not just the English text, is what keeps them saying one word per
        // category once the drawer is translated.
        val known = ItemTypeFilter.entries.mapTo(mutableSetOf()) { it.labelRes }
        NewItemType.entries.forEach { type ->
            assertTrue(
                type.categoryLabelRes in known,
                "${type.name} claims a category resource no filter provides",
            )
        }
    }
}
