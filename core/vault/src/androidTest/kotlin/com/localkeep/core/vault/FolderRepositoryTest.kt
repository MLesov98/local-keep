package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The four folder write paths, and every way each of them is allowed to say no.
 *
 * Folders are containers now, so a refused write is the only thing standing between the user and a
 * tree the app cannot render — a branch moved inside itself, a folder nested past what the UI can
 * indent. Each rejection is asserted by the state it protects, not just by the throw.
 */
@RunWith(AndroidJUnit4::class)
class FolderRepositoryTest {
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

    // ---- createFolder ----

    @Test
    fun createRefusesABlankName() = runBlocking {
        assertFailsWith<IllegalStateException> { repository.createFolder("   ") }
        assertTrue(repository.folders().isEmpty())
    }

    @Test
    fun createRefusesASiblingWithTheSameNameIgnoringCase() = runBlocking {
        val work = repository.createFolder("Work")
        repository.createFolder("Bank", work.id)
        assertFailsWith<IllegalStateException> { repository.createFolder("bank", work.id) }
        assertEquals(2, repository.folders().size)
    }

    /**
     * QA 2026-10-03: an invisible folder held its name. Outside a reveal, typing it answered "That
     * didn't work" over a list showing no such folder — telling whoever holds the phone that a
     * hidden one is there, the question `FolderViewModel.nameTakenAmongSiblings` already refuses
     * to answer. Revealed, the clash is visible, so it is refused again.
     */
    @Test
    fun anInvisibleFolderHoldsItsNameOnlyWhileRevealed() = runBlocking {
        val hidden = repository.createFolder("Bank")
        repository.hide(
            HiddenTarget.Folder(hidden.id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        repository.createFolder("bank")
        assertEquals(2, repository.getCompleteStorageInventory().allFolders.count { it.name.equals("bank", true) })

        val generation = lockManager.nextRevealAttemptGeneration()
        assertEquals(
            RevealAttemptOutcome.Revealed,
            lockManager.attemptHiddenReveal("open sesame".toCharArray(), generation),
        )
        assertFailsWith<IllegalStateException> { repository.createFolder("BANK") }
        Unit
    }

    /** QA 2026-10-03: the refusal says whether the name is held in Trash, so a screen can say where. */
    @Test
    fun aNameRefusalSaysWhetherTheHolderIsInTrash() = runBlocking {
        val work = repository.createFolder("Work")
        repository.trashFolder(work.id)
        assertTrue(assertFailsWith<FolderNameTakenException> { repository.createFolder("work") }.inTrash)

        repository.createFolder("Bank")
        assertFalse(assertFailsWith<FolderNameTakenException> { repository.createFolder("BANK") }.inTrash)
    }

    @Test
    fun createAllowsTheSameNameUnderADifferentParent() = runBlocking {
        val work = repository.createFolder("Work")
        val personal = repository.createFolder("Personal")
        repository.createFolder("Bank", work.id)
        repository.createFolder("Bank", personal.id)
        assertEquals(2, repository.folders().count { it.name == "Bank" })
    }

    @Test
    fun createRefusesAnUnknownParent() = runBlocking {
        assertFailsWith<IllegalStateException> { repository.createFolder("Orphan", UUID.randomUUID()) }
        Unit
    }

    @Test
    fun createRefusesGoingPastTheDepthLimit() = runBlocking {
        var parent = repository.createFolder("d1").id
        repeat(FolderTree.MAX_DEPTH - 1) { parent = repository.createFolder("d${it + 2}", parent).id }
        assertFailsWith<IllegalStateException> { repository.createFolder("too deep", parent) }
        assertEquals(FolderTree.MAX_DEPTH, repository.folders().size)
    }

    // ---- renameFolder ----

    @Test
    fun renameStampsTheNameAndLeavesTheParentStampAlone() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val before = repository.folders().single { it.id == bank.id }
        Thread.sleep(5)
        val renamed = repository.renameFolder(bank.id, "Banking")

        assertEquals("Banking", renamed.name)
        assertTrue(renamed.updatedAt!!.isAfter(before.updatedAt))
        assertEquals(before.parentUpdatedAt, renamed.parentUpdatedAt)
    }

    @Test
    fun renameRefusesASiblingsName() = runBlocking {
        val work = repository.createFolder("Work")
        repository.createFolder("Bank", work.id)
        val cards = repository.createFolder("Cards", work.id)
        assertFailsWith<IllegalStateException> { repository.renameFolder(cards.id, "bank") }
        assertEquals("Cards", repository.folders().single { it.id == cards.id }.name)
    }

    // ---- moveFolder ----

    @Test
    fun moveStampsTheParentAndLeavesTheNameStampAlone() = runBlocking {
        val work = repository.createFolder("Work")
        val personal = repository.createFolder("Personal")
        val bank = repository.createFolder("Bank", work.id)
        val before = repository.folders().single { it.id == bank.id }
        Thread.sleep(5)
        val moved = repository.moveFolder(bank.id, personal.id)

        assertEquals(personal.id, moved.parentId)
        assertTrue(moved.parentUpdatedAt!!.isAfter(before.parentUpdatedAt))
        assertEquals(before.updatedAt, moved.updatedAt)
    }

    @Test
    fun moveIntoItsOwnSubtreeIsRefused() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        assertFailsWith<IllegalStateException> { repository.moveFolder(work.id, bank.id) }
        assertFailsWith<IllegalStateException> { repository.moveFolder(work.id, work.id) }
        assertNull(repository.folders().single { it.id == work.id }.parentId)
    }

