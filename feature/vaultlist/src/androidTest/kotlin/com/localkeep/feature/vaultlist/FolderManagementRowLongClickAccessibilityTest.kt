package com.localkeep.feature.vaultlist

import android.app.Activity
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.junit4.createComposeRule
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Fix-on-sight (finding alongside 6-2): `FolderManagementRow`'s `LkGroupRow` wires `onLongClick`
 * (opens the rename/duplicate/delete menu) without an `onLongClickLabel`, so TalkBack announces the
 * long-press action unnamed. `LkLockButton` and `SecretReadField`
 * ([SecretReadFieldCopyOnlyAccessibilityTest] in `:feature:itemedit`) are the correct pattern this
 * pins the same way. `FolderManagementRow` is `private` in `FolderScreen.kt`, so it cannot be
 * composed directly from this file — driven instead through the real, public [FolderScreen] with a
 * seeded folder, the way [FolderViewModelTest] drives the same ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class FolderManagementRowLongClickAccessibilityTest {

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

    private fun <T> SemanticsConfiguration.getOrNull(key: SemanticsPropertyKey<T>): T? =
        if (contains(key)) this[key] else null

    @Test
    fun theFolderRowNamesItsLongClickMenu() {
        val name = "Work"
        seedFolder(name)
        // FolderViewModel.load() reads a repository snapshot synchronously in init — constructed
        // after seeding, like FolderViewModelTest's own newViewModel(), so the first composed state
        // already carries the row.
        val viewModel = FolderViewModel(repository, lockManager, FreeEntitlement)

        // waitForIdle() hit ComposeNotIdleException here (lkEnter's stagger/entrance animation),
        // the same class of problem SecretReadFieldCopyOnlyAccessibilityTest and
        // EditorHeaderInstrumentedTest solve by driving the clock by hand instead of polling
        // Espresso's idling resource.
        compose.mainClock.autoAdvance = false

        compose.setContent {
            LocalKeepTheme {
                FolderScreen(viewModel = viewModel, onOpenFolder = {}, onBack = {})
            }
        }
        compose.mainClock.advanceTimeBy(2_000)

        // The folder name is unique text on this screen, and LkGroupRow's combinedClickable branch
        // (onClick and onLongClick both set here) merges its subtree's semantics into the row's own
        // node — the same reasoning ReadFieldCopyOnlyAccessibilityTest relies on in :feature:itemedit.
        val config = compose.onNodeWithText(name).fetchSemanticsNode().config
        val longAction = config.getOrNull(SemanticsActions.OnLongClick)
        assertNotNull(longAction, "the folder row's long-press menu action must be exposed to a screen reader")
        assertEquals(
            context.getString(R.string.vaultlist_folder_action_more, name),
            longAction.label,
            "the long-click action must carry a non-null, real label naming what it does",
        )
    }

    private fun seedFolder(name: String): UUID {
        val id = UUID.randomUUID()
        runBlocking { repository.mutate { it.folders.add(Folder(id, name, updatedAt = Instant.EPOCH)) } }
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

    /** Never Pro, nothing to buy — the `foss` posture. The row's menu does not depend on it. */
    private object FreeEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): PurchaseOutcome = PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }
}
