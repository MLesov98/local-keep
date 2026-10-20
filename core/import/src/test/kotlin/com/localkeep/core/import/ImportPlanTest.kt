package com.localkeep.core.import

import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPlanTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun login(
        title: String,
        username: String,
        url: String,
        updatedAt: Instant = base,
        password: String = "pw",
    ) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = base,
        updatedAt = updatedAt,
        content = Login(
            username = username,
            password = password,
            uris = listOf(UriMatch(uri = url)),
        ),
    )

    /**
     * `URI.host` is null for any host containing an underscore, and the fallback used to hand back
     * the whole raw URL — so the same account, listed once with a path and once without, produced
     * two identities and imported as a second copy of itself.
     */
    @Test
    fun aHostWithAnUnderscoreStillMatchesAcrossDifferingPaths() {
        val plan = ImportMerger.plan(
            existing = listOf(login("Bank", "ada", "https://my_bank.example.com")),
            imported = listOf(
                login("Bank", "ada", "https://my_bank.example.com/login", updatedAt = base.plusSeconds(60)),
            ),
        )

        assertEquals(listOf(ImportMerger.Disposition.UPDATES_EXISTING), plan.map { it.disposition })
    }

    /** A port, a `www.`, and a trailing path must not split one account into two either. */
    @Test
    fun trivialUrlDifferencesResolveToTheSameAccount() {
        val plan = ImportMerger.plan(
            existing = listOf(login("Shop", "ada", "https://www.shop.example:8443/cart")),
            imported = listOf(
                login("Shop", "ada", "shop.example", updatedAt = base.plusSeconds(60)),
            ),
        )

        assertEquals(listOf(ImportMerger.Disposition.UPDATES_EXISTING), plan.map { it.disposition })
    }

    @Test
    fun freshItemsAreNew() {
        val plan = ImportMerger.plan(
            existing = emptyList(),
            imported = listOf(login("GitHub", "octocat", "github.com")),
        )
        assertEquals(listOf(ImportMerger.Disposition.NEW), plan.map { it.disposition })
    }

    @Test
    fun newerImportUpdatesExistingAndNamesTheCollision() {
        val existing = login("GitHub", "octocat", "github.com", updatedAt = base)
        val plan = ImportMerger.plan(
            existing = listOf(existing),
            imported = listOf(
                login("GitHub (export)", "octocat", "https://www.github.com/", base.plusSeconds(60)),
            ),
        )
        assertEquals(ImportMerger.Disposition.UPDATES_EXISTING, plan.single().disposition)
        // The preview has to name the vault entry, not the imported one — that's the thing the
        // user is being asked to overwrite.
        assertEquals("GitHub", plan.single().collidesWith)
    }

    @Test
    fun olderImportIsReportedAsAlreadyCurrent() {
        val existing = login("GitHub", "octocat", "github.com", updatedAt = base.plusSeconds(60))
        val plan = ImportMerger.plan(
            existing = listOf(existing),
            imported = listOf(login("GitHub", "octocat", "github.com", updatedAt = base)),
        )
        assertEquals(ImportMerger.Disposition.ALREADY_CURRENT, plan.single().disposition)
    }

    @Test
    fun duplicateWithinTheImportItselfIsNotReportedTwiceAsNew() {
        // A file containing the same login twice must not claim both are new; the user would then
        // approve a preview that quietly collapses to one item.
        val plan = ImportMerger.plan(
            existing = emptyList(),
            imported = listOf(
                login("GitHub", "octocat", "github.com"),
                login("GitHub copy", "octocat", "github.com"),
            ),
        )
        assertEquals(ImportMerger.Disposition.NEW, plan[0].disposition)
        assertTrue(plan[1].disposition != ImportMerger.Disposition.NEW)
    }

    @Test
    fun planAgreesWithMergeAboutWhatIsNew() {
        // The preview would be a lie if it disagreed with what merge() actually does.
        val existing = listOf(login("GitHub", "octocat", "github.com"))
        val imported = listOf(
            login("GitHub", "octocat", "github.com", updatedAt = base.plusSeconds(5)),
            login("GitLab", "octocat", "gitlab.com"),
        )
        val newCount = ImportMerger.plan(existing, imported)
            .count { it.disposition == ImportMerger.Disposition.NEW }
        val merged = ImportMerger.merge(existing, imported)
        assertEquals(newCount, merged.size - existing.size)
    }

    @Test
    fun addAllKeepsDuplicatesThatMergeWouldCollapse() {
        val existing = listOf(login("GitHub", "octocat", "github.com"))
        val imported = listOf(
            login("GitHub", "octocat", "github.com", updatedAt = base.plusSeconds(5)),
        )
        assertEquals(1, ImportMerger.apply(existing, imported, ImportMerger.Mode.MERGE).size)
        assertEquals(2, ImportMerger.apply(existing, imported, ImportMerger.Mode.ADD_ALL).size)
    }

    @Test
    fun onlySelectedItemsAreApplied() {
        val existing = emptyList<VaultItem>()
        val all = listOf(
            login("Keep", "a", "a.com"),
            login("Skip", "b", "b.com"),
        )
        val applied = ImportMerger.apply(existing, listOf(all[0]), ImportMerger.Mode.MERGE)
        assertEquals(listOf("Keep"), applied.map { it.title })
    }

    /**
     * Three rows sharing one identity: the third is compared against whichever of the first two
     * merge() left in the slot, not against the first one seen. The plan used to record a
     * collision target only when a row was classified NEW, so row 3 (Jan 3) was measured against
     * row 1 (Jan 1) and previewed as "will update" — while merge() measured it against the Jan 5
     * slot and discarded it.
     */
    @Test
    fun theThirdRowOfACollisionChainIsComparedAgainstTheRunningWinner() {
        val imported = listOf(
            login("First", "octocat", "github.com", updatedAt = base),
            login("Newest", "octocat", "github.com", updatedAt = base.plusSeconds(120)),
            login("Middle", "octocat", "github.com", updatedAt = base.plusSeconds(60)),
        )
        val plan = ImportMerger.plan(existing = emptyList(), imported = imported)

        // Control — these two held before the fix as well, so a regression here is not the fix.
        assertEquals(ImportMerger.Disposition.NEW, plan[0].disposition)
        assertEquals(ImportMerger.Disposition.UPDATES_EXISTING, plan[1].disposition)

        assertEquals(ImportMerger.Disposition.ALREADY_CURRENT, plan[2].disposition)
        assertEquals("Newest", plan[2].collidesWith)
        // ...and merge() agrees: one item survives, and it is the newest of the three.
        val merged = ImportMerger.merge(existing = emptyList(), imported = imported)
        assertEquals(listOf("Newest"), merged.map { it.title })
    }

    /** The same staleness against a vault item: the slot advances once an import row wins it. */
    @Test
    fun aVaultCollisionSlotAdvancesToTheImportRowThatWonIt() {
        val existing = listOf(login("Vault", "octocat", "github.com", updatedAt = base))
        val imported = listOf(
            login("Newest", "octocat", "github.com", updatedAt = base.plusSeconds(120)),
            login("Middle", "octocat", "github.com", updatedAt = base.plusSeconds(60)),
        )
        val plan = ImportMerger.plan(existing = existing, imported = imported)

        // Control — held before the fix too.
        assertEquals(ImportMerger.Disposition.UPDATES_EXISTING, plan[0].disposition)
        assertEquals("Vault", plan[0].collidesWith)

        assertEquals(ImportMerger.Disposition.ALREADY_CURRENT, plan[1].disposition)
        assertEquals("Newest", plan[1].collidesWith)
        assertEquals(listOf("Newest"), ImportMerger.merge(existing, imported).map { it.title })
    }

    @Test
    fun anIdenticalNoteIsAlreadyCurrentAndADifferentOneIsNew() {
        // Until 2026-09-14 a note carried no identity at all, so re-importing the same export
        // appended a second copy of every note in it. A note's identity is its title plus its exact
        // body: the same note is reported as already held (and merging keeps the vault's copy —
        // nothing is lost, the bodies are equal), while a note that differs by a character is new.
        val note = VaultItem(
            id = UUID.randomUUID(),
            title = "Recovery codes",
            createdAt = base,
            updatedAt = base,
            content = Note(body = "..."),
        )
        val same = ImportMerger.plan(existing = listOf(note), imported = listOf(note.copy(id = UUID.randomUUID())))
        assertEquals(ImportMerger.Disposition.ALREADY_CURRENT, same.single().disposition)
        assertEquals(listOf(note.id), ImportMerger.merge(listOf(note), listOf(note.copy(id = UUID.randomUUID()))).map { it.id })

        val edited = note.copy(id = UUID.randomUUID(), content = Note(body = "... and one more"))
        val different = ImportMerger.plan(existing = listOf(note), imported = listOf(edited))
        assertEquals(ImportMerger.Disposition.NEW, different.single().disposition)
    }
}
