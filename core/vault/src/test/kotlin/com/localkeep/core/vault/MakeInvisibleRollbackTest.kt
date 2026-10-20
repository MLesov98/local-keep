package com.localkeep.core.vault

import com.localkeep.core.model.Folder
import com.localkeep.core.model.Note
import com.localkeep.core.model.Vault
import com.localkeep.core.model.VaultSerializer
import com.localkeep.core.model.VaultItem
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rehearsal for the only rollback that is actually available after release.
 *
 * Once a vault has been written at schema 5 an older APK correctly refuses to open it, so shipping
 * the previous build is not a rollback — it is a lockout. What remains is a forward build with
 * [MakeInvisibleAvailability] turned off: it stops *offering* the feature and keeps honouring
 * everything already committed.
 *
 * The whole risk of that plan is one mistake, and it is an inviting one: gating the recovery path
 * with the same flag that gates the Hide action. Do that and the emergency build shipped to protect
 * people is the build that puts their entries permanently out of reach. So the tests here are mostly
 * about what the flags must NOT reach.
 */
class MakeInvisibleRollbackTest {

    private val now: Instant = Instant.parse("2026-08-18T12:00:00Z")

    private fun item(title: String, hiddenUntil: Instant? = null, folderId: UUID? = null) =
        VaultItem(
            id = UUID.randomUUID(),
            title = title,
            createdAt = now,
            updatedAt = now,
            folderId = folderId,
            content = Note("n"),
            hiddenUntil = hiddenUntil,
            hiddenUpdatedAt = hiddenUntil?.let { now },
        )

    @Test
    fun theProjectionNeverConsultsTheAvailabilityFlags() {
        // A source check rather than a behavioural one, because the behaviour it guards only appears
        // in a build nobody has compiled yet. If VaultVisibility ever asked about availability, an
        // emergency build would un-conceal every entry the previous build hid — the list would fill
        // back up on the version the user installed to make it safer.
        val root = repoRoot()
        val projection = File(root, "core/vault/src/main/kotlin/com/localkeep/core/vault/VaultVisibility.kt")
        assertTrue(projection.isFile)
        assertFalse(
            "MakeInvisibleAvailability" in codeOnly(projection.readText()),
            "the projection must conceal unconditionally; an emergency build cannot be allowed to " +
                "reveal what a previous build hid",
        )
    }

    @Test
    fun theRecoveryPathIsNeverGatedOnAvailability() {
        // Same reasoning, aimed at the other half. Stop hiding, Change duration, the authenticated
        // entry feed and the lock-boundary reveal gate all have to keep working in an emergency
        // build, or the rollback strands data.
        val root = repoRoot()
        val ungatable = listOf(
            "core/vault/src/main/kotlin/com/localkeep/core/vault/VaultRepository.kt",
            "core/vault/src/main/kotlin/com/localkeep/core/vault/VaultLockManager.kt",
            "feature/settings/src/main/kotlin/com/localkeep/feature/settings/HiddenItemsViewModel.kt",
            "feature/settings/src/main/kotlin/com/localkeep/feature/settings/HiddenItemsScreen.kt",
        )
        val offenders = ungatable.filter { path ->
            val f = File(root, path)
            f.isFile && "MakeInvisibleAvailability" in codeOnly(f.readText())
        }
        assertEquals(
            emptyList(),
            offenders,
            "recovery must not be gated by the rollback flags — an emergency build that cannot " +
                "un-hide is worse than the feature it is rolling back",
        )
    }

    @Test
    fun bothFlagsAreReadByTheEntryPointTheyGate() {
        // Without this the policy is decorative, and decorative is worse than absent: the rollback
        // plan says an emergency build stops offering the feature, and a flag nothing consults means
        // flipping it ships a build that still offers it. Each flag is checked at exactly one seam.
        val root = repoRoot()
        val consumers = mapOf(
            "feature/vaultlist/src/main/kotlin/com/localkeep/feature/vaultlist/VaultListScreen.kt"
                to "MakeInvisibleAvailability.HIDE_ACTIONS_ENABLED",
            "feature/vaultlist/src/main/kotlin/com/localkeep/feature/vaultlist/VaultListViewModel.kt"
                to "MakeInvisibleAvailability.SEARCH_PHRASE_REVEAL_ENABLED",
        )
        for ((path, expression) in consumers) {
            val file = File(root, path)
            assertTrue(file.isFile, "the gated entry point moved: $path")
            assertTrue(
                expression in codeOnly(file.readText()),
                "$path no longer reads $expression, so the rollback flag gates nothing",
            )
        }
    }

    @Test
    fun aDirectlyHiddenLiveRecordStaysConcealedWithTheFeatureDisabled() {
        // The flags are compile-time constants, so this cannot flip them at runtime. What it can do
        // is state the invariant the disabled build depends on: concealment is a property of the
        // stored lease, not of whether the UI still offers to create one.
        val hidden = item("Offshore", hiddenUntil = now.plusSeconds(3600))
        val visible = item("Cafe wifi")
        val vault = vaultOf(listOf(hidden, visible), emptyList())

        val snapshot = VaultVisibility.project(vault, now, revealSessionActive = false)

        assertTrue(hidden.id in snapshot.concealedItemIds)
        assertFalse(snapshot.liveItems.any { it.id == hidden.id })
        assertTrue(snapshot.liveItems.any { it.id == visible.id })
    }