    @Test
    fun movingToTheSameParentIsANoOpThatDoesNotRestamp() = runBlocking {
        val work = repository.createFolder("Work")
        val before = repository.folders().single { it.id == work.id }
        assertEquals(before, repository.moveFolder(work.id, null))
    }

    @Test
    fun moveRefusesAnUnknownTarget() = runBlocking {
        val work = repository.createFolder("Work")
        assertFailsWith<IllegalStateException> { repository.moveFolder(work.id, UUID.randomUUID()) }
        Unit
    }

    @Test
    fun moveRefusesGoingPastTheDepthLimit() = runBlocking {
        // createFolder has guarded this since it shipped; moveFolder never has. It checked unknown
        // parent, cycle and name clash and nothing else, so an over-deep move was accepted here and
        // then thrown out by FolderTree.validate inside VaultMutator.build — a failure raised from
        // the commit rather than from the request.
        var deepest = repository.createFolder("d1").id
        repeat(FolderTree.MAX_DEPTH - 1) { deepest = repository.createFolder("d${it + 2}", deepest).id }
        val loose = repository.createFolder("Loose")

        // The message is the assertion, not the throw. Without the guard this still fails — from
        // VaultMutator.build()'s tree validation, at commit time, with "invalid folder tree" — so
        // assertFailsWith alone passes against the very bug this test exists to catch.
        val failure = assertFailsWith<IllegalStateException> { repository.moveFolder(loose.id, deepest) }
        assertTrue(
            failure.message.orEmpty().contains("deeper than"),
            "the request must be refused by moveFolder, not by the commit; got: ${failure.message}",
        )
        assertNull(repository.folders().single { it.id == loose.id }.parentId)
    }

    @Test
    fun moveRefusesWhenTheMovedSubtreeIsWhatOverflows() = runBlocking {
        // The target has room for a childless folder and not for this one. Measuring the payload's
        // own height is the difference, and nothing in the shipped guards measured it at all.
        var deepest = repository.createFolder("d1").id
        repeat(FolderTree.MAX_DEPTH - 2) { deepest = repository.createFolder("d${it + 2}", deepest).id }
        val tall = repository.createFolder("Tall")
        repository.createFolder("TallChild", tall.id)

        val failure = assertFailsWith<IllegalStateException> { repository.moveFolder(tall.id, deepest) }
        assertTrue(
            failure.message.orEmpty().contains("deeper than"),
            "the request must be refused by moveFolder, not by the commit; got: ${failure.message}",
        )
        assertNull(repository.folders().single { it.id == tall.id }.parentId)
    }

    @Test
    fun moveAcceptsALandingExactlyAtTheDepthLimit() = runBlocking {
        // The other side of the boundary, so the guard cannot be satisfied by refusing everything.
        var deepest = repository.createFolder("d1").id
        repeat(FolderTree.MAX_DEPTH - 2) { deepest = repository.createFolder("d${it + 2}", deepest).id }
        val loose = repository.createFolder("Loose")

        assertEquals(deepest, repository.moveFolder(loose.id, deepest).parentId)
    }

    // ---- deleteFolder ----
    //
    // The behaviour these used to pin — unfiling direct items, promoting child folders one level
    // up, renaming a promoted child out of a clash — is gone. It existed because deleting a folder
    // could not be undone, and `trashFolder` is the undo now, so what it did to the survivors is no
    // longer a contract to keep. What replaced it lives in `FolderTrashTest`, which asserts the
    // subtree goes down together and comes back together. Only the rejection stays here, with the
    // other rejections it belongs beside.

    @Test
    fun deleteRefusesAnUnknownFolder() = runBlocking {
        assertFailsWith<IllegalStateException> { repository.deleteFolder(UUID.randomUUID()) }
        Unit
    }

    // ---- descendants and paths ----

    @Test
    fun descendantsAndPathsReflectTheStoredTree() = runBlocking {
        val work = repository.createFolder("Work")
        val bank = repository.createFolder("Bank", work.id)
        val statements = repository.createFolder("Statements", bank.id)
        repository.createFolder("Personal")

        assertEquals(setOf(work.id, bank.id, statements.id), repository.folderDescendants(work.id))
        assertEquals(
            listOf("Work", "Bank", "Statements"),
            repository.folderPathOf(statements.id)?.map { it.name },
        )
    }

    @Test
    fun theDescendantCacheFollowsAMove() = runBlocking {
        val work = repository.createFolder("Work")
        val personal = repository.createFolder("Personal")
        val bank = repository.createFolder("Bank", work.id)

        assertEquals(setOf(work.id, bank.id), repository.folderDescendants(work.id))
        repository.moveFolder(bank.id, personal.id)
        assertEquals(setOf(work.id), repository.folderDescendants(work.id))
        assertEquals(setOf(personal.id, bank.id), repository.folderDescendants(personal.id))
    }

    private fun VaultRepository.folders() = folderPathOfAll()

    private fun VaultRepository.folderPathOfAll() =
        (lockManager.lockState.value as LockState.Unlocked).vault.folders

}
