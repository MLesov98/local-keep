package com.localkeep.feature.importwizard

import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reconciling an imported folder tree against the one the vault already has.
 *
 * The importer mints fresh ids, so matching is by name — and once folders nest, by name *and place*.
 * A global match would pull a KeePass "Work/Bank" into an unrelated "Personal/Bank" the user
 * already had, moving credentials somewhere they never put them, in an operation that reports
 * success.
 */
class NestedImportReconcileTest {

    private fun folder(name: String, parent: UUID? = null, id: UUID = UUID.randomUUID()) =
        Folder(id = id, name = name, parentId = parent)

    private fun item(folderId: UUID?, title: String = "item") = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        folderId = folderId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    @Test
    fun `a nested import creates the whole path and files the item at the bottom of it`() {
        val work = folder("Work")
        val bank = folder("Bank", parent = work.id)
        val (created, refiled) = reconcileImportedFolders(
            existing = emptyList(),
            selected = listOf(item(bank.id)),
            parsed = listOf(work, bank),
        )

        assertEquals(listOf("Work", "Bank"), created.map { it.name })
        val createdBank = created.single { it.name == "Bank" }
        assertEquals(created.single { it.name == "Work" }.id, createdBank.parentId)
        assertEquals(createdBank.id, refiled.single().folderId)
        assertTrue(FolderTree.validate(created).isValid)
    }

    @Test
    fun `a folder that already exists in the same place is matched, not duplicated`() {
        val existingWork = folder("Work")
        val existingBank = folder("Bank", parent = existingWork.id)
        val incomingWork = folder("work")
        val incomingBank = folder("BANK", parent = incomingWork.id)

        val (created, refiled) = reconcileImportedFolders(
            existing = listOf(existingWork, existingBank),
            selected = listOf(item(incomingBank.id)),
            parsed = listOf(incomingWork, incomingBank),
        )

        assertTrue(created.isEmpty(), "case-insensitive match: two folders a user cannot tell apart are one")
        assertEquals(existingBank.id, refiled.single().folderId)
    }

    @Test
    fun `a name that matches only under a different parent is created rather than borrowed`() {
        // The whole reason matching is sibling-scoped. "Bank" under Personal is not the "Bank" the
        // import means, and filing into it would move credentials somewhere the user never put them.
        val personal = folder("Personal")
        val personalBank = folder("Bank", parent = personal.id)
        val incomingWork = folder("Work")
        val incomingBank = folder("Bank", parent = incomingWork.id)

        val (created, refiled) = reconcileImportedFolders(
            existing = listOf(personal, personalBank),
            selected = listOf(item(incomingBank.id)),
            parsed = listOf(incomingWork, incomingBank),
        )

        assertEquals(listOf("Work", "Bank"), created.map { it.name })
        assertTrue(refiled.single().folderId != personalBank.id)
    }

    @Test
    fun `the order the importer emits folders in does not change the outcome`() {
        // A child arriving before its parent must still resolve against the parent's fate, so the
        // reconcile sorts by depth rather than trusting the list.
        val work = folder("Work")
        val bank = folder("Bank", parent = work.id)
        val statements = folder("Statements", parent = bank.id)
        val selected = listOf(item(statements.id))

        val forwards = reconcileImportedFolders(emptyList(), selected, listOf(work, bank, statements))
        val backwards = reconcileImportedFolders(emptyList(), selected, listOf(statements, bank, work))

        assertEquals(
            forwards.first.map { it.name },
            backwards.first.map { it.name },
        )
    }

    @Test
    fun `only folders something is actually being imported into are created`() {
        // Deselecting rows in the preview must not still reorganise the vault.
        val work = folder("Work")
        val bank = folder("Bank", parent = work.id)
        val unused = folder("Archive")

        val (created, _) = reconcileImportedFolders(
            existing = emptyList(),
            selected = listOf(item(bank.id)),
            parsed = listOf(work, bank, unused),
        )

        assertEquals(setOf("Work", "Bank"), created.map { it.name }.toSet())
    }

    @Test
    fun `an item pointing at a folder the import does not carry is filed nowhere`() {
        // Never a dangling id: that item would be invisible in every list. Unfiled is the state it
        // would have had before folders were importable at all.
        val (_, refiled) = reconcileImportedFolders(
            existing = emptyList(),
            selected = listOf(item(UUID.randomUUID())),
            parsed = listOf(folder("Work")),
        )
        assertNull(refiled.single().folderId)
    }

    @Test
    fun `an import with no folders leaves every item exactly as it was`() {
        val items = listOf(item(null), item(null))
        val (created, refiled) = reconcileImportedFolders(emptyList(), items, emptyList())
        assertTrue(created.isEmpty())
        assertEquals(items, refiled)
    }

    @Test
    fun `created folders carry both stamps, so a later merge can order them`() {
        val (created, _) = reconcileImportedFolders(
            existing = emptyList(),
            selected = listOf(item(UUID.randomUUID().let { _ -> null })),
            parsed = emptyList(),
        )
        assertTrue(created.isEmpty())

        val work = folder("Work")
        val (stamped, _) = reconcileImportedFolders(emptyList(), listOf(item(work.id)), listOf(work))
        assertTrue(stamped.single().updatedAt != null)
        assertTrue(stamped.single().parentUpdatedAt != null)
    }
}
