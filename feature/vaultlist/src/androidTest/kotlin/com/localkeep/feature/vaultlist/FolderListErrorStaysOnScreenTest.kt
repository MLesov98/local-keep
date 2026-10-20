package com.localkeep.feature.vaultlist

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
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
import com.localkeep.core.model.Folder
import com.localkeep.core.ui.theme.LocalKeepTheme
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A refused rename says why, where the user can read it.
 *
 * The Folders screen became a LazyColumn on 2026-10-02 (S1-09) with the error as its first item. A
 * lazy list keeps its first visible row anchored when an item is inserted ahead of it, so the error
 * arrived scrolled off the top: renaming a folder to a sibling's name looked like a Save button
 * that did nothing (found 2026-10-03). Any scroll offset at all is enough — the
 * keyboard opening for the rename provides one — which is why this test scrolls first.
 *
 * Fixture duplicated from [FolderViewModelTest], the precedent this module's tests already set.
 */
@RunWith(AndroidJUnit4::class)
class FolderListErrorStaysOnScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

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
    fun renamingAFolderToASiblingsNameShowsTheWholeError() {
        val folders = List(FOLDERS) { n -> Folder(UUID.randomUUID(), name(n), updatedAt = Instant.EPOCH) }
        runBlocking { repository.mutate { it.folders += folders } }
        val viewModel = FolderViewModel(repository, lockManager, FreeEntitlement())

        compose.setContent {
            LocalKeepTheme { FolderScreen(viewModel = viewModel, onOpenFolder = {}, onBack = {}) }
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(name(0)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(SCROLLED_TO)

        val renamed = folders[SCROLLED_TO + 1]
        compose.runOnIdle {
            viewModel.startRenaming(renamed.id)
            viewModel.onRenameTextChanged(folders[SCROLLED_TO].name)
            viewModel.commitRename()
        }
        compose.waitForIdle()

        val error = compose.onNodeWithText(context.getString(R.string.vaultlist_folders_error_name_exists))
        // Exists, and is not clipped: the anchored list rendered it partly or wholly above the
        // viewport, which a bare assertIsDisplayed would pass on as long as one pixel showed.
        assertEquals(error.getUnclippedBoundsInRoot(), error.getBoundsInRoot())
    }

    private fun name(n: Int) = "Folder %02d".format(n)

    private class FreeEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): PurchaseOutcome = PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
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
        const val FOLDERS = 30
        const val SCROLLED_TO = 10
        const val TIMEOUT_MS = 10_000L
    }
}
