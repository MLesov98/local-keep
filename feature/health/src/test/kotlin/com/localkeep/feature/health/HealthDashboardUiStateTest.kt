package com.localkeep.feature.health

import com.localkeep.core.health.BreachFilter
import com.localkeep.core.health.BreachFilterSource
import com.localkeep.core.health.SecurityDashboard
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthDashboardUiStateTest {

    private val now: Instant = Instant.parse("2026-07-27T00:00:00Z")

    private fun login(password: String, username: String = "ada@example.com", title: String = "acct"): VaultItem = VaultItem(
        id = UUID.randomUUID(),
        title = title,
        createdAt = now,
        updatedAt = now,
        content = Login(
            username = username,
            password = password,
            passwordChangedAt = now,
        ),
    )

    private fun result(
        category: SecurityDashboard.Category,
        ids: Set<UUID>,
    ) = SecurityDashboard.CategoryResult(category, ids)

    private fun summary(
        weak: Set<UUID> = emptySet(),
        reused: Set<UUID> = emptySet(),
        old: Set<UUID> = emptySet(),
        breached: Set<UUID> = emptySet(),
        duplicate: Set<UUID> = emptySet(),
        scanned: Int = 0,
        passwordsChecked: Int = scanned,
    ) = SecurityDashboard.Summary(
        weak = result(SecurityDashboard.Category.WEAK, weak),
        reused = result(SecurityDashboard.Category.REUSED, reused),
        old = result(SecurityDashboard.Category.OLD, old),
        breached = result(SecurityDashboard.Category.BREACHED, breached),
        duplicate = result(SecurityDashboard.Category.DUPLICATE, duplicate),
        breachFilterVintage = "",
        breachCorpusEntries = 0L,
        breachDataAvailable = false,
        scannedCount = scanned,
        passwordsChecked = passwordsChecked,
    )

    @Test
    fun `an item in several categories is one affected item, not several`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        val counted = summary(weak = setOf(a, b), reused = setOf(a, b), scanned = 3).affectedItemCount()

        assertEquals(2, counted)
    }

    @Test
    fun `breached, reused and weak all contribute to the affected set`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()

        val counted = summary(
            weak = setOf(a),
            reused = setOf(b),
            breached = setOf(c),
            scanned = 4,
        ).affectedItemCount()

        assertEquals(3, counted)
    }

    @Test
    fun `age and duplicates are not password faults and stay out of the affected set`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        val counted = summary(old = setOf(a), duplicate = setOf(b), scanned = 2).affectedItemCount()

        assertEquals(0, counted)
    }

    @Test
    fun `a real scan of two logins sharing one weak password reports two affected items`() {
        val shared = "password1"
        val items = listOf(
            login(shared, username = "ada@example.com"),
            login(shared, username = "grace@example.com"),
            login("h7Qv-nzKp-4rTm-9wLd-2xBc", username = "hopper@example.com"),
        )
        val dashboard = SecurityDashboard(BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null }))

        val result = dashboard.analyze(items)

        assertEquals(2, result.weak.count)
        assertEquals(2, result.reused.count)
        assertEquals(2, result.affectedItemCount())
    }

    // 19-3: a vault of password-less Logins (routine after a browser import that drops passwords)
    // is scanned — scannedCount counts the items — but has nothing to check against the breach
    // corpus or score for weakness. passwordsChecked is the only field that says so; scannedCount
    // and breachDataAvailable both stay exactly as they would for a real completed check.
    @Test
    fun `logins with no password are scanned but nothing is actually checked`() {
        val items = listOf(login(password = ""), login(password = "", username = "grace@example.com"))
        val dashboard = SecurityDashboard(BreachFilter(minimumCredibleEntries = 0, source = BreachFilterSource { null }))

        val result = dashboard.analyze(items)

        assertEquals(2, result.scannedCount)
        assertEquals(0, result.passwordsChecked)
        assertEquals(0, result.weak.count)
        assertEquals(0, result.breached.count)
    }

    @Test
    fun `verdict counts affected items rather than category memberships`() {
        val state = HealthDashboardUiState(
            loading = false,
            weakCount = 2,
            reusedCount = 2,
            affectedCount = 2,
            scannedCount = 3,
            passwordsChecked = 3,
        )

        // null verdictRes means "resolve the affected-count plural at the call site" — see
        // HealthDashboardViewModel.verdictRes. affectedCount carries the quantity.
        assertEquals(null, state.verdictRes)
        assertEquals(2, state.affectedCount)
    }

    @Test
    fun `one affected item also resolves to the plural, singular form is a call-site concern`() {
        val state = HealthDashboardUiState(
            loading = false,
            weakCount = 1,
            reusedCount = 1,
            affectedCount = 1,
            scannedCount = 3,
            passwordsChecked = 3,
        )

        assertEquals(null, state.verdictRes)
        assertEquals(1, state.affectedCount)
    }

    @Test
    fun `a vault with only ageing passwords is healthy`() {
        val state = HealthDashboardUiState(loading = false, oldCount = 2, scannedCount = 4, passwordsChecked = 4)

        assertEquals(R.string.health_verdict_healthy_ageing, state.verdictRes)
    }

    @Test
    fun `a vault with only duplicate entries is healthy`() {
        val state = HealthDashboardUiState(loading = false, duplicateCount = 2, scannedCount = 4, passwordsChecked = 4)

        assertEquals(R.string.health_verdict_healthy_duplicates, state.verdictRes)
    }

    @Test
    fun `a clean vault has nothing worth fixing`() {
        val state = HealthDashboardUiState(loading = false, scannedCount = 4, passwordsChecked = 4)

        assertEquals(R.string.health_verdict_nothing_worth_fixing, state.verdictRes)
    }

    @Test
    fun `a scan that checked no passwords does not report a clean bill of health`() {
        // The state a browser CSV export produces routinely: Logins imported with a URL and a
        // username but no password. `CsvColumnMapper` keeps such a row - it only skips one where
        // url, username AND password are all blank - so the vault has items to scan and nothing to
        // score. Every category count is then 0 by arithmetic rather than by anything having been
        // checked, and gating on `scannedCount` alone let that fall through to "Nothing worth
        // fixing": a clean bill of health issued without a single password having been looked at,
        // on the one screen whose entire job is answering "are my passwords OK".
        //
        // The distinction was already built - `adviceRes` and `footnoteRes` branch on
        // `passwordsChecked` and say so in their own comments - and simply never reached the
        // verdict or the ring.
        val state = HealthDashboardUiState(loading = false, scannedCount = 4, passwordsChecked = 0)

        assertEquals(R.string.health_verdict_nothing_to_check, state.verdictRes)
    }

    @Test
    fun `an empty scan says there is nothing to check`() {
        val state = HealthDashboardUiState(loading = false, scannedCount = 0)

        assertEquals(R.string.health_verdict_nothing_to_check, state.verdictRes)
    }

    // The faulted verdict used to open with "Good" for every non-zero count. Ten logins sharing
    // "123456" - breached, reused and weak, every one - score 5 and read "Good, with 10 things worth
    // fixing" beside a ring reading 5 and advice to treat the passwords as compromised.
    @Test
    fun `a vault of breached passwords does not open with Good`() {
        val state = HealthDashboardUiState(
            loading = false,
            breachedCount = 10,
            reusedCount = 10,
            weakCount = 10,
            affectedCount = 10,
            scannedCount = 10,
            passwordsChecked = 1,
        )

        assertEquals(5, state.score, "precondition: the score the finding reported")
        assertEquals(R.plurals.health_verdict_affected_count_attention, state.faultVerdictPluralRes)
    }

    @Test
    fun `one breached password in a large clean vault still does not open with Good`() {
        // 99 / 100 clean: the score is 99. A breach match is the one finding the advice line calls
        // compromised rather than worth fixing, so the share of the vault it touches cannot matter.
        val state = HealthDashboardUiState(
            loading = false,
            breachedCount = 1,
            affectedCount = 1,
            scannedCount = 100,
            passwordsChecked = 100,
        )

        assertEquals(99, state.score, "precondition: a score that would otherwise read Good")
        assertEquals(R.plurals.health_verdict_affected_count_attention, state.faultVerdictPluralRes)
    }

    @Test
    fun `a score below the good line does not open with Good`() {
        // 10 of 10 reused: 100 - 30 = 70.
        val state = HealthDashboardUiState(
            loading = false,
            reusedCount = 10,
            affectedCount = 10,
            scannedCount = 10,
            passwordsChecked = 10,
        )

        assertEquals(70, state.score)
        assertEquals(R.plurals.health_verdict_affected_count_attention, state.faultVerdictPluralRes)
    }

    /** The control: a vault that is mostly fine still reads Good, or the fix just removed the word. */
    @Test
    fun `a high score with no breach match still opens with Good`() {
        val state = HealthDashboardUiState(
            loading = false,
            weakCount = 1,
            affectedCount = 1,
            scannedCount = 10,
            passwordsChecked = 10,
        )

        assertEquals(98, state.score)
        assertEquals(R.plurals.health_verdict_affected_count, state.faultVerdictPluralRes)
    }

    @Test
    fun `the good line itself is Good and one point under it is not`() {
        // 10 of 10 weak: 100 - 20 = 80, exactly on the line.
        val onTheLine = HealthDashboardUiState(
            loading = false, weakCount = 10, affectedCount = 10, scannedCount = 10, passwordsChecked = 10,
        )
        // The same plus one ageing password: 100 - 20 - 0.8 = 79.2, which truncates to 79.
        val under = onTheLine.copy(oldCount = 1)

        assertEquals(HealthDashboardUiState.GOOD_VERDICT_MIN_SCORE, onTheLine.score)
        assertEquals(R.plurals.health_verdict_affected_count, onTheLine.faultVerdictPluralRes)
        assertEquals(79, under.score)
        assertEquals(R.plurals.health_verdict_affected_count_attention, under.faultVerdictPluralRes)
    }

    // F43, 2026-09-13: score's KDoc documents scannedCount (every live Login) as the denominator,
    // deliberately including the password-less items the category counts can never touch — but that
    // choice had no pinning test for the case where passwordsChecked < scannedCount. Without this
    // test, changing the denominator to passwordsChecked (which would read as the more "correct"
    // fix to someone who had not read the KDoc) would pass every other test in this file silently.
    //
    // Ten scanned Logins, only two of which carry a password, and both of those are breached: the
    // documented formula spreads the 45-point breach penalty over all ten (n = scannedCount), so a
    // vault where every checkable password is breached still scores 91 rather than the 55 a
    // passwordsChecked-denominator formula would give. The mandatory advice line (adviceRes ==
    // health_advice_breached whenever breachedCount > 0) is what keeps this from reading as a clean
    // bill of health on its own — the score is never the only thing shown.
    @Test
    fun `the score denominator is scannedCount, not passwordsChecked, when the two differ`() {
        val state = HealthDashboardUiState(
            loading = false,
            scannedCount = 10,
            passwordsChecked = 2,
            breachedCount = 2,
        )

        assertEquals(91, state.score)
        assertEquals(R.plurals.health_advice_breached, state.advicePluralRes)
    }

    // F20: the category drill-down dialog listed every matching item with no way to narrow it.
    // filteredItems is the dialog's exposed list, so it is what has to move when the query does.

    @Test
    fun `category dialog filters items by query`() {
        val matching1 = login(password = "x", title = "GitHub")
        val matching2 = login(password = "x", title = "Gitea")
        val nonMatching = login(password = "x", title = "Amazon")
        val state = HealthDashboardUiState(
            categoryItems = listOf(matching1, matching2, nonMatching),
            categoryQuery = "git",
        )

        assertEquals(listOf(matching1, matching2), state.filteredItems)
    }

    @Test
    fun `category dialog query is case-insensitive`() {
        val item = login(password = "x", title = "GitHub")
        val state = HealthDashboardUiState(categoryItems = listOf(item), categoryQuery = "GITHUB")

        assertEquals(listOf(item), state.filteredItems)
    }

    @Test
    fun `an empty category query shows the full category list`() {
        val items = listOf(
            login(password = "x", title = "GitHub"),
            login(password = "x", title = "Amazon"),
        )
        val state = HealthDashboardUiState(categoryItems = items, categoryQuery = "")

        assertEquals(items, state.filteredItems)
    }

    @Test
    fun `a query matching nothing narrows the dialog to an empty list`() {
        val items = listOf(login(password = "x", title = "GitHub"))
        val state = HealthDashboardUiState(categoryItems = items, categoryQuery = "zzz-no-match")

        assertEquals(emptyList(), state.filteredItems)
    }
}
