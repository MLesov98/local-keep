package com.localkeep.feature.files

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
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The file editor had the same first-frame defect as the item editor, and it read worse.
 *
 * `LaunchedEffect` runs after the frame that created it has been drawn, so opening a stored file
 * painted one frame of [FileItemEditUiState]'s defaults — `isNew = true`, no title, no filename, no
 * blob — which is not a neutral placeholder but the *new file* form stating the wrong thing
 * confidently. Then the file arrived. Invisible on an ordinary open, plainly visible when you go
 * back and open the same file again straight away, which is exactly how the flicker was reported.
 *
 * Asserted at construction rather than on frames, for the reason `ItemDetailLoadGuardTest` gives:
 * `loadItem` has no suspension point, so there is no dispatcher hop to race, and a frame-based test
 * would be flaky about the very state it exists to catch. If this passes there is no frame in which
 * the screen could draw the wrong file, because there is no moment at which the state holds one.
 */
@RunWith(AndroidJUnit4::class)
class FileItemEditLoadTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    @BeforeTest
    fun setUp() = runBlocking {
        wipe()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context))
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        wipe()
    }

    @Test
    fun theRoutesFileIsLoadedBeforeAnythingCanCompose() = runBlocking {
        val id = seedFile()

        // Nothing is called on it. Construction is the whole test.
        val vm = viewModel(SavedStateHandle(mapOf(ROUTE_FILE_ITEM_ID to id.toString())))

        assertEquals(id, vm.state.value.itemId, "the state must describe the route's file")
        assertEquals("Passport scan", vm.state.value.title)
        assertEquals("passport.jpg", vm.state.value.filename)
        assertFalse(vm.state.value.isNew, "a stored file must never render as the new-file form")
        assertFalse(isShowingWrongFileItem(vm.state.value, id.toString()))
        assertFalse(vm.needsInitialLoad(id.toString()), "nothing left for the screen's effect to do")
    }

    @Test
    fun theNewFileFormIsLeftAlone() = runBlocking {
        val vm = viewModel(SavedStateHandle())

        assertTrue(vm.state.value.isNew)
        assertEquals("", vm.state.value.filename)
    }

    /**
     * A sealed vault is not a crash. `getAllItems` goes through `requireVault`, which throws — and
     * `file/{itemId}` is restored onto the back stack after process death exactly like `item/{itemId}`,
     * which is where the identical crash was reproduced on the emulator.
     */
    @Test
    fun loadingFromASealedVaultIsRefusedRatherThanFatal() = runBlocking {
        val id = seedFile()
        lockManager.lock()

        val vm = viewModel(SavedStateHandle(mapOf(ROUTE_FILE_ITEM_ID to id.toString())))

        assertTrue(vm.state.value.isNew, "a sealed vault must put no file on screen")
        assertTrue(vm.needsInitialLoad(id.toString()), "and must still leave the effect work to do")
    }

    /** A route naming a file that is gone is looked up once, not once per composition. */
    @Test
    fun aFileThatIsGoneIsLookedUpOnceAndThenLeftAlone() = runBlocking {
        val missing = UUID.randomUUID()

        val vm = viewModel(SavedStateHandle(mapOf(ROUTE_FILE_ITEM_ID to missing.toString())))

        assertTrue(isShowingWrongFileItem(vm.state.value, missing.toString()))
        assertFalse(vm.needsInitialLoad(missing.toString()), "the lookup already ran and found nothing")
    }

    private fun viewModel(handle: SavedStateHandle) = FileItemEditViewModel(
        repository,
        lockManager,
        PhotoPickerImport(context, FileBlobStore(context)),
        FileBlobStore(context),
        FreeEntitlement(),
        handle,
    )

    private suspend fun seedFile(): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Passport scan",
                createdAt = now,
                updatedAt = now,
                content = com.localkeep.core.model.File(
                    filename = "passport.jpg",
                    mime = "image/jpeg",
                    sizeBytes = 82_113,
                    blobRef = "blob-1",
                ),
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

    private fun wipe() {
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }

    /** The `foss` posture — never Pro, nothing to buy. */
    private class FreeEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }
}
