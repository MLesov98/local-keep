package com.localkeep.feature.vaultlist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.ForegroundVisibilityCoordinator
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.HideOutcome
import com.localkeep.core.vault.RevealAttemptOutcome
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * The make-invisible feature, exercised at the vault-list surface: [VaultListViewModel] and
 * [TrashViewModel] driven against a real [VaultRepository] and [VaultLockManager] on device, the
 * same shape as `HiddenRepositoryInstrumentedTest` (core/vault) and `ItemEditVisibilityInstrumentedTest`
 * (itemedit) — a ViewModel test, not a Compose UI test, because what is under test is the projection
 * reaching `state`, not a rendered row.
 *
 * [trashExcludesConcealedItemsAndFolders] is the regression net for a real shipped bug:
 * `TrashViewModel.refresh` used to read `repository.getTrashItems()`/`getTrashedFolders()`, the raw
 * accessors, rather than `repository.visibilitySnapshot()` — and for as long as it did, hiding an
 * item and then trashing it put it *back* on screen, on the one screen with a "Delete forever"
 * button beside it. [aFoldersItemCountExcludesConcealedMembers] pins the companion bug: a folder
 * holding nothing but a concealed item used to read its true count in the strip's badge, which is an
 * oracle for "something is hidden here" on a feature whose whole point is to say nothing.
 */
