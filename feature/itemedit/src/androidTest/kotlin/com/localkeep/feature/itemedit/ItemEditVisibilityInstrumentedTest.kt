package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.RevealAttemptOutcome
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * A detail screen holding a revealed item must not keep holding it after the reveal ends.
 *
 * This is the one concealment gap a read-only audit cannot see, because nothing about the code is
 * wrong until you put time into it: reveal a hidden login, open it, and hand the phone over. The
 * vault list conceals the entry when the background grace expires — and the screen already on top of
 * it carries on showing the title, the username and a password one tap from being unmasked, because
 * no list redraw ever reaches a screen that is not the list.
 *
 * Lease expiry is the case that must *not* fire. An expired lease makes the record ordinary and
 * visible; closing the screen there would punish the user for waiting.
 */
@RunWith(AndroidJUnit4::class)
class ItemEditVisibilityInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

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

    @Test
    fun concealingTheOpenItemDropsItsFieldsAndSendsTheScreenBack() = runBlocking {
        val id = seedLogin("Offshore bank", "hunter2")
        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )
        reveal("open sesame")

        val vm = viewModel(id)
        assertEquals("Offshore bank", vm.state.value.title, "control: revealed, the screen holds the item")
        assertEquals("hunter2", vm.state.value.password)

        lockManager.concealHiddenSession()
        settle()

        assertTrue(vm.state.value.concealed, "the screen is told to go back")
        assertEquals("", vm.state.value.title, "and holds no part of the item any more")
        assertEquals("", vm.state.value.password)
        assertEquals("", vm.state.value.username)
    }

    /**
     * O1-02, 2026-10-02: the edit form is blanked when the reveal ends, and nothing it saves after
     * that may reach the vault. It used to: a retyped title committed an empty Login over the hidden
     * item. JVM half: `ConcealedEditorLeavesAndCannotSaveTest`.
     */
    @Test
    fun aSaveAfterTheRevealEndsWritesNothing() = runBlocking {
        val id = seedLogin("Offshore bank", "hunter2")
        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )
        reveal("open sesame")
        val vm = viewModel(id)
        assertEquals("Offshore bank", vm.state.value.title, "control: revealed, the form holds the item")

        lockManager.concealHiddenSession()
        awaitVm("the form to be concealed") { vm.state.value.concealed }
        vm.onTitleChanged("Retyped")
        vm.save()
        settle()

        val stored = repository.getCompleteStorageInventory().allItems.first { it.id == id }
        assertEquals("Offshore bank", stored.title, "a save from the blanked form overwrote the hidden item")
        val login = stored.content as Login
        assertEquals("me@example", login.username)
        assertEquals("hunter2", login.password)
    }

    /**
     * The complement, and the reason the condition is written as narrowly as it is: an *ordinary*
     * item must never be taken off the screen by this collector. Only a leased record the projection
     * is currently hiding qualifies — "no longer in liveItems" alone is also true of a deletion, of
     * a save in flight, and of a screen that subscribed before `presentationState`'s upstream had
     * produced anything.
     */
    @Test
    fun anOrdinaryItemIsNeverTakenOffTheScreen() = runBlocking {
        val hidden = seedLogin("Offshore bank", "hunter2")
        val ordinary = seedLogin("Cafe wifi", "hunter2")
        repository.hide(
            HiddenTarget.Item(hidden),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        val vm = viewModel(ordinary)
        settle()

        assertFalse(vm.state.value.concealed, "concealing something else does not close this screen")
        assertEquals("Cafe wifi", vm.state.value.title)
    }

    /**
     * The lock path. MainActivity's observer already replaces the graph with Unlock, but this
     * ViewModel survives on the back stack, so it drops the fields too rather than relying on the
     * navigation alone.
     */
    @Test
    fun lockingDropsTheOpenItemsFields() = runBlocking {
        val id = seedLogin("Ordinary login", "hunter2")
        val vm = viewModel(id)
        assertEquals("Ordinary login", vm.state.value.title, "control")

        lockManager.lock()
        settle()

        assertTrue(vm.state.value.concealed)
        assertEquals("", vm.state.value.password)
    }

    /**
     * Expiry is the opposite instruction. The lease ran out, the record is ordinary again, and the
     * screen the user is reading stays exactly where it is.
     */
    @Test
    fun anExpiringLeaseLeavesTheOpenScreenAlone() = runBlocking {
        val id = seedLogin("Offshore bank", "hunter2")
        // A lease one second long: short enough to expire inside the test, long enough that `hide`
        // does not reject it as already past.
        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(1),
            firstPhrase = "open sesame".toCharArray(),
        )
        reveal("open sesame")
        val vm = viewModel(id)
        assertEquals("Offshore bank", vm.state.value.title, "control")

        delay(1_500)
        // What the foreground coordinator does at a lease boundary: recompute, do not save.
        repository.refreshPresentation()

        // Polled, not slept. `settle()`'s fixed 300 ms was enough for this class alone and not
        // enough once the suite grew — the recompute is published by a collector on the ViewModel's
        // scope, so how long it takes is a function of what else the device is doing. Waiting on the
        // asserted condition is safe here in a way it would not be elsewhere: the failure mode this
        // test guards against is the marker *staying* set, so a timeout is that defect and says so,
        // rather than hiding one.
        awaitVm("the expired lease to stop marking the open screen") { !vm.state.value.concealed }
        assertFalse(vm.state.value.concealed, "an expired lease is not a concealment")
        assertEquals("Offshore bank", vm.state.value.title, "and the screen keeps what it is showing")
    }

    /**
     * QA 2026-10-03: the editor's folder picker read the raw vault, so a folder made invisible was
     * gone from the vault list and still offered by name under "Change folder", one tap from any
     * login. The picker reads the list's projection now; revealed, the folder comes back.
     */
    @Test
    fun theFolderPickerOffersAnInvisibleFolderOnlyWhileRevealed() = runBlocking {
        val hidden = repository.createFolder("Offshore")
        repository.createFolder("Personal")
        repository.hide(
            HiddenTarget.Folder(hidden.id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )
        val item = seedLogin("Cafe wifi", "hunter2")

        val concealed = viewModel(item)
        awaitVm("the folders to load") { concealed.state.value.folders.isNotEmpty() }
        assertEquals(listOf("Personal"), concealed.state.value.folders.map { it.name })

        reveal("open sesame")
        val revealed = viewModel(item)
        awaitVm("the revealed folder to be offered") { revealed.state.value.folders.size == 2 }
    }

    /**
     * QA 2026-10-03: the picker counted a folder's own items only, so a folder whose items all sat
     * in a subfolder read "(0)" here and "(4)" in every other picker. It counts the subtree now.
     */
    @Test
    fun theFolderPickerCountsAFoldersWholeSubtree() = runBlocking {
        val work = repository.createFolder("Work")
        val clients = repository.createFolder("Clients", work.id)
        val item = seedLogin("Cafe wifi", "hunter2")
        repository.setItemFolder(seedLogin("Acme", "x"), clients.id)
        repository.setItemFolder(seedLogin("Globex", "y"), clients.id)

        val vm = viewModel(item)
        awaitVm("the folders to load") { vm.state.value.folders.size == 2 }

        assertEquals(2, vm.state.value.folderItemCounts[work.id], "Work holds Clients' two")
        assertEquals(2, vm.state.value.folderItemCounts[clients.id])
    }

    private fun viewModel(id: UUID) = ItemEditViewModel(
        repository,
        lockManager,
        FreeEntitlement(),
        SavedStateHandle(mapOf(ROUTE_ITEM_ID to id.toString())),
    )

    /** The collectors are launched on the ViewModel scope; give them a turn of the loop. */
    private suspend fun settle() = delay(300)

    /**
     * Waits for the ViewModel to reach [predicate], up to a bound.
     *
     * Use this instead of [settle] wherever the assertion depends on a collector having run.
     * A fixed sleep encodes a guess about device speed, and the guess held only while the suite
     * stayed the size it was when it was written.
     */
    private suspend fun awaitVm(what: String, predicate: () -> Boolean) {
        val landed = withTimeoutOrNull(10_000L) {
            while (!predicate()) delay(25L)
            true
        }
        assertTrue(landed == true, "timed out waiting for $what")
    }

    private suspend fun reveal(phrase: String) {
        val generation = lockManager.nextRevealAttemptGeneration()
        assertEquals(
            RevealAttemptOutcome.Revealed,
            lockManager.attemptHiddenReveal(phrase.toCharArray(), generation),
        )
    }

    private suspend fun seedLogin(title: String, password: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = title, createdAt = now, updatedAt = now,
                content = Login(username = "me@example", password = password),
            ),
        )
        return id
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

    private class FreeEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }
}
