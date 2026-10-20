package com.localkeep.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Login
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.DuressUnlocker
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.HideOutcome
import com.localkeep.core.vault.RevealAttemptOutcome
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.VaultInitializer
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.test.device.DeviceReadyRule
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app-shell/navigation half of "make invisible": what a reveal session does across LOCK,
 * a background-conceal that leaves the vault unlocked, and a DURESS unlock — read from the exact
 * flow [VaultLockManager.revealSession] and [VaultRepository] are, real classes instantiated
 * directly against `ApplicationProvider`'s context. Same shape as [VaultTestDriver] and
 * [HiddenFixtureSeeder] in this package — no Compose, no Hilt, no `NavController` — because this
 * module ships no Hilt test runner (`app/build.gradle.kts` still declares
 * `androidx.test.runner.AndroidJUnitRunner`) and inventing one was out of scope here.
 *
 * That is also the reason [MainActivity]'s own coarse-grained defense is NOT exercised by this
 * suite: `MainActivity.kt` (~L108-155) observes `lockManager.lockState` in a `LaunchedEffect` and,
 * on [com.localkeep.core.vault.LockState.Locked], does
 * `navController.navigate(UNLOCK) { popUpTo(navController.graph.id) { inclusive = true } }` —
 * destroying any open item-detail/edit back-stack entry (and its ViewModel) the instant the vault
 * locks. That logic is entangled in Activity/Compose/Hilt wiring with no pure seam to call into,
 * so driving it for real needs `@HiltAndroidTest` + `ActivityScenario` + a Hilt test application,
 * none of which exist in `:app` yet. [aLockedVaultRefusesToResolveAnyItemEvenARawRead] below tests
 * the data-layer backstop underneath it instead: even if a stale route survived the pop, the read
 * it would need throws.
 *
 * The finer-grained defense — dropping an *already-open* item screen the instant a reveal session
 * ends while the vault stays unlocked (background grace expiring) — is `ItemEditViewModel`'s own
 * `presentationState` collector, added by commit 1543403 ("drop an open item when the reveal
 * session ends") and already covered by `ItemEditVisibilityInstrumentedTest` in `:feature:itemedit`.
 * It is not re-tested at the ViewModel level here; [theConcealedAndNotLiveConditionMatchesTheRealSeam]
 * below instead drives the exact data both that collector and this module's own screens read —
 * `VaultRepository.visibilitySnapshot()`'s `concealedItemIds` and `liveItems` — through the same
 * transition (revealed, then concealed without a lock) and checks 1543403's own stated condition,
 * `id in concealedItemIds && id !in liveItems`, flips exactly where an open route must react.
 */
@RunWith(AndroidJUnit4::class)
class AppHiddenNavigationInstrumentedTest {

    @get:Rule(order = DeviceReadyRule.OUTERMOST)
    val device = DeviceReadyRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Wipes app-private storage and returns a real, unlocked [VaultLockManager]/[VaultRepository] pair. */
    private fun freshVault(password: String): Pair<VaultLockManager, VaultRepository> {
        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()
        val lockManager = VaultLockManager(context, KeystoreManager(context))
        runBlocking {
            VaultInitializer.create(context, password.toCharArray())
            assertEquals(
                UnlockResult.REAL,
                lockManager.unlock(password.toCharArray()),
                "fixture vault did not unlock",
            )
        }
        return lockManager to VaultRepository(lockManager)
    }

    private fun addLogin(repository: VaultRepository, title: String): VaultItem {
        val now = Instant.now()
        val item = VaultItem(
            id = UUID.randomUUID(),
            title = title,
            createdAt = now,
            updatedAt = now,
            content = Login(username = "u", password = "p"),
        )
        runBlocking { repository.addItem(item) }
        return item
    }

    /** Path -> bytes for every file under [roots] — the whole point is an unequal map on either side. */
    private fun snapshotFiles(roots: List<File>): Map<String, List<Byte>> {
        val out = LinkedHashMap<String, List<Byte>>()
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown().filter { it.isFile }.forEach { f -> out[f.absolutePath] = f.readBytes().toList() }
        }
        return out
    }

    /**
     * `filesDir`/`noBackupFilesDir` hold the vault container; `shared_prefs` is where
     * `getSharedPreferences` lands. Deliberately excludes `cacheDir`/`codeCacheDir` (unrelated
     * framework churn, not persistence a process-death restart could read) and `databases/`
     * (WorkManager/Room live there, never touched by anything in this suite) so the diff in
     * [revealingWritesNothingToDiskOrSharedPreferences] stays a clean signal.
     */
    private fun persistenceRoots(): List<File> = listOf(
        context.filesDir,
        context.noBackupFilesDir,
        File(context.applicationInfo.dataDir, "shared_prefs"),
    )

    /**
     * [RevealSession][com.localkeep.core.vault.RevealSession]'s own doc: "concealed again by
     * manual lock, panic lock, idle/background auto-lock, process death and payload replacement" —
     * and never resumed by an unlock. Drives the real gate
     * ([VaultLockManager.attemptHiddenReveal]) to an actual [RevealAttemptOutcome.Revealed] first,
     * so the lock is proven to conceal something real rather than a flag that started false anyway.
     */
    @Test
    fun lockClearsTheRevealedSessionAndUnlockDoesNotResumeIt() {
        val password = "correct horse battery staple one"
        val (lockManager, repository) = freshVault(password)
        val hidden = addLogin(repository, "Offshore login one")
        val phrase = "umbrella harbor lighthouse nine"

        val revealOutcome = runBlocking {
            assertEquals(
                HideOutcome.Hidden,
                repository.hide(
                    HiddenTarget.Item(hidden.id),
                    Instant.now().plusSeconds(3600),
                    phrase.toCharArray(),
                ),
            )
            lockManager.attemptHiddenReveal(phrase.toCharArray(), lockManager.nextRevealAttemptGeneration())
        }
        assertEquals(RevealAttemptOutcome.Revealed, revealOutcome, "the correct phrase must reveal")
        assertTrue(lockManager.revealSession.revealed.value, "revealed after a correct attempt")

        lockManager.lock()
        assertFalse(
            lockManager.revealSession.revealed.value,
            "lock() must conceal in the same critical section that zeroizes the DEK",
        )

        val reUnlock = runBlocking { lockManager.unlock(password.toCharArray()) }
        assertEquals(UnlockResult.REAL, reUnlock, "re-unlock with the real password")
        assertFalse(
            lockManager.revealSession.revealed.value,
            "an unlock must never resume a session that was revealed before the lock",
        )
    }

    /**
     * The session is documented as in-memory only. An instrumented test cannot kill its own
     * process mid-run and keep asserting afterwards, so this proves the reachable, weaker true
     * thing instead: revealing changes not one byte on disk. A process-death restart can only ever
     * carry forward what disk held, so an unchanged disk state across a successful reveal is a
     * sound proxy for "the revealed flag cannot survive process death" — if nothing was written,
     * there is nothing for a fresh process to read back.
     */
    @Test
    fun revealingWritesNothingToDiskOrSharedPreferences() {
        val password = "correct horse battery staple two"
        val (lockManager, repository) = freshVault(password)
        val hidden = addLogin(repository, "Offshore login two")
        val phrase = "umbrella harbor lighthouse ten"

        runBlocking {
            assertEquals(
                HideOutcome.Hidden,
                repository.hide(
                    HiddenTarget.Item(hidden.id),
                    Instant.now().plusSeconds(3600),
                    phrase.toCharArray(),
                ),
            )
        }

        // Baseline taken AFTER vault creation and the hide's own commit (both real writes), and
        // immediately BEFORE the reveal attempt, so the diff below is scoped to attemptHiddenReveal
        // alone.
        val before = snapshotFiles(persistenceRoots())

        val outcome = runBlocking {
            lockManager.attemptHiddenReveal(phrase.toCharArray(), lockManager.nextRevealAttemptGeneration())
        }
        assertEquals(RevealAttemptOutcome.Revealed, outcome)
        assertTrue(lockManager.revealSession.revealed.value)

        val after = snapshotFiles(persistenceRoots())
        assertEquals(
            before,
            after,
            "attemptHiddenReveal must be RAM-only: no file added, removed or changed under " +
                "filesDir/noBackupFilesDir/shared_prefs as a result of a successful reveal",
        )
    }

    /**
     * [VaultLockManager.attemptHiddenReveal] forces `useReal = verifier != null && verifier.isValid
     * && !isDuress` — so a duress session never even compares against the real vault's stored
     * verifier, it always burns the same dummy-verifier derivation a wrong guess would. This drives
     * a real duress vault into existence via [DuressUnlocker] (the same public entry point
     * `SettingsViewModel.setupDuress` uses) rather than asserting against the private gate directly.
     */
    @Test
    fun duressUnlockRejectsEvenTheCorrectRevealPhrase() {
        val realPassword = "correct horse battery staple three"
        val duressPassword = "a wholly different password"
        val (lockManager, repository) = freshVault(realPassword)
        val hidden = addLogin(repository, "Offshore login three")
        val phrase = "umbrella harbor lighthouse eleven"

        runBlocking {
            assertEquals(
                HideOutcome.Hidden,
                repository.hide(
                    HiddenTarget.Item(hidden.id),
                    Instant.now().plusSeconds(3600),
                    phrase.toCharArray(),
                ),
            )
            DuressUnlocker.setupDuress(
                lockManager = lockManager,
                duressPassword = duressPassword.toCharArray(),
                decoyVault = Vault(createdAt = Instant.now(), modifiedAt = Instant.now()),
            )
        }

        lockManager.lock()
        val unlockResult = runBlocking { lockManager.unlock(duressPassword.toCharArray()) }
        assertEquals(UnlockResult.DURESS, unlockResult, "the duress password must open the decoy session")

        val revealOutcome = runBlocking {
            lockManager.attemptHiddenReveal(phrase.toCharArray(), lockManager.nextRevealAttemptGeneration())
        }
        assertEquals(
            RevealAttemptOutcome.Rejected,
            revealOutcome,
            "a duress session must reject even the real vault's own correct reveal phrase",
        )
        assertFalse(lockManager.revealSession.revealed.value, "a duress session must never flip revealed")
    }

    /**
     * The data-layer backstop under [MainActivity]'s lock-driven `popUpTo` — see the class doc.
     * `VaultRepository.requireVault()` does `check(state is LockState.Unlocked) { "vault is
     * locked" }` before any read, so even a route the pop somehow missed cannot get stale decrypted
     * content back: the very call it would make to resolve an id throws.
     */
    @Test
    fun aLockedVaultRefusesToResolveAnyItemEvenARawRead() {
        val password = "correct horse battery staple four"
        val (lockManager, repository) = freshVault(password)
        addLogin(repository, "Ordinary login four")
        assertTrue(repository.getAllItems().isNotEmpty(), "sanity: the item is readable while unlocked")

        lockManager.lock()

        assertFailsWith<IllegalStateException>(
            "a stale route surviving a lock must still hit VaultRepository.requireVault()'s hard " +
                "backstop rather than read stale decrypted content",
        ) {
            repository.getAllItems()
        }
    }

    /**
     * `VaultVisibility`'s own doc: "[concealedItemIds]/[concealedFolderIds] are populated whether
     * or not a reveal session is active ... these sets are what a row marker reads to say 'this is
     * hidden'". Unlike `liveItems`, the concealed-id set's computation never reads
     * `revealSessionActive` at all — it is keyed purely by `HiddenLease.isHidden`, so an id stays in
     * it for the whole life of the lease, reveal or not. [theConcealedAndNotLiveConditionMatchesTheRealSeam]
     * below is what tests resolvability; this one pins the half of the pair that does NOT move, so a
     * future change that made concealedItemIds reveal-sensitive (breaking the marker every "hidden"
     * badge reads) would be caught here rather than only downstream.
     */
    @Test
    fun concealedItemIdsTracksTheLeaseNotTheRevealSession() {
        val password = "correct horse battery staple five"
        val (lockManager, repository) = freshVault(password)
        val hidden = addLogin(repository, "Offshore login five")
        val phrase = "umbrella harbor lighthouse twelve"

        runBlocking {
            assertEquals(
                HideOutcome.Hidden,
                repository.hide(
                    HiddenTarget.Item(hidden.id),
                    Instant.now().plusSeconds(3600),
                    phrase.toCharArray(),
                ),
            )
        }
        assertTrue(
            hidden.id in repository.visibilitySnapshot().concealedItemIds,
            "a leased-hidden item must be marked concealed before any reveal",
        )

        val revealOutcome = runBlocking {
            lockManager.attemptHiddenReveal(phrase.toCharArray(), lockManager.nextRevealAttemptGeneration())
        }
        assertEquals(RevealAttemptOutcome.Revealed, revealOutcome)
        assertTrue(
            hidden.id in repository.visibilitySnapshot().concealedItemIds,
            "the marker id set is keyed by the lease, not by reveal state, so it stays marked while revealed",
        )

        lockManager.concealHiddenSession()
        assertFalse(
            lockManager.revealSession.revealed.value,
            "concealHiddenSession must end the session without a full lock",
        )
        assertTrue(
            hidden.id in repository.visibilitySnapshot().concealedItemIds,
            "still marked concealed once the session ends, exactly the moment an open item route must react to",
        )
    }

    /**
     * The exact boolean commit 1543403 gives for when an open item screen must be dropped:
     * "The condition is 'in concealedItemIds and not in liveItems', not merely 'not in liveItems'."
     * Walks the same three states an open detail screen lives through — concealed before any reveal,
     * resolvable while revealed, concealed again once the session ends without a full lock (the
     * background-grace-expires case, not a lock) — and checks the condition flips exactly there:
     * false while revealed (screen may resolve the item), true again the instant `concealHiddenSession`
     * ends it (screen must drop it).
     */
    @Test
    fun theConcealedAndNotLiveConditionMatchesTheRealSeam() {
        val password = "correct horse battery staple six"
        val (lockManager, repository) = freshVault(password)
        val hidden = addLogin(repository, "Offshore login six")
        val phrase = "umbrella harbor lighthouse thirteen"

        runBlocking {
            assertEquals(
                HideOutcome.Hidden,
                repository.hide(
                    HiddenTarget.Item(hidden.id),
                    Instant.now().plusSeconds(3600),
                    phrase.toCharArray(),
                ),
            )
        }

        fun mustDropOpenScreen(): Boolean {
            val snapshot = repository.visibilitySnapshot()
            return hidden.id in snapshot.concealedItemIds && snapshot.liveItems.none { it.id == hidden.id }
        }

        assertTrue(mustDropOpenScreen(), "hidden and never revealed: nothing could legitimately have it open")

        val revealOutcome = runBlocking {
            lockManager.attemptHiddenReveal(phrase.toCharArray(), lockManager.nextRevealAttemptGeneration())
        }
        assertEquals(RevealAttemptOutcome.Revealed, revealOutcome)
        assertFalse(mustDropOpenScreen(), "revealed: an open screen may legitimately resolve this id")

        lockManager.concealHiddenSession()
        assertFalse(lockManager.revealSession.revealed.value)
        assertTrue(
            mustDropOpenScreen(),
            "the session ended without a lock: an open screen still holding this id must be dropped now",
        )
    }
}
