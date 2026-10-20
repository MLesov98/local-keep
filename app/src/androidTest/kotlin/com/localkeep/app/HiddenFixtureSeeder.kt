package com.localkeep.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.model.Attachment
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Passkey
import com.localkeep.core.model.VaultItem
import com.localkeep.core.vault.HiddenTarget
import com.localkeep.core.vault.HideOutcome
import com.localkeep.core.vault.UnlockResult
import com.localkeep.core.vault.VaultInitializer
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import com.localkeep.feature.files.FileBlobStore
import com.localkeep.feature.files.currentDekOrThrow
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Builds the device fixture the make-invisible manual and Maestro passes are driven against.
 *
 * It is an instrumented class rather than a debug menu item or a broadcast receiver, and that is the
 * whole design. A seeder reachable from production code is a way to destroy a real vault — the first
 * thing it does is delete `filesDir` — so it lives in `androidTest`, ships in no APK the user can
 * install, and is invoked by name through `tools/seed-hidden-fixture.sh`, which refuses to run
 * against anything but an emulator.
 *
 * Every id, title and instant below is fixed. A UI pass that has to first read the screen to learn
 * what it is looking at cannot assert an absence, and absence is the only thing this feature has to
 * prove: the interesting assertion is always "the string `Offshore account` appears nowhere", which
 * needs the string to be known in advance.
 *
 * The password arrives as an instrumentation argument rather than being hard-coded, so no credential
 * lives in this file. `tools/seed-hidden-fixture.sh` states plainly where that argument is visible.
 */
