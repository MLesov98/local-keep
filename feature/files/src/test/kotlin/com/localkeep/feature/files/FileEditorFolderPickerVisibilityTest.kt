package com.localkeep.feature.files

import androidx.lifecycle.SavedStateHandle
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultRepository
import com.localkeep.core.vault.VaultVisibility
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file editor's "Change folder" picker must list what the vault list lists, and count the way
 * it counts.
 *
 * It read the raw vault, so a folder under an active hidden lease was offered by name (device-proven:
 * "Personal (3)" and its subfolder, while Personal was hidden from the list), the count included
 * concealed items, and only direct children were counted — a folder whose items all sat in a
 * subfolder read "(0)". The item editor fixed the same three things on 2026-10-03; this is the
 * file editor's half. The vault is projected by the real `VaultVisibility.project` rather than a
 * hand-built snapshot, so the test cannot agree with a fake about what "invisible" means.
 *
 * Control: the visible folders and their live counts must stay exactly what they were.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileEditorFolderPickerVisibilityTest {

    private val dispatcher = StandardTestDispatcher()
    private val now: Instant = Instant.parse("2026-10-17T09:00:00Z")
    private val lease: Instant = now.plus(1, ChronoUnit.HOURS)

    private fun folder(name: String, parentId: UUID? = null, hiddenUntil: Instant? = null) = Folder(
        id = UUID.randomUUID(),
        name = name,
        parentId = parentId,
        updatedAt = now,
        hiddenUntil = hiddenUntil,
    )

    private fun item(
        title: String,
        folderId: UUID?,
        hiddenUntil: Instant? = null,
        trashed: Boolean = false,
    ) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        folderId = folderId,
        createdAt = now,
        updatedAt = now,
        deletedAt = if (trashed) now else null,
        hiddenUntil = hiddenUntil,
        content = Note(body = "x"),
    )

    // Work > Banking > Statements, with items at two levels; Travel is a plain visible folder;
    // Personal is under a hidden lease and has a subfolder with items of its own.
    private val work = folder("Work")
    private val banking = folder("Banking", parentId = work.id)
    private val statements = folder("Statements", parentId = banking.id)
    private val travel = folder("Travel")
    private val personal = folder("Personal", hiddenUntil = lease)
    private val taxes = folder("Taxes", parentId = personal.id)

    private val vault = Vault(
        folders = listOf(work, banking, statements, travel, personal, taxes),
        items = listOf(
            item("Statement 1", statements.id),
            item("Statement 2", statements.id),
            item("Bank note", banking.id),
            // Two levels below Work, directly hidden: must not be counted anywhere.
            item("Hidden statement", statements.id, hiddenUntil = lease),
            item("Trip", travel.id),
            item("Old trip", travel.id, trashed = true),
            item("Tax return", taxes.id),
            item("Passport", personal.id),
        ),
        createdAt = now,
        modifiedAt = now,
    )

    private lateinit var repository: VaultRepository
    private lateinit var lockManager: VaultLockManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mock()
        lockManager = mock()
        whenever(repository.presentationState).thenReturn(MutableStateFlow(VaultPresentationState.Locked))
        whenever(repository.visibilitySnapshot()).thenAnswer {
            VaultVisibility.project(vault, now = now, revealSessionActive = false)
        }
        whenever(lockManager.lockState).thenReturn(
            MutableStateFlow<LockState>(LockState.Unlocked(dek = ByteArray(32), vault = vault)),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): FileItemEditViewModel {
        val entitlement = mock<Entitlement>()
        whenever(entitlement.isPro).thenReturn(MutableStateFlow(false))
        return FileItemEditViewModel(
            repository = repository,
            lockManager = lockManager,
            photoPickerImport = mock(),
            fileBlobStore = mock(),
            entitlement = entitlement,
            savedStateHandle = SavedStateHandle(),
        )
    }

    @Test
    fun `the picker offers no invisible folder and counts by whole subtree`() = runTest(dispatcher) {
        val viewModel = buildViewModel()
        advanceUntilIdle()
        val state = viewModel.state.value

        val offered = state.folders.map { it.name }.toSet()
        assertFalse("Personal" in offered, "a folder under an active hidden lease is not offered: $offered")
        assertFalse("Taxes" in offered, "nor is a subfolder of one: $offered")

        // Control: every visible folder is still there.
        assertEquals(setOf("Work", "Banking", "Statements", "Travel"), offered)

        val counts = state.folderItemCounts
        // Statements holds two visible items (the third is concealed); Banking adds its own note;
        // Work holds nothing directly and reads the whole subtree.
        assertEquals(2, counts[statements.id] ?: 0, "concealed item must not be counted")
        assertEquals(3, counts[banking.id] ?: 0, "Banking = its note + the Statements subtree")
        assertEquals(3, counts[work.id] ?: 0, "a folder whose items all sit in subfolders is not 0")
        // Control: a plain visible folder keeps its live count, trashed item excluded.
        assertEquals(1, counts[travel.id] ?: 0)
        assertFalse(personal.id in counts, "no count leaks for an invisible folder")
        assertFalse(taxes.id in counts, "no count leaks for an invisible subfolder")
    }
}