    @Test
    fun aHiddenFolderStillConcealsItsSubtreeAndItsTrash() {
        val folder = Folder(
            id = UUID.randomUUID(),
            name = "Offshore",
            hiddenUntil = now.plusSeconds(3600),
            hiddenUpdatedAt = now,
        )
        val inside = item("Statement", folderId = folder.id)
        val trashedInside = item("Old statement", folderId = folder.id)
            .copy(deletedAt = now, trashedWithFolderId = null)
        val vault = vaultOf(listOf(inside, trashedInside), listOf(folder))

        val snapshot = VaultVisibility.project(vault, now, revealSessionActive = false)

        assertFalse(snapshot.liveFolders.any { it.id == folder.id })
        assertFalse(snapshot.liveItems.any { it.id == inside.id })
        assertFalse(snapshot.trashItems.any { it.id == trashedInside.id }, "concealed trash conceals too")
        assertTrue(inside.id in snapshot.concealedItemIds)
        assertFalse(
            inside.id in snapshot.directlyHiddenItemIds,
            "an ancestry-concealed item has no lease of its own, so an emergency build must not " +
                "offer it a Stop hiding it cannot honour",
        )
    }

    @Test
    fun theCommittedSchemaFiveFixtureOpensConcealedAndStaysRecoverable() {
        // The emergency build's opening move, on real committed bytes rather than an in-memory value:
        // it must conceal by default and it must still be able to give everything back. Those two
        // together are the whole rollback promise. `Schema5HiddenFixtureTest` owns the format side of
        // this file; what is checked here is what the projection makes of it.
        val bytes = File(repoRoot(), "core/model/src/test/resources/vectors/vault-v5-hidden.bin")
        assertTrue(bytes.isFile, "the schema-5 rollback fixture is missing")
        val vault = VaultSerializer.decode(bytes.readBytes())

        val snapshot = VaultVisibility.project(vault, now, revealSessionActive = false)

        val live = vault.items.single { it.title == "Offshore account" }
        val trashed = vault.items.single { it.title == "Old statement" }
        val insideHiddenFolder = vault.items.single { it.title == "Cafe wifi" }
        val folder = vault.folders.single { it.name == "Offshore" }

        assertFalse(snapshot.liveItems.any { it.id == live.id }, "direct-hidden live item conceals")
        assertFalse(snapshot.trashItems.any { it.id == trashed.id }, "direct-hidden trash conceals")
        assertFalse(snapshot.liveFolders.any { it.id == folder.id }, "direct-hidden folder conceals")
        assertFalse(
            snapshot.liveItems.any { it.id == insideHiddenFolder.id },
            "and so does an ordinary item that happens to live inside it",
        )

        // Recovery. The authenticated Settings feed lists exactly the records carrying a lease of
        // their own — `getHiddenEntriesForManagement` applies the same `HiddenLease.isHidden`
        // predicate this set does — so an emergency build can still hand every one of them back.
        assertEquals(setOf(live.id, trashed.id), snapshot.directlyHiddenItemIds)
        assertEquals(setOf(folder.id), snapshot.directlyHiddenFolderIds)
        assertFalse(
            insideHiddenFolder.id in snapshot.directlyHiddenItemIds,
            "the ancestry-concealed item has no lease to stop, and offering one would do nothing",
        )
    }

    @Test
    fun anExpiredLeaseIsOrdinaryAgainWithoutAnySave() {
        // Expiry has to keep working in a disabled build: it is the one route back to visibility
        // that needs no UI at all, and it is what makes a short lease safe to grant.
        val expired = item("Offshore", hiddenUntil = now.minusSeconds(1))
        val vault = vaultOf(listOf(expired), emptyList())

        val snapshot = VaultVisibility.project(vault, now, revealSessionActive = false)

        assertTrue(snapshot.liveItems.any { it.id == expired.id })
        assertFalse(expired.id in snapshot.concealedItemIds)
    }

    /** Visible exactly when `until == now`; hidden while `until > now`. Boundary, not an interval. */
    @Test
    fun theLeaseBoundaryIsVisibleAtEquality() {
        val atBoundary = item("Offshore", hiddenUntil = now)
        val vault = vaultOf(listOf(atBoundary), emptyList())

        assertTrue(VaultVisibility.project(vault, now, false).liveItems.any { it.id == atBoundary.id })
        assertFalse(
            VaultVisibility.project(vault, now.minusSeconds(1), false)
                .liveItems.any { it.id == atBoundary.id },
        )
    }

    private fun vaultOf(items: List<VaultItem>, folders: List<Folder>) =
        Vault(items = items, folders = folders, createdAt = now, modifiedAt = now)

    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return requireNotNull(dir) { "repo root not found" }
    }
}
