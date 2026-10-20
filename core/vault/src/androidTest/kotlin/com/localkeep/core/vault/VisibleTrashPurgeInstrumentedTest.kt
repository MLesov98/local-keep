package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The two irreversible controls in the app, driven against a partly-concealed trash.
 *
 * This is the one place in LocalKeep where getting concealment wrong destroys data instead of merely
 * showing it. Empty Trash used to remove every record carrying a `deletedAt`, which is a correct
 * definition of "the trash" only while the user can see all of it. Hide a login, delete it, empty
 * the trash, and the entry the user asked the app to protect was gone — with no row, no count and no
 * notice ever having named it.
 *
 * So each test here has the same shape: build a trash that is part visible and part concealed, run
 * the destructive path, and assert on *both* halves — that the visible half is really gone (a purge
 * that quietly preserves everything would pass a survival-only test) and that the concealed half
 * survives intact and comes back usable after Stop hiding.
 */
@RunWith(AndroidJUnit4::class)
class VisibleTrashPurgeInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private val now: Instant = Instant.parse("2026-08-18T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val phrase get() = "let me in".toCharArray()

    @BeforeTest
    fun setup() = runBlocking {
        FolderTestVault.cleanup(context)
        FolderTestVault.create(context)
        lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        repository = VaultRepository(lockManager, clock = clock)
        lockManager.unlock(FolderTestVault.password())
        Unit
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        FolderTestVault.cleanup(context)
    }

    @Test
    fun emptyTrashDestroysTheVisibleTrashAndSpareTheConcealedTrash() = runBlocking {
        val visible = seedItem("Cafe wifi")
        val hidden = seedItem("Offshore account")
        repository.moveToTrash(visible)
        repository.moveToTrash(hidden)
        hide(HiddenTarget.Item(hidden))

        // The control that matters as much as the survival one: the visible half must really be in
        // the plan. A purge that preserved everything would satisfy "the hidden item survived".
        val plan = repository.planTrashPurge(PurgeScope.AllTrash)
        assertEquals(setOf(visible), plan.itemIds, "the plan covers exactly what the user can see")

        val outcome = repository.purgeVisibleTrash(plan)

        assertEquals(PurgeOutcome.Purged(itemCount = 1, folderCount = 0), outcome)
        assertNull(rawItem(visible), "the visible trashed item is destroyed")
        assertNotNull(rawItem(hidden), "the concealed trashed item survives Empty Trash")
        assertTrue(hidden in repository.visibilitySnapshot().concealedItemIds)
    }

    @Test
    fun emptyTrashKeepsAConcealedSubtreeAndLeavesNoDanglingParentOrRoot() = runBlocking {
        // Work/  (trashed as one operation, so Work is the trash root of everything below)
        //   Public/    visible
        //   Offshore/  hidden -> survives, and its parent Work does not
        val work = repository.createFolder("Work")
        val public = repository.createFolder("Public", work.id)
        val offshore = repository.createFolder("Offshore", work.id)
        val publicItem = seedItem("Cafe wifi", public.id)
        val offshoreItem = seedItem("Statement", offshore.id)
        repository.trashFolder(work.id)
        hide(HiddenTarget.Folder(offshore.id))

        val plan = repository.planTrashPurge(PurgeScope.AllTrash)
        assertEquals(setOf(work.id, public.id), plan.folderIds)
        assertEquals(setOf(publicItem), plan.itemIds)

        assertEquals(PurgeOutcome.Purged(itemCount = 1, folderCount = 2), repository.purgeVisibleTrash(plan))

        assertNull(rawFolder(work.id))
        assertNull(rawFolder(public.id))
        assertNull(rawItem(publicItem))

        val survivor = assertNotNull(rawFolder(offshore.id), "the concealed folder survives")
        assertNull(survivor.parentId, "its parent was destroyed, so it climbs to the root")
        assertEquals(
            offshore.id,
            survivor.trashRootId,
            "and it is now its own trash root — a trashRootId naming a folder that no longer " +
                "exists is a row that can never be restored",
        )
        val survivingItem = assertNotNull(rawItem(offshoreItem))
        assertEquals(offshore.id, survivingItem.trashedWithFolderId, "regrouped under the surviving root")
        assertNotNull(survivingItem.deletedAt, "still in the trash, not resurrected")
        Unit
    }

    @Test
    fun everySurvivorListsAndRestoresOnceHidingStops() = runBlocking {
        val work = repository.createFolder("Work")
        val offshore = repository.createFolder("Offshore", work.id)
        val offshoreItem = seedItem("Statement", offshore.id)
        val loose = seedItem("Passport")
        repository.trashFolder(work.id)
        repository.moveToTrash(loose)
        hide(HiddenTarget.Folder(offshore.id))
        hide(HiddenTarget.Item(loose))

        repository.purgeVisibleTrash(repository.planTrashPurge(PurgeScope.AllTrash))
        repository.stopHiding(HiddenTarget.Folder(offshore.id))
        repository.stopHiding(HiddenTarget.Item(loose))

        val snapshot = repository.visibilitySnapshot()
        assertTrue(
            snapshot.trashedFolderRows.any { it.folder.id == offshore.id },
            "the survivor has a trash row of its own now that its old root is gone",
        )
        assertTrue(snapshot.trashItems.any { it.id == loose })

        repository.restoreFolder(offshore.id)
        repository.restoreFromTrash(loose)

        val restoredFolder = assertNotNull(rawFolder(offshore.id))
        assertNull(restoredFolder.deletedAt, "restores cleanly")
        assertNull(restoredFolder.parentId, "and lands at the root, which always exists")
        assertNull(assertNotNull(rawItem(offshoreItem)).deletedAt, "its contents come back with it")
        assertNull(assertNotNull(rawItem(loose)).deletedAt)
    }

    @Test
    fun purgingOneFolderKeepsTheConcealedPartOfItsOwnSubtree() = runBlocking {
        val work = repository.createFolder("Work")
        val offshore = repository.createFolder("Offshore", work.id)
        val other = repository.createFolder("Personal")
        val workItem = seedItem("Payslip", work.id)
        val offshoreItem = seedItem("Statement", offshore.id)
        val otherItem = seedItem("Lease", other.id)
        repository.trashFolder(work.id)
        repository.trashFolder(other.id)
        hide(HiddenTarget.Folder(offshore.id))

        val plan = repository.planTrashPurge(PurgeScope.Folder(work.id))
        assertEquals(setOf(work.id), plan.folderIds, "the concealed child is out of scope")
        assertEquals(setOf(workItem), plan.itemIds)

        assertEquals(PurgeOutcome.Purged(itemCount = 1, folderCount = 1), repository.purgeVisibleTrash(plan))

        assertNull(rawFolder(work.id))
        assertNotNull(rawFolder(offshore.id))
        assertNotNull(rawItem(offshoreItem))
        assertNotNull(rawFolder(other.id), "an unrelated trash root is untouched")
        assertNotNull(rawItem(otherItem))
        Unit
    }

    @Test
    fun purgingAFolderSparesAnItemThatWasAlreadyInTheTrashBeforeIt() {
        runBlocking {
            // The ordering is the whole test: trash the item FIRST, then its folder. `trashFolder`
            // only marks items that were still live, so this one keeps `trashedWithFolderId == null`,
            // keeps its own row in the flat trash list, and is not in the number the folder's
            // confirmation names. Scoped by `folderId` the purge took it anyway — the dialog could
            // say "1 folder, 0 items" and destroy an item whose row was on screen beside it.
            val work = repository.createFolder("Work")
            val separately = seedItem("Old receipt", work.id)
            repository.moveToTrash(separately)
            val wentDownWithIt = seedItem("Payslip", work.id)
            repository.trashFolder(work.id)

            val row = repository.visibilitySnapshot().trashedFolderRows.single { it.folder.id == work.id }
            val plan = repository.planTrashPurge(PurgeScope.Folder(work.id))

            // Both halves, so a fix that only moved the count could not pass this.
            assertEquals(setOf(wentDownWithIt), plan.itemIds, "only what went down with the folder")
            assertEquals(
                row.itemCount,
                plan.itemIds.size,
                "the row's number and the plan must be the same number — that is the promise the " +
                    "confirmation makes",
            )

            assertEquals(PurgeOutcome.Purged(itemCount = 1, folderCount = 1), repository.purgeVisibleTrash(plan))

            assertNull(rawItem(wentDownWithIt))
            val survivor = assertNotNull(rawItem(separately), "the separately-trashed item survives")
            assertNotNull(survivor.deletedAt, "and it is still in the trash, where the user put it")
            assertNull(survivor.folderId, "its folder is gone, so it is unfiled rather than dangling")
            assertTrue(
                repository.visibilitySnapshot().trashItems.any { it.id == separately },
                "and it still has a row of its own to restore from",
            )
        }
    }

    @Test
    fun aChangedProjectionRefusesThePurgeAndDeletesNothing() = runBlocking {
        val visible = seedItem("Cafe wifi")
        val hidden = seedItem("Offshore account")
        repository.moveToTrash(visible)
        repository.moveToTrash(hidden)
        hide(HiddenTarget.Item(hidden))

        // The user opens Empty Trash, and *then* the picture changes underneath the dialog: the
        // concealed item stops hiding, so "the visible trash" now includes something they were never
        // shown a row for.
        val plan = repository.planTrashPurge(PurgeScope.AllTrash)
        repository.stopHiding(HiddenTarget.Item(hidden))

        assertEquals(PurgeOutcome.Stale, repository.purgeVisibleTrash(plan))
        assertNotNull(rawItem(visible), "a stale plan destroys nothing at all, not merely the new part")
        assertNotNull(rawItem(hidden))

        // Re-reading is the only way forward, and it works.
        val fresh = repository.planTrashPurge(PurgeScope.AllTrash)
        assertEquals(setOf(visible, hidden), fresh.itemIds)
        assertEquals(PurgeOutcome.Purged(itemCount = 2, folderCount = 0), repository.purgeVisibleTrash(fresh))
    }

    @Test
    fun aConcealedFolderRowNeverCountsWhatItConceals() = runBlocking {
        // The count is an oracle if it does not conceal: "3 items" over a row showing two is a
        // statement that a third exists.
        val work = repository.createFolder("Work")
        val offshore = repository.createFolder("Offshore", work.id)
        seedItem("Payslip", work.id)
        seedItem("Statement", offshore.id)
        repository.trashFolder(work.id)
        hide(HiddenTarget.Folder(offshore.id))

        val row = repository.visibilitySnapshot().trashedFolderRows.single { it.folder.id == work.id }
        assertEquals(1, row.folderCount, "the concealed subfolder is not counted")
        assertEquals(1, row.itemCount, "nor is the item inside it")
    }

    // ---- helpers ----

    /**
     * A one-hour lease. The phrase goes in on every call: `hide` only consumes it when the vault has
     * no verifier yet, and wipes it either way, so the first hide creates it and the rest ignore it.
     */
    private suspend fun hide(target: HiddenTarget) {
        assertEquals(
            HideOutcome.Hidden,
            repository.hide(target, until = now.plusSeconds(3600), firstPhrase = phrase),
        )
    }

    private suspend fun seedItem(title: String, folderId: UUID? = null): UUID {
        val id = UUID.randomUUID()
        repository.addItem(
            VaultItem(
                id = id,
                title = title,
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
                content = Note("n"),
            ),
        )
        return id
    }

    /**
     * Read raw, never through a presentation getter. The whole question here is whether a record the
     * projection is hiding still physically exists, and a filtered read cannot tell "concealed" from
     * "destroyed" — which is exactly the confusion this feature can turn into data loss.
     */
    private fun vault(): Vault = (lockManager.lockState.value as LockState.Unlocked).vault

    private fun rawItem(id: UUID): VaultItem? = vault().items.firstOrNull { it.id == id }

    private fun rawFolder(id: UUID): Folder? = vault().folders.firstOrNull { it.id == id }
}
