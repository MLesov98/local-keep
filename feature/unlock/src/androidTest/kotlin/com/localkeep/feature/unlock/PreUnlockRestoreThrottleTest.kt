package com.localkeep.feature.unlock

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.BiometricUnlock
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.VaultSubkeys
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.StaleContainerSweeper
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultHeaderCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.feature.backup.VaultImporter
import com.localkeep.feature.files.FileBlobStore
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The restore screen's lockout counts down and clears itself.
 *
 * The throttle exists because a rolling snapshot is sealed under the master password of the moment
 * it was taken, so an unthrottled check here is an oracle for the very secret the unlock screen
 * throttles. That part was right from the start. Its *display* was not: `checkSelectedBackup`
 * computed the seconds once and never again, so the sentence froze at its initial value and the
 * only way to learn the wait had ended was to guess — which a wrong guess extends. `retryCountdown`
 * was written about exactly that defect on the unlock screen; it was reintroduced one screen over.
 *
 * A one-second lockout — the first the backoff produces — is enough to prove it, and it keeps this
 * test to six Argon2id derivations at [KdfParams.FLOOR] rather than sixty.
 */
@RunWith(AndroidJUnit4::class)
class PreUnlockRestoreThrottleTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var keystoreManager: KeystoreManager
    private lateinit var lockManager: VaultLockManager
    private lateinit var viewModel: UnlockViewModel

    private val vaultPassword = "TheRealVaultPassword1"
    private val backupPassword = "TheBackupPassword2"

    private val vaultFile get() = File(context.filesDir, "vault.enc")
    private val backupsDir get() = File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR)

    @BeforeTest
    fun setUp() {
        clearFiles()
        keystoreManager = KeystoreManager(context)
        lockManager = VaultLockManager(context, keystoreManager)
        viewModel = UnlockViewModel(
            context = context,
            lockManager = lockManager,
            keystoreManager = keystoreManager,
            biometricUnlock = BiometricUnlock(keystoreManager),
            vaultImporter = VaultImporter(context, FileBlobStore(context), lockManager),
        )
    }

    @AfterTest
    fun tearDown() = clearFiles()

    private fun clearFiles() {
        vaultFile.delete()
        File(context.filesDir, "vault.enc.bak").delete()
        File(context.filesDir, "vault.enc.tmp").delete()
        backupsDir.deleteRecursively()
        File(context.filesDir, PreUnlockRestore.QUARANTINE_DIR).deleteRecursively()
    }

    private fun container(password: String): ByteArray {
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password.toCharArray(), salt, KdfParams.FLOOR)
        val dek = EnvelopeCrypto.generateDek()
        val header = VaultHeaderCodec.create(
            salt = salt,
            kdfParams = KdfParams.FLOOR,
            wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
            commitment = EnvelopeCrypto.slotCommitment(dek),
        )
        val stamp = Instant.parse("2026-09-13T00:00:00Z")
        val vault = Vault(
            items = listOf(
                VaultItem(
                    id = UUID.randomUUID(),
                    title = "sample",
                    createdAt = stamp,
                    updatedAt = stamp,
                    content = Note("secret note"),
                ),
            ),
            createdAt = stamp,
            modifiedAt = stamp,
        )
        return VaultFileCodec.encrypt(
            vault,
            VaultSubkeys.encKey(dek),
            header,
            VaultFileCodec.revokedRecoveryBlock(),
        )
    }

    private fun damaged(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        val region = VaultHeaderCodec.decode(copy).regions[0]
        for (i in region.offset until region.offset + minOf(64, region.length)) {
            copy[i] = (copy[i].toInt() xor 0xFF).toByte()
        }
        return copy
    }

    private fun waitUntil(what: String, timeoutMs: Long = 90_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun tryPassword(password: String) {
        viewModel.onRestorePasswordChanged(password)
        viewModel.checkSelectedBackup()
        waitUntil("the check to finish") { viewModel.state.value.restore?.isWorking == false }
    }

    @Test
    fun theLockoutSentenceCountsDownAndClearsItself() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(container(backupPassword))

        viewModel.onPasswordChanged(vaultPassword)
        viewModel.unlockWithPassword()
        waitUntil("the unlock to fail") { viewModel.state.value.error != null }
        viewModel.openRestore()
        waitUntil("the ring") { viewModel.state.value.restore?.isWorking == false }
        viewModel.selectSnapshot(viewModel.state.value.restore!!.snapshots.single())

        // Five wrong passwords is where VaultLockManager's own backoff starts, and this mirrors it.
        // The fifth sets the lockout; it does not yet report one, because the attempt it came from
        // was allowed.
        repeat(5) { tryPassword("definitely not the password $it") }
        assertEquals(
            UnlockError.Simple(R.string.unlock_restore_error_wrong_password),
            viewModel.state.value.restore?.error,
            "the fifth attempt is still an attempt, and must read as one",
        )

        // The sixth is refused before any derivation runs, and that is what shows the countdown.
        tryPassword("one more wrong password")
        val locked = viewModel.state.value.restore?.error
        assertIs<UnlockError.RetryAfter>(locked, "the sixth attempt must be refused with a wait")
        assertTrue(locked.seconds > 0, "a lockout of zero seconds says nothing; was ${locked.seconds}")

        // The whole defect, in one assertion: without startRestoreRetryCountdown nothing ever
        // revisits this field, so the sentence stays on screen forever.
        waitUntil("the lockout sentence to clear itself", timeoutMs = 15_000) {
            viewModel.state.value.restore?.error == null
        }
    }

    /**
     * The control: a wrong password that is *not* throttled must not be given a countdown, or every
     * ordinary mistake would erase its own explanation a second later.
     */
    @Test
    fun anUnthrottledWrongPasswordKeepsItsMessage() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(container(backupPassword))

        viewModel.onPasswordChanged(vaultPassword)
        viewModel.unlockWithPassword()
        waitUntil("the unlock to fail") { viewModel.state.value.error != null }
        viewModel.openRestore()
        waitUntil("the ring") { viewModel.state.value.restore?.isWorking == false }
        viewModel.selectSnapshot(viewModel.state.value.restore!!.snapshots.single())

        tryPassword("wrong once")
        Thread.sleep(3_000)

        assertEquals(
            UnlockError.Simple(R.string.unlock_restore_error_wrong_password),
            viewModel.state.value.restore?.error,
            "a single wrong password must keep saying so",
        )
    }
}
