package com.localkeep.feature.health

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.health.BreachFilter
import com.localkeep.core.health.BreachFilterSource
import com.localkeep.core.health.SecurityDashboard
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.RevealAttemptOutcome
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The health screen leaks by arithmetic, which is why it needs its own harness.
 *
 * Nothing on this screen names an item until you tap into a category, so the temptation is to treat
 * it as a low-risk surface. It is the opposite: a headline that says "3 weak passwords" while the
 * list shows two of them tells the reader there is a third one they cannot see. Subtraction is the
 * disclosure, and it survives every filter applied at the row level — the count has to be computed
 * from the projection or it says too much.
 *
 * The drill-down is the second half. `selectCategory` filters by the ids the *scan* found, so an id
 * captured during a reveal session must not resurface its item after conceal.
 *
 * Instrumented because the vault, the lock manager and the reveal gate are all real here; the JVM
 * tests in this module exercise the state object, not the scan.
 */
@RunWith(AndroidJUnit4::class)
class HealthVisibilityInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    /** No breach corpus: this test is about which items are counted, not about what they match. */
    private val dashboard =
        SecurityDashboard(BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null }))

    @BeforeTest
    fun setUp() = runBlocking {
        wipe()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
        Unit
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        wipe()
    }

    @Test
    fun aConcealedWeakPasswordIsNotCounted() = runBlocking {
        val visible = seedWeakLogin("Cafe wifi")
        val hidden = seedWeakLogin("Offshore bank")

        val before = scan()
        assertEquals(2, before.scannedCount, "control: both are scanned while both are visible")
        assertTrue(before.weak.itemIds.containsAll(setOf(visible, hidden)), "control: both are weak")

        repository.hide(
            HiddenTarget.Item(hidden),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        val after = scan()
        assertEquals(1, after.scannedCount, "the concealed item is not part of the denominator either")
        assertEquals(setOf(visible), after.weak.itemIds)
    }

    /**
     * The denominator matters as much as the count. `scannedCount` is what the score is a fraction
     * of, so leaving a concealed item in it would move the headline number without any visible cause
     * — a score that drops when nothing on screen got worse is itself a signal.
     */
    @Test
    fun concealingAnItemDoesNotStrandItInTheDenominator() = runBlocking {
        val strong = seedLogin("Bank", "9x!Qv2#mLp7@zR4tYw6&")
        val hidden = seedWeakLogin("Offshore bank")
        assertEquals(2, scan().scannedCount)

        repository.hide(
            HiddenTarget.Item(hidden),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        val after = scan()
        assertEquals(1, after.scannedCount)
        assertEquals(emptySet(), after.weak.itemIds, "only the strong one is left, and it is not weak")
        assertFalse(strong in after.weak.itemIds)
    }

    /**
     * A drill-down id captured while revealed must not resurface the item after conceal. This is the
     * path `HealthDashboardViewModel.itemsIn` takes, and it is the one place a stale id from an
     * earlier scan gets turned back into a titled row.
     */
    @Test
    fun aDrillDownIdFromARevealedScanDoesNotSurviveConcealing() = runBlocking {
        val hidden = seedWeakLogin("Offshore bank")
        repository.hide(
            HiddenTarget.Item(hidden),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        val generation = lockManager.nextRevealAttemptGeneration()
        assertEquals(
            RevealAttemptOutcome.Revealed,
            lockManager.attemptHiddenReveal("open sesame".toCharArray(), generation),
        )
        val revealedScan = scan()
        assertTrue(hidden in revealedScan.weak.itemIds, "control: a revealed session counts it")
        assertTrue(
            repository.visibilitySnapshot().liveItems.any { it.id == hidden },
            "control: and can resolve the id to an item",
        )

        lockManager.concealHiddenSession()

        assertTrue(
            repository.visibilitySnapshot().liveItems.none { it.id == hidden },
            "the id from the revealed scan resolves to nothing once concealed",
        )
        assertEquals(0, scan().scannedCount)
    }

    private fun scan(): SecurityDashboard.Summary =
        dashboard.analyze(repository.visibilitySnapshot().liveItems)

    private suspend fun seedWeakLogin(title: String): UUID = seedLogin(title, "password1")

    private suspend fun seedLogin(title: String, password: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = title, createdAt = now, updatedAt = now,
                content = Login(username = "me@$title", password = password),
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

    private fun wipe() {
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }
}
