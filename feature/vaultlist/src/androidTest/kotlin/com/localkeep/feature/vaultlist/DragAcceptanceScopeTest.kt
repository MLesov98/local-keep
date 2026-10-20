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
import com.localkeep.core.ui.theme.LkDropAcceptance
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
 * A drag survives the navigation the drag itself causes.
 *
 * Spring-loading is the feature's whole point: rest on a folder and it opens **with the payload
 * still in hand**. That navigation replaces the vault list's visible items — and the acceptance
 * rules used to be computed against exactly that visible list, so one hop into a folder left the
 * dragged item out of scope and every target after it refused the drop. Nothing in the build said
 * so: a green build, 0 lint errors, and a feature that could not reach a second level.
 *
 * Driven on emulator-5554 on 2026-08-18, where a nested descent opened the first folder and then
 * rejected everything inside it. These pin the rule the fix restores: **what may be dropped where
 * is a question about the vault, not about what this screen happens to be listing.**
 */
@RunWith(AndroidJUnit4::class)
class DragAcceptanceScopeTest {

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
    fun anItemDraggedOutOfViewIsStillAcceptedByTheFolderUnderTheFinger() {
        val archive = seedFolder("Archive", parent = null)
        val inbox = seedFolder("Inbox", parent = archive)
        // Filed nowhere, so it is on the root list the drag would start from — the vault list shows
        // an item in the scope it belongs to, and this test needs it visible before the navigation.
        val payroll = seedItem("Payroll", null)
        newViewModel()
        awaitState("the vault to load") { viewModel.state.value.items.any { it.id == payroll } }

        // The spring-load, in the one respect this test cares about: the screen is now listing
        // Archive's contents, and the dragged item is not among them.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.openFolder(archive) }
        awaitState("the list to narrow to Archive") {
            viewModel.state.value.items.none { it.id == payroll }
        }

        // FAILS WITHOUT THE FIX with Rejects: `acceptanceFor` looked the payload up in
        // `state.items`, found nothing, and refused. A drag can never reach a nested folder if the
        // act of opening that folder is what makes the payload invalid.
        assertEquals(
            LkDropAcceptance.Accepts,
            viewModel.acceptanceFor(ItemsDragPayload(setOf(payroll)), inbox),
            "a folder one level down must still take the item the drag is carrying",
        )
    }

    @Test
    fun theUndoOfAMoveMadeAfterASpringLoadRestoresTheOriginalFolder() {
        // The same read backs the undo. With the payload out of scope, `previous` was an empty map,
        // so Undo ran, reported success, and put nothing back — the worst shape a destructive-ish
        // action can have, because the notice says it worked.
        val archive = seedFolder("Archive", parent = null)
        val payroll = seedItem("Payroll", null)
        newViewModel()
        awaitState("the vault to load") { viewModel.state.value.items.any { it.id == payroll } }

        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.openFolder(archive) }
        awaitState("the list to narrow to Archive") {
            viewModel.state.value.items.none { it.id == payroll }
        }

