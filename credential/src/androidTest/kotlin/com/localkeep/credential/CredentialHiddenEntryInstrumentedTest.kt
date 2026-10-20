package com.localkeep.credential

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.model.Login
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.LockState
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * A concealed passkey or login is not offered by the credential provider — and, less obviously, does
 * not announce itself by refusing a registration.
 *
 * The credential-provider surface has a disclosure channel the vault list does not: WebAuthn's
 * exclude list. A relying party asks "do you already hold a credential for this account?", and the
 * answer travels back to the *calling site*. Answering it from the raw vault means any web page can
 * probe for a concealed passkey by trying to create one and reading the error. So a concealed
 * passkey does not exclude, and a duplicate may be registered while it is hidden — the alternative
 * is a feature that hides an entry from its owner and discloses it to a stranger.
 *
 * On a device, because the reveal gate is real Argon2 and the passkey path needs a real DEK.
 */
@RunWith(AndroidJUnit4::class)
class CredentialHiddenEntryInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    private val caller = CallerIdentity(
        packageName = "com.example.caller",
        origin = null,
        currentCertHashes = setOf("Y2VydC1oYXNo"),
        certHistoryHashes = emptySet(),
    )

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
    fun aHiddenLoginProducesNoPasswordEntry() = runBlocking {
        val id = seedLogin("bank.example")
        val webCaller = caller.copy(origin = "https://bank.example")

        assertTrue(
            PasswordProvider.matchingLogins(repository, webCaller).any { it.id == id },
            "control: the login is offered before anything is hidden",
        )

        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        assertEquals(
            emptyList(),
            PasswordProvider.matchingLogins(repository, webCaller),
            "a concealed login must produce no credential entry",
        )
    }

    @Test
    fun aRevealedSessionOffersThePasswordEntryAgain() = runBlocking {
        val id = seedLogin("bank.example")
        val webCaller = caller.copy(origin = "https://bank.example")
        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )
        assertTrue(PasswordProvider.matchingLogins(repository, webCaller).isEmpty(), "concealed first")

        val generation = lockManager.nextRevealAttemptGeneration()
        assertEquals(
            RevealAttemptOutcome.Revealed,
            lockManager.attemptHiddenReveal("open sesame".toCharArray(), generation),
        )

        assertTrue(PasswordProvider.matchingLogins(repository, webCaller).any { it.id == id })
    }

    /**
     * The control for the disclosure test below: with the passkey visible, registering an excluded
     * duplicate is refused, and the refusal is what the relying party sees.
     */
    @Test
    fun aVisiblePasskeyStillExcludesADuplicateRegistration() = runBlocking {
        val credentialId = byteArrayOf(1, 2, 3, 4)
        seedPasskey("rp.example", credentialId)

        assertFailsWith<IllegalStateException> {
            PasskeyCreator.create(
                options = creationOptions("rp.example", listOf(credentialId)),
                caller = caller,
                clientDataHash = null,
                dek = requireDek(),
                repository = repository,
                nativeBindingConfirmed = true,
            )
        }
        Unit
    }

    @Test
    fun aHiddenPasskeyDoesNotDiscloseItselfByExcludingARegistration() = runBlocking {
        val credentialId = byteArrayOf(1, 2, 3, 4)
        val id = seedPasskey("rp.example", credentialId)
        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        // No exception: the registration goes through as if the vault held no such credential.
        PasskeyCreator.create(
            options = creationOptions("rp.example", listOf(credentialId)),
            caller = caller,
            clientDataHash = null,
            dek = requireDek(),
            repository = repository,
            nativeBindingConfirmed = true,
        )

        assertTrue(
            repository.getCompleteStorageInventory().allItems.any { it.id == id },
            "and the concealed original is still physically there, untouched",
        )
    }

    private fun requireDek(): ByteArray =
        (lockManager.lockState.value as LockState.Unlocked).dek

    private fun creationOptions(rpId: String, excluded: List<ByteArray>) =
        PasskeyCreator.CreationOptions(
            rpId = rpId,
            challenge = "Y2hhbGxlbmdl",
            userId = byteArrayOf(9, 9, 9),
            userName = "me@example",
            userDisplayName = "Me",
            excludedCredentialIds = excluded,
        )

    private suspend fun seedLogin(domain: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = "Offshore bank", createdAt = now, updatedAt = now,
                content = Login(
                    username = "me",
                    password = "hunter2",
                    uris = listOf(UriMatch(uri = "https://$domain/login")),
                ),
            ),
        )
        return id
    }

    private suspend fun seedPasskey(rpId: String, credentialId: ByteArray): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = "Offshore passkey", createdAt = now, updatedAt = now,
                content = Passkey(
                    rpId = rpId,
                    credentialId = credentialId,
                    userHandle = byteArrayOf(9, 9, 9),
                    privateKeyRef = ByteArray(48),
                    createdAt = now,
                    userName = "me@example",
                    userDisplayName = "Me",
                ),
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
