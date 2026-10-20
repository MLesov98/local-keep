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
import com.localkeep.core.vault.LockState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * A login's notes reach the editor and come back out again.
 *
 * `Login.notes` is written by every importer — KeePass's Notes field is the common source — and
 * `VaultRepository.searchHaystack` has indexed it since search existed. What it never had was a
 * screen: the `is Login ->` load branch did not read it and the save branch did not write it, so a
 * KeePass migration looked complete while dropping that field from view, and a search matching only
 * a note returned a row whose every visible field lacked the term.
 *
 * Both directions are asserted, and they are not equally sharp — measured, not assumed. Removing
 * `notes = loginNotes` from the save branch fails only [aNoteTypedIntoTheEditorReachesTheVault];
 * [anImportedNoteIsShownAndSurvivesAnEditToAnotherField] stays green, because the save builds on
 * `original.copy(...)` and an unassigned field keeps the stored value. So the editor was never
 * *destroying* notes, which is what this KDoc claimed until the red run was actually watched — it
 * was only unable to show or change them. The survives-an-edit case is kept anyway: it is the
 * assertion that would catch a future save path that rebuilds `Login()` from the form instead of
 * copying, which is exactly how a field that nothing renders gets quietly dropped.
 */
@RunWith(AndroidJUnit4::class)
class LoginNotesRoundTripInstrumentedTest {

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
        viewModel = ItemEditViewModel(repository, lockManager, ProEntitlement(), SavedStateHandle())
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun anImportedNoteIsShownAndSurvivesAnEditToAnotherField() = runBlocking {
        val id = seedLoginWithNote(IMPORTED_NOTE)

        viewModel.loadItem(id.toString())
        await("the item to load") { viewModel.state.value.username == "ada@example.com" }

        assertEquals(
            IMPORTED_NOTE,
            viewModel.state.value.loginNotes,
            "an imported note has to reach the form, or the editor cannot show it",
        )

        // Edit something else entirely and save. This is the destructive case: a form that loaded
        // the note but did not carry it back would write an empty string over it here, and the user
        // would have lost the note by changing their username.
        viewModel.onUsernameChanged("grace@example.com")
        viewModel.save()
        await("the save to land") { loginInVault().username == "grace@example.com" }

        assertEquals(
            IMPORTED_NOTE,
            loginInVault().notes,
            "editing an unrelated field must not erase the note",
        )
    }

    @Test
    fun aNoteTypedIntoTheEditorReachesTheVault() = runBlocking {
        val id = seedLoginWithNote("")

        viewModel.loadItem(id.toString())
        await("the item to load") { viewModel.state.value.username == "ada@example.com" }

        viewModel.onLoginNotesChanged("recovery codes are in the safe")
        viewModel.save()
        await("the save to land") { loginInVault().notes.isNotBlank() }

        assertEquals("recovery codes are in the safe", loginInVault().notes)
    }

    // ── harness ───────────────────────────────────────────────────────────

    private fun seedLoginWithNote(note: String): UUID {
        val id = UUID.randomUUID()
        runBlocking {
            repository.addItem(
                VaultItem(
                    id = id,
                    title = "Bank",
                    content = Login(username = "ada@example.com", notes = note),
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
        return id
    }

    private fun loginInVault(): Login =
        (lockManager.lockState.value as LockState.Unlocked).vault.items.single().content as Login

    private fun await(what: String, predicate: () -> Boolean) = runBlocking {
        val landed = withTimeoutOrNull(10_000L) {
            while (!predicate()) delay(25L)
            true
        }
        assertEquals(true, landed, "timed out waiting for $what")
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

    private companion object {
        const val IMPORTED_NOTE = "security questions: first pet = Rex"
    }
}
