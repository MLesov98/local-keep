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
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.LockState
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.credential.webauthn.WebAuthnCodec
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.runner.RunWith

/**
 * Registering a passkey for an account the vault already holds one for replaces it, as CTAP2 does
 * for a discoverable credential: one per rpId and user handle. The old item goes to the Trash rather
 * than away, so a relying party that never received the new registration cannot cost the user the
 * credential it still has on file.
 *
 * Until this, every registration was a plain add, and the sign-in chooser listed two entries for the
 * same account with the same name — one of which the site no longer accepted.
 *
 * On a device, because the passkey path needs a real DEK and the repository a real unlocked vault.
 */
@RunWith(AndroidJUnit4::class)
class PasskeyReRegistrationInstrumentedTest {

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
    fun reRegisteringTheSameAccountReplacesThePasskeyAndTrashesTheOldOne() = runBlocking {
        create(userId = byteArrayOf(9, 9, 9))
        val original = livePasskeys().single()

        val response = create(userId = byteArrayOf(9, 9, 9))

        val live = livePasskeys()
        assertEquals(1, live.size, "one live passkey per rpId and user handle, got ${live.size}")
        val replacement = live.single().content as Passkey
        assertTrue(
            replacement.credentialId.contentEquals(credentialIdOf(response)),
            "the live passkey is the one the relying party was just handed",
        )
        assertEquals(
            listOf(original.id),
            repository.getTrashItems().map(VaultItem::id),
            "the replaced passkey is in the Trash, recoverable, not deleted",
        )
    }

    /** The control: a user handle is the account, so a second one on the same site is kept. */
    @Test
    fun aDifferentUserHandleOnTheSameRpIsAnotherAccountAndBothStay() = runBlocking {
        create(userId = byteArrayOf(9, 9, 9))
        create(userId = byteArrayOf(7, 7, 7))

        assertEquals(2, livePasskeys().size)
        assertTrue(repository.getTrashItems().isEmpty(), "nothing replaced, nothing trashed")
    }

    /**
     * A concealed passkey for the same account is left exactly where it is: live, concealed, not in
     * the Trash. Replacing it would make a registration from a stranger's page an operation on an
     * entry the user hid, and its Trash row would surface the moment a reveal ended.
     */
    @Test
    fun aConcealedPasskeyForTheSameAccountIsNotReplaced() = runBlocking {
        create(userId = byteArrayOf(9, 9, 9))
        val concealed = livePasskeys().single()
        repository.hide(
            HiddenTarget.Item(concealed.id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        create(userId = byteArrayOf(9, 9, 9))

        val stored = repository.getCompleteStorageInventory().allItems.single { it.id == concealed.id }
        assertNull(stored.deletedAt, "the concealed original is still live")
        assertTrue(concealed.id in repository.visibilitySnapshot().concealedItemIds, "and still concealed")
        assertTrue(repository.getTrashItems().isEmpty())
    }

    private suspend fun create(userId: ByteArray): String =
        PasskeyCreator.create(
            options = PasskeyCreator.CreationOptions(
                rpId = "rp.example",
                challenge = "Y2hhbGxlbmdl",
                userId = userId,
                userName = "me@example",
                userDisplayName = "Me",
                excludedCredentialIds = emptyList(),
            ),
            caller = caller,
            clientDataHash = null,
            dek = (lockManager.lockState.value as LockState.Unlocked).dek,
            repository = repository,
            nativeBindingConfirmed = true,
        )

    private fun livePasskeys(): List<VaultItem> =
        repository.visibilitySnapshot().liveItems.filter { it.content is Passkey }

    private fun credentialIdOf(response: String): ByteArray =
        WebAuthnCodec.base64UrlDecode(JSONObject(response).getString("id"))

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