@RunWith(AndroidJUnit4::class)
class HiddenFixtureSeeder {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Wipes app-private storage and writes the fixture. Named `seed*` rather than `test*` so a
     * broad `connectedAndroidTest` never runs it by accident: the module's other suites would be
     * looking at a vault this replaced.
     */
    @Test
    fun seedHiddenFixture() = runBlocking {
        // `assumeTrue`, not `require`: this class carries a JUnit `@Test`, so a broad
        // `connectedAndroidTest` — which CI runs — would otherwise execute it, wipe the vault the
        // other suites in this module are using, and go red on a missing argument. Skipping without
        // the argument makes the seeder inert unless someone asked for it by name.
        val password = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("lkPassword")
        org.junit.Assume.assumeTrue(
            "no lkPassword argument — not seeding (run tools/seed-hidden-fixture.sh to seed)",
            password != null,
        )
        checkNotNull(password)

        context.filesDir.deleteRecursively()
        context.filesDir.mkdirs()

        val lockManager = VaultLockManager(context, KeystoreManager(context))
        VaultInitializer.create(context, password.toCharArray())
        assertEquals(UnlockResult.REAL, lockManager.unlock(password.toCharArray()), "fixture vault did not unlock")

        val repository = VaultRepository(lockManager)
        val blobStore = FileBlobStore(context)
        val dek = lockManager.currentDekOrThrow()
        val now = Instant.now()

        // --- folders -------------------------------------------------------------------------
        // Two roots: one ordinary, one whose whole subtree is concealed by a single lease on the
        // parent. The nested child is what proves ancestry concealment reaches more than one level.
        val public = repository.createFolder("Public")
        val offshore = repository.createFolder("Offshore")
        val offshoreInner = repository.createFolder("Statements", offshore.id)

        // --- the visible controls ------------------------------------------------------------
        // Present so an absence assertion cannot pass by the vault simply being empty, and so a
        // screenshot of the concealed state is visibly a working app rather than a broken one.
        add(repository, VISIBLE_LOGIN_ID, "Cafe wifi", public.id, Login(username = "guest", password = "hunter2"))
        add(repository, VISIBLE_NOTE_ID, "Shopping list", null, Note("milk"))

        // --- direct-hidden live item ---------------------------------------------------------
        add(repository, HIDDEN_LOGIN_ID, HIDDEN_LOGIN_TITLE, null, Login(username = "offshore.user", password = "s3cret"))

        // --- the autofill pair, both bound to the same package --------------------------------
        // The negative control this feature actually needs, and the one a previous device pass could
        // not finish: `findMatching` is a single feed, so if concealment works, the test app's
        // suggestion strip offers the visible login and never the hidden one. Two entries on one
        // package rather than one, because "no suggestion appeared" is also what a broken autofill
        // service looks like — the visible half is what tells those apart.
        add(
            repository,
            AUTOFILL_VISIBLE_ID,
            AUTOFILL_VISIBLE_TITLE,
            null,
            Login(
                username = "visible.user",
                password = "visiblepass",
                androidPackages = listOf(AUTOFILL_TEST_PACKAGE),
            ),
        )
        add(
            repository,
            AUTOFILL_HIDDEN_ID,
            AUTOFILL_HIDDEN_TITLE,
            null,
            Login(
                username = "offshore.user",
                password = "hiddenpass",
                androidPackages = listOf(AUTOFILL_TEST_PACKAGE),
            ),
        )

        // --- ancestry-concealed records ------------------------------------------------------
        add(repository, IN_HIDDEN_FOLDER_ID, "Statement 2026-01", offshoreInner.id, Note("balance"))

        // --- a passkey and a login that autofill/credential surfaces must not offer -----------
        add(
            repository,
            HIDDEN_PASSKEY_ID,
            "Offshore passkey",
            offshore.id,
            Passkey(
                rpId = "offshore.example",
                credentialId = ByteArray(16) { it.toByte() },
                userHandle = ByteArray(8) { it.toByte() },
                privateKeyRef = ByteArray(32) { it.toByte() },
                createdAt = now,
                userName = "offshore.user",
                userDisplayName = "Offshore",
            ),
        )

        // --- a file with an attachment, so blob GC has something to get wrong ------------------
        val attachmentRef = blobStore.encryptToBlob(ByteArrayInputStream("fixture".toByteArray()), dek)
        add(
            repository,
            HIDDEN_FILE_ID,
            "Offshore scan",
            offshoreInner.id,
            com.localkeep.core.model.File(
                filename = "scan.bin",
                mime = "application/octet-stream",
                sizeBytes = 7,
                blobRef = blobStore.encryptToBlob(ByteArrayInputStream("scan".toByteArray()), dek),
            ),
            attachments = listOf(
                Attachment(
                    id = ATTACHMENT_ID,
                    filename = "receipt.bin",
                    mime = "application/octet-stream",
                    sizeBytes = 7,
                    blobRef = attachmentRef,
                ),
            ),
        )

        // --- trash ----------------------------------------------------------------------------
        // Both shapes, because they are destroyed by different code paths: one trashed on its own,
        // one that went down with a folder and is therefore represented by the folder's row.
        add(repository, VISIBLE_TRASH_ID, "Old receipt", null, Note("expired"))
        repository.moveToTrash(VISIBLE_TRASH_ID)
        add(repository, HIDDEN_TRASH_ID, HIDDEN_TRASH_TITLE, null, Note("archived"))
        repository.moveToTrash(HIDDEN_TRASH_ID)

        val trashedFolder = repository.createFolder("Old offshore")
        add(repository, FOLDER_TRASHED_HIDDEN_ID, "Statement 2025-12", trashedFolder.id, Note("older"))
        repository.trashFolder(trashedFolder.id)

        // --- the leases, and the phrase, last ---------------------------------------------------
        // The first hide is also the commit that creates the reveal phrase, so it goes first and
        // carries it. Everything after simply leases.
        assertEquals(
            HideOutcome.Hidden,
            repository.hide(HiddenTarget.Item(HIDDEN_LOGIN_ID), INDEFINITE_ENOUGH(now), REVEAL_PHRASE.toCharArray()),
        )
        hide(repository, HiddenTarget.Folder(offshore.id), now)
        hide(repository, HiddenTarget.Item(HIDDEN_TRASH_ID), now)
        hide(repository, HiddenTarget.Item(FOLDER_TRASHED_HIDDEN_ID), now)
        hide(repository, HiddenTarget.Item(HIDDEN_PASSKEY_ID), now)
        hide(repository, HiddenTarget.Item(AUTOFILL_HIDDEN_ID), now)

        // --- prove the fixture is the fixture ----------------------------------------------------
        // A seeder that half-worked leaves a UI pass chasing a bug that is really a bad fixture, and
        // it looks exactly like the feature working. So it self-checks before it reports success.
        val snapshot = repository.visibilitySnapshot()
        assertTrue(snapshot.liveItems.any { it.id == VISIBLE_LOGIN_ID }, "the visible control is missing")
        assertTrue(snapshot.trashItems.any { it.id == VISIBLE_TRASH_ID }, "the visible trash control is missing")
        for (concealed in listOf(HIDDEN_LOGIN_ID, IN_HIDDEN_FOLDER_ID, HIDDEN_PASSKEY_ID, HIDDEN_FILE_ID)) {
            assertTrue(concealed in snapshot.concealedItemIds, "not concealed: $concealed")
        }
        assertTrue(HIDDEN_TRASH_ID in snapshot.concealedItemIds, "the hidden trash item is not concealed")
        assertTrue(offshore.id in snapshot.concealedFolderIds)
        assertTrue(offshoreInner.id in snapshot.concealedFolderIds, "ancestry concealment must reach the child")

        // The autofill pair, asserted through the exact accessor the service reads rather than
        // through the snapshot: this is the one claim a UI dump on another app's screen will be
        // checked against, so it is worth failing here if it is not already true in the data.
        val offered = repository.findMatching(webDomain = null, packageName = AUTOFILL_TEST_PACKAGE)
        assertEquals(
            listOf(AUTOFILL_VISIBLE_ID),
            offered.map { it.id },
            "autofill must offer the visible login for $AUTOFILL_TEST_PACKAGE and only that one",
        )

        lockManager.lock()
        println("SEEDED phrase=$REVEAL_PHRASE visible=${snapshot.liveItems.size} concealed=${snapshot.concealedItemIds.size}")
    }