        LkNotices.current.value?.let(LkNotices::dismiss)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.commitDrag(
                payload = ItemsDragPayload(setOf(payroll)),
                targetFolderId = archive,
                movedNotice = LkNotices.Message.Text("moved"),
                undoLabel = "Undo",
                undoneNotice = "undone",
                onSettled = {},
            )
        }
        awaitVault("the move to land") { itemsInVault().single { it.id == payroll }.folderId == archive }
        awaitState("the undo to be offered") { LkNotices.current.value?.onAction != null }

        // Pressed the way the user presses it, through the notice the ViewModel posted. Calling
        // `setItemFolder(payroll, work)` here instead would assert only that the repository works —
        // the defect was in what `commitDrag` *remembered*, and a test that supplies the answer
        // itself cannot see it.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { LkNotices.current.value!!.onAction!!.invoke() }
        awaitVault("the undo to land") { itemsInVault().single { it.id == payroll }.folderId != archive }

        // FAILS WITHOUT THE FIX: `previous` was built from the visible list, which no longer held
        // the item, so the undo restored nothing and the item stayed in Archive.
        assertEquals(
            null,
            itemsInVault().single { it.id == payroll }.folderId,
            "undo must put the item back where it actually came from — here, no folder at all",
        )
    }

    @Test
    fun theListBackgroundRefusesToMoveSomethingThatIsAlreadyInThisSubtree() {
        // The reported defect, as the vault sees it. A folder list shows its whole subtree, so an
        // item living one level down has a row here; releasing over that row — or any empty space
        // beside it — asked `acceptanceFor(payload, browsedFolder)`, which sees a real change of
        // folder and accepts. Reported 2026-08-18: *"pasting it to the same position from where I
        // picked it moves it somewhere, I need to click undo"*. Reproduced on emulator-5554 the
        // same day: an item shown as "Bank, Login, in Alpha" was dropped back on its own row and
        // left Alpha.
        val archive = seedFolder("Archive", parent = null)
        val inbox = seedFolder("Inbox", parent = archive)
        val payroll = seedItem("Payroll", inbox)
        newViewModel()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.openFolder(archive) }
        awaitState("Archive's subtree to load") { viewModel.state.value.items.any { it.id == payroll } }

        // FAILS WITHOUT THE FIX with Accepts, and an accepted drop here is a write plus an undo
        // notice for a gesture that put something back where it was.
        assertEquals(
            LkDropAcceptance.NoOp,
            viewModel.backgroundAcceptanceFor(ItemsDragPayload(setOf(payroll))),
            "releasing over the list must not re-file an item that is already inside this folder",
        )
        assertEquals(
            inbox,
            itemsInVault().single { it.id == payroll }.folderId,
            "and the item must still be in Inbox",
        )
    }

    @Test
    fun theListBackgroundStillTakesSomethingCarriedInFromOutside() {
        // The control. Without this, deleting the whole target would "pass" the test above — a
        // background that accepts nothing is not the fix, it is a second bug.
        val archive = seedFolder("Archive", parent = null)
        val elsewhere = seedFolder("Elsewhere", parent = null)
        val payroll = seedItem("Payroll", elsewhere)
        newViewModel()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.openFolder(archive) }
        awaitState("Archive to open") { viewModel.state.value.folderStack.lastOrNull() == archive }

        assertEquals(
            LkDropAcceptance.Accepts,
            viewModel.backgroundAcceptanceFor(ItemsDragPayload(setOf(payroll))),
            "an item from another folder is a real move and must still be accepted",
        )
    }

    @Test
    fun theListBackgroundRefusesAFolderThatAlreadyLivesUnderThisOne() {
        // Same rule for the other payload type. A subfolder's row is on this screen too, and
        // dropping it back where it sits would re-parent it to the folder it is already under —
        // silently, for a grandchild.
        val archive = seedFolder("Archive", parent = null)
        val inbox = seedFolder("Inbox", parent = archive)
        val deep = seedFolder("Deep", parent = inbox)
        newViewModel()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.openFolder(archive) }
        awaitState("Archive to open") { viewModel.state.value.folderStack.lastOrNull() == archive }

        assertEquals(
            LkDropAcceptance.NoOp,
            viewModel.backgroundAcceptanceFor(FolderDragPayload(inbox)),
            "a direct child dropped on the background is already where it would land",
        )
        assertEquals(
            LkDropAcceptance.NoOp,
            viewModel.backgroundAcceptanceFor(FolderDragPayload(deep)),
            "and a grandchild is inside this subtree just as much as a child is",
        )
    }

    // ── harness ──────────────────────────────────────────────────────────────

    /**
     * Constructed on the main thread, because its `init` loads through `viewModelScope`.
     *
     * That scope dispatches on `Main.immediate`; an instrumentation test does not run there, so a
     * ViewModel built off the main thread never gets its first load and every wait here times out.
     */
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
    }
}
