package com.localkeep.feature.unlock

import android.content.Context
import android.net.Uri
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
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * **A pre-unlock restore must never be a pre-unlock overwrite.**
 *
 * This is the security property of the whole F34 flow, and it is the one an attacker tests. The
 * unlock screen is reachable by anyone holding the phone, so a restore route that wrote before it
 * verified would be a one-tap way to destroy a vault — or to swap in a container the holder *can*
 * open, on a device they cannot otherwise get into.
 *
 * Driven end to end through the real [UnlockViewModel] against real containers: no fakes, because
 * every fake here would be a fake of the one thing under test. Instrumented rather than a unit test
 * for the reason `UnlockViewModelBiometricInvalidationTest` gives — `KeystoreManager` opens
 * `AndroidKeyStore` in its constructor, which a bare JVM and Robolectric both lack.
 *
 * The test app has its own `filesDir`, so nothing here touches the device's real vault.
 */
@RunWith(AndroidJUnit4::class)
class PreUnlockRestoreNoWriteTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var keystoreManager: KeystoreManager
    private lateinit var lockManager: VaultLockManager
    private lateinit var viewModel: UnlockViewModel

    private val vaultPassword = "TheRealVaultPassword1"
    private val backupPassword = "TheBackupPassword2"

    private val vaultFile get() = File(context.filesDir, "vault.enc")
    private val backupsDir get() = File(context.filesDir, StaleContainerSweeper.BACKUPS_DIR)
    private val quarantineDir get() = File(context.filesDir, PreUnlockRestore.QUARANTINE_DIR)

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
    fun tearDown() {
        clearFiles()
    }

    private fun clearFiles() {
        vaultFile.delete()
        File(context.filesDir, "vault.enc.bak").delete()
        File(context.filesDir, "vault.enc.tmp").delete()
        backupsDir.deleteRecursively()
        quarantineDir.deleteRecursively()
    }

    /** A real container, sealed under [password] at the cheapest legal Argon2id cost. */
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

    /** The same container with its payload damaged in place — header and slot still perfect. */
    private fun damaged(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        val region = VaultHeaderCodec.decode(copy).regions[0]
        for (i in region.offset until region.offset + minOf(64, region.length)) {
            copy[i] = (copy[i].toInt() xor 0xFF).toByte()
        }
        return copy
    }

    private fun digest(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Gets the view-model into the state that offers the restore route, and returns the snapshot. */
    private fun reachRestoreScreen(): RestoreSnapshot {
        viewModel.onPasswordChanged(vaultPassword)
        viewModel.unlockWithPassword()
        waitUntil("the unlock to fail as unreadable") { viewModel.state.value.error != null }
        assertIs<UnlockError.Actionable>(
            viewModel.state.value.error,
            "a damaged container must offer the restore action",
        )

        viewModel.openRestore()
        waitUntil("the snapshot ring to be listed") {
            viewModel.state.value.restore?.isWorking == false
        }
        val snapshots = viewModel.state.value.restore?.snapshots.orEmpty()
        assertEquals(1, snapshots.size, "expected exactly one snapshot on the ring")
        return snapshots.single()
    }

    private fun waitUntil(what: String, timeoutMs: Long = 60_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /**
     * The message is not only *set*, it is **visible**.
     *
     * Every failure assertion in this class checked `restore.error` and stopped there, which proved
     * the state and not the screen. Measured on `emulator-5554` (2026-09-13) with the quarantine
     * blocked: `unlock_restore_error_not_kept` was set correctly and rendered *behind* the
     * confirmation dialog — `LkDialog` is a Compose `Dialog` in its own window and the screen shows
     * it on `phase == CONFIRM`, so the `uiautomator` tree held the unchanged dialog and no error
     * text at all. Three red-proofed failure branches, all invisible in practice.
     *
     * Phase is the whole check: the dialog is drawn from it, so an error that arrives while the
     * phase is still CONFIRM is an error nobody reads.
     */
    private fun assertErrorIsReachable() {
        assertEquals(
            RestorePhase.PROVE,
            viewModel.state.value.restore?.phase,
            "the error is rendered behind the confirmation dialog while the phase is CONFIRM",
        )
    }

    /**
     * The quarantine cannot be written, so nothing is written — and the user is told so.
     *
     * A regular file where the directory has to go is how `VaultLoader.quarantineContainers` is made
     * to fail without root: it refuses to `mkdirs` over one. This is the exact condition the device
     * repro used, and the branch whose message was invisible.
     */
    @Test
    fun aQuarantineThatCannotBeWrittenStopsTheRestoreAndSaysSo() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(container(backupPassword))
        File(context.filesDir, PreUnlockRestore.QUARANTINE_DIR).writeText("not a directory")
        val before = digest(vaultFile)

        val snapshot = reachRestoreScreen()
        viewModel.selectSnapshot(snapshot)
        viewModel.onRestorePasswordChanged(backupPassword)
        viewModel.checkSelectedBackup()
        waitUntil("the check to finish") { viewModel.state.value.restore?.isWorking == false }
        assertEquals(RestorePhase.CONFIRM, viewModel.state.value.restore?.phase)

        viewModel.confirmRestore()
        waitUntil("the refusal") { viewModel.state.value.restore?.isWorking == false }

        assertEquals(
            UnlockError.Simple(R.string.unlock_restore_error_not_kept),
            viewModel.state.value.restore?.error,
        )
        assertErrorIsReachable()
        assertEquals(before, digest(vaultFile), "vault.enc changed although the damage was not kept")
    }

    /**
     * The assigned property, in its sharpest form: a candidate the user cannot open leaves
     * `vault.enc` byte-identical and creates no quarantine at all.
     *
     * The wrong password here is the *vault's own* password — the realistic mistake, and the one
     * that makes the point: proving you can open the live vault proves nothing about the backup,
     * so it does not authorise replacing the live vault with that backup.
     */
    @Test
    fun aBackupThatDoesNotOpenLeavesTheVaultByteIdentical() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(container(backupPassword))
        val before = digest(vaultFile)

        val snapshot = reachRestoreScreen()
        viewModel.selectSnapshot(snapshot)
        viewModel.onRestorePasswordChanged(vaultPassword)
        viewModel.checkSelectedBackup()
        waitUntil("the backup check to finish") {
            viewModel.state.value.restore?.isWorking == false
        }

        val restore = viewModel.state.value.restore
        assertEquals(RestorePhase.PROVE, restore?.phase, "a failed check must not reach the confirmation")
        assertEquals(
            UnlockError.Simple(R.string.unlock_restore_error_wrong_password),
            restore?.error,
        )
        assertEquals(before, digest(vaultFile), "vault.enc changed after a failed check")
        assertFalse(quarantineDir.exists(), "nothing may be quarantined before the password is proven")
    }

    /**
     * The same, for a candidate whose password is right but whose payload is damaged.
     *
     * This is the case a key-only check gets wrong: the DEK unwraps, the commitment matches, and a
     * restore that stopped there would replace a vault that does not open with another one that
     * does not open — destroying the `.bak` on the way past.
     */
    @Test
    fun aBackupWhosePayloadIsDamagedLeavesTheVaultByteIdentical() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(damaged(container(backupPassword)))
        val before = digest(vaultFile)

        val snapshot = reachRestoreScreen()
        viewModel.selectSnapshot(snapshot)
        viewModel.onRestorePasswordChanged(backupPassword)
        viewModel.checkSelectedBackup()
        waitUntil("the backup check to finish") {
            viewModel.state.value.restore?.isWorking == false
        }

        val restore = viewModel.state.value.restore
        assertEquals(RestorePhase.PROVE, restore?.phase)
        assertEquals(
            UnlockError.Simple(R.string.unlock_restore_error_unreadable),
            restore?.error,
            "a damaged payload must not be reported as a wrong password",
        )
        assertEquals(before, digest(vaultFile), "vault.enc changed after an unreadable candidate")
        assertFalse(quarantineDir.exists(), "nothing may be quarantined for a candidate that will not open")
    }

    /**
     * `confirmRestore` is not a second entry point. Called before the confirmation step it must do
     * nothing at all — what stops a future caller, or a replayed click, from writing.
     *
     * Named for the *phase* guard, which is what it actually exercises: measured 2026-09-13, with
     * the proof guard removed this test still passed, because the phase guard fires first. The
     * proof guard has its own test below, which is the one that watches `vault.enc` change.
     */
    @Test
    fun confirmingBeforeTheConfirmationStepWritesNothing() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(container(backupPassword))
        val before = digest(vaultFile)

        val snapshot = reachRestoreScreen()
        viewModel.selectSnapshot(snapshot)
        viewModel.confirmRestore()
        Thread.sleep(1_500)

        assertEquals(before, digest(vaultFile), "vault.enc changed without a proven candidate")
        assertFalse(quarantineDir.exists(), "nothing may be quarantined without a proven candidate")
    }



    // ---- the picked-file half (SAF). Same gate, and it has to be, because this file is chosen by
    // whoever is holding the phone. `Uri.fromFile` rather than a fake ContentProvider: the importer
    // reads through `contentResolver.openInputStream`, which resolves `file:` exactly as it does
    // `content:`, so this exercises the real read path rather than a stand-in for it.

    private fun pickedFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, name).apply { writeBytes(bytes) }

    /** A picked file the user cannot open changes nothing, and is quarantined nothing for. */
    @Test
    fun aPickedFileThatDoesNotOpenLeavesTheVaultByteIdentical() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(container(backupPassword))
        val picked = pickedFile("picked-wrong.vault", container("SomeThirdPassword4"))
        val before = digest(vaultFile)

        reachRestoreScreen()
        viewModel.selectPickedFile(Uri.fromFile(picked))
        viewModel.onRestorePasswordChanged(backupPassword)
        viewModel.checkSelectedBackup()
        waitUntil("the check to finish") { viewModel.state.value.restore?.isWorking == false }

        assertEquals(RestorePhase.PROVE, viewModel.state.value.restore?.phase)
        assertEquals(
            UnlockError.Simple(R.string.unlock_restore_error_wrong_password),
            viewModel.state.value.restore?.error,
        )
        assertEquals(before, digest(vaultFile), "vault.enc changed for a picked file that did not open")
        assertFalse(quarantineDir.exists(), "nothing may be quarantined before the password is proven")
        picked.delete()
        Unit
    }

    /**
     * The case a content URI makes real: the bytes behind it are not the app's to hold still.
     *
     * A SAF provider may answer a second read differently — a synced folder, a file the user edits
     * in another app, or a hostile provider doing it on purpose. The proof is bound to the bytes
     * that opened, so the write refuses rather than replacing the vault with a container nobody
     * ever proved.
     */
    @Test
    fun aPickedFileSwappedAfterItWasProvenIsRefused() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(container(backupPassword))
        val picked = pickedFile("picked-swap.vault", container(backupPassword))
        val before = digest(vaultFile)

        reachRestoreScreen()
        viewModel.selectPickedFile(Uri.fromFile(picked))
        viewModel.onRestorePasswordChanged(backupPassword)
        viewModel.checkSelectedBackup()
        waitUntil("the check to finish") { viewModel.state.value.restore?.isWorking == false }
        assertEquals(RestorePhase.CONFIRM, viewModel.state.value.restore?.phase)

        val swapped = container("SomeThirdPassword4")
        picked.writeBytes(swapped)

        viewModel.confirmRestore()
        waitUntil("the refusal") { viewModel.state.value.restore?.isWorking == false }

        assertEquals(before, digest(vaultFile), "vault.enc was replaced with bytes nobody proved")
        assertFalse(swapped.contentEquals(vaultFile.readBytes()), "the swapped file became the vault")
        assertFalse(quarantineDir.exists(), "nothing may be quarantined for a file that was swapped")
        assertEquals(
            UnlockError.Simple(R.string.unlock_restore_error_unreadable),
            viewModel.state.value.restore?.error,
        )
        assertErrorIsReachable()
        picked.delete()
        Unit
    }

    /** The control for the picked-file half: a proven file does replace the vault, and keeps the old one. */
    @Test
    fun aProvenPickedFileReplacesTheVaultAndKeepsTheDamagedOne() = runBlocking {
        val damagedBytes = damaged(container(vaultPassword))
        vaultFile.writeBytes(damagedBytes)
        File(context.filesDir, "vault.enc.bak").writeBytes(damagedBytes)
        backupsDir.mkdirs()
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(container(backupPassword))
        val good = container("SomeThirdPassword4")
        val picked = pickedFile("picked-good.vault", good)

        reachRestoreScreen()
        viewModel.selectPickedFile(Uri.fromFile(picked))
        viewModel.onRestorePasswordChanged("SomeThirdPassword4")
        viewModel.checkSelectedBackup()
        waitUntil("the check to finish") { viewModel.state.value.restore?.isWorking == false }
        assertEquals(RestorePhase.CONFIRM, viewModel.state.value.restore?.phase)

        viewModel.confirmRestore()
        waitUntil("the restore to finish") { viewModel.state.value.restore?.phase == RestorePhase.DONE }

        assertTrue(good.contentEquals(vaultFile.readBytes()), "the proven file did not become the vault")
        val kept = quarantineDir.listFiles().orEmpty().map { it.name }
        assertTrue(kept.any { it.startsWith("vault.enc-") }, "the damaged primary was not kept: $kept")
        assertTrue(kept.any { it.startsWith("vault.enc.bak-") }, "the damaged .bak was not kept: $kept")
        picked.delete()
        Unit
    }

    /**
     * The proof is about **bytes**, not about a filename.
     *
     * The user authorises "this container, which I just opened". What gets written is read off disk
     * again a moment later, so a proof bound to the path alone authorises whatever is at that path
     * *then*. This swaps the file between the check and the confirmation — the honest form of a
     * check-then-use gap — and the write must refuse rather than replace the vault with bytes
     * nobody ever opened.
     *
     * This is also the test that watches `vault.enc` change when the guard is removed; the
     * phase-guard test above cannot, because the phase guard stops it first.
     */
    @Test
    fun aBackupSwappedAfterItWasProvenIsRefused() = runBlocking {
        vaultFile.writeBytes(damaged(container(vaultPassword)))
        backupsDir.mkdirs()
        val snapshotFile = File(backupsDir, "vault-20260913-090000.enc")
        snapshotFile.writeBytes(container(backupPassword))
        val before = digest(vaultFile)

        val snapshot = reachRestoreScreen()
        viewModel.selectSnapshot(snapshot)
        viewModel.onRestorePasswordChanged(backupPassword)
        viewModel.checkSelectedBackup()
        waitUntil("the backup check to finish") {
            viewModel.state.value.restore?.isWorking == false
        }
        assertEquals(RestorePhase.CONFIRM, viewModel.state.value.restore?.phase)

        // Someone else's container, at the same path, after the proof.
        val swapped = container("SomeOtherPassword3")
        snapshotFile.writeBytes(swapped)

        viewModel.confirmRestore()
        waitUntil("the refusal") { viewModel.state.value.restore?.isWorking == false }

        assertEquals(before, digest(vaultFile), "vault.enc was replaced with bytes nobody proved")
        assertFalse(swapped.contentEquals(vaultFile.readBytes()), "the swapped container became the vault")
        assertFalse(quarantineDir.exists(), "nothing may be quarantined for a candidate that was swapped")
        assertEquals(
            UnlockError.Simple(R.string.unlock_restore_error_unreadable),
            viewModel.state.value.restore?.error,
        )
        assertErrorIsReachable()
    }

    /**
     * The control, and the other half of the claim: when the password *does* open the candidate,
     * the replacement happens **and** the damaged container is kept.
     *
     * Without this the two tests above would pass just as happily if the restore never worked at
     * all, which is the failure mode a "nothing was written" assertion cannot see on its own.
     */
    @Test
    fun aProvenBackupReplacesTheVaultAndKeepsTheDamagedOne() = runBlocking {
        val damagedBytes = damaged(container(vaultPassword))
        vaultFile.writeBytes(damagedBytes)
        File(context.filesDir, "vault.enc.bak").writeBytes(damagedBytes)
        backupsDir.mkdirs()
        val good = container(backupPassword)
        File(backupsDir, "vault-20260913-090000.enc").writeBytes(good)

        val snapshot = reachRestoreScreen()
        viewModel.selectSnapshot(snapshot)
        viewModel.onRestorePasswordChanged(backupPassword)
        viewModel.checkSelectedBackup()
        waitUntil("the backup check to finish") {
            viewModel.state.value.restore?.isWorking == false
        }
        assertEquals(RestorePhase.CONFIRM, viewModel.state.value.restore?.phase)

        viewModel.confirmRestore()
        waitUntil("the restore to finish") {
            viewModel.state.value.restore?.phase == RestorePhase.DONE
        }

        assertTrue(good.contentEquals(vaultFile.readBytes()), "the proven backup did not become the vault")
        val kept = quarantineDir.listFiles().orEmpty().map { it.name }
        assertTrue(
            kept.any { it.startsWith("vault.enc-") },
            "the damaged primary was not kept: $kept",
        )
        assertTrue(
            kept.any { it.startsWith("vault.enc.bak-") },
            "the damaged .bak was not kept, and AtomicVaultWriter has now overwritten it: $kept",
        )
        assertTrue(
            damagedBytes.contentEquals(File(quarantineDir, kept.first { it.startsWith("vault.enc-") }).readBytes()),
            "the kept copy is not the damaged container",
        )
    }
}
