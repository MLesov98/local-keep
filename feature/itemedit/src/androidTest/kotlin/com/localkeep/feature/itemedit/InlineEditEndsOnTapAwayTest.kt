package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A tap anywhere else on the item view screen ends an inline edit and keeps what was typed.
 *
 * Device pass 2026-10-04 (A3, ITM-04), release on API 35: tap the username, change it, then tap a
 * blank area, the PASSWORD label or "Show Password". The field stayed focused and nothing was
 * saved, so the user's next move — Back — threw the edit away, because Back is the discard path.
 * The rule this screen documents is "IME Done or focus loss → save", and a tap on something that
 * is not a text field takes no focus in Compose, so the focus-loss half never happened on a tap.
 *
 * Both halves of "a tap elsewhere" are pinned — plain text and a real control, which must still do
 * its own job — and the control at the bottom is the tap that must *not* end the edit.
 */
@RunWith(AndroidJUnit4::class)
class InlineEditEndsOnTapAwayTest {

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
    fun tappingPlainTextElsewhereSavesTheEdit() {
        val (id, vm) = openDetailAndEditTheUsername()

        // The item's title: a heading, not a control. Stands in for the blank area and the
        // section label the tester tapped — none of them takes focus.
        compose.onNodeWithText(TITLE).performClick()

        // FAILS WITHOUT THE FIX: the field kept focus and the vault kept the old username.
        settle { storedUsername(id) == EDITED }
        assertEquals(EDITED, storedUsername(id), "a tap-away did not save the inline edit")
        settle { !hasFocusedTextField() }
        assertTrue(!hasFocusedTextField(), "the inline edit is still open after a tap-away")
        assertEquals(EDITED, vm.state.value.username)
    }

    @Test
    fun tappingAnotherControlSavesTheEditAndStillDoesItsJob() {
        val (id, vm) = openDetailAndEditTheUsername()
        val showPassword = context.getString(
            R.string.itemedit_action_show,
            context.getString(R.string.itemedit_password_label),
        )

        compose.onNodeWithContentDescription(showPassword).performClick()

        // FAILS WITHOUT THE FIX: the reveal happened, the edit did not end.
        settle { storedUsername(id) == EDITED }
        assertEquals(EDITED, storedUsername(id), "tapping a button left the inline edit unsaved")
        // The tap was observed, not swallowed: the button still did what it says.
        assertTrue("password" in vm.state.value.revealedFields, "the tap-away swallowed the button's own tap")
    }

    /** The control: a tap inside the field being edited is editing, not leaving. */
    @Test
    fun tappingInsideTheFieldKeepsTheEditOpen() {
        val (id, _) = openDetailAndEditTheUsername()

        compose.onNode(hasSetTextAction()).performClick()
        settle(timeoutMs = 1_000) { false }

        assertTrue(hasFocusedTextField(), "a tap inside the field ended its own edit")
        assertEquals(ORIGINAL, storedUsername(id), "a tap inside the field committed the draft")
    }

    /** Opens the seeded login's detail screen, taps the username and replaces the text. */
    private fun openDetailAndEditTheUsername(): Pair<UUID, ItemEditViewModel> {
        val id = runBlocking { seedLogin() }
        val vm = ItemEditViewModel(
            repository,
            lockManager,
            FreeEntitlement(),
            SavedStateHandle(mapOf(ROUTE_ITEM_ID to id.toString())),
        )
        // The fixture the other detail-screen tests use: frames run when the test says so.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                ItemDetailScreen(viewModel = vm, itemId = id.toString(), onEdit = {}, onBack = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)

        compose.onNodeWithText(ORIGINAL).performClick()
        settle { hasFocusedTextField() }
        // Premise: the tap opened the inline edit, focused, as it does on device.
        assertTrue(hasFocusedTextField(), "tapping the username did not open the inline edit")
        compose.onNode(hasSetTextAction() and isFocused()).performTextReplacement(EDITED)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(ORIGINAL, storedUsername(id), "premise: typing alone must not save")
        return id to vm
    }

    private fun hasFocusedTextField(): Boolean =
        compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()

    private fun storedUsername(id: UUID): String = runBlocking {
        (repository.getAllItems().first { it.id == id }.content as Login).username
    }

    /**
     * Drives frames and real time together until [condition] holds. The save runs on the
     * ViewModel's own scope on the main looper, and the screen only recomposes when the test clock
     * moves, so both have to advance.
     */
    private fun settle(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            compose.mainClock.advanceTimeBy(50)
            Thread.sleep(10)
        }
    }

    private suspend fun seedLogin(): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = TITLE, createdAt = now, updatedAt = now,
                content = Login(username = ORIGINAL, password = "hunter2"),
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

    private companion object {
        const val TITLE = "Alpha Login"
        const val ORIGINAL = "alice@example.com"
        const val EDITED = "bob@example.com"
    }
}
