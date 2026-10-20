package com.localkeep.core.import

import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ImportMerger.identityOf` used to key a Login on `uris.firstOrNull()` alone, while
 * [com.localkeep.core.health.DuplicateDetector] matches on EVERY normalised host — the two were
 * supposed to agree (see the docstring on `identityKeysOf`) but didn't for multi-URI logins, and a
 * no-URI Login degraded to username-only identity instead of the detector's title fallback.
 */
class ImportMergerIdentityTest {

    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun login(
        title: String,
        username: String,
        urls: List<String>,
        updatedAt: Instant = base,
        password: String = "pw",
        passwordChangedAt: Instant? = null,
    ) = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = base,
        updatedAt = updatedAt,
        content = Login(
            username = username,
            password = password,
            uris = urls.map { UriMatch(uri = it) },
            passwordChangedAt = passwordChangedAt,
        ),
    )

    /**
     * Two logins sharing only their *second* URI must still collide. The old `identityOf` keyed
     * solely on `uris.firstOrNull()`, so `[a.com, shared.com]` vs `[shared.com]` never matched even
     * though `DuplicateDetector` (which checks every host) would flag them as the same account.
     */
    @Test
    fun loginsSharingOnlyTheirSecondUriStillCollide() {
        val existing = login("Old", "ada", listOf("a.example.com", "shared.example.com"))
        val imported = login(
            "New",
            "ada",
            listOf("shared.example.com"),
            updatedAt = base.plusSeconds(60),
        )
        val plan = ImportMerger.plan(existing = listOf(existing), imported = listOf(imported))
        assertEquals(ImportMerger.Disposition.UPDATES_EXISTING, plan.single().disposition)

        val merged = ImportMerger.merge(existing = listOf(existing), imported = listOf(imported))
        assertEquals(1, merged.size)
    }

    /**
     * Reordered URI lists must collide too — identity can't depend on list order, only on the
     * old code depended on "first" position, and this pins the fix against regressing to that.
     */
    @Test
    fun reorderedUriListsStillCollide() {
        val existing = login("Old", "ada", listOf("first.example.com", "second.example.com"))
        val imported = login(
            "New",
            "ada",
            listOf("second.example.com", "first.example.com"),
            updatedAt = base.plusSeconds(60),
        )
        val plan = ImportMerger.plan(existing = listOf(existing), imported = listOf(imported))
        assertEquals(ImportMerger.Disposition.UPDATES_EXISTING, plan.single().disposition)
    }

    /**
     * A Login with no URI at all must fall back to its title, matching `DuplicateDetector`'s
     * fallback — not to username alone, which would collide unrelated accounts sharing a username
     * across different hand-typed titles.
     */
    @Test
    fun noUriLoginFallsBackToTitleNotUsernameAlone() {
        val existing = login("Netflix", "ada", urls = emptyList())
        val sameTitle = login("Netflix", "ada", urls = emptyList(), updatedAt = base.plusSeconds(60))
        val differentTitle = login("Hulu", "ada", urls = emptyList(), updatedAt = base.plusSeconds(60))

        val samePlan = ImportMerger.plan(existing = listOf(existing), imported = listOf(sameTitle))
        assertEquals(ImportMerger.Disposition.UPDATES_EXISTING, samePlan.single().disposition)

        val differentPlan = ImportMerger.plan(existing = listOf(existing), imported = listOf(differentTitle))
        assertEquals(ImportMerger.Disposition.NEW, differentPlan.single().disposition)
    }

    @Test
    fun existingWinnerStillKeepsTheNewerImportedPassword() {
        val existing = login(
            "Example",
            "ada",
            listOf("example.com"),
            updatedAt = base.plusSeconds(120),
            password = "old-password",
        )
        val imported = login(
            "Example",
            "ada",
            listOf("example.com"),
            updatedAt = base.plusSeconds(60),
            password = "new-password",
            passwordChangedAt = base.plusSeconds(180),
        )

        val merged = ImportMerger.merge(existing = listOf(existing), imported = listOf(imported))

        assertEquals("new-password", (merged.single().content as Login).password)
    }

    /**
     * Identity keys are `host + separator + username`, so the separator must be one no user can put
     * into either half. A `|` fails that: `UrlHosts.normalise` hands back a pipe untouched whenever
     * `URI.host` throws (it does for `https://my|bank.example.com`) and the hand-rolled authority
     * fallback slices the string as-is. Two unrelated logins then key alike and one silently
     * replaces the other in the vault.
     */
    @Test
    fun pipeInHostOrUsernameDoesNotFabricateACollision() {
        val existing = login("Bank", "ada", listOf("https://my|bank.example.com"))
        val unrelated = login(
            "Something else",
            "bank.example.com|ada",
            listOf("https://my"),
            updatedAt = base.plusSeconds(60),
        )

        val plan = ImportMerger.plan(existing = listOf(existing), imported = listOf(unrelated))
        assertEquals(ImportMerger.Disposition.NEW, plan.single().disposition)

        val merged = ImportMerger.merge(existing = listOf(existing), imported = listOf(unrelated))
        assertEquals(2, merged.size)
    }

    /**
     * The same hazard on the no-URI branch, where the key is `"title:" + title + separator +
     * username` and a pipe can be typed straight into either field.
     */
    @Test
    fun pipeInTitleOrUsernameDoesNotFabricateACollision() {
        val existing = login("Netflix|x", "ada", urls = emptyList())
        val unrelated = login(
            "Netflix",
            "x|ada",
            urls = emptyList(),
            updatedAt = base.plusSeconds(60),
        )

        val plan = ImportMerger.plan(existing = listOf(existing), imported = listOf(unrelated))
        assertEquals(ImportMerger.Disposition.NEW, plan.single().disposition)

        val merged = ImportMerger.merge(existing = listOf(existing), imported = listOf(unrelated))
        assertEquals(2, merged.size)
    }
}
