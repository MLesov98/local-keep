package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.StreamingAead
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.MatchStrategy
import com.localkeep.core.model.Note
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultLockManagerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    @BeforeTest
    fun setup() {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
        repository = VaultRepository(lockManager)
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    @Test
    fun masterUnlock_consumesPasswordAndPublishesRealVault() = runBlocking {
        val password = password()

        assertEquals(UnlockResult.REAL, lockManager.unlock(password))

        assertTrue(password.all { it == '\u0000' })
        val state = lockManager.lockState.value as LockState.Unlocked
        assertFalse(state.isDuress)
        assertTrue(state.vault.items.isEmpty())
    }

    @Test
    fun wrongPassword_consumesInputAndKeepsVaultLocked() = runBlocking {
        val password = "wrong password".toCharArray()

        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlock(password))

        assertTrue(password.all { it == '\u0000' })
        assertEquals(LockState.Locked, lockManager.lockState.value)
    }

    /**
     * The right password against a damaged payload is not a wrong password.
     *
     * Region 0's ciphertext still carries a valid header and a slot whose wrapped key unwraps and
     * matches its commitment — so the password authenticated — but the payload will not decrypt.
     * That used to fall through to the same `null` a mistyped password produces: the user was told
     * "Wrong password", the attempt was counted against the backoff, and the healthy `.bak` sitting
     * next to the damaged file was never consulted, because the loader stops at the first container
     * whose *header* parses.
     */
    @Test
    fun corruptPayload_opensFromTheBackupAndRepairsThePrimary() = runBlocking {
        // A second write, so `vault.enc.bak` holds a complete, openable container.
        lockManager.unlock(password())
        repository.addItem(item("kept", "Kept Login"))
        lockManager.lock()
        corruptFirstRegion(File(context.filesDir, AtomicVaultWriter.VAULT_FILE))

        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))

        // Repaired in place: the primary is the container that actually opened, so the next save
        // does not splice this session's region into damaged bytes.
        lockManager.lock()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
    }

    @Test
    fun corruptPayloadWithNoBackup_reportsUnreadableAndDoesNotThrottle() = runBlocking {
        corruptFirstRegion(File(context.filesDir, AtomicVaultWriter.VAULT_FILE))
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).delete()

        // Well past MAX_FAILED_ATTEMPTS. A damaged file must never earn a lockout: the backoff
        // exists to slow guessing, and the only thing it slows here is the user's route to restore.
        repeat(MAX_FAILED_ATTEMPTS + 2) {
            assertEquals(UnlockResult.VAULT_UNREADABLE, lockManager.unlock(password()))
        }

        // Still no lockout, and a genuine wrong password is still reported as one.
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlock("nope".toCharArray()))
    }

    /**
     * The autofill-vs-editor collision. An item screen holds a copy of the item from the moment it
     * opens; the autofill Save-confirm activity shares this lock manager and can write a new
     * password into the vault while it is open. The editor's save replaced the item wholesale, so
     * the old password came back — silently, leaving a password manager holding a dead password.
     */
    @Test
    fun updateItem_withAStaleExpectedTimestamp_keepsTheNewerPassword() = runBlocking {
        lockManager.unlock(password())
        val opened = login("acct", "Account", password = "original")
        repository.addItem(opened)
        // What the editor read when it opened.
        val editorsCopy = repository.getAllItems().single()

        // Meanwhile, autofill writes a new password.
        val fromAutofill = editorsCopy.copy(
            updatedAt = editorsCopy.updatedAt.plusSeconds(30),
            content = (editorsCopy.content as Login).copy(
                password = "changed-by-autofill",
                passwordChangedAt = editorsCopy.updatedAt.plusSeconds(30),
            ),
        )
        assertEquals(UpdateOutcome.UPDATED, repository.updateItem(fromAutofill))

        // Now the editor saves a title change against its stale copy.
        val outcome = repository.updateItem(
            editorsCopy.copy(title = "Renamed", updatedAt = Instant.now()),
            expectedUpdatedAt = editorsCopy.updatedAt,
        )

        assertEquals(UpdateOutcome.KEPT_NEWER_PASSWORD, outcome)
        val stored = repository.getAllItems().single()
        assertEquals("Renamed", stored.title, "the user's own edit must still land")
        assertEquals("changed-by-autofill", (stored.content as Login).password)
    }

    /** An editor whose copy is current writes exactly what it was given. */
    @Test
    fun updateItem_withACurrentExpectedTimestamp_writesTheEditVerbatim() = runBlocking {
        lockManager.unlock(password())
        repository.addItem(login("acct", "Account", password = "original"))
        val current = repository.getAllItems().single()

        val outcome = repository.updateItem(
            current.copy(
                title = "Renamed",
                updatedAt = Instant.now(),
                content = (current.content as Login).copy(
                    password = "typed-by-the-user",
                    passwordChangedAt = Instant.now(),
                ),
            ),
            expectedUpdatedAt = current.updatedAt,
        )

        assertEquals(UpdateOutcome.UPDATED, outcome)
        val stored = repository.getAllItems().single()
        assertEquals("typed-by-the-user", (stored.content as Login).password)
    }

    @Test
    fun lock_dropsStateAndZeroizesHeldDek() = runBlocking {
        lockManager.unlock(password())
        val prior = lockManager.lockState.value as LockState.Unlocked

        lockManager.lock()

        assertEquals(LockState.Locked, lockManager.lockState.value)
        assertContentEquals(ByteArray(CryptoConstants.KEY_LEN), prior.dek)
    }

    @Test
    fun panicLock_blocksBiometricUntilSuccessfulMasterUnlock() = runBlocking {
        lockManager.unlock(password())
        lockManager.panicLock()

        assertTrue(lockManager.isBiometricUnlockBlocked)
        assertEquals(
            UnlockResult.BIOMETRIC_UNAVAILABLE,
            lockManager.unlockWithBiometric(Cipher.getInstance("AES/GCM/NoPadding")),
        )

        lockManager.unlock(password())
        assertFalse(lockManager.isBiometricUnlockBlocked)
    }

    @Test
    fun fifthFailure_startsBackoffAndSuccessfulRetryResetsIt() = runBlocking {
        repeat(5) {
            assertEquals(
                    UnlockResult.WRONG_CREDENTIAL,
                    lockManager.unlock("wrong-$it".toCharArray()),
                )
        }
        val throttledPassword = password()

        assertFailsWith<VaultLockedException> {
            lockManager.unlock(throttledPassword)
        }
        assertTrue(throttledPassword.all { it == '\u0000' })

        delay(1_100)
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        lockManager.lock()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
    }

    @Test
    fun autoLock_expiresAndZeroizesDek() = runBlocking {
        lockManager.autoLockTimeoutMs = 100
        lockManager.unlock(password())
        val prior = lockManager.lockState.value as LockState.Unlocked

        delay(250)

        assertEquals(LockState.Locked, lockManager.lockState.value)
        assertContentEquals(ByteArray(CryptoConstants.KEY_LEN), prior.dek)
    }

    @Test
    fun authenticatedCipherPath_unlocksBiometricWrappedDek() = runBlocking {
        assumeTrue("requires secure device lock", KeystoreManager(context).isDeviceSecure())
        lockManager.unlock(password())
        val dek = (lockManager.lockState.value as LockState.Unlocked).dek.copyOf()
        lockManager.lock()
        val wrappingKey = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
        try {
            val secretKey = SecretKeySpec(wrappingKey, "AES")
            val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }
            DeviceKeyStore.save(
                context,
                com.localkeep.core.crypto.WrappedDek(
                    nonce = encryptCipher.iv,
                    ciphertext = encryptCipher.doFinal(dek),
                ),
            )
            val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(CryptoConstants.AEAD_TAG_BITS, encryptCipher.iv),
                )
            }

            assertEquals(UnlockResult.REAL, lockManager.unlockWithBiometric(decryptCipher))
        } finally {
            Zeroizer.wipe(dek)
            Zeroizer.wipe(wrappingKey)
        }
    }

    @Test
    fun enableBiometricUnlock_wrapsCurrentDekForNextUnlock() = runBlocking {
        lockManager.unlock(password())
        val wrappingKey = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
        try {
            val secretKey = SecretKeySpec(wrappingKey, "AES")
            val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }

            assertTrue(lockManager.enableBiometricUnlock(encryptCipher))
            val wrapped = checkNotNull(DeviceKeyStore.load(context))
            lockManager.lock()
            val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(CryptoConstants.AEAD_TAG_BITS, wrapped.nonce),
                )
            }

            assertEquals(UnlockResult.REAL, lockManager.unlockWithBiometric(decryptCipher))
        } finally {
            Zeroizer.wipe(wrappingKey)
        }
    }

    /**
     * A failed biometric attempt must not cost the master password its next try.
     *
     * Found on a device: cancelling the fingerprint prompt and then typing the correct master
     * password got "Wrong password", and kept getting it until the process was killed. A biometric
     * failure is not evidence about the password, so it must not consume the password's attempt
     * budget — the two credentials are throttled independently.
     */
    @Test
    fun failedBiometricUnlock_leavesTheMasterPasswordWorking() = runBlocking {
        val wrappingKey = enrolBiometric()
        try {
            lockManager.lock()

            assertEquals(
                    UnlockResult.BIOMETRIC_UNAVAILABLE,
                    lockManager.unlockWithBiometric(failingCipher()),
                )

            assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        } finally {
            Zeroizer.wipe(wrappingKey)
        }
    }

    /**
     * The same, repeated until the *biometric* path locks itself out.
     *
     * This is the shape the device repro actually had: several cancelled prompts in a row, then a
     * correct password. Biometrics earn their own backoff — attempt six is refused — but that
     * lockout must stay on the biometric side. The master password was never tried, so it cannot
     * have failed, and it must still open the vault.
     */
    @Test
    fun repeatedBiometricFailures_lockOutBiometricsOnly() = runBlocking {
        val wrappingKey = enrolBiometric()
        try {
            lockManager.lock()

            repeat(MAX_FAILED_ATTEMPTS) {
                assertEquals(
                    UnlockResult.BIOMETRIC_UNAVAILABLE,
                    lockManager.unlockWithBiometric(failingCipher()),
                )
            }
            assertFailsWith<VaultLockedException> {
                lockManager.unlockWithBiometric(failingCipher())
            }

            assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        } finally {
            Zeroizer.wipe(wrappingKey)
        }
    }

    /** And the reverse: a password lockout must not spill onto biometrics. */
    @Test
    fun passwordLockout_doesNotBlockBiometricUnlock() = runBlocking {
        val wrappingKey = enrolBiometric()
        try {
            val wrapped = checkNotNull(DeviceKeyStore.load(context))
            lockManager.lock()
            repeat(MAX_FAILED_ATTEMPTS) {
                assertEquals(
                    UnlockResult.WRONG_CREDENTIAL,
                    lockManager.unlock("wrong-$it".toCharArray()),
                )
            }
            assertFailsWith<VaultLockedException> { lockManager.unlock(password()) }

            val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(wrappingKey, "AES"),
                    GCMParameterSpec(CryptoConstants.AEAD_TAG_BITS, wrapped.nonce),
                )
            }
            assertEquals(UnlockResult.REAL, lockManager.unlockWithBiometric(decryptCipher))
        } finally {
            Zeroizer.wipe(wrappingKey)
        }
    }

    /** A biometric failure must still not reset a lockout the *password* has earned. */
    @Test
    fun biometricFailure_doesNotClearAnExistingPasswordLockout() = runBlocking {
        val wrappingKey = enrolBiometric()
        try {
            lockManager.lock()
            repeat(5) {
                assertEquals(
                    UnlockResult.WRONG_CREDENTIAL,
                    lockManager.unlock("wrong-$it".toCharArray()),
                )
            }

            assertEquals(
                    UnlockResult.BIOMETRIC_UNAVAILABLE,
                    lockManager.unlockWithBiometric(failingCipher()),
                )

            // `assertFailsWith` returns the exception, and a JUnit test method must be void — so
            // the result is deliberately discarded rather than left as the block's value.
            assertFailsWith<VaultLockedException> { lockManager.unlock(password()) }
            Unit
        } finally {
            Zeroizer.wipe(wrappingKey)
        }
    }

    /**
     * Leaves a device-wrapped DEK on disk and the vault unlocked, and returns the wrapping key so
     * the caller can wipe it. [failingCipher] then stands in for a prompt the user cancelled.
     */
    private suspend fun enrolBiometric(): ByteArray {
        lockManager.unlock(password())
        val wrappingKey = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"))
        }
        assertTrue(lockManager.enableBiometricUnlock(encryptCipher))
        return wrappingKey
    }

    /**
     * A cipher that carries the enrolled nonce but the wrong key, so `doFinal` fails its AEAD tag.
     *
     * That is the same code path a cancelled prompt takes on a real device: the cipher is never
     * completed, so unwrapping the device DEK throws rather than returning a wrong answer.
     */
    private fun failingCipher(): Cipher {
        val nonce = checkNotNull(DeviceKeyStore.load(context)).nonce
        val wrongKey = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(wrongKey, "AES"),
                GCMParameterSpec(CryptoConstants.AEAD_TAG_BITS, nonce),
            )
        }
    }

    @Test
    fun repositoryCrudTrashAndQueries_persistAcrossRelock() = runBlocking {
        lockManager.unlock(password())
        val now = Instant.now()
        val item = VaultItem(
            id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
            title = "Example Login",
            favorite = true,
            createdAt = now,
            updatedAt = now,
            content = Login(
                username = "alice",
                password = "secret",
                uris = listOf(UriMatch("https://example.com", MatchStrategy.DOMAIN)),
                androidPackages = listOf("com.example.app"),
            ),
        )

        repository.addItem(item)
        assertEquals(1, repository.getFavorites().size)
        assertEquals(1, repository.search("alice").size)
        assertEquals(1, repository.findMatching("login.example.com", null).size)
        assertEquals(1, repository.findMatching(null, "com.example.app").size)

        repository.moveToTrash(item.id)
        assertTrue(repository.getAllItems().isEmpty())
        assertEquals(1, repository.getTrashItems().size)
        repository.restoreFromTrash(item.id)
        repository.updateItem(item.copy(title = "Updated"))

        lockManager.lock()
        lockManager.unlock(password())
        assertEquals("Updated", repository.getAllItems().single().title)

        repository.moveToTrash(item.id)
        repository.purgeTrash()
        assertTrue(repository.getTrashItems().isEmpty())
    }

    @Test
    fun batchMutation_emitsOneSaveAndSurvivesRelock() = runBlocking {
        lockManager.unlock(password())
        val saved = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { lockManager.onSaved.first() }
        }
        val now = Instant.now()

        repository.mutate { mutator ->
            repeat(10) { index ->
                mutator.items += VaultItem(
                    id = UUID.nameUUIDFromBytes("batch-$index".toByteArray()),
                    title = "Batch $index",
                    createdAt = now,
                    updatedAt = now,
                    content = Note("Body $index"),
                )
            }
        }

        saved.await()
        lockManager.lock()
        lockManager.unlock(password())
        assertEquals(10, repository.getAllItems().size)
    }

    @Test
    fun concurrentAdds_doNotLoseUpdates() = runBlocking {
        lockManager.unlock(password())
        val now = Instant.now()

        coroutineScope {
            (1..8).map { index ->
                async {
                    repository.addItem(
                        VaultItem(
                            id = UUID.nameUUIDFromBytes("concurrent-$index".toByteArray()),
                            title = "Concurrent $index",
                            createdAt = now,
                            updatedAt = now,
                            content = Note("$index"),
                        ),
                    )
                }
            }.awaitAll()
        }

        assertEquals(8, repository.getAllItems().size)
    }

    @Test
    fun reauthenticate_usesCurrentSlotAndConsumesPasswords() = runBlocking {
        lockManager.unlock(password())
        val correct = password()
        val wrong = "wrong".toCharArray()

        assertTrue(lockManager.reauthenticate(correct))
        assertFalse(lockManager.reauthenticate(wrong))
        assertTrue(correct.all { it == '\u0000' })
        assertTrue(wrong.all { it == '\u0000' })
    }

    @Test
    fun reauthenticate_afterAutoLock_throwsVaultSessionEndedNotBareIllegalState() = runBlocking {
        // Found 2026-08-06 (medium severity). When the auto-lock timer fires while
        // the autofill reprompt sits open, reauthenticate used to throw a bare `error("vault is
        // locked")` — an IllegalStateException that AuthActivity's catch-all collapsed into
        // VAULT_UNREADABLE, telling the user their vault was corrupted when it had merely re-locked.
        // The typed exception is what lets the gate say "the vault locked" instead.
        lockManager.unlock(password())
        val exception = assertFailsWith<VaultSessionEndedException> {
            lockManager.lock()
            lockManager.reauthenticate(password())
        }
        assertEquals("reauthenticate", exception.operation)
        // Reauthenticating after a successful unlock still works, so the throw is lock-state
        // specific rather than a broken method.
        lockManager.unlock(password())
        assertTrue(lockManager.reauthenticate(password()))
    }

    @Test
    fun duressSetupAndBothRegionSaves_surviveManagerRestart() = runBlocking {
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        val realItem = item("real", "Real Secret")
        repository.addItem(realItem)
        val decoyVault = vaultWith(item("decoy", "Believable Login"))
        val setupPassword = duressPassword()

        DuressUnlocker.setupDuress(lockManager, setupPassword, decoyVault)

        assertTrue(setupPassword.all { it == '\u0000' })
        assertTrue(DuressUnlocker.isConfigured(lockManager))
        restartManager()
        assertEquals(UnlockResult.DURESS, lockManager.unlock(duressPassword()))
        assertEquals("Believable Login", repository.getAllItems().single().title)
        repository.addItem(item("decoy-added", "Decoy Added"))

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        assertEquals(listOf("Real Secret"), repository.getAllItems().map { it.title })
        repository.addItem(item("real-added", "Real Added"))

        restartManager()
        assertEquals(UnlockResult.DURESS, lockManager.unlock(duressPassword()))
        assertEquals(
            setOf("Believable Login", "Decoy Added"),
            repository.getAllItems().map { it.title }.toSet(),
        )

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        assertEquals(
            setOf("Real Secret", "Real Added"),
            repository.getAllItems().map { it.title }.toSet(),
        )
    }

    @Test
    fun removeDuress_randomizesFillerAndInvalidatesDuressPassword() = runBlocking {
        lockManager.unlock(password())
        DuressUnlocker.setupDuress(
            lockManager,
            duressPassword(),
            vaultWith(item("decoy", "Decoy")),
        )
        assertTrue(DuressUnlocker.isConfigured(lockManager))

        DuressUnlocker.removeDuress(lockManager)

        assertFalse(DuressUnlocker.isConfigured(lockManager))
        restartManager()
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlock(duressPassword()))
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
    }

    @Test
    fun setupRejectsMasterPasswordAndConsumesIt() = runBlocking {
        lockManager.unlock(password())
        val duplicate = password()

        assertFailsWith<IllegalArgumentException> {
            DuressUnlocker.setupDuress(
                lockManager,
                duplicate,
                vaultWith(item("decoy", "Decoy")),
            )
        }

        assertTrue(duplicate.all { it == '\u0000' })
        assertFalse(DuressUnlocker.isConfigured(lockManager))
    }

    @Test
    fun duressSessionCannotConfigureOrRemoveDuress() = runBlocking {
        lockManager.unlock(password())
        DuressUnlocker.setupDuress(
            lockManager,
            duressPassword(),
            vaultWith(item("decoy", "Decoy")),
        )
        restartManager()
        lockManager.unlock(duressPassword())
        val replacementPassword = "replacement-duress-password".toCharArray()

        assertFailsWith<IllegalStateException> {
            DuressUnlocker.setupDuress(
                lockManager,
                replacementPassword,
                vaultWith(item("other", "Other")),
            )
        }
        assertTrue(replacementPassword.all { it == '\u0000' })
        assertFailsWith<IllegalStateException> {
            DuressUnlocker.removeDuress(lockManager)
        }
        Unit
    }

    @Test
    fun passwordChange_rotatesRealKeyAndPreservesRealAndDuressAccess() = runBlocking {
        lockManager.unlock(password())
        repository.addItem(item("real", "Real"))
        DuressUnlocker.setupDuress(
            lockManager,
            duressPassword(),
            vaultWith(item("decoy", "Decoy")),
        )
        val before = File(context.filesDir, AtomicVaultWriter.VAULT_FILE).readBytes()
        val beforeHeader = VaultHeaderCodec.decode(before)
        val current = password()
        val replacement = newPassword()

        val result = MasterPasswordChanger.changePassword(
            lockManager,
            currentPassword = current,
            newPassword = replacement,
        )

        assertTrue(result.success)
        // No kit was ever saved in this vault, so there was none to void. This line asserted true
        // until 2026-10-04 and pinned the defect `passwordChange_withNoKitReportsNoneVoided` names.
        assertFalse(result.oldRecoveryKitVoided)
        assertTrue(current.all { it == '\u0000' })
        assertTrue(replacement.all { it == '\u0000' })
        val after = File(context.filesDir, AtomicVaultWriter.VAULT_FILE).readBytes()
        val afterHeader = VaultHeaderCodec.decode(after)
        // The decoy is keyed by a password nobody entered here, so its region has to come across
        // byte for byte while the real region is resealed under the new key.
        val decoy = beforeHeader.regions[1]
        assertContentEquals(
            before.copyOfRange(decoy.offset, decoy.offset + decoy.length),
            after.copyOfRange(decoy.offset, decoy.offset + decoy.length),
        )
        assertEquals(beforeHeader.slots[1], afterHeader.slots[1])
        assertFalse(beforeHeader.slots[0].salt.contentEquals(afterHeader.slots[0].salt))
        assertFalse(
            beforeHeader.slots[0].commitment.contentEquals(afterHeader.slots[0].commitment),
        )

        restartManager()
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlock(password()))
        assertEquals(UnlockResult.REAL, lockManager.unlock(newPassword()))
        assertEquals("Real", repository.getAllItems().single().title)
        restartManager()
        assertEquals(UnlockResult.DURESS, lockManager.unlock(duressPassword()))
        assertEquals("Decoy", repository.getAllItems().single().title)
    }

    @Test
    fun passwordChange_voidsTheOldRecoveryKitAndTheStaleDeviceKey() = runBlocking {
        // The password used to be re-wrapped around the same data key, so every other route to that
        // key survived the change: the 24 words printed before it still opened the vault, and the
        // user was told the password had been changed and nothing else. Someone changing their
        // password because it had been seen kept a live bypass they had no way to revoke.
        lockManager.unlock(password())
        repository.addItem(item("real", "Real"))
        val oldMnemonic = assertNotNull(saveANewRecoveryKit())
        DeviceKeyStore.save(
            context,
            com.localkeep.core.crypto.WrappedDek(
                nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
                ciphertext = SecureRandomProvider.nextBytes(
                    CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
                ),
            ),
        )
        val current = password()
        val replacement = newPassword()

        val result = MasterPasswordChanger.changePassword(lockManager, current, replacement)

        assertTrue(result.success)
        assertTrue(result.oldRecoveryKitVoided)
        assertNull(DeviceKeyStore.load(context))

        restartManager()
        assertEquals(
            UnlockResult.WRONG_CREDENTIAL,
            lockManager.unlockWithRecoveryKit(oldMnemonic),
        )
        assertEquals(UnlockResult.REAL, lockManager.unlock(newPassword()))
        assertEquals("Real", repository.getAllItems().single().title)
        val newMnemonic = assertNotNull(saveANewRecoveryKit())

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(newMnemonic))
        assertEquals("Real", repository.getAllItems().single().title)
    }

    /**
     * Device, 2026-10-04: with Settings › Recovery Kit reading "Not saved yet", every password
     * change still said "Your old Recovery Kit no longer works". The rotation reported a voided kit
     * unconditionally, and Settings appends that sentence exactly when the result says so.
     */
    @Test
    fun passwordChange_withNoKitReportsNoneVoided() = runBlocking {
        lockManager.unlock(password())

        val result = MasterPasswordChanger.changePassword(lockManager, password(), newPassword())

        assertTrue(result.success)
        assertFalse(result.oldRecoveryKitVoided, "this vault never had a Recovery Kit to void")
    }

    /** The tester's exact sequence: the first change voids the kit, so the next has none left. */
    @Test
    fun passwordChange_aSecondChangeReportsNoKitLeftToVoid() = runBlocking {
        lockManager.unlock(password())
        assertNotNull(saveANewRecoveryKit())

        val first = MasterPasswordChanger.changePassword(lockManager, password(), newPassword())
        assertTrue(first.success)
        assertTrue(first.oldRecoveryKitVoided, "control: a saved kit is reported voided")

        val second = MasterPasswordChanger.changePassword(lockManager, newPassword(), password())
        assertTrue(second.success)
        assertFalse(second.oldRecoveryKitVoided, "the first change already revoked it")
    }

    @Test
    fun passwordChange_rejectsWrongCurrentAndDuressCollision() = runBlocking {
        lockManager.unlock(password())
        DuressUnlocker.setupDuress(
            lockManager,
            duressPassword(),
            vaultWith(item("decoy", "Decoy")),
        )
        val wrongCurrent = "wrong-current-password".toCharArray()
        val unusedReplacement = newPassword()

        val failed = MasterPasswordChanger.changePassword(
            lockManager,
            wrongCurrent,
            unusedReplacement,
        )

        assertFalse(failed.success)
        assertTrue(wrongCurrent.all { it == '\u0000' })
        assertTrue(unusedReplacement.all { it == '\u0000' })
        val current = password()
        val colliding = duressPassword()
        assertFailsWith<IllegalArgumentException> {
            MasterPasswordChanger.changePassword(lockManager, current, colliding)
        }
        assertTrue(current.all { it == '\u0000' })
        assertTrue(colliding.all { it == '\u0000' })

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        restartManager()
        assertEquals(UnlockResult.DURESS, lockManager.unlock(duressPassword()))
    }

    @Test
    fun dekRotation_voidsOldKitPreservesDataAndDisablesStaleBiometric() = runBlocking {
        lockManager.unlock(password())
        repository.addItem(item("rotation", "Rotation Data"))
        val priorState = lockManager.lockState.value as LockState.Unlocked
        // Through the real path now: the kit is written into the container, so this also covers
        // the rotation actually revoking it rather than the test holding a private copy.
        val oldMnemonic = assertNotNull(saveANewRecoveryKit())
        DeviceKeyStore.save(
            context,
            com.localkeep.core.crypto.WrappedDek(
                nonce = SecureRandomProvider.nextBytes(CryptoConstants.AEAD_NONCE_LEN),
                ciphertext = SecureRandomProvider.nextBytes(
                    CryptoConstants.KEY_LEN + CryptoConstants.AEAD_TAG_LEN,
                ),
            ),
        )
        val current = password()

        val result = lockManager.rotateRealDek(current, biometricCipher = null)

        assertTrue(result.success)
        assertTrue(result.oldRecoveryKitVoided)
        assertTrue(current.all { it == '\u0000' })
        assertContentEquals(ByteArray(CryptoConstants.KEY_LEN), priorState.dek)
        assertEquals(null, DeviceKeyStore.load(context))

        restartManager()
        assertEquals(
            UnlockResult.WRONG_CREDENTIAL,
            lockManager.unlockWithRecoveryKit(oldMnemonic),
        )
        assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
        assertEquals("Rotation Data", repository.getAllItems().single().title)
        val newMnemonic = assertNotNull(saveANewRecoveryKit())

        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(newMnemonic))
        assertEquals("Rotation Data", repository.getAllItems().single().title)
    }

    @Test
    fun recoveryKit_opensTheVaultFromTheWordsAlone() = runBlocking {
        // The whole point of the change. Before it, unlocking needed the words *and* the wrapped
        // key, and the wrapped key lived only in the QR code — so the printed PDF, the export the
        // app pushes hardest, could not open anything. This is what the paper claims to do.
        lockManager.unlock(password())
        repository.addItem(item("recover", "Recoverable"))
        val mnemonic = assertNotNull(saveANewRecoveryKit())

        restartManager()

        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(mnemonic))
        assertEquals("Recoverable", repository.getAllItems().single().title)
    }

    /**
     * The right words against a damaged payload are not the wrong words — the Recovery Kit half of
     * [corruptPayloadWithNoBackup_reportsUnreadableAndDoesNotThrottle].
     *
     * The words unwrap the kit's key and that key matches the slot commitment, so they are provably
     * correct; only the payload they should open is damaged. One catch-all around the whole method
     * used to answer that with WRONG_CREDENTIAL *and* charge it to the knowledge-domain backoff, so
     * the user was told to retype 24 words that were right, and was locked out for doing it.
     */
    @Test
    fun recoveryKit_onADamagedPayload_reportsUnreadableAndDoesNotThrottle() = runBlocking {
        lockManager.unlock(password())
        val mnemonic = assertNotNull(saveANewRecoveryKit())
        lockManager.lock()
        corruptFirstRegion(File(context.filesDir, AtomicVaultWriter.VAULT_FILE))

        repeat(MAX_FAILED_ATTEMPTS + 2) {
            assertEquals(UnlockResult.VAULT_UNREADABLE, lockManager.unlockWithRecoveryKit(mnemonic))
        }

        // No lockout accumulated — a sixth *guess* would have thrown VaultLockedException — and
        // words that really are wrong are still reported as wrong.
        assertEquals(
            UnlockResult.WRONG_CREDENTIAL,
            lockManager.unlockWithRecoveryKit("these are definitely not the twenty four words"),
        )
    }

    /**
     * What a long-running export holds while the auto-lock timer runs underneath it.
     *
     * The independent-passphrase export calibrates Argon2id and derives a KEK before it first reads
     * the DEK, and it used to read the manager's own array — the one [lock] zeroes. A lock landing
     * inside that window produced a backup wrapped and encrypted under all zeroes.
     */
    @Test
    fun sessionSnapshot_keepsItsKeyAfterALockZeroesTheLiveOne() = runBlocking {
        lockManager.unlock(password())
        val live = (lockManager.lockState.value as LockState.Unlocked).dek
        val expected = live.copyOf()

        val snapshot = assertNotNull(lockManager.sessionSnapshotOrNull())
        lockManager.lock()

        assertTrue(live.all { it == 0.toByte() }, "lock() should have zeroed the session's own DEK")
        assertContentEquals(expected, snapshot.dek)
        assertNull(lockManager.sessionSnapshotOrNull())
        Zeroizer.wipe(snapshot.dek)
    }

    /**
     * A lock landing inside a decrypt must not zero the key that decrypt is reading.
     *
     * The media viewer reads through a [SessionKey] for as long as it is on screen, so an
     * auto-lock, a screen-off or a panic lock can land in the middle of a frame decrypt. It used to
     * be handed the manager's own array outright, and `lock()` zeroes that array in place — so the
     * read finished against all zeroes and failed on the AEAD tag. That is the safe direction to
     * fail, and it is still not a property to rely on. Here the blob is sealed before the lock and
     * opened after it, inside one borrow.
     */
    @Test
    fun borrowedDek_survivesALockLandingMidDecrypt() = runBlocking {
        lockManager.unlock(password())
        val live = (lockManager.lockState.value as LockState.Unlocked).dek
        val plaintext = ByteArray(96) { (it * 7 + 1).toByte() }
        val sealed = ByteArrayOutputStream()
            .also { out -> StreamingAead.encrypt(live, ByteArrayInputStream(plaintext), out) }
            .toByteArray()

        val borrowStarted = CountDownLatch(1)
        val opened = async(Dispatchers.IO) {
            lockManager.withSessionDek { dek ->
                borrowStarted.countDown()
                // Comfortably inside the one-second drain bound, and long enough that the lock
                // below is certain to land first.
                Thread.sleep(200)
                ByteArrayOutputStream()
                    .also { out -> StreamingAead.decrypt(dek, ByteArrayInputStream(sealed), out) }
                    .toByteArray()
            }
        }
        assertTrue(borrowStarted.await(5, TimeUnit.SECONDS), "the borrow never started")

        lockManager.lock()

        // Prompt regardless: Locked is published on the caller's thread, before anything waits.
        assertEquals(LockState.Locked, lockManager.lockState.value)
        assertContentEquals(plaintext, assertNotNull(opened.await()))
    }

    /** The wait is a delay, not a reprieve: the key is zeroed as soon as the borrow lets go. */
    @Test
    fun theWipeLandsAsSoonAsTheBorrowReturns() = runBlocking {
        lockManager.unlock(password())
        val live = (lockManager.lockState.value as LockState.Unlocked).dek
        val borrowStarted = CountDownLatch(1)
        val holding = async(Dispatchers.IO) {
            lockManager.withSessionDek {
                borrowStarted.countDown()
                Thread.sleep(400)
            }
        }
        assertTrue(borrowStarted.await(5, TimeUnit.SECONDS), "the borrow never started")

        lockManager.lock()

        assertFalse(live.all { it == 0.toByte() }, "the wipe should still be waiting for the borrow")
        holding.await()
        withTimeout(3_000) { while (!live.all { it == 0.toByte() }) delay(10) }
    }

    /**
     * A borrow that will not return must not be able to hold the key alive.
     *
     * Auto-lock, the screen-off receiver and the panic lock are security controls, so the drain is
     * a courtesy to short reads and is bounded. Past the bound the array is zeroed with the borrow
     * still held, and the work reading it fails closed exactly as it did before borrows existed.
     */
    @Test
    fun aStuckBorrow_doesNotHoldTheKeyPastTheBound() = runBlocking<Unit> {
        lockManager.unlock(password())
        val live = (lockManager.lockState.value as LockState.Unlocked).dek
        val borrowStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stuck = async(Dispatchers.IO) {
            lockManager.withSessionDek {
                borrowStarted.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(borrowStarted.await(5, TimeUnit.SECONDS), "the borrow never started")

        lockManager.lock()

        assertEquals(LockState.Locked, lockManager.lockState.value)
        // The bound is one second; the assertion is that the wait ends on its own, with the borrow
        // never released.
        withTimeout(5_000) { while (!live.all { it == 0.toByte() }) delay(20) }
        release.countDown()
        stuck.await()
    }

    /** A handle is not a claim the vault is open, and a stale epoch is not the live session. */
    @Test
    fun aBorrowIsRefusedOnceLockedAndAcrossAReUnlock() = runBlocking<Unit> {
        lockManager.unlock(password())
        val token = assertNotNull(lockManager.unlockedSessionToken())
        val key = assertNotNull(lockManager.sessionKeyOrNull(token))

        lockManager.lock()
        assertNull(key.use { it.copyOf() })
        assertNull(lockManager.sessionKeyOrNull())

        // The same handle against a *new* session: unlocked again, but not the session it named.
        lockManager.unlock(password())
        assertNull(key.use { it.copyOf() })
        assertNotNull(lockManager.sessionKeyOrNull()?.use { it.size })
    }

    @Test
    fun recoveryKit_isReplacedByTheNextOneAndSurvivesOrdinarySaves() = runBlocking {
        lockManager.unlock(password())
        val first = assertNotNull(saveANewRecoveryKit())
        val second = assertNotNull(saveANewRecoveryKit())
        // An ordinary edit rewrites the container. If the save path dropped the block, the kit
        // would be revoked by writing a note — invisible until the day it was needed.
        repository.addItem(item("saved", "Saved After Kit"))

        restartManager()
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlockWithRecoveryKit(first))
        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(second))
        assertEquals("Saved After Kit", repository.getAllItems().single().title)
    }

    /**
     * The regeneration window. Preparing a kit shows 24 words; until they are saved, the kit the
     * user already has on paper must keep working — because "tapped Create, then walked away" is a
     * thing people do, and it used to silently destroy their only recovery document.
     */
    @Test
    fun recoveryKit_preparedButNotCommitted_leavesThePreviousKitWorking() = runBlocking {
        lockManager.unlock(password())
        repository.addItem(item("real", "Real"))
        val saved = assertNotNull(saveANewRecoveryKit())

        val neverSaved = assertNotNull(lockManager.prepareRecoveryKit())
        lockManager.clearPreparedRecoveryKit()

        restartManager()
        // The words the user actually holds still open the vault...
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(saved))
        assertEquals("Real", repository.getAllItems().single().title)
        restartManager()
        // ...and the ones they saw and abandoned never became real.
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlockWithRecoveryKit(neverSaved))
    }

    /** A lock between "show the words" and "I saved them" must not revoke the working kit either. */
    @Test
    fun recoveryKit_commitAfterALockIsRefusedAndKeepsThePreviousKit() = runBlocking {
        lockManager.unlock(password())
        val saved = assertNotNull(saveANewRecoveryKit())

        val orphaned = assertNotNull(lockManager.prepareRecoveryKit())
        lockManager.lock()
        lockManager.unlock(password())
        assertFalse(lockManager.commitPreparedRecoveryKit())

        restartManager()
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlockWithRecoveryKit(orphaned))
        restartManager()
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(saved))
    }

    @Test
    fun recoveryKit_isRefusedInsideTheDuressVaultAndLeavesTheRealKitAlone() = runBlocking {
        lockManager.unlock(password())
        repository.addItem(item("real", "Real"))
        val realMnemonic = assertNotNull(saveANewRecoveryKit())
        DuressUnlocker.setupDuress(lockManager, duressPassword(), vaultWith(item("decoy", "Decoy")))

        restartManager()
        assertEquals(UnlockResult.DURESS, lockManager.unlock(duressPassword()))
        // A kit minted here would wrap the decoy's key and be indistinguishable from a real one —
        // handing a coercer's victim a recovery document that opens the wrong vault.
        assertNull(lockManager.prepareRecoveryKit())

        restartManager()
        // Configuring a decoy must not have disturbed the real vault's kit: the real DEK never
        // changed, and installing the decoy rewrites the whole container.
        assertEquals(UnlockResult.REAL, lockManager.unlockWithRecoveryKit(realMnemonic))
        assertEquals("Real", repository.getAllItems().single().title)
    }

    @Test
    fun recoveryKit_unlockIsRefusedWhenTheVaultHasNoKit() = runBlocking {
        lockManager.unlock(password())
        repository.addItem(item("nokit", "No Kit"))
        val orphan = RecoveryKit.generate(ByteArray(CryptoConstants.KEY_LEN) { 7 }).mnemonic

        restartManager()

        // Indistinguishable from wrong words, deliberately: whether a vault has a kit is not
        // something the file should answer for someone holding it.
        assertEquals(UnlockResult.WRONG_CREDENTIAL, lockManager.unlockWithRecoveryKit(orphan))
    }

    @Test
    fun dekRotation_preservesIndependentDecoyAndRewrapsBiometricWhenProvided() = runBlocking {
        lockManager.unlock(password())
        repository.addItem(item("real", "Real"))
        DuressUnlocker.setupDuress(
            lockManager,
            duressPassword(),
            vaultWith(item("decoy", "Decoy")),
        )
        val wrappingKey = SecureRandomProvider.nextBytes(CryptoConstants.KEY_LEN)
        try {
            val secretKey = SecretKeySpec(wrappingKey, "AES")
            val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }

            val result = lockManager.rotateRealDek(password(), encryptCipher)

            assertTrue(result.success)
            val wrapped = checkNotNull(DeviceKeyStore.load(context))
            val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey,
                    GCMParameterSpec(CryptoConstants.AEAD_TAG_BITS, wrapped.nonce),
                )
            }
            val unwrapped = decryptCipher.doFinal(wrapped.ciphertext)
            try {
                assertContentEquals(
                    (lockManager.lockState.value as LockState.Unlocked).dek,
                    unwrapped,
                )
            } finally {
                Zeroizer.wipe(unwrapped)
            }

            restartManager()
            assertEquals(UnlockResult.DURESS, lockManager.unlock(duressPassword()))
            assertEquals("Decoy", repository.getAllItems().single().title)
            restartManager()
            assertEquals(UnlockResult.REAL, lockManager.unlock(password()))
            assertEquals("Real", repository.getAllItems().single().title)
        } finally {
            Zeroizer.wipe(wrappingKey)
        }
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

    /**
     * Flips one byte of region 0's ciphertext, leaving the header untouched.
     *
     * Deliberately not truncation or a header edit: those are caught by
     * [VaultFileCodec.validateContainer] and were already reported correctly. This is the case the
     * structural check cannot see — a container that parses perfectly and whose payload will not
     * authenticate.
     */
    private fun corruptFirstRegion(file: File) {
        val bytes = file.readBytes()
        val offset = VaultHeaderCodec.decode(bytes).regions[0].offset
        bytes[offset] = (bytes[offset].toInt() xor 0x5A).toByte()
        file.writeBytes(bytes)
    }

    private fun cleanupFiles() {
        File(context.filesDir, AtomicVaultWriter.VAULT_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.BACKUP_FILE).delete()
        File(context.filesDir, AtomicVaultWriter.TEMP_FILE).delete()
        File(context.filesDir, DeviceKeyStore.FILE_NAME).delete()
    }

    /**
     * Mints a kit and makes it the vault's, which is what "create a Recovery Kit" used to mean in
     * one call. It is two now — see [VaultLockManager.prepareRecoveryKit] — because the commit
     * revokes the previous kit and must not happen until the user has the new words in hand.
     */
    private suspend fun saveANewRecoveryKit(): String? {
        val mnemonic = lockManager.prepareRecoveryKit() ?: return null
        return if (lockManager.commitPreparedRecoveryKit()) mnemonic else null
    }

    private fun restartManager() {
        lockManager.lock()
        lockManager = VaultLockManager(context, KeystoreManager(context)).also {
            it.autoLockTimeoutMs = 0
        }
        repository = VaultRepository(lockManager)
    }

    private fun item(seed: String, title: String): VaultItem {
        val now = Instant.now()
        return VaultItem(
            id = UUID.nameUUIDFromBytes(seed.toByteArray()),
            title = title,
            createdAt = now,
            updatedAt = now,
            content = Note("$title body"),
        )
    }

    private fun login(seed: String, title: String, password: String): VaultItem {
        val now = Instant.now()
        return VaultItem(
            id = UUID.nameUUIDFromBytes(seed.toByteArray()),
            title = title,
            createdAt = now,
            updatedAt = now,
            content = Login(username = "user@example.com", password = password),
        )
    }

    private fun vaultWith(item: VaultItem): Vault {
        val now = Instant.now()
        return Vault(
            items = listOf(item),
            createdAt = now,
            modifiedAt = now,
        )
    }

    private fun password(): CharArray = MASTER_PASSWORD.toCharArray()
    private fun duressPassword(): CharArray = DURESS_PASSWORD.toCharArray()
    private fun newPassword(): CharArray = NEW_MASTER_PASSWORD.toCharArray()

    private companion object {
        /** Mirrors `VaultLockManager.MAX_FAILED_ATTEMPTS`, which is private to that class. */
        const val MAX_FAILED_ATTEMPTS = 5
        const val MASTER_PASSWORD = "test-master-password-123"
        const val DURESS_PASSWORD = "test-duress-password-456"
        const val NEW_MASTER_PASSWORD = "test-new-master-password-789"
    }
}
