package com.localkeep.feature.vaultlist

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.billing.PurchaseOutcome
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Card
import com.localkeep.core.model.Folder
import com.localkeep.core.model.HiddenLease
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.HideOutcome
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Folder management, read back through the vault rather than off the ViewModel's own state.
 *
 * The distinction is the point. A rename that only updated a `StateFlow` would satisfy any test of
 * this class's public surface and lose the name on the next unlock, and the mutations here are new
 * call sites on a model whose existing ones lived inside the item editor. So every assertion goes
 * through `repository`, which encrypts, writes, and decrypts on the way back.
 *
 * The delete case carries the most weight: removing a folder must **unfile** its items, never
 * remove them. That is the one thing a user has to be able to trust about a destructive button on a
 * container, and it is asserted by counting the items that survive it.
 */
@RunWith(AndroidJUnit4::class)
class FolderViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var viewModel: FolderViewModel

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
    fun creatingAFolderReachesTheVault() {
        newViewModel()
        viewModel.onNewNameChanged("Work")
        viewModel.createFolder()
        // Waits on the field clearing, not on the write landing — the same correction
        // `renamingSurvivesAVaultRoundTrip` above already carries, for the same reason. The write
        // lands first and `runMutation` reloads before it clears the field, so a predicate watching
        // the vault returns during the gap and the assertion below reads the pre-clear value. It
        // passed only because the reload used to be cheap; routing `load()` through
        // `visibilitySnapshot()` widened the gap enough to make the latent race fire every time.
        awaitVault("the new-folder field to clear") { viewModel.state.value.newName.isEmpty() }

        assertEquals(listOf("Work"), foldersInVault().map { it.name })
        assertEquals("", viewModel.state.value.newName, "the field clears once the write lands")
    }

    /**
     * 2026-10-12: the card closed after every create, so twenty folders were twenty re-openings.
     * It stays open, empty, under the parent it was given — and Cancel is still the way out.
     */
    @Test
    fun creatingFoldersBackToBackNeverClosesTheCard() {
        val parent = seedFolder("Parent")
        newViewModel()
        awaitVault("the folders to load") { viewModel.state.value.folders.size == 1 }
        viewModel.setNewParent(parent)
        viewModel.showNewFolderInput()

        for (name in listOf("One", "Two", "Three")) {
            viewModel.onNewNameChanged(name)
            viewModel.createFolder()
            awaitVault("$name to land and the field to clear") {
                viewModel.state.value.newName.isEmpty() && foldersInVault().any { it.name == name }
            }
            assertTrue(viewModel.state.value.showNewFolder, "the card closed after $name")
            assertEquals(parent, viewModel.state.value.newParentId, "the parent was forgotten after $name")
        }
        assertEquals(setOf("Parent", "One", "Two", "Three"), foldersInVault().map { it.name }.toSet())
        assertTrue(foldersInVault().filter { it.name != "Parent" }.all { it.parentId == parent })

        viewModel.hideNewFolderInput()
        assertTrue(!viewModel.state.value.showNewFolder, "Cancel must still close it")
    }

    @Test
    fun aDuplicateNameIsRefusedRatherThanMerged() {
        // Two folders called "Work" are indistinguishable on the chip strip, the row marker and the
        // picker — so the folder an item was filed into would become unknowable.
        seedFolder("Work")
        newViewModel()
        viewModel.onNewNameChanged("work")
        viewModel.createFolder()
        awaitVault("the refusal to surface") { viewModel.state.value.error != null }

        assertEquals(1, foldersInVault().size)
        assertEquals(R.string.vaultlist_folders_error_name_exists, viewModel.state.value.error)
    }

    /**
     * QA 2026-10-03: a name held by a folder in Trash — which keeps it, so a restore cannot make two
     * — was refused as "That didn't work. Nothing was changed." over a list showing no such folder.
     */
    @Test
    fun aNameHeldByAFolderInTrashSaysWhereItIs() {
        val trashed = seedFolder("Work")
        runBlocking { repository.trashFolder(trashed) }
        newViewModel()
        viewModel.onNewNameChanged("Work")
        viewModel.createFolder()
        awaitVault("the refusal to surface") { viewModel.state.value.error != null }

        assertEquals(R.string.vaultlist_folders_error_name_in_trash, viewModel.state.value.error)
        assertEquals(1, foldersInVault().size)
    }

    /**
     * QA 2026-10-03: moving a folder into one that already holds its name left the Move dialog open
     * over a list whose error line it covered, so the refusal read as a tap that did nothing.
     */
    @Test
    fun aRefusedMoveClosesTheDialogAndSaysWhy() {
        val target = seedFolder("Target")
        runBlocking { repository.createFolder("Dup", target) }
        val dup = seedFolder("Dup")
        newViewModel()
        awaitVault("the folders to load") { viewModel.state.value.folders.size == 3 }

        viewModel.startMoving(dup)
        viewModel.commitMove(target)
        awaitVault("the refusal to surface") { viewModel.state.value.error != null }

        assertEquals(R.string.vaultlist_folders_error_name_exists, viewModel.state.value.error)
        assertNull(viewModel.state.value.movingId, "the dialog closes so the error can be read")
        assertNull(foldersInVault().first { it.id == dup }.parentId, "and nothing moved")
    }

    @Test
    fun renamingSurvivesAVaultRoundTrip() {
        val id = seedFolder("Work")
        newViewModel()
        viewModel.startRenaming(id)
        viewModel.onRenameTextChanged("Employment")
        viewModel.commitRename()
        // Waits on the field closing, not on the write landing. The write is what the *predicate*
        // used to watch, and the field closes one continuation later — so the assertion below ran
        // in the gap and passed only because the emulator was slow enough to hide it. A real
        // physical phone failed it on the first run: "expected null, but was <29d9750e…>". Wait on the
        // thing being asserted; the vault assertion after this is then free.
        // Both, not either. The comment above says waiting on the write and asserting the field
        // raced; waiting on the field and asserting the write races the other way, because
        // `mutate(after = ::cancelRenaming)` closes the field once the mutation returns while the
        // vault this reads comes from the lock state, refreshed on its own schedule. CI caught the
        // mirror image of the original failure — "expected:<[Employment]> but was:<[Work]>". A
        // predicate over both is ordering-independent, which is the only version of this that
        // cannot come back.
        awaitVault("the rename to land and its field to close") {
            viewModel.state.value.renamingId == null &&
                foldersInVault().any { it.name == "Employment" }
        }

        assertEquals(listOf("Employment"), foldersInVault().map { it.name })
        assertNull(viewModel.state.value.renamingId, "the rename field closes once the write lands")
    }

    @Test
    fun renamingKeepsTheItemsFiledInIt() {
        // The id survives a rename, which is what keeps membership intact — worth asserting rather
        // than assuming, because the alternative implementation (delete plus recreate) would look
        // identical on screen and silently unfile everything.
        val id = seedFolder("Work")
        seedItem("Payroll", id)
        newViewModel()
        viewModel.startRenaming(id)
        viewModel.onRenameTextChanged("Employment")
        viewModel.commitRename()
        awaitVault("the rename to be written") { foldersInVault().any { it.name == "Employment" } }

        val item = itemsInVault().single { it.title == "Payroll" }
        assertEquals(id, item.folderId)
    }

    @Test
    fun deletingAFolderTrashesItWithItsContentsRatherThanUnfilingThem() {
        // What this asserted until 2026-08-18 — "the folder goes, the items stay, unfiled" — is the
        // behaviour the folder trash replaced. `deleteFolder` now redirects to `trashFolder`: the
        // subtree is marked, nothing is destroyed, and nothing is moved. The old assertion did not
        // merely go stale, it inverted: unfiling was the data-loss path the trash exists to end.
        val id = seedFolder("Work")
        seedItem("Payroll", id)
        seedItem("Loose", null)
        newViewModel()
        viewModel.askDelete(id)
        viewModel.confirmDelete()
        awaitVault("the folder to be trashed") { foldersInVault().any { it.deletedAt != null } }

        val folder = foldersInVault().single()
        assertEquals("Work", folder.name, "a trashed folder keeps its name — Trash labels the row with it")

        val payroll = itemsInVault().single { it.title == "Payroll" }
        assertTrue(payroll.isDeleted, "the folder's item went to the trash with it")
        assertEquals(id, payroll.folderId, "and stayed filed, so a restore puts it back where it was")
        assertEquals(id, payroll.trashedWithFolderId, "tagged with the folder that took it")

        val loose = itemsInVault().single { it.title == "Loose" }
        assertTrue(!loose.isDeleted, "an item outside the folder is untouched")
    }

    @Test
    fun theRowCountsWhatIsActuallyInTheFolder() {
        val id = seedFolder("Work")
        seedItem("Payroll", id)
        seedItem("Contract", id)
        seedItem("Loose", null)
        newViewModel()

        val row = viewModel.state.value.folders.single()
        assertEquals(2, row.itemCount)
    }

    /**
     * A hidden folder is absent from this screen — by name, by count, and from the move picker.
     *
     * This screen filtered on `deletedAt` alone while every other user-facing read went through
     * `VaultVisibility.project`, so Make-Invisible held everywhere except the one screen that lists
     * folders by name. The plan that introduced the feature had already named this ViewModel as a
     * bypass to remove rather than a precedent to copy; it was the one of the three that was left.
     *
     * Asserted on `state.folders` rather than on the vault, deliberately — the folder is *supposed*
     * to still be in the vault. What must not happen is it reaching a row, and the row is what the
     * screen renders, what feeds the subtree counts, and what the "Move to…" picker is built from.
     */
    @Test
    fun aHiddenFolderIsNeitherListedNorCounted() {
        val visible = seedFolder("Work")
        val hidden = seedFolder("Divorce")
        seedItem("Payroll", visible)
        seedItem("Settlement draft", hidden)
        // The phrase is required on the vault's first hide, and without it `hide` returns
        // `PhraseRequired` and changes nothing. Asserting the outcome is the control: the first
        // version of this test omitted the phrase, hid nothing, and would have reported the screen
        // as leaking whatever the fix did.
        val outcome = runBlocking {
            repository.hide(
                HiddenTarget.Folder(hidden),
                HiddenLease.INDEFINITE,
                firstPhrase = "reveal me please".toCharArray(),
            )
        }
        assertEquals(HideOutcome.Hidden, outcome, "control: the folder really was hidden")
        // Hiding leaves the reveal session open, so the user can see what they just did and undo it.
        // `VaultVisibility.project` conceals nothing while that session is live — correctly — so a
        // test that skipped this would assert against a fully revealed projection and pass whether
        // or not the screen reads through one at all.
        lockManager.concealHiddenSession()

        newViewModel()
        awaitVault("the folder rows to load") { viewModel.state.value.folders.isNotEmpty() }
        val rows = viewModel.state.value.folders

        assertEquals(
            listOf("Work"),
            rows.map { it.folder.name },
            "a hidden folder must not be listed by name on the one screen that names folders",
        )
        assertTrue(
            foldersInVault().any { it.id == hidden },
            "control: it is concealed, not deleted — the vault still holds it",
        )
        assertEquals(
            1,
            rows.single().itemCount,
            "and its item must not be counted into a folder the user can see",
        )
    }

    /**
     * 2026-10-02 (S5-04): Duplicate copied every Card under the folder with no allowance asked, so
     * a free vault's one card became two, then four. The refusal is named, and nothing is written.
     */
    @Test
    fun duplicatingAFolderWhoseCopyWouldPassTheFreeCardAllowanceIsRefused() {
        val work = seedFolder("Work")
        seedCard(work)
        newViewModel(pro = false)
        viewModel.duplicate(work) { name, n -> if (n == 1) "$name (copy)" else "$name (copy $n)" }
        awaitVault("the refusal to surface") { viewModel.state.value.error != null }

        assertEquals(R.string.vaultlist_folders_error_duplicate_allowance, viewModel.state.value.error)
        assertEquals(listOf("Work"), foldersInVault().map { it.name }, "nothing was copied")
        assertEquals(1, itemsInVault().count { it.content is Card })
    }

    /** The control: the same folder copies for Pro, so the refusal above is the allowance's. */
    @Test
    fun proDuplicatesTheSameFolder() {
        val work = seedFolder("Work")
        seedCard(work)
        newViewModel(pro = true)
        viewModel.duplicate(work) { name, n -> if (n == 1) "$name (copy)" else "$name (copy $n)" }
        awaitVault("the copy to land") { foldersInVault().size == 2 }

        assertNull(viewModel.state.value.error)
        assertEquals(2, itemsInVault().count { it.content is Card })
    }

    // ── harness ───────────────────────────────────────────────────────────

    /** Constructed after the fixture is seeded, because the ViewModel loads in its initialiser. */
    private fun newViewModel(pro: Boolean = false) {
        viewModel = FolderViewModel(repository, lockManager, FixedEntitlement(pro))
    }

    /** The `foss` posture by default — never Pro, nothing to buy — or entitled. */
    private class FixedEntitlement(pro: Boolean) : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(pro)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): PurchaseOutcome = PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }

    private fun seedCard(folderId: UUID) {
        runBlocking {
            repository.addItem(
                VaultItem(
                    id = UUID.randomUUID(),
                    title = "Debit",
                    content = Card(),
                    folderId = folderId,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
    }

    private fun seedFolder(name: String): UUID {
        val id = UUID.randomUUID()
        runBlocking { repository.mutate { it.folders.add(Folder(id, name, updatedAt = Instant.EPOCH)) } }
        return id
    }

    private fun seedItem(title: String, folderId: UUID?) {
        runBlocking {
            repository.addItem(
                VaultItem(
                    id = UUID.randomUUID(),
                    title = title,
                    content = Login(username = "ada@example.com"),
                    folderId = folderId,
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
    }

    /**
     * Waits for a mutation to actually reach the vault.
     *
     * `viewModelScope` dispatches on `Main.immediate`, and an instrumentation test does not run on
     * the main thread — so every mutation here is genuinely asynchronous and asserting straight
     * after the call reads the vault as it was before it. Polling a condition rather than sleeping a
     * fixed interval: a sleep long enough to be reliable is long enough to be the slowest test in
     * the module, and one short enough to be quick is a flake waiting for a slower machine.
     */
    private fun awaitVault(what: String, predicate: () -> Boolean) = runBlocking {
        val landed = withTimeoutOrNull(TIMEOUT_MS) {
            while (!predicate()) delay(POLL_MS)
            true
        }
        assertTrue(landed == true, "timed out waiting for $what")
    }

    private fun unlockedVault() = (lockManager.lockState.value as LockState.Unlocked).vault

    private fun foldersInVault() = unlockedVault().folders

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
