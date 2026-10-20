package com.localkeep.feature.vaultlist

import android.content.Context
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
import com.localkeep.core.vault.ForegroundVisibilityCoordinator
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * A lock landing while the vault list is refreshing must not take the process with it.
 *
 * `refresh` wraps its scan in `runCatching`, but then does two more vault reads *inside* the
 * success branch — `getAllItems()` for selection pruning and `quotaUsed()` — which are outside that
 * guard. `requireVault()` throws `IllegalStateException("vault is locked")`, and this runs in a
 * `launch` on `Dispatchers.Default` with no handler above it, so the throw is not a failed refresh:
 * it is an uncaught exception on a background thread, which is a process death.
 *
 * The window is ordinary rather than exotic. The auto-lock timer and the screen-off receiver both
 * fire without any regard for what the list is doing, and the list refreshes on every save, every
 * filter change and every keystroke in the search box.
 *
 * **Without the fix this does not fail as an assertion — the instrumentation run dies**
 * ("Process crashed. java.lang.IllegalStateException: vault is locked"), taking the rest of the
 * module's tests with it. That is what it looked like when it was found: a crash reported against
 * whichever unrelated test happened to be running.
 */
@RunWith(AndroidJUnit4::class)
class RefreshSurvivesALockInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private lateinit var viewModel: VaultListViewModel

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
        Unit
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun lockingWhileTheListIsRefreshingDoesNotCrash() = runBlocking {
        // Enough items that the scan is still running when the lock lands. The scan is the part
        // that takes time; the unguarded reads are what it runs into on the way out.
        repeat(300) { seedLogin("Account $it") }

        repeat(REPEATS) {
            newViewModel()
            // No wait. The point is to land the lock inside the refresh rather than after it, so
            // this races deliberately — and repeats, because a race won once proves nothing.
            lockManager.lock()
            delay(40)
            lockManager.unlock(password())
        }

        // Reaching this line at all is most of the assertion: without the guard the process is gone
        // before it, and no assertion in this file runs. The state check is the rest — a locked
        // vault must leave the list empty rather than half-published from a scan that raced it.
        lockManager.lock()
        awaitState("the list to settle after a lock") { viewModel.state.value.items.isEmpty() }
        assertTrue(viewModel.state.value.items.isEmpty())
    }

    // ── harness ───────────────────────────────────────────────────────────

    private fun newViewModel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            viewModel = VaultListViewModel(
                repository,
                lockManager,
                FreeBuild,
                ForegroundVisibilityCoordinator(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    clock = Clock.systemUTC(),
                    nextExpiry = { null },
                    onExpiry = {},
                ),
            )
            viewModel.retryLoad()
            viewModel.onEnteredList()
        }
    }

    private fun seedLogin(title: String) {
        runBlocking {
            repository.addItem(
                VaultItem(
                    id = UUID.randomUUID(),
                    title = title,
                    content = Login(username = "ada@example.com"),
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
    }

    private fun awaitState(what: String, predicate: () -> Boolean) = runBlocking {
        val landed = withTimeoutOrNull(TIMEOUT_MS) {
            while (!predicate()) delay(POLL_MS)
            true
        }
        assertTrue(landed == true, "timed out waiting for $what")
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

    private object FreeBuild : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: android.app.Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L

        /** A race won once proves nothing; this is enough attempts to hit the window reliably. */
        const val REPEATS = 12
    }
}
