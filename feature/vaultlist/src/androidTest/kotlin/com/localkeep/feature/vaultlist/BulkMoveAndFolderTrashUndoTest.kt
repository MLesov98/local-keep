package com.localkeep.feature.vaultlist

import android.content.Context
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.ForegroundVisibilityCoordinator
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Re-filing in bulk, and trashing a folder from the list, can be taken back from the notice.
 *
 * Found 2026-10-03: a bulk "Move to folder" said "2 items moved" and offered no Undo — the folders
 * the items came from were simply gone — and long-pressing a folder's "Move to trash" sent the whole
 * subtree with no confirmation and no Undo. A drag and a bulk trash already offered one; these two
 * were the exceptions.
 *
 * Fixture duplicated from [DragAcceptanceScopeTest], the precedent this module's tests already set.
 */
@RunWith(AndroidJUnit4::class)
class BulkMoveAndFolderTrashUndoTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var viewModel: VaultListViewModel

    /** The `foss` answer to every entitlement question: no, and nothing to sell. */
    private object FreeBuild : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: android.app.Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context))
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun aBulkMoveIsUndoneBackToWhereEachItemWas() {
        val work = seedFolder("Work", parent = null)
        val home = seedFolder("Home", parent = null)
        val archive = seedFolder("Archive", parent = null)
        val a = seedItem("A", work)
        val b = seedItem("B", home)
        newViewModel()
        awaitState("the folders to load") { viewModel.state.value.folders.size == 3 }
        clearNotice()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.toggleSelected(a)
            viewModel.toggleSelected(b)
            viewModel.bulkMoveToFolder(archive, "none", "Undo", "undone")
        }
        awaitVault("the move to land") { itemsInVault().filter { it.id in setOf(a, b) }.all { it.folderId == archive } }
        awaitState("the undo to be offered") { LkNotices.current.value?.onAction != null }

        // FAILS WITHOUT THE FIX: the notice carried no action at all.
        val undo = assertNotNull(LkNotices.current.value?.onAction)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { undo() }
        awaitVault("each item back in its own folder") {
            itemsInVault().single { it.id == a }.folderId == work && itemsInVault().single { it.id == b }.folderId == home
        }
    }

    @Test
    fun aFolderTrashedFromTheListIsUndoneBackOutOfTrash() {
        val work = seedFolder("Work", parent = null)
        val item = seedItem("Payroll", work)
        newViewModel()
        awaitState("the folders to load") { viewModel.state.value.folders.size == 1 }
        clearNotice()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.trashFolderFromDrag(work, { _, _ -> LkNotices.Message.Text("trashed") }, "Undo", "restored") {}
        }
        awaitVault("the folder to reach Trash") { unlockedVault().folders.single { it.id == work }.deletedAt != null }
        awaitState("the undo to be offered") { LkNotices.current.value?.onAction != null }

        val undo = assertNotNull(LkNotices.current.value?.onAction)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { undo() }
        awaitVault("the folder and its item back out of Trash") {
            unlockedVault().folders.single { it.id == work }.deletedAt == null &&
                itemsInVault().single { it.id == item }.deletedAt == null
        }
    }

    /**
     * Device pass 2026-10-04: two top-level items moved to "No folder" announced "2 items removed
     * from folder", and two items already in Work moved to Work announced "2 items moved". Nothing
     * had moved, and the notice that says so existed and was never reached.
     */
    @Test
    fun aBulkMoveThatMovesNothingSaysSo() {
        val work = seedFolder("Work", parent = null)
        val top = setOf(seedItem("Top one", null), seedItem("Top two", null))
        val filed = setOf(seedItem("Filed one", work), seedItem("Filed two", work))
        newViewModel()
        awaitState("the folders to load") { viewModel.state.value.folders.size == 1 }

        assertEquals(
            LkNotices.Message.Text(NOTHING_CHANGED),
            bulkMove(top, to = null),
            "top-level items moved to no folder were reported as removed from one",
        )
        assertEquals(
            LkNotices.Message.Text(NOTHING_CHANGED),
            bulkMove(filed, to = work),
            "items already in Work were reported as moved there",
        )
        // And nothing was written to them: an item nothing happened to keeps its timestamp.
        val untouched = itemsInVault().filter { it.id in top + filed }
        assertTrue(
            untouched.all { it.updatedAt == Instant.EPOCH },
            "a bulk move re-stamped items it did not move: ${untouched.map { it.title to it.updatedAt }}",
        )
    }

    /** The count is what moved, not what was selected. */
    @Test
    fun aPartialBulkMoveCountsOnlyWhatMoved() {
        val work = seedFolder("Work", parent = null)
        val alreadyThere = seedItem("Filed", work)
        val moving = seedItem("Top", null)
        newViewModel()
        awaitState("the folders to load") { viewModel.state.value.folders.size == 1 }

        assertEquals(
            LkNotices.Message.Plural(R.plurals.vaultlist_bulk_moved, 1),
            bulkMove(setOf(alreadyThere, moving), to = work),
        )
        awaitVault("the one real move to land") { itemsInVault().single { it.id == moving }.folderId == work }
    }

    /**
     * Device pass 2026-10-04: a two-item selection dragged out of the folder being browsed stays
     * selected, and the header read "2 selected. 2 hidden by filter" with no filter applied.
     */
    @Test
    fun aSelectionDraggedOutOfTheFolderIsNotBlamedOnAFilter() {
        val work = seedFolder("Work", parent = null)
        val home = seedFolder("Home", parent = null)
        val a = seedItem("A", work)
        val b = seedItem("B", work)
        newViewModel()
        awaitState("the folders to load") { viewModel.state.value.folders.size == 2 }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.openFolder(work) }
        awaitState("the folder's rows") { viewModel.state.value.items.map { it.id }.containsAll(listOf(a, b)) }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.toggleSelected(a)
            viewModel.toggleSelected(b)
            viewModel.commitDrag(ItemsDragPayload(setOf(a, b)), home, LkNotices.Message.Text("moved"), "Undo", "undone") {}
        }
        awaitState("the dragged rows to leave the folder, still selected") {
            viewModel.state.value.items.none { it.id == a || it.id == b } && viewModel.state.value.hiddenSelectedCount == 2
        }
        assertEquals(
            R.plurals.vaultlist_selection_not_in_list_subtitle,
            viewModel.state.value.hiddenSelectionSubtitle,
            "rows that left the folder were reported as hidden by a filter that is not on",
        )
    }

    private fun bulkMove(ids: Set<UUID>, to: UUID?): LkNotices.Message? {
        clearNotice()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ids.forEach(viewModel::toggleSelected)
            viewModel.bulkMoveToFolder(to, NOTHING_CHANGED, "Undo", "undone")
        }
        awaitState("the move to report") { LkNotices.current.value != null }
        return LkNotices.current.value?.message
    }

    private fun clearNotice() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        LkNotices.current.value?.let(LkNotices::dismiss)
    }

    private fun newViewModel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = VaultListViewModel(
                repository,
                lockManager,
                FreeBuild,
                // Inert: this suite tests drag acceptance, not the foreground auto-lock timer. With
                // `nextExpiry` always null the coordinator never schedules a thing, so the
                // acquire/release that onEnteredList/onLeftList drive are no-ops and cannot perturb
                // the assertions below. Matches the production shape in AppModule, minus the vault-
                // backed expiry it has no reason to read here.
                ForegroundVisibilityCoordinator(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    clock = Clock.systemUTC(),
                    nextExpiry = { null },
                    onExpiry = {},
                ),
            )
            // The init loads by collecting the lock state, which is already Unlocked here; this
            // makes the first read explicit rather than depending on when that collector is
            // scheduled relative to the assertions below.
            viewModel.retryLoad()
            viewModel.onEnteredList()
        }
    }

    private fun seedFolder(name: String, parent: UUID?): UUID {
        val id = UUID.randomUUID()
        runBlocking {
            repository.mutate {
                it.folders.add(Folder(id, name, parentId = parent, updatedAt = Instant.EPOCH))
            }
        }
        return id
    }

    private fun seedItem(title: String, folderId: UUID?): UUID {
        val id = UUID.randomUUID()
        runBlocking {
            repository.addItem(
                VaultItem(
                    id = id,
                    title = title,
                    content = Login(username = "ada@example.com"),
                    folderId = folderId,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
        return id
    }

    private fun awaitState(what: String, predicate: () -> Boolean) =
        await("$what (state was ${viewModel.state.value.items.map { it.title }}, error=${viewModel.state.value.error})", predicate)

    private fun awaitVault(what: String, predicate: () -> Boolean) = await(what, predicate)

    /** `viewModelScope` runs on Main.immediate and this test does not, so every write is async. */
    private fun await(what: String, predicate: () -> Boolean) = runBlocking {
        val landed = withTimeoutOrNull(TIMEOUT_MS) {
            while (!predicate()) delay(POLL_MS)
            true
        }
        kotlin.test.assertTrue(landed == true, "timed out waiting for $what")
    }

    private fun unlockedVault() = (lockManager.lockState.value as LockState.Unlocked).vault

    private fun itemsInVault() = unlockedVault().items

    private fun password() = "correct horse battery staple".toCharArray()

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, KdfParams.FLOOR)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = KdfParams.FLOOR,
                    wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
                    commitment = EnvelopeCrypto.slotCommitment(dek),
                ),
            )
        } finally {
            Zeroizer.wipe(password)
            Zeroizer.wipe(dek)
            Zeroizer.wipe(kek)
        }
    }

    private fun cleanupFiles() {
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val NOTHING_CHANGED = "nothing changed"
    }
}
