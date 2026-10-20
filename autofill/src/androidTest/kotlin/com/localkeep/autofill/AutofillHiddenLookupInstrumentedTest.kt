package com.localkeep.autofill

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
import com.localkeep.core.model.UriMatch
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
 * The promise the whole feature is measured against: a hidden login does not offer itself to another
 * app.
 *
 * Autofill is the surface where concealment either holds or is worthless. Everything else the user
 * can at least see going wrong — a hidden entry showing up in the vault list is obvious the moment
 * they look. An autofill sheet is rendered by the system in someone *else's* app, often while the
 * phone is in someone else's hand, and the user is not present to notice. So this runs on a real
 * device against the real lock manager, real Argon2 reveal gate and a real encrypted vault: the JVM
 * tests cannot reach any of that.
 *
 * The negative half matters as much as the positive one. A hidden login must also stop suppressing
 * the "save this password?" prompt — the prompt has to behave exactly as it does for a domain the
 * vault has never seen. Suppression is a disclosure: the user types a password into a site, no offer
 * to save appears, and that silence says the vault already holds an entry for it.
 */
@RunWith(AndroidJUnit4::class)
class AutofillHiddenLookupInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

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
    fun aHiddenLoginOffersNoAutofillMatch() = runBlocking {
        val id = seedLogin("Offshore bank", "bank.example")

        assertTrue(
            repository.findMatching("bank.example", null).any { it.id == id },
            "control: the login matches its own domain before anything is hidden",
        )

        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        assertEquals(
            emptyList(),
            repository.findMatching("bank.example", null),
            "a concealed login must produce no autofill match at all",
        )
    }

    /**
     * The other half of the same fact. `FillResponseFactory` decides "is there anything to offer"
     * from `findMatching`, and `SaveHandler` decides "does the vault already have this" from the
     * same call — so an empty result has to mean the vault has never seen the domain, not "there is
     * one but you may not see it".
     */
    @Test
    fun aHiddenLoginDoesNotSuppressTheSavePrompt() = runBlocking {
        val id = seedLogin("Offshore bank", "bank.example")
        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        val unknownDomain = repository.findMatching("never-seen.example", null)
        val hiddenDomain = repository.findMatching("bank.example", null)

        assertEquals(
            unknownDomain,
            hiddenDomain,
            "a domain whose only entry is concealed must be indistinguishable from one the vault " +
                "has never held",
        )
    }

    /** Package-scoped matching is a separate branch of `findMatching` and conceals the same way. */
    @Test
    fun aHiddenLoginOffersNoMatchForItsAndroidPackageEither() = runBlocking {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = "Offshore bank", createdAt = now, updatedAt = now,
                content = Login(
                    username = "me",
                    password = "hunter2",
                    androidPackages = listOf("com.example.bank"),
                ),
            ),
        )
        assertTrue(repository.findMatching(null, "com.example.bank").any { it.id == id }, "control")

        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )

        assertTrue(repository.findMatching(null, "com.example.bank").isEmpty())
    }

    /**
     * Reveal lifts it, deliberately (spec §5). Reaching autofill always means backgrounding this
     * app, so a reveal that died on background would make a revealed login unfillable — the reveal
     * follows the existing background grace timer instead of inventing a second, stricter rule.
     */
    @Test
    fun aRevealedSessionFillsAgain() = runBlocking {
        val id = seedLogin("Offshore bank", "bank.example")
        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )
        assertTrue(repository.findMatching("bank.example", null).isEmpty(), "concealed to begin with")

        val generation = lockManager.nextRevealAttemptGeneration()
        assertEquals(
            RevealAttemptOutcome.Revealed,
            lockManager.attemptHiddenReveal("open sesame".toCharArray(), generation),
        )

        assertTrue(repository.findMatching("bank.example", null).any { it.id == id })
    }

    /** And the lock takes it away again, in the same critical section that zeroizes the DEK. */
    @Test
    fun lockingConcealsTheRevealedLoginAgain() = runBlocking {
        val id = seedLogin("Offshore bank", "bank.example")
        repository.hide(
            HiddenTarget.Item(id),
            until = Instant.now().plusSeconds(3600),
            firstPhrase = "open sesame".toCharArray(),
        )
        val generation = lockManager.nextRevealAttemptGeneration()
        lockManager.attemptHiddenReveal("open sesame".toCharArray(), generation)
        assertTrue(repository.findMatching("bank.example", null).any { it.id == id }, "control")

        lockManager.lock()
        lockManager.unlock(password())

        assertFalse(lockManager.revealSession.revealed.value, "a new session starts concealed")
        assertTrue(repository.findMatching("bank.example", null).isEmpty())
    }

    private suspend fun seedLogin(title: String, domain: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id, title = title, createdAt = now, updatedAt = now,
                content = Login(
                    username = "me",
                    password = "hunter2",
                    uris = listOf(UriMatch(uri = "https://$domain/login")),
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
