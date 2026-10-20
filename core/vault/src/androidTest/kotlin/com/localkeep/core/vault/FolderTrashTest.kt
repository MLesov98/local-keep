package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Trashing a folder, restoring it, and emptying the trash.
 *
 * Deleting a folder used to be irreversible, so it was written to never take anything down with
 * it — items were unfiled, child folders promoted. Now the whole subtree goes and Trash is the
 * undo, which moves the risk: the failure to guard against is no longer "the delete destroyed
 * something", it is "the restore did not bring all of it back", and that one is invisible. An item
 * that fails to return is not an error the user sees; it is an item that is simply not there.
 *
 * The merge-produced states get their own cases because they are the ones no ordinary sequence of
 * taps can reach — a vault arriving from an import can hold a live item inside a trashed folder, or
 * a subtree whose members disagree about when they were trashed, and those are exactly the shapes a
 * restore keyed on a timestamp would tear apart.
 */
@RunWith(AndroidJUnit4::class)
class FolderTrashTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    @BeforeTest
    fun setup() = runBlocking {
        FolderTestVault.cleanup(context)
        FolderTestVault.create(context)
        lockManager = VaultLockManager(context, com.localkeep.core.crypto.KeystoreManager(context))
            .also { it.autoLockTimeoutMs = 0 }
        repository = VaultRepository(lockManager)
        lockManager.unlock(FolderTestVault.password())
        Unit
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        FolderTestVault.cleanup(context)
    }

    // ---- trashFolder ----

    @Test
    fun trashTakesTheWholeSubtreeAndNothingElse() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val personal = repository.createFolder("Personal")
        val inBank = seedItem(bank.id)
        val inPersonal = seedItem(personal.id)
        val unfiled = seedItem(null)

        val outcome = repository.trashFolder(work.id)

        assertEquals(2, outcome.folderCount)
        assertEquals(1, outcome.itemCount)
        assertTrue(folder(work.id).deletedAt != null)
        assertTrue(folder(bank.id).deletedAt != null)
        assertNull(folder(personal.id).deletedAt)
        assertNotNull(item(inBank).deletedAt)
        assertNull(item(inPersonal).deletedAt)
        assertNull(item(unfiled).deletedAt)
    }

    @Test
    fun trashStampsOneInstantAcrossEveryRecordItTouches() = runBlocking {
        // Not cosmetic. FolderTree.mergeFolder orders trash-vs-restore on trashUpdatedAt, so a
        // subtree whose members carry different stamps is already the torn state trashRootId exists
        // to survive — there is no reason to manufacture one at the moment of trashing.
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val deep = repository.createFolder("Deep", bank.id)
        val itemId = seedItem(deep.id)

        repository.trashFolder(work.id)

        val stamps = listOf(work.id, bank.id, deep.id).map { folder(it).trashUpdatedAt }.toSet()
        assertEquals(1, stamps.size, "the subtree must share one stamp; got $stamps")
        assertEquals(stamps.single(), folder(work.id).deletedAt)
        assertEquals(stamps.single(), item(itemId).deletedAt)
    }

    @Test
    fun trashTagsEveryFolderWithTheRootTheUserDeleted() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)

        repository.trashFolder(work.id)

        assertEquals(work.id, folder(work.id).trashRootId)
        assertEquals(work.id, folder(bank.id).trashRootId)
    }

    @Test
    fun trashKeepsTheSubtreesShape() = runBlocking {
        // parentId is never cleared, which is the whole reason a restore can rebuild the tree
        // without anyone storing a snapshot of where things were.
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)

        repository.trashFolder(work.id)

        assertEquals(work.id, folder(bank.id).parentId)
    }

    @Test
    fun trashLeavesAnItemAlreadyInTheTrashAlone() = runBlocking {
        // It belongs to an earlier deletion. Re-tagging it would fold two separate acts into one,
        // and restoring this folder would resurrect something the user threw away on its own.
        val work = repository.createFolder("Work")
        val separately = seedItem(work.id)
        repository.moveToTrash(separately)
        val before = item(separately)

        repository.trashFolder(work.id)

        assertEquals(before.deletedAt, item(separately).deletedAt)
        assertNull(item(separately).trashedWithFolderId)
    }

    @Test
    fun trashRefusesAFolderAlreadyInTheTrash() = runBlocking {
        val work = repository.createFolder("Work")
        repository.trashFolder(work.id)
        assertFailsWith<IllegalStateException> { repository.trashFolder(work.id) }
        Unit
    }

    @Test
    fun trashRefusesAnUnknownFolder() = runBlocking {
        assertFailsWith<IllegalStateException> { repository.trashFolder(UUID.randomUUID()) }
        Unit
    }

    @Test
    fun trashingAParentLeavesAnAlreadyTrashedChildOnItsOwnRoot() = runBlocking {
        // Nested deletions stay separate: the child was thrown away first and keeps its own root,
        // so restoring the parent later does not drag it back out with it.
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        repository.trashFolder(bank.id)

        repository.trashFolder(work.id)

        assertEquals(bank.id, folder(bank.id).trashRootId)
        assertEquals(work.id, folder(work.id).trashRootId)
    }

    // ---- restoreFolder ----

    @Test
    fun restoreBringsBackExactlyWhatItsOwnTrashingTook() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val withFolder = seedItem(bank.id)
        val separately = seedItem(bank.id)
        repository.moveToTrash(separately)
        repository.trashFolder(work.id)

        val outcome = repository.restoreFolder(work.id)

        assertEquals(2, outcome.folderCount)
        assertEquals(1, outcome.itemCount)
        assertNull(folder(work.id).deletedAt)
        assertNull(folder(bank.id).deletedAt)
        assertNull(item(withFolder).deletedAt)
        // The one the user trashed on its own stays where they put it. This is what the marker is
        // for: without it, "restore everything in this subtree" would resurrect it too.
        assertNotNull(item(separately).deletedAt)
        Unit
    }

    @Test
    fun restoreClearsTheMarkersItRestores() = runBlocking {
        val work = repository.createFolder("Work")
        val itemId = seedItem(work.id)
        repository.trashFolder(work.id)

        repository.restoreFolder(work.id)

        assertNull(folder(work.id).trashRootId)
        assertNotNull(folder(work.id).trashUpdatedAt)
        assertNull(item(itemId).trashedWithFolderId)
    }

    @Test
    fun restoringANestedDeletionLeavesTheOuterOneInTheTrash() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        repository.trashFolder(bank.id)
        repository.trashFolder(work.id)

        repository.restoreFolder(bank.id)

        assertNull(folder(bank.id).deletedAt)
        assertNotNull(folder(work.id).deletedAt)
        Unit
    }

    @Test
    fun restoreUnderAStillTrashedParentLandsOnTheNearestLiveAncestor() = runBlocking {
        // A folder keeps its parentId through a trashing, so the place it wants back may itself
        // still be in the trash. Restoring it there would return it somewhere the user cannot
        // navigate to — visible in no list, and indistinguishable from the restore having failed.
        val top = repository.createFolder("Top")
        val middle = repository.createFolder("Middle", top.id)
        val leaf = repository.createFolder("Leaf", middle.id)
        repository.trashFolder(leaf.id)
        repository.trashFolder(middle.id)

        val outcome = repository.restoreFolder(leaf.id)

        assertTrue(outcome.reparented)
        assertEquals(top.id, outcome.reparentedTo)
        assertEquals(top.id, folder(leaf.id).parentId)
        assertNull(folder(leaf.id).deletedAt)
    }

    @Test
    fun restoreClimbsAllTheWayToTheRootWhenEveryAncestorIsTrashed() = runBlocking {
        val top = repository.createFolder("Top")
        val leaf = repository.createFolder("Leaf", top.id)
        repository.trashFolder(leaf.id)
        repository.trashFolder(top.id)

        val outcome = repository.restoreFolder(leaf.id)

        assertTrue(outcome.reparented)
        assertNull(outcome.reparentedTo)
        assertNull(folder(leaf.id).parentId)
    }

    @Test
    fun restoreLeavesAFolderWhereItWasWhenItsParentIsLive() = runBlocking {
        // The other side of the fallback, so it cannot be satisfied by re-parenting everything.
        val top = repository.createFolder("Top")
        val leaf = repository.createFolder("Leaf", top.id)
        repository.trashFolder(leaf.id)

        val outcome = repository.restoreFolder(leaf.id)

        assertTrue(!outcome.reparented)
        assertEquals(top.id, folder(leaf.id).parentId)
    }

    @Test
    fun restoreRefusesAFolderThatIsNotInTheTrash() = runBlocking {
        val work = repository.createFolder("Work")
        assertFailsWith<IllegalStateException> { repository.restoreFolder(work.id) }
        Unit
    }

    // ---- the states only a merge can produce ----

    @Test
    fun restoreLeavesAnItemInTheTrashWhenItsOwnFolderIsStillTrashed() = runBlocking {
        // The torn subtree. After a merge, mergeFolder decides each folder separately, so a child
        // can be left trashed while its parent comes back. An item marked with the parent's root
        // must not be restored into it: it would be live inside an invisible folder, which is the
        // one failure worse than staying in the trash.
        val parent = UUID.randomUUID()
        val child = UUID.randomUUID()
        val trashedAt = Instant.now()
        val itemId = UUID.randomUUID()
        commitRaw(
            folders = listOf(
                Folder(parent, "Parent", deletedAt = trashedAt, trashUpdatedAt = trashedAt, trashRootId = parent),
                // The tear: same subtree, different root, so restoring `parent` does not free it.
                Folder(child, "Child", parentId = parent, deletedAt = trashedAt, trashUpdatedAt = trashedAt, trashRootId = child),
            ),
            items = listOf(vaultItem(itemId, folderId = child, deletedAt = trashedAt, trashedWith = parent)),
        )

        val outcome = repository.restoreFolder(parent)

        assertNull(folder(parent).deletedAt)
        assertNotNull(folder(child).deletedAt)
        assertNotNull(item(itemId).deletedAt)
        assertEquals(0, outcome.itemCount)
    }

    @Test
    fun aFolderCarryingSomeoneElsesRootIsStillRestorableByItself() = runBlocking {
        // Same tear, approached from the row the user actually taps. Restoring is keyed on
        // trashRootId, and a merge can leave a folder pointing at a root that is already live — so
        // the folder itself is always part of its own restore, or that row would do nothing at all.
        val stranded = UUID.randomUUID()
        val trashedAt = Instant.now()
        commitRaw(
            folders = listOf(
                Folder(stranded, "Stranded", deletedAt = trashedAt, trashUpdatedAt = trashedAt, trashRootId = UUID.randomUUID()),
            ),
            items = emptyList(),
        )

        repository.restoreFolder(stranded)

        assertNull(folder(stranded).deletedAt)
    }

    @Test
    fun purgeUnfilesALiveItemLeftInsideATrashedFolder() = runBlocking {
        // A merge can put a live item in a trashed folder. Emptying the trash must destroy what the
        // user threw away, not an item they never did — so it is unfiled rather than deleted.
        val trashed = UUID.randomUUID()
        val liveItem = UUID.randomUUID()
        val trashedAt = Instant.now()
        commitRaw(
            folders = listOf(Folder(trashed, "Trashed", deletedAt = trashedAt, trashUpdatedAt = trashedAt, trashRootId = trashed)),
            items = listOf(vaultItem(liveItem, folderId = trashed, deletedAt = null, trashedWith = null)),
        )

        repository.purgeTrash()

        assertEquals(1, vault().items.size)
        assertNull(item(liveItem).folderId)
        assertTrue(vault().folders.isEmpty())
    }

    @Test
    fun purgeLiftsALiveFolderLeftUnderATrashedParent() = runBlocking {
        // The tree-shape version of the same case. Dropping the parent while a live child points at
        // it leaves a dangling parentId, which VaultMutator.build refuses — the purge would throw
        // from the commit and the trash could never be emptied again.
        val trashedParent = UUID.randomUUID()
        val liveChild = UUID.randomUUID()
        val trashedAt = Instant.now()
        commitRaw(
            folders = listOf(
                Folder(trashedParent, "Trashed", deletedAt = trashedAt, trashUpdatedAt = trashedAt, trashRootId = trashedParent),
                Folder(liveChild, "Live", parentId = trashedParent),
            ),
            items = emptyList(),
        )

        repository.purgeTrash()

        assertEquals(listOf(liveChild), vault().folders.map { it.id })
        assertNull(folder(liveChild).parentId)
    }

    // ---- purgeTrash ----

    @Test
    fun purgeDropsTrashedFoldersAndEverythingTheyHeld() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val withFolder = seedItem(bank.id)
        val separately = seedItem(bank.id)
        repository.moveToTrash(separately)
        val survivor = seedItem(null)
        repository.trashFolder(work.id)

        repository.purgeTrash()

        assertTrue(vault().folders.isEmpty())
        assertEquals(listOf(survivor), vault().items.map { it.id })
        assertTrue(vault().items.none { it.id == withFolder || it.id == separately })
    }

    @Test
    fun purgeLeavesLiveFoldersAlone() = runBlocking {
        val keep = repository.createFolder("Keep")
        val drop = repository.createFolder("Drop")
        repository.trashFolder(drop.id)

        repository.purgeTrash()

        assertEquals(listOf(keep.id), vault().folders.map { it.id })
    }

    // ---- setItemFolder ----

    @Test
    fun setItemFolderFilesAndUnfiles() = runBlocking {
        val work = repository.createFolder("Work")
        val itemId = seedItem(null)

        repository.setItemFolder(itemId, work.id)
        assertEquals(work.id, item(itemId).folderId)

        repository.setItemFolder(itemId, null)
        assertNull(item(itemId).folderId)
    }

    @Test
    fun setItemFolderStampsTheItem() = runBlocking {
        val work = repository.createFolder("Work")
        val itemId = seedItem(null)
        val before = item(itemId).updatedAt
        Thread.sleep(5)

        repository.setItemFolder(itemId, work.id)

        assertTrue(item(itemId).updatedAt.isAfter(before))
    }

    @Test
    fun setItemFolderRefusesATrashedTarget() = runBlocking {
        // Filing into an invisible folder is how an item disappears without being deleted.
        val work = repository.createFolder("Work")
        val itemId = seedItem(null)
        repository.trashFolder(work.id)

        assertFailsWith<IllegalStateException> { repository.setItemFolder(itemId, work.id) }
        assertNull(item(itemId).folderId)
    }

    @Test
    fun setItemFolderRefusesAnUnknownTarget() = runBlocking {
        val itemId = seedItem(null)
        assertFailsWith<IllegalStateException> { repository.setItemFolder(itemId, UUID.randomUUID()) }
        Unit
    }

    // ---- deleteFolder ----

    @Test
    fun deleteNoLongerPromotesOrUnfiles() = runBlocking {
        // It used to lift child folders one level up and unfile direct items. Both were there
        // because the delete was irreversible; Trash replaces them.
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val itemId = seedItem(work.id)

        repository.deleteFolder(work.id)

        assertEquals(work.id, folder(bank.id).parentId)
        assertNotNull(folder(bank.id).deletedAt)
        assertEquals(work.id, item(itemId).folderId)
        assertNotNull(item(itemId).deletedAt)
        Unit
    }

    // ---- helpers ----

    // ---- what the Trash screen reads ----

    @Test
    fun theFlatItemListLeavesOutWhateverWentDownWithAFolder() {
        // Otherwise one forty-item folder buries the items the user came here to find, and offers
        // forty separate restores for something that is one thing.
        runBlocking {
            val work = repository.createFolder("Work")
            val inFolder = seedItem(work.id)
            val loose = seedItem(null)
            repository.moveToTrash(loose)
            repository.trashFolder(work.id)

            val flat = repository.getTrashItems().map { it.id }
            assertEquals(listOf(loose), flat)
            assertTrue(inFolder !in flat)
        }
    }

    @Test
    fun anItemTrashedOnItsOwnStaysInTheFlatListWhenItsFolderIsTrashedLater() {
        // It carries no marker — trashFolder only touches items that are still live — so it stays
        // restorable by itself. The user deleted it deliberately, before the folder went anywhere.
        runBlocking {
            val work = repository.createFolder("Work")
            val id = seedItem(work.id)
            repository.moveToTrash(id)
            repository.trashFolder(work.id)

            assertNull(item(id).trashedWithFolderId)
            assertEquals(listOf(id), repository.getTrashItems().map { it.id })
        }
    }

    @Test
    fun onlyTrashRootsGetARow() {
        runBlocking {
            val work = repository.createFolder("Work")
            val bank = repository.createFolder("Bank", work.id)
            repository.trashFolder(work.id)

            val roots = repository.getTrashedFolders()
            assertEquals(listOf(work.id), roots.map { it.folder.id })
            assertEquals(2, roots.single().folderCount, "the root counts itself")
            assertTrue(bank.id !in roots.map { it.folder.id })
        }
    }

    @Test
    fun aTrashedFolderUnderALiveOneIsStillARoot() {
        // The shape the ancestor fallback and a merge both produce. Defining a root as "no parent"
        // would leave this one with no row at all, and its whole subtree unreachable.
        runBlocking {
            val live = repository.createFolder("Live")
            val inner = repository.createFolder("Inner", live.id)
            repository.trashFolder(inner.id)

            assertEquals(listOf(inner.id), repository.getTrashedFolders().map { it.folder.id })
        }
    }

    @Test
    fun theRowCountsAreWhatTheRestoreActuallyReturns() {
        // The number on the row and the number restored come from one predicate, so they cannot
        // drift apart — which is the only reason it is safe to print a count in a confirmation.
        runBlocking {
            val work = repository.createFolder("Work")
            val bank = repository.createFolder("Bank", work.id)
            seedItem(work.id)
            seedItem(bank.id)
            repository.trashFolder(work.id)

            val row = repository.getTrashedFolders().single()
            val restored = repository.restoreFolder(work.id)
            assertEquals(row.folderCount, restored.folderCount)
            assertEquals(row.itemCount, restored.itemCount)
        }
    }

    // ---- purgeFolder ----

    @Test
    fun purgingOneFolderLeavesTheRestOfTheTrashAlone() {
        runBlocking {
            val work = repository.createFolder("Work")
            val doomed = seedItem(work.id)
            val other = repository.createFolder("Other")
            val spared = seedItem(other.id)
            val loose = seedItem(null)
            repository.moveToTrash(loose)
            repository.trashFolder(work.id)
            repository.trashFolder(other.id)

            repository.purgeFolder(work.id)

            assertTrue(vault().items.none { it.id == doomed })
            assertTrue(vault().folders.none { it.id == work.id })
            assertNotNull(vault().items.firstOrNull { it.id == spared })
            assertNotNull(vault().items.firstOrNull { it.id == loose })
            assertEquals(listOf(other.id), repository.getTrashedFolders().map { it.folder.id })
        }
    }

    @Test
    fun purgingAFolderTakesItsWholeSubtree() {
        runBlocking {
            val work = repository.createFolder("Work")
            val bank = repository.createFolder("Bank", work.id)
            val deep = seedItem(bank.id)
            repository.trashFolder(work.id)

            repository.purgeFolder(work.id)

            assertTrue(vault().folders.none { it.id == bank.id })
            assertTrue(vault().items.none { it.id == deep })
        }
    }

    @Test
    fun purgingAFolderSparesALiveItemAMergeLeftInside() {
        // Purge is the one path here with no undo, and it should destroy what the user trashed —
        // not what a merge happened to leave sitting under it.
        runBlocking {
            val work = repository.createFolder("Work")
            repository.trashFolder(work.id)
            val intruder = UUID.randomUUID()
            commitRaw(
                folders = vault().folders,
                items = vault().items + vaultItem(intruder, work.id, deletedAt = null, trashedWith = null),
            )

            repository.purgeFolder(work.id)

            val survivor = vault().items.firstOrNull { it.id == intruder }
            assertNotNull(survivor)
            assertNull(survivor.folderId, "lifted out, not destroyed")
        }
    }

    @Test
    fun purgeRefusesAFolderThatIsNotInTheTrash() {
        runBlocking {
            val work = repository.createFolder("Work")
            assertFailsWith<IllegalStateException> { repository.purgeFolder(work.id) }
            assertFailsWith<IllegalStateException> { repository.purgeFolder(UUID.randomUUID()) }
            Unit
        }
    }

    // ---- the reads that must NOT filter ----
    //
    // Filtering a folder read is safe almost everywhere and quietly destructive in three places.
    // Each of these fails only if someone "tidies up" by swapping the raw list for `.live`, which is
    // exactly the change that looks correct in review — which is why the consequence, not the
    // accessor, is what gets asserted here.

    @Test
    fun editingAnItemInsideATrashedFolderDoesNotUnfileIt() {
        // `withLiveFolder` is the folder read that writes. Filtered, this update would blank the
        // item's folderId, and the folder would restore empty with the item stranded at the root.
        runBlocking {
            val work = repository.createFolder("Work")
            val id = seedItem(work.id)
            repository.trashFolder(work.id)

            repository.updateItem(item(id).copy(title = "renamed while in the trash"))
            assertEquals(work.id, item(id).folderId)

            repository.restoreFolder(work.id)
            assertEquals(work.id, item(id).folderId)
            assertNull(item(id).deletedAt)
        }
    }

    @Test
    fun aTrashedFoldersItemsAreNotCountedAsOrphans() {
        // Filtered, every item in every trashed folder becomes an orphan, the unlock banner fires,
        // and the "repair" it offers is the unfiling this test exists to prevent.
        runBlocking {
            val work = repository.createFolder("Work")
            repeat(3) { seedItem(work.id) }
            repository.trashFolder(work.id)

            val health = repository.checkFolderTreeHealth()
            assertEquals(0, health.orphanedItemCount, "a trashed folder is not a missing folder")
            assertTrue(!health.needsRepair)
        }
    }

    @Test
    fun repairLeavesTheContentsOfATrashedFolderWhereTheyAre() {
        // The same rule on the writing side: even asked to repair outright, nothing in a trashed
        // folder moves.
        runBlocking {
            val work = repository.createFolder("Work")
            val ids = List(3) { seedItem(work.id) }
            repository.trashFolder(work.id)

            val outcome = repository.repairFolderTree()

            assertEquals(0, outcome.unfiledItemCount)
            ids.forEach { assertEquals(work.id, item(it).folderId) }
        }
    }

    @Test
    fun aTrashedFolderStillHoldsItsName() {
        // Otherwise a new folder takes the name and restoring produces two same-name siblings —
        // a state create, rename and move all refuse to produce, arrived at by the back door.
        runBlocking {
            val work = repository.createFolder("Work")
            repository.createFolder("Bank", work.id)
            repository.trashFolder(work.id)

            assertFailsWith<IllegalStateException> { repository.createFolder("work") }
            Unit
        }
    }

    @Test
    fun repairTrashesALiveFolderLeftUnderATrashedParentByAMerge() {
        // Only a merge produces this shape, and until normalize gained the rule it survived every
        // repair: a live folder whose parent is trashed is in no list at all.
        runBlocking {
            val stamp = Instant.parse("2026-01-01T00:00:00Z")
            val parent = UUID.randomUUID()
            val child = UUID.randomUUID()
            commitRaw(
                folders = listOf(
                    Folder(id = parent, name = "Parent", deletedAt = stamp, trashUpdatedAt = stamp),
                    Folder(id = child, name = "Child", parentId = parent),
                ),
                items = emptyList(),
            )

            repository.repairFolderTree()

            val repaired = folder(child)
            assertEquals(stamp, repaired.deletedAt, "inherited, never stamped now")
            assertEquals(parent, repaired.trashRootId, "so restoring the root brings it back too")
        }
    }

    @Test
    fun savingAStaleEditorSnapshotDoesNotResurrectAnItemTrashedUnderneathIt() = runBlocking {
        // Reachable inside one screen: the item editor's own folder picker can trash the folder the
        // open item is filed in, and that trashes the item with it. The editor's snapshot still says
        // `deletedAt = null` from when it loaded, and `updateItem` used to write that straight back —
        // the item came back live and unfiled, silently undoing the deletion the user had just
        // confirmed. It reconciled the hidden pair and the password against the stored item, but not
        // the trash state.
        val work = repository.createFolder("Work")
        val itemId = seedItem(work.id)
        val loaded = item(itemId)

        repository.trashFolder(work.id)

        val outcome = repository.updateItem(
            loaded.copy(title = "edited while it was being trashed"),
            expectedUpdatedAt = loaded.updatedAt,
        )

        assertEquals(UpdateOutcome.SAVED_INTO_TRASH, outcome, "and the editor has to be told")
        val saved = item(itemId)
        assertNotNull(saved.deletedAt, "it stays in the trash")
        assertEquals(work.id, saved.trashedWithFolderId, "still represented by its folder's row")
        assertEquals(
            "edited while it was being trashed",
            saved.title,
            "the edit is kept too — refusing the write would throw away real typing",
        )
    }

    private fun vault(): Vault = (lockManager.lockState.value as LockState.Unlocked).vault

    private fun folder(id: UUID): Folder = vault().folders.single { it.id == id }

    private fun item(id: UUID): VaultItem = vault().items.single { it.id == id }

    private suspend fun seedItem(folderId: UUID?): UUID {
        val id = UUID.randomUUID()
        repository.addItem(vaultItem(id, folderId, deletedAt = null, trashedWith = null))
        return id
    }

    private fun vaultItem(
        id: UUID,
        folderId: UUID?,
        deletedAt: Instant?,
        trashedWith: UUID?,
    ): VaultItem {
        val now = Instant.now()
        return VaultItem(
            id = id,
            title = "item-$id",
            folderId = folderId,
            createdAt = now,
            updatedAt = now,
            deletedAt = deletedAt,
            content = Login(username = "u", password = "p"),
            trashedWithFolderId = trashedWith,
        )
    }

    /**
     * Writes a vault straight through the lock manager, the way the restore and import paths do.
     *
     * The merge-produced states cannot be reached through the repository's own API — every write it
     * offers keeps the tree consistent — so the only honest way to test the code that has to survive
     * them is to put the vault into that shape directly.
     */
    private suspend fun commitRaw(folders: List<Folder>, items: List<VaultItem>) {
        lockManager.commit(vault().copy(folders = folders, items = items))
    }
}
