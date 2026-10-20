package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.Totp
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The detail screen shows the item the editor saved, second factor included.
 *
 * Device pass 2026-10-04 (A3, LOG-07), release on API 35: Alpha Login › Edit › Two-factor › Remove
 * two-factor › Remove › Save. The removal reached the vault — the list row lost its 2FA pill, a
 * reopened editor showed the chooser — but the detail screen it returned to kept the live code card
 * and the "2FA secret" row, ticking, until the user left it.
 *
 * Two ViewModels, because that is the shape of the bug: the detail screen and the editor are
 * separate back-stack entries, and the detail's copy only learns about the save through the
 * `onSaved` collector's `loadItem`. Instrumented for the reason [ItemEditViewModelTest] gives; the
 * state transition alone is pinned on the JVM by `LoginReloadDropsARemovedTwoFactorTest`.
 */
@RunWith(AndroidJUnit4::class)
class DetailDropsARemovedTwoFactorInstrumentedTest {

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
    fun theDetailDropsTheSecondFactorTheEditorRemoved() = runBlocking {
        val id = seedLoginWithTotp()
        val detail = viewModelFor(id)
        // Premise: the detail screen is showing the factor, with a live code, as it did on device.
        waitUntil { detail.state.value.totpCurrentCode.isNotEmpty() }
        // The `onSaved` collector is launched onto the main looper from `init`; a save made before
        // it subscribes is a save nobody was listening for, and the test would measure that.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val editor = viewModelFor(id)
        editor.removeLoginTotp()
        editor.save()
        waitUntil { editor.state.value.saved }
        val stored = repository.getAllItems().first { it.id == id }
        assertNull((stored.content as Login).totp, "control: the removal reached the vault")

        // The detail screen followed the save — this is what the collector's reload changes, and it
        // is the same on the buggy build, so the assertions below are about *what* it reloaded.
        waitUntil { detail.state.value.updatedAt == stored.updatedAt }

        // FAILS WITHOUT THE FIX: the reload kept the old secret, so the card and the gated
        // "2FA secret" row both stayed, and the ticker went on computing codes.
        assertEquals("", detail.state.value.totpSecret, "the detail still holds the removed seed")
        waitUntil { detail.state.value.totpCurrentCode.isEmpty() }
    }

    /** The control: a save that keeps the factor leaves it on the detail screen. */
    @Test
    fun theDetailKeepsASecondFactorTheEditorKept() = runBlocking {
        val id = seedLoginWithTotp()
        val detail = viewModelFor(id)
        waitUntil { detail.state.value.totpCurrentCode.isNotEmpty() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val editor = viewModelFor(id)
        editor.onUsernameChanged("bob@example.com")
        editor.save()
        waitUntil { editor.state.value.saved }
        val stored = repository.getAllItems().first { it.id == id }
        waitUntil { detail.state.value.updatedAt == stored.updatedAt }

        assertEquals("bob@example.com", detail.state.value.username)
        assertEquals(SECRET, detail.state.value.totpSecret)
        assertTrue(detail.state.value.totpCurrentCode.isNotEmpty(), "the code stopped for a kept factor")
    }

    private fun viewModelFor(id: UUID) = ItemEditViewModel(
        repository,
        lockManager,
        FreeEntitlement(),
        SavedStateHandle(mapOf(ROUTE_ITEM_ID to id.toString())),
    )

    private suspend fun seedLoginWithTotp(): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = "Alpha Login",
                createdAt = now,
                updatedAt = now,
                content = Login(
                    username = "alice@example.com",
                    password = "hunter2",
                    totp = Totp(issuer = "Acme", account = "alice", secretBase32 = SECRET),
                ),
            ),
        )
        return id
    }

    /** Spins until [condition] holds — `save()` and the reload run on the ViewModels' own scopes. */
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(condition(), "timed out waiting for the ViewModel to settle")
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
        const val SECRET = "JBSWY3DPEHPK3PXP"
    }
}
