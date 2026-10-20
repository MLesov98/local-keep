package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * Tapping a word slot puts the caret in the field that types that word.
 *
 * Device pass 2026-10-04, release build on API 28: a slot is not itself a text field, it names
 * which word the entry field under the grid edits. Tapping one moved only the highlight, so the
 * keyboard never came up, and "aban" typed after tapping slot 01 went into the title instead. On a
 * 1920px screen the entry field was below the fold, so nothing on screen offered a way in except
 * "Paste all".
 */
@RunWith(AndroidJUnit4::class)
class PhraseSlotTapFocusesTheEntryFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var viewModel: ItemEditViewModel

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context))
        lockManager.unlock(password())
        viewModel = ItemEditViewModel(VaultRepository(lockManager), lockManager, ProEntitlement(), SavedStateHandle())
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun tappingASlotFocusesTheWordField() {
        compose.setContent {
            LocalKeepTheme {
                ItemEditScreen(
                    viewModel = viewModel,
                    itemId = null,
                    onSaved = {},
                    onBack = {},
                    presetType = ItemEditUiState.ItemType.PHRASE,
                )
            }
        }
        compose.waitForIdle()

        // Control: opening the form focuses nothing, so the title still comes first.
        compose.onNode(hasSetTextAction() and isFocused()).assertDoesNotExist()

        // Slot 01 is already the selected slot on a new phrase, which is the case that changed no
        // state at all and so could not have raised the keyboard from a state-keyed effect.
        compose.onNodeWithText("01").performScrollTo().performClick()
        compose.waitForIdle()

        // FAILS WITHOUT THE FIX: the tap moved the highlight and left focus where it was.
        val wordLabel = context.getString(R.string.itemedit_phrase_word_label, 1)
        compose.onNode(hasSetTextAction() and isFocused()).assertIsFocused()
        // The field label is drawn in capitals ("WORD 01").
        compose.onNodeWithText(wordLabel, ignoreCase = true).assertExists()
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