    /**
     * Reads the seeded vault back and reports what the reveal gate says about the fixture's phrase,
     * without touching the vault. Diagnostic only, and inert without its argument for the same
     * reason [seedHiddenFixture] is.
     *
     * It exists because "the phrase did not reveal" has two very different causes — a gate that
     * rejects it, or a UI path that never submitted it — and a screen recording cannot tell them
     * apart. Run with `-e probe 1 -e lkPassword <password>`.
     */
    @Test
    fun probeRevealGate() = runBlocking {
        val args = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("no probe argument", args.getString("probe") == "1")
        val password = checkNotNull(args.getString("lkPassword"))

        val lockManager = VaultLockManager(context, KeystoreManager(context)).also { it.autoLockTimeoutMs = 0 }
        assertEquals(UnlockResult.REAL, lockManager.unlock(password.toCharArray()))
        val repository = VaultRepository(lockManager)

        val snapshot = repository.visibilitySnapshot()
        val generation = lockManager.nextRevealAttemptGeneration()
        val outcome = lockManager.attemptHiddenReveal(REVEAL_PHRASE.toCharArray(), generation)
        // The raw inventory, not the projection: the question this answers is whether a record the
        // UI is hiding still physically exists, and a filtered read cannot tell "concealed" from
        // "destroyed" — which is the exact confusion an Empty Trash can turn into data loss.
        val inventory = repository.getCompleteStorageInventory()
        val trashed = inventory.allItems.filter { it.deletedAt != null }
        println(
            "PROBE concealedItems=${snapshot.concealedItemIds.size} " +
                "concealedFolders=${snapshot.concealedFolderIds.size} " +
                "revealOutcome=$outcome revealed=${lockManager.revealSession.revealed.value}",
        )
        println("PROBE items=${inventory.allItems.size} folders=${inventory.allFolders.size}")
        println("PROBE trashedTitles=" + trashed.joinToString("|") { it.title })
        println("PROBE liveTitles=" + inventory.allItems.filter { it.deletedAt == null }.joinToString("|") { it.title })
        lockManager.lock()
        Unit
    }

    private suspend fun hide(repository: VaultRepository, target: HiddenTarget, now: Instant) {
        assertEquals(HideOutcome.Hidden, repository.hide(target, INDEFINITE_ENOUGH(now)), "could not hide $target")
    }

    private suspend fun add(
        repository: VaultRepository,
        id: UUID,
        title: String,
        folderId: UUID?,
        content: com.localkeep.core.model.ItemContent,
        attachments: List<Attachment> = emptyList(),
    ) {
        val now = Instant.now()
        repository.addItem(
            VaultItem(
                id = id,
                title = title,
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
                content = content,
                attachments = attachments,
            ),
        )
    }

    private companion object {
        /**
         * A year out rather than [com.localkeep.core.model.HiddenLease.INDEFINITE].
         *
         * Indefinite would exercise the one lease that never appears in `nextExpiry`, and the device
         * pass needs the expiry machinery live so a "nearest expiry" reading can be checked at all.
         */
        val INDEFINITE_ENOUGH: (Instant) -> Instant = { it.plusSeconds(365L * 24 * 3600) }

        const val REVEAL_PHRASE = "open sesame please"

        // Stable ids, so a Maestro flow or a `uiautomator dump` assertion can name a record without
        // reading the screen first. The suffixes are readable on purpose: a failing dump is easier
        // to interpret when the id in it says what it was supposed to be.
        val VISIBLE_LOGIN_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a1")
        val VISIBLE_NOTE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a2")
        val VISIBLE_TRASH_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000a3")
        val HIDDEN_LOGIN_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b1")
        val IN_HIDDEN_FOLDER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b2")
        val HIDDEN_PASSKEY_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b3")
        val HIDDEN_FILE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b4")
        val HIDDEN_TRASH_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b5")
        val FOLDER_TRASHED_HIDDEN_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000b6")
        val ATTACHMENT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000c1")

        val AUTOFILL_VISIBLE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000d1")
        val AUTOFILL_HIDDEN_ID: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000d2")

        /** The titles every absence assertion greps for. Distinctive on purpose. */
        const val HIDDEN_LOGIN_TITLE = "Offshore account"
        const val HIDDEN_TRASH_TITLE = "Offshore archive"
        const val AUTOFILL_VISIBLE_TITLE = "Autofill visible control"
        const val AUTOFILL_HIDDEN_TITLE = "Autofill hidden control"

        /** `tools/autofill-testapp`, the harness the real Android autofill service is driven against. */
        const val AUTOFILL_TEST_PACKAGE = "com.localkeep.autofilltest"
    }
}
