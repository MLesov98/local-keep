package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * Fix-on-sight (finding alongside 6-2): [ReadField]'s copyable rows — the username row on the
 * item view screen among them — wire `LkGroupRow`'s `onLongClick` without an `onLongClickLabel`,
 * so TalkBack announces the long-press action unnamed. `LkLockButton` and `SecretReadField`
 * ([SecretReadFieldCopyOnlyAccessibilityTest]) are the correct pattern this pins the same way for
 * [ReadField], which is `private` in `ItemDetailScreen.kt` and so cannot be composed directly from
 * this file — driven instead through the real, public [ItemDetailScreen] with a seeded login,
 * exactly as [ItemEditVisibilityInstrumentedTest] drives the same screen.
 */
@RunWith(AndroidJUnit4::class)
class ReadFieldCopyOnlyAccessibilityTest {

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
    fun theUsernameRowNamesCopyOnTheLongClick() = runBlocking {
        val id = seedLogin("Ordinary login", username = "me@example", password = "hunter2")
        val vm = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to id.toString())),
        )

        // ItemEditViewModel runs a `while (isActive) { refreshTotpCode(); delay(1_000) }` loop
        // from `init`, so Compose's idling resource never reports idle unless the clock is driven
        // by hand — same fixture SecretReadFieldCopyOnlyAccessibilityTest and
        // EditorHeaderInstrumentedTest use.
        compose.mainClock.autoAdvance = false

        compose.setContent {
            LocalKeepTheme {
                ItemDetailScreen(viewModel = vm, itemId = id.toString(), onEdit = {}, onBack = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        // The username value is unique text on this screen (the title reads "Ordinary login"). It
        // does NOT resolve to the row carrying the actions, though: ReadField wraps its label+value
        // Column in its own `semantics(mergeDescendants = true) { contentDescription = readout }`,
        // which makes that Column its own merge boundary — a peer of LkGroupRow's Row, not merged
        // into it. Confirmed empirically via `onRoot().printToLog(...)`: the Row (parent) carries
        // `Actions = [OnClick, OnLongClick, RequestFocus]`; the Column (matched by text) does not.
        // So the row under test is the text node's parent, not the text node itself.
        val textNode = compose.onNodeWithText("me@example").fetchSemanticsNode()
        val config = requireNotNull(textNode.parent) { "the username text node has no parent row" }.config
        val longAction = config.getOrNull(SemanticsActions.OnLongClick)
        assertNotNull(longAction, "the username row's long-press copy action must be exposed to a screen reader")
        assertEquals(
            context.getString(R.string.itemedit_action_copy, context.getString(R.string.itemedit_username_label)),
            longAction.label,
            "the long-click action must carry a non-null, real label naming what it does",
        )
    }

    private fun password() = "correct horse battery staple".toCharArray()

    private suspend fun seedLogin(title: String, username: String, password: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = title, createdAt = now, updatedAt = now,
                content = Login(username = username, password = password),
            ),
        )
        return id
    }

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