@RunWith(AndroidJUnit4::class)
class VaultListHiddenVisibilityInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var vaultListVm: VaultListViewModel
    private lateinit var trashVm: TrashViewModel

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
        Unit
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    // ── 1. a directly-hidden item ────────────────────────────────────────────────────────────

    @Test
    fun aDirectlyHiddenItemIsAbsentAndReappearsAfterStopHiding() = runBlocking {
        val visible = seedLogin("Cafe wifi")
        val hidden = seedLogin("Offshore bank")
        assertEquals(
            HideOutcome.Hidden,
            repository.hide(HiddenTarget.Item(hidden), until = future(), firstPhrase = PHRASE.toCharArray()),
        )

        newVaultListViewModel()
        awaitVaultList("the vault to load") { vaultListVm.state.value.items.any { it.id == visible } }

        assertFalse(
            vaultListVm.state.value.items.any { it.id == hidden },
            "a directly hidden item must be absent from the vault list",
        )

        repository.stopHiding(HiddenTarget.Item(hidden))
        awaitVaultList("stopHiding to restore visibility") {
            vaultListVm.state.value.items.any { it.id == hidden }
        }
        Unit
    }

    // ── 2. an item inside a hidden folder, and the folder itself ────────────────────────────────

    @Test
    fun anItemInsideAHiddenFolderIsAbsentAndSoIsTheFolder() = runBlocking {
        val visibleFolder = seedFolder("Personal")
        val hiddenFolder = seedFolder("Archive")
        val itemInHiddenFolder = seedLogin("Old password", folderId = hiddenFolder)
        seedLogin("Wifi", folderId = visibleFolder)
        assertEquals(
            HideOutcome.Hidden,
            repository.hide(HiddenTarget.Folder(hiddenFolder), until = future(), firstPhrase = PHRASE.toCharArray()),
        )

        newVaultListViewModel()
        awaitVaultList("folders to load") { vaultListVm.state.value.folders.any { it.id == visibleFolder } }

        assertFalse(
            vaultListVm.state.value.folders.any { it.id == hiddenFolder },
            "the hidden folder itself must be absent from the folder strip",
        )

        // Browsed directly by id — reachable even though the folder is off the strip — to prove the
        // concealment holds for the items inside it too, not merely for the folder row.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vaultListVm.openFolderPath(listOf(hiddenFolder)) }
        awaitVaultList("the browse path to move into the hidden folder") {
            vaultListVm.state.value.folderStack == listOf(hiddenFolder)
        }
        assertFalse(
            vaultListVm.state.value.items.any { it.id == itemInHiddenFolder },
            "an item inside a hidden folder must be absent even when browsed to directly",
        )
        Unit
    }

    // ── 3. a folder's item count excludes concealed members ─────────────────────────────────────

    @Test
    fun aFoldersItemCountExcludesConcealedMembers() = runBlocking {
        val folder = seedFolder("Shared")
        seedLogin("Wifi", folderId = folder)
        val hiddenItem = seedLogin("Offshore bank", folderId = folder)
        assertEquals(
            HideOutcome.Hidden,
            repository.hide(HiddenTarget.Item(hiddenItem), until = future(), firstPhrase = PHRASE.toCharArray()),
        )

        newVaultListViewModel()
        awaitVaultList("folder counts to load") { vaultListVm.state.value.folderItemCounts.containsKey(folder) }

        assertEquals(
            1,
            vaultListVm.state.value.folderItemCounts[folder],
            "the concealed member must not inflate the folder's item-count badge",
        )
        Unit
    }

    // ── 4. trash omits concealed items and folders ───────────────────────────────────────────────

    @Test
    fun trashExcludesConcealedItemsAndFolders() = runBlocking {
        val visibleTrashedItem = seedLogin("Household bill")
        val hiddenTrashedItem = seedLogin("Offshore bank")
        val visibleTrashedFolder = seedFolder("Old project")
        val hiddenTrashedFolder = seedFolder("Archive")

        assertEquals(
            HideOutcome.Hidden,
            repository.hide(HiddenTarget.Item(hiddenTrashedItem), until = future(), firstPhrase = PHRASE.toCharArray()),
        )
        assertEquals(HideOutcome.Hidden, repository.hide(HiddenTarget.Folder(hiddenTrashedFolder), until = future()))

        repository.moveToTrash(visibleTrashedItem)
        repository.moveToTrash(hiddenTrashedItem)
        repository.trashFolder(visibleTrashedFolder)
        repository.trashFolder(hiddenTrashedFolder)

        newTrashViewModel()
        awaitTrash("the trash to load") { trashVm.state.value.items.any { it.id == visibleTrashedItem } }

        assertFalse(
            trashVm.state.value.items.any { it.id == hiddenTrashedItem },
            "a concealed trashed item must be absent from TrashUiState.items",
        )
        assertTrue(trashVm.state.value.items.any { it.id == visibleTrashedItem }, "the visible trashed item is unaffected")

        assertFalse(
            trashVm.state.value.folders.any { it.folder.id == hiddenTrashedFolder },
            "a concealed trashed folder must be absent from TrashUiState.folders",
        )
        assertTrue(
            trashVm.state.value.folders.any { it.folder.id == visibleTrashedFolder },
            "the visible trashed folder is unaffected",
        )
        Unit
    }

    // ── 5. trash search does not surface a concealed trashed item ───────────────────────────────

    @Test
    fun trashSearchDoesNotSurfaceAConcealedTrashedItem() = runBlocking {
        val visibleTitle = "Household bill"
        val hiddenTitle = "Offshore vault secret"
        val visibleTrashedItem = seedLogin(visibleTitle)
        val hiddenTrashedItem = seedLogin(hiddenTitle)
        assertEquals(
            HideOutcome.Hidden,
            repository.hide(HiddenTarget.Item(hiddenTrashedItem), until = future(), firstPhrase = PHRASE.toCharArray()),
        )
        repository.moveToTrash(visibleTrashedItem)
        repository.moveToTrash(hiddenTrashedItem)

        newTrashViewModel()
        awaitTrash("the trash to load") { trashVm.state.value.items.any { it.id == visibleTrashedItem } }

        InstrumentationRegistry.getInstrumentation().runOnMainSync { trashVm.onSearchChanged(hiddenTitle) }
        // Wait for the *visible* row to stop matching, not for `searchQuery` to hold the new text.
        // `onSearchChanged` writes the query synchronously and only then starts a 250 ms debounce,
        // so a predicate on the query is already true on its first evaluation and the assertion
        // below used to run before any search had happened — reading the unfiltered list and
        // failing on a concealment guard that works.
        //
        // Deliberately not `items.isEmpty()`, which is what the assertion itself claims: waiting on
        // that would make this test self-fulfilling, and a real leak would then surface as "timed
        // out waiting for …" — infrastructure noise no reader would chase — instead of naming it.
        awaitTrash("the debounced search to run") {
            trashVm.state.value.items.none { it.id == visibleTrashedItem }
        }
        assertTrue(
            trashVm.state.value.items.isEmpty(),
            "searching the concealed trashed item's own title must return nothing",
        )

        // The control: the same search path, for a title that is not concealed, must still work —
        // otherwise the assertion above would pass for the wrong reason (a broken search box).
        InstrumentationRegistry.getInstrumentation().runOnMainSync { trashVm.onSearchChanged(visibleTitle) }
        awaitTrash("the search for the visible title to find it") {
            trashVm.state.value.items.any { it.id == visibleTrashedItem }
        }
        Unit
    }

    // ── 6. a successful reveal shows concealed records, still marked ────────────────────────────

    @Test
    fun revealingTheSessionShowsConcealedRecordsMarkedInTheListAndTrash() = runBlocking {
        val hiddenFolder = seedFolder("Archive")
        val itemInHiddenFolder = seedLogin("Old password", folderId = hiddenFolder)
        val directlyHiddenItem = seedLogin("Offshore bank")
        val hiddenTrashedItem = seedLogin("Secret invoice")

        assertEquals(
            HideOutcome.Hidden,
            repository.hide(HiddenTarget.Folder(hiddenFolder), until = future(), firstPhrase = PHRASE.toCharArray()),
        )
        assertEquals(HideOutcome.Hidden, repository.hide(HiddenTarget.Item(directlyHiddenItem), until = future()))
        assertEquals(HideOutcome.Hidden, repository.hide(HiddenTarget.Item(hiddenTrashedItem), until = future()))
        repository.moveToTrash(hiddenTrashedItem)

        newVaultListViewModel()
        newTrashViewModel()
        awaitVaultList("the concealed folder id to be known") { hiddenFolder in vaultListVm.state.value.concealedFolderIds }
        awaitTrash("the trash to load") { trashVm.state.value.totalCount == 0 }
        // `concealedFolderIds` above is written by the folder pass, so it says nothing about
        // whether the item pass has run. Without this the item assertion below could pass while
        // `items` was still empty for no reason at all — a control that cannot fail is not one.
        awaitVaultList("the first item scan to publish") { vaultListVm.state.value.projectionVersion > 0L }

        // Pre-reveal control: everything above is concealed, so none of it shows yet.
        assertFalse(vaultListVm.state.value.folders.any { it.id == hiddenFolder })
        assertFalse(vaultListVm.state.value.items.any { it.id == directlyHiddenItem })
        assertTrue(trashVm.state.value.items.isEmpty())

        // Captured before the reveal, because the item list is published by a different producer
        // than the folder list and lands later. `loadFolders` writes folders synchronously in the
        // collector; `refresh` scans on Dispatchers.Default and only then publishes items, bumping
        // `projectionVersion`. Awaiting the folder and asserting on the item therefore raced — it
        // passed or failed depending on how fast the background scan happened to be, which is why
        // this case read as an intermittent flake rather than as the wait-on-the-wrong-signal
        // mistake it is.
        val versionBeforeReveal = vaultListVm.state.value.projectionVersion

        val generation = lockManager.nextRevealAttemptGeneration()
        assertEquals(RevealAttemptOutcome.Revealed, lockManager.attemptHiddenReveal(PHRASE.toCharArray(), generation))

        awaitVaultList("the reveal session to show the hidden folder") {
            vaultListVm.state.value.folders.any { it.id == hiddenFolder }
        }
        awaitVaultList("the item scan that follows the reveal to publish") {
            vaultListVm.state.value.projectionVersion > versionBeforeReveal
        }
        assertTrue(
            vaultListVm.state.value.concealedFolderIds.contains(hiddenFolder),
            "a revealed folder still carries the marker",
        )
        assertTrue(
            vaultListVm.state.value.items.any { it.id == directlyHiddenItem },
            "a revealed direct item is shown at the root",
        )
        assertTrue(
            vaultListVm.state.value.concealedItemIds.contains(directlyHiddenItem),
            "a revealed direct item still carries the marker",
        )
        assertTrue(
            vaultListVm.state.value.concealedItemIds.contains(itemInHiddenFolder),
            "an ancestry-concealed item carries the marker too, even though it has no lease of its own",
        )

        awaitTrash("the reveal session to show the hidden trashed item") {
            trashVm.state.value.items.any { it.id == hiddenTrashedItem }
        }
        Unit
    }

    // ── 7. ending a reveal session that no longer shows anything ────────────────────────────────

    /**
     * Device pass 2026-10-04: reveal with the phrase, make the only invisible entry visible again
     * from Settings › Invisible items, and the vault list kept the reveal banner — with "Stop
     * showing" doing nothing, three taps running. The session *was* ended; the list never heard,
     * because it read the reveal flag only when the projection changed, and with nothing concealed
     * a revealed projection and a concealed one are equal. The two-minute expiry ends a session
     * the same way, so it left the same banner standing.
     */
    @Test
    fun stopShowingTakesTheBannerDownWhenNothingIsConcealedAnyMore() = runBlocking {
        val item = seedLogin("Offshore bank")
        assertEquals(
            HideOutcome.Hidden,
            repository.hide(HiddenTarget.Item(item), until = future(), firstPhrase = PHRASE.toCharArray()),
        )
        newVaultListViewModel()
        awaitVaultList("the first item scan to publish") { vaultListVm.state.value.projectionVersion > 0L }

        val generation = lockManager.nextRevealAttemptGeneration()
        assertEquals(RevealAttemptOutcome.Revealed, lockManager.attemptHiddenReveal(PHRASE.toCharArray(), generation))
        awaitVaultList("the reveal banner") { vaultListVm.state.value.revealSessionActive }

        // Settings › Invisible items › Make visible, on the only invisible entry.
        repository.stopHiding(HiddenTarget.Item(item))
        awaitVaultList("nothing to be concealed any more") { vaultListVm.state.value.concealedItemIds.isEmpty() }

        InstrumentationRegistry.getInstrumentation().runOnMainSync { vaultListVm.hideAgainNow() }
        // The control: the session itself did end, so a banner still up below is the list's fault.
        assertFalse(lockManager.revealSession.revealed.value, "the lock manager did not end the session")
        awaitVaultList("Stop showing to take the banner down") { !vaultListVm.state.value.revealSessionActive }
        Unit
    }

    // ── harness ──────────────────────────────────────────────────────────────

    /** The `foss` answer to every entitlement question: no, and nothing to sell. */
    private object FreeBuild : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: android.app.Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }

    /**
     * Constructed on the main thread, because its `init` loads through `viewModelScope`, which
     * dispatches on `Main.immediate` — an instrumentation test does not run there, so a ViewModel
     * built off the main thread never gets its first load and every wait below times out.
     */
    private fun newVaultListViewModel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vaultListVm = VaultListViewModel(
                repository,
                lockManager,
                FreeBuild,
                // Inert: this suite does not exercise the foreground auto-lock timer. With
                // `nextExpiry` always null the coordinator never schedules anything, so the
                // acquire/release the ViewModel drives are no-ops.
                ForegroundVisibilityCoordinator(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    clock = Clock.systemUTC(),
                    nextExpiry = { null },
                    onExpiry = {},
                ),
            )
            vaultListVm.retryLoad()
            vaultListVm.onEnteredList()
        }
    }

    private fun newTrashViewModel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            trashVm = TrashViewModel(repository, lockManager, FileBlobStore(context))
        }
    }

    private fun seedFolder(name: String, parent: UUID? = null): UUID {
        val id = UUID.randomUUID()
        runBlocking {
            repository.mutate {
                it.folders.add(Folder(id, name, parentId = parent, updatedAt = Instant.now()))
            }
        }
        return id
    }

    private fun seedLogin(title: String, folderId: UUID? = null): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        runBlocking {
            repository.addItem(
                VaultItem(
                    id = id,
                    title = title,
                    createdAt = now,
                    updatedAt = now,
                    folderId = folderId,
                    content = Login(username = "me@example", password = "hunter2"),
                ),
            )
        }
        return id
    }

    private fun future(): Instant = Instant.now().plusSeconds(3600)

    private fun awaitVaultList(what: String, predicate: () -> Boolean) =
        await("$what (items were ${vaultListVm.state.value.items.map { it.title }}, error=${vaultListVm.state.value.error})", predicate)

    private fun awaitTrash(what: String, predicate: () -> Boolean) =
        await("$what (items were ${trashVm.state.value.items.map { it.title }}, error=${trashVm.state.value.error})", predicate)

    /** `viewModelScope` runs on Main.immediate and this test does not, so every write is async. */
    private fun await(what: String, predicate: () -> Boolean) = runBlocking {
        val landed = withTimeoutOrNull(TIMEOUT_MS) {
            while (!predicate()) delay(POLL_MS)
            true
        }
        assertTrue(landed == true, "timed out waiting for $what")
    }

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
        const val PHRASE = "open sesame"
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
    }
}
