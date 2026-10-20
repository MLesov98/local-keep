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
 * A folder write the vault refuses says so on screen.
 *
 * Testing on 2026-10-03 found two ways a refusal left nothing behind. The add sheet's New folder screen
 * left before the write landed, so a name held by a folder in Trash, or a ninth level, closed it and
 * created nothing. And a move from the "Move to folder…" sheet into a folder already holding that
 * name set the list's inline error, which the refresh straight after it cleared before it drew.
 *
 * Fixture duplicated from [DragAcceptanceScopeTest], the precedent this module's tests already set.
 */
@RunWith(AndroidJUnit4::class)
class RefusedFolderWriteIsReportedTest {

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
    fun aRefusedNewFolderStaysAndSaysWhy() {
        val trashed = seedFolder("Work", parent = null)
        runBlocking { repository.trashFolder(trashed) }
        newViewModel()
        var left = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LkNotices.dismiss(LkNotices.current.value ?: return@runOnMainSync)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.createFolder("Work", null) { left = true }
        }
        awaitState("the refusal notice") { LkNotices.current.value != null }

        assertEquals(LkNotices.Message.Res(R.string.vaultlist_folders_error_name_in_trash), LkNotices.current.value?.message)
        assertFalse(left, "the screen leaves only once the folder exists")
    }

    @Test
    fun aCreatedNewFolderLeaves() {
        newViewModel()
        var left = false

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.createFolder("Work", null) { left = true }
        }
        awaitState("the screen to leave") { left }

        assertTrue(unlockedVault().folders.any { it.name == "Work" })
    }

    @Test
    fun aMoveRefusedForItsNameSaysWhy() {
        val target = seedFolder("Target", parent = null)
        seedFolder("Dup", parent = target)
        val dup = seedFolder("Dup", parent = null)
        newViewModel()
        awaitState("the folders to load") { viewModel.state.value.folders.size == 3 }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LkNotices.dismiss(LkNotices.current.value ?: return@runOnMainSync)
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel.commitDrag(
                payload = FolderDragPayload(dup),
                targetFolderId = target,
                movedNotice = LkNotices.Message.Text("moved"),
                undoLabel = "Undo",
                undoneNotice = "undone",
                onSettled = {},
            )
        }
        awaitState("the refusal notice") { LkNotices.current.value != null }

        assertEquals(LkNotices.Message.Res(R.string.vaultlist_folders_error_name_exists), LkNotices.current.value?.message)
        assertEquals(null, unlockedVault().folders.first { it.id == dup }.parentId, "nothing moved")
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
    }
}
