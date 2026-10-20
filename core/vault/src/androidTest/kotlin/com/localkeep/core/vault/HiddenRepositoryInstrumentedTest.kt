package com.localkeep.core.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * The make-invisible core, exercised on-device end to end: hide conceals from the presentation
 * snapshot but not from the raw inventory, the reveal phrase gate accepts the right phrase and
 * rejects the wrong one, a lock conceals the session, and stop-hiding restores visibility. Unit
 * tests cannot prove this — the reveal gate runs real Argon2 and the whole thing runs through the
 * live lock manager and an encrypted save.
 */
@RunWith(AndroidJUnit4::class)
class HiddenRepositoryInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository
    private val now = Instant.parse("2026-08-18T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeTest
    fun setup() = runBlocking {
        FolderTestVault.cleanup(context)
        FolderTestVault.create(context)
        lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        repository = VaultRepository(lockManager, clock = clock)
        lockManager.unlock(FolderTestVault.password())
        Unit
    }

    @AfterTest
    fun cleanup() {
        if (::lockManager.isInitialized) lockManager.lock()
        FolderTestVault.cleanup(context)
    }

    private suspend fun seedItem(title: String): UUID {
        val id = UUID.randomUUID()
        repository.addItem(
            VaultItem(id = id, title = title, createdAt = now, updatedAt = now, content = Note("n")),
        )
        return id
    }

    @Test
    fun hideConcealsFromSnapshotButNotFromInventory() = runBlocking {
        val id = seedItem("Offshore account")
        val outcome = repository.hide(
            HiddenTarget.Item(id),
            until = now.plusSeconds(3600),
            firstPhrase = "let me in".toCharArray(),
        )
        assertEquals(HideOutcome.Hidden, outcome)

        val snapshot = repository.visibilitySnapshot()
        assertTrue(id in snapshot.concealedItemIds)
        assertFalse(snapshot.liveItems.any { it.id == id }, "hidden item absent from the visible list")
        assertTrue(
            repository.getCompleteStorageInventory().allItems.any { it.id == id },
            "hidden item still physically present for storage integrity",
        )
    }

    @Test
    fun firstHideWithoutAPhraseIsRefused() = runBlocking {
        val id = seedItem("Secret")
        assertEquals(
            HideOutcome.PhraseRequired,
            repository.hide(HiddenTarget.Item(id), until = now.plusSeconds(3600), firstPhrase = null),
        )
        assertFalse(id in repository.visibilitySnapshot().concealedItemIds, "nothing committed")
    }

    @Test
    fun aPhraseThatCollidesWithAVisibleItemIsRefused() = runBlocking {
        seedItem("collideword")
        val id = seedItem("Another")
        assertEquals(
            HideOutcome.PhraseCollision,
            repository.hide(HiddenTarget.Item(id), until = now.plusSeconds(3600), firstPhrase = "collideword".toCharArray()),
        )
    }

    @Test
    fun theRightPhraseRevealsAndTheWrongOneDoesNot() = runBlocking {
        val id = seedItem("Bank")
        repository.hide(HiddenTarget.Item(id), until = now.plusSeconds(3600), firstPhrase = "swordfish".toCharArray())

        val wrongGen = lockManager.nextRevealAttemptGeneration()
        assertEquals(RevealAttemptOutcome.Rejected, lockManager.attemptHiddenReveal("guess".toCharArray(), wrongGen))
        assertFalse(lockManager.revealSession.revealed.value)
        assertFalse(repository.visibilitySnapshot().liveItems.any { it.id == id })

        val rightGen = lockManager.nextRevealAttemptGeneration()
        assertEquals(RevealAttemptOutcome.Revealed, lockManager.attemptHiddenReveal("swordfish".toCharArray(), rightGen))
        assertTrue(lockManager.revealSession.revealed.value)
        assertTrue(repository.visibilitySnapshot().liveItems.any { it.id == id }, "revealed session shows it")
        assertTrue(id in repository.visibilitySnapshot().concealedItemIds, "marker set stays populated")
    }

    @Test
    fun aStaleGenerationCannotReveal() = runBlocking {
        val id = seedItem("Bank")
        repository.hide(HiddenTarget.Item(id), until = now.plusSeconds(3600), firstPhrase = "swordfish".toCharArray())
        val gen = lockManager.nextRevealAttemptGeneration()
        lockManager.invalidateHiddenRevealAttempt(gen) // a newer query superseded this attempt
        assertEquals(RevealAttemptOutcome.Rejected, lockManager.attemptHiddenReveal("swordfish".toCharArray(), gen))
        assertFalse(lockManager.revealSession.revealed.value, "a correct but superseded attempt must not reveal")
    }

    @Test
    fun lockConcealsTheRevealSession() = runBlocking {
        val id = seedItem("Bank")
        repository.hide(HiddenTarget.Item(id), until = now.plusSeconds(3600), firstPhrase = "swordfish".toCharArray())
        val gen = lockManager.nextRevealAttemptGeneration()
        lockManager.attemptHiddenReveal("swordfish".toCharArray(), gen)
        assertTrue(lockManager.revealSession.revealed.value)

        lockManager.lock()
        assertFalse(lockManager.revealSession.revealed.value, "lock conceals")
        lockManager.unlock(FolderTestVault.password())
        assertFalse(lockManager.revealSession.revealed.value, "unlock starts concealed")
        assertFalse(repository.visibilitySnapshot().liveItems.any { it.id == id })
    }

    @Test
    fun presentationStateReflectsConcealmentAndLock() = runBlocking {
        val id = seedItem("Bank")
        repository.hide(HiddenTarget.Item(id), until = now.plusSeconds(3600), firstPhrase = "swordfish".toCharArray())

        val unlocked = withTimeout(5_000) {
            repository.presentationState.first {
                it is VaultPresentationState.Unlocked && id !in it.snapshot.liveItems.map { i -> i.id }
            }
        }
        assertTrue(unlocked is VaultPresentationState.Unlocked)
        assertTrue(id in (unlocked as VaultPresentationState.Unlocked).snapshot.concealedItemIds)

        lockManager.lock()
        val locked = withTimeout(5_000) {
            repository.presentationState.first { it is VaultPresentationState.Locked }
        }
        assertEquals(VaultPresentationState.Locked, locked)
    }

    @Test
    fun stopHidingRestoresVisibility() = runBlocking {
        val id = seedItem("Bank")
        repository.hide(HiddenTarget.Item(id), until = now.plusSeconds(3600), firstPhrase = "swordfish".toCharArray())
        assertFalse(repository.visibilitySnapshot().liveItems.any { it.id == id })

        repository.stopHiding(HiddenTarget.Item(id))
        val snapshot = repository.visibilitySnapshot()
        assertTrue(snapshot.liveItems.any { it.id == id })
        assertFalse(id in snapshot.concealedItemIds)
    }
}
