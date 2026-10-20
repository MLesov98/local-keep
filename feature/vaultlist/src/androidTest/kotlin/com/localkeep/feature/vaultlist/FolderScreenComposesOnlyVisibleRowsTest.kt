package com.localkeep.feature.vaultlist

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The Folders screen composes the folders that are on screen, not every folder in the vault.
 *
 * It was a scrolling Column that built every row up front — each its own card with three icon
 * buttons and a menu — so a large KeePass or Bitwarden import opened it with seconds of main-thread
 * composition (QA S1-09, 2026-10-02). A Column composes off-screen rows too, which is exactly what
 * the final assertion would find.
 *
 * Fixture duplicated from [FolderViewModelTest], the precedent this module's tests already set.
 */
@RunWith(AndroidJUnit4::class)
class FolderScreenComposesOnlyVisibleRowsTest {

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
    fun aLongFolderListComposesOnlyTheRowsOnScreen() {
        runBlocking {
            repository.mutate { vault ->
                repeat(FOLDERS) { n ->
                    vault.folders.add(Folder(UUID.randomUUID(), name(n), updatedAt = Instant.EPOCH))
                }
            }
        }
        val viewModel = FolderViewModel(repository, lockManager, FreeEntitlement())

        compose.setContent {
            LocalKeepTheme { FolderScreen(viewModel = viewModel, onOpenFolder = {}, onBack = {}) }
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithText(name(0)).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(name(FOLDERS - 1)).assertDoesNotExist()
    }

    private fun name(n: Int) = "Folder %03d".format(n)

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
        const val FOLDERS = 150
        const val TIMEOUT_MS = 10_000L
    }
}
