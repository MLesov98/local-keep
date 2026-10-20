package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The Saved screen leaves exactly once, however it is left.
 *
 * It has two exits: it advances itself after a dwell, and its Done button (and Back) leave at once.
 * Until 2026-10-02 nothing stopped both from firing. The editor stays composed for the length of the
 * vault list's entrance after the first exit, so a Done tap landing just before the dwell ran out
 * fired `onSaved` a second time. On the generator route the first call has already replaced the
 * stack with a fresh vault list, so the second found no previous entry and popped that — leaving an
 * empty NavHost, a dark screen with the bottom bar and nothing else, until Back closed the app.
 * Reported from the principal's phone after saving a generated password; reproduced on
 * emulator-5554 with a Back press 1.33–1.38 s after Save.
 *
 * Fixture duplicated from [EditorHeaderInstrumentedTest], for the reason that class gives.
 */
@RunWith(AndroidJUnit4::class)
class SavedScreenExitsOnceInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var viewModel: ItemEditViewModel

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context))
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
        viewModel = ItemEditViewModel(
            repository,
            lockManager,
            ProEntitlement(),
            SavedStateHandle(),
        )
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun doneTappedBeforeTheDwellEndsLeavesOnce() {
        var exits = 0
        // See EditorHeaderInstrumentedTest: the ViewModel's TOTP loop never lets Compose idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                ItemEditScreen(
                    viewModel = viewModel,
                    itemId = null,
                    onSaved = { exits++ },
                    onBack = {},
                    presetType = ItemEditUiState.ItemType.NOTE,
                )
            }
        }
        compose.mainClock.advanceTimeBy(500)

        compose.runOnUiThread { viewModel.saveWithTitle("Leaves once") }
        // The write runs on IO in real time; frames are advanced one at a time so the dwell, which
        // runs on the test clock, starts no more than a frame before this loop sees the save land.
        val deadline = System.currentTimeMillis() + 10_000
        while (!viewModel.state.value.saved && System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeByFrame()
            Thread.sleep(20)
        }
        assertTrue(viewModel.state.value.saved, "the save never landed")

        // Inside the dwell: the Saved screen is up and has not left yet.
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, exits)

        compose.onNodeWithText(context.getString(R.string.itemedit_identity_capture_done)).performClick()
        assertEquals(1, exits)

        // Past the dwell. The tap already left; the timer must not leave again.
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(1, exits)
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

    private class ProEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(true)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }
}
