package com.localkeep.feature.vaultlist

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LkDropAcceptance
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Found 2026-10-02: the single-item "Move to folder…" sheet read the whole vault once per
 * folder, during composition.
 *
 * The sheet filtered `state.folders` through `viewModel.acceptanceFor(payload, folder.id)`, and for an
 * item payload `acceptanceFor` resolves the dragged items with `repository.getAllItems()` — a fresh
 * copy of the vault, filtered — every call. With a few hundred folders over ten thousand items that
 * is millions of element visits and megabytes of garbage on the main thread, repeated on each
 * recomposition while the sheet is open.
 *
 * [moveDestinations] resolves the payload once. The behaviour that has to survive the change is
 * [itemsDropAcceptance], which `acceptanceFor` and the sheet now share.
 */
class MoveSheetDestinationsTest {

    private val work = Folder(UUID.randomUUID(), "Work")
    private val bank = Folder(UUID.randomUUID(), "Bank", parentId = work.id)
    private val personal = Folder(UUID.randomUUID(), "Personal")

    private fun item(folderId: UUID?) = VaultItem(
        id = UUID.randomUUID(),
        title = "item",
        folderId = folderId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "u", password = "p"),
    )

    // ---- the vault is read once, not once per folder ----

    @Test
    fun `the dragged items are resolved once however many folders are offered`() {
        val many = List(200) { Folder(UUID.randomUUID(), "Folder $it") }
        val moving = item(folderId = many.first().id)
        var resolutions = 0

        val destinations = moveDestinations(
            folders = many,
            payload = ItemsDragPayload(setOf(moving.id)),
            resolveItems = { resolutions++; listOf(moving) },
            acceptanceFor = { _, _ -> error("an item payload must not ask the per-folder lookup") },
        )

        // FAILS WITHOUT THE FIX: one resolution per folder, 200 here.
        assertEquals(1, resolutions)
        assertEquals(200, destinations.size)
    }

    @Test
    fun `an item is offered every folder, its current one included, as before`() {
        val moving = item(folderId = work.id)

        val destinations = moveDestinations(
            folders = listOf(work, bank, personal),
            payload = ItemsDragPayload(setOf(moving.id)),
            resolveItems = { listOf(moving) },
            acceptanceFor = { _, _ -> error("unused") },
        )

        // The folder it is already in is NoOp, not Rejects, and has always been listed.
        assertEquals(listOf(work, bank, personal), destinations)
    }

    @Test
    fun `an item payload that resolves to nothing is offered nowhere`() {
        val destinations = moveDestinations(
            folders = listOf(work, personal),
            payload = ItemsDragPayload(setOf(UUID.randomUUID())),
            resolveItems = { emptyList() },
            acceptanceFor = { _, _ -> error("unused") },
        )

        assertEquals(emptyList(), destinations)
    }

    @Test
    fun `a folder payload still asks the tree rules for each destination`() {
        val asked = mutableListOf<UUID?>()

        val destinations = moveDestinations(
            folders = listOf(work, bank, personal),
            payload = FolderDragPayload(work.id),
            resolveItems = { error("a folder payload carries no items") },
            acceptanceFor = { _, target ->
                asked += target
                if (target == work.id || target == bank.id) LkDropAcceptance.Rejects else LkDropAcceptance.Accepts
            },
        )

        assertEquals(listOf(personal), destinations)
        assertEquals(listOf<UUID?>(work.id, bank.id, personal.id), asked)
    }

    // ---- itemsDropAcceptance, shared with acceptanceFor ----

    @Test
    fun `dropping items where they all already are is a silent no-op`() {
        val moving = listOf(item(work.id), item(work.id))

        assertEquals(LkDropAcceptance.NoOp, itemsDropAcceptance(moving, listOf(work), work.id))
    }

    @Test
    fun `dropping items on a folder this vault does not have is refused`() {
        assertEquals(
            LkDropAcceptance.Rejects,
            itemsDropAcceptance(listOf(item(null)), listOf(work), UUID.randomUUID()),
        )
    }

    @Test
    fun `dropping items somewhere they are not accepts, root included`() {
        val moving = listOf(item(work.id))

        assertEquals(LkDropAcceptance.Accepts, itemsDropAcceptance(moving, listOf(work, personal), personal.id))
        assertEquals(LkDropAcceptance.Accepts, itemsDropAcceptance(moving, listOf(work, personal), null))
    }

    @Test
    fun `an empty payload is refused everywhere`() {
        assertEquals(LkDropAcceptance.Rejects, itemsDropAcceptance(emptyList(), listOf(work), work.id))
        assertEquals(LkDropAcceptance.Rejects, itemsDropAcceptance(emptyList(), listOf(work), null))
    }

    // ---- the wiring ----

    @Test
    fun `the sheet takes its destinations from one call, not one acceptanceFor per folder`() {
        val screen = File("src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt")
        if (!screen.isFile) fail("cannot find ${screen.absolutePath} — this test reads the source it guards")
        val text = screen.readText()
        val start = text.indexOf("moveRequest?.let { payload ->")
        assertTrue(start >= 0, "VaultListScreen no longer opens the move sheet from moveRequest")
        val sheet = text.substring(start, text.indexOf("if (showTagDialog)", start))
        assertTrue(
            "moveDestinationsFor(payload)" in sheet && "remember(" in sheet,
            "the move sheet must resolve its destinations once per payload, in a remember, through " +
                "moveDestinationsFor. Found:\n$sheet",
        )
        assertFalse(
            "folders = state.folders.filter" in sheet,
            "the move sheet filters state.folders through acceptanceFor again: one whole-vault read " +
                "per folder, inside composition. Found:\n$sheet",
        )
    }
}
