package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Progress reporting must not become a password oracle.
 *
 * `scanPasswordSlots` runs both slots to full completion before it branches, so a wrong password
 * costs exactly what a right one does and the duress slot costs exactly what the real one does.
 * Reporting checkpoints is only safe while that holds, and the way it would stop holding is subtle:
 * someone adds an emission beside `commitmentValid`, or inside the `SlotTrial.Match` branch, because
 * it reads more precisely — and the overlay starts answering "was that the right password" seconds
 * before the unlock result does, cheaply, and without the throttle ever seeing an attempt.
 *
 * These pin the property rather than the implementation: the sequence a wrong password produces must
 * be indistinguishable from a right one's, up to the terminal checkpoint that fires only after the
 * session is already published.
 */
@RunWith(AndroidJUnit4::class)
class UnlockProgressInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager

    @BeforeTest
    fun setup() {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun aWrongPasswordProducesTheSameSequenceAsARightOneUpToOpening() = runBlocking {
        val wrong = mutableListOf<UnlockProgress>()
        assertEquals(
            UnlockResult.WRONG_CREDENTIAL,
            lockManager.unlock("definitely not it".toCharArray()) { wrong += it },
        )

        val right = mutableListOf<UnlockProgress>()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()) { right += it })

        // OPENING is the only checkpoint a wrong password never reaches, and it fires after the
        // session is published — the UnlockResult returned alongside it already says as much.
        assertEquals(UnlockPhase.OPENING, right.last().phase)
        assertContentEquals(
            wrong.map { it.phase },
            right.dropLast(1).map { it.phase },
            "The phase sequence must not distinguish a wrong password from a right one.",
        )
        assertContentEquals(
            wrong.map { it.fractionDone },
            right.dropLast(1).map { it.fractionDone },
            "Nor must the fractions.",
        )
    }

    /**
     * The duress slot is the second one, and both are always tried. A shorter or differently-shaped
     * sequence for the decoy would tell an attacker standing over the phone which password they had
     * just been handed.
     */
    @Test
    fun theDuressPasswordProducesTheSameSequenceAsTheRealOne() = runBlocking {
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        DuressUnlocker.setupDuress(lockManager, duressPassword(), vaultWith(note()))
        lockManager.lock()

        val real = mutableListOf<UnlockProgress>()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()) { real += it })
        lockManager.lock()
        val duress = mutableListOf<UnlockProgress>()
        assertEquals(UnlockResult.DURESS, lockManager.unlock(duressPassword()) { duress += it })

        assertContentEquals(real.map { it.phase }, duress.map { it.phase })
        assertContentEquals(real.map { it.fractionDone }, duress.map { it.fractionDone })
    }

    /** A percentage that walks backwards reads as a bug even when the work behind it is honest. */
    @Test
    fun progressNeverGoesBackwards() = runBlocking {
        val seen = mutableListOf<UnlockProgress>()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()) { seen += it })

        assertEquals(0f, seen.first().fractionDone)
        assertTrue(seen.last().fractionDone <= 1f)
        seen.zipWithNext().forEach { (a, b) ->
            assertTrue(
                b.fractionDone >= a.fractionDone,
                "Progress went from ${a.fractionDone} to ${b.fractionDone}.",
            )
        }
    }

    /** The reporter is a default argument, so the three call sites that do not want it stay as they are. */
    @Test
    fun unlockStillWorksWithNoReporter() = runBlocking {
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
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

    private fun note(): VaultItem {
        val now = Instant.now()
        return VaultItem(
            id = UUID.nameUUIDFromBytes("progress-decoy".toByteArray()),
            title = "Believable Note",
            createdAt = now,
            updatedAt = now,
            content = Note("nothing here"),
        )
    }

    private fun vaultWith(item: VaultItem): Vault {
        val now = Instant.now()
        return Vault(items = listOf(item), createdAt = now, modifiedAt = now)
    }

    private fun cleanupFiles() {
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).delete()
        File(context.filesDir, "${AtomicVaultWriter.VAULT_FILE}.bak").delete()
    }

    private fun password(): CharArray = "progress-test-master".toCharArray()
    private fun duressPassword(): CharArray = "progress-test-duress".toCharArray()
}
