package com.localkeep.feature.health

import com.localkeep.core.vault.VaultPresentationState
import com.localkeep.core.vault.VaultVisibilitySnapshot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * The Health scan's strength cache must not outlive the unlocked session.
 *
 * `SecurityDashboard` now remembers a verdict per distinct password so a re-scan only pays for what
 * changed (1B-F6) — the reason it did not before was lifetime, not caching: nothing derived from
 * the passwords may survive the one state in which nothing password-derived is supposed to exist.
 * The tags are keyed under an in-memory key and `forgetStrengthScores` wipes it, so what is pinned
 * here is that something actually calls it on a lock, and that it is not the ViewModel — which is
 * destroyed when the screen leaves the back stack while the dashboard singleton is not.
 */
class StrengthCacheLifetimeTest {

    private fun unlocked() = VaultPresentationState.Unlocked(
        VaultVisibilitySnapshot(
            liveItems = emptyList(),
            trashItems = emptyList(),
            liveFolders = emptyList(),
            trashedFolderRows = emptyList(),
            concealedItemIds = emptySet(),
            concealedFolderIds = emptySet(),
            directlyHiddenItemIds = emptySet(),
            directlyHiddenFolderIds = emptySet(),
            nextExpiry = null,
            purgeableTrashItemCount = 0,
        ),
    )

    @Test
    fun `a lock wipes the remembered verdicts`() = runBlocking {
        var forgotten = 0
        val state = MutableStateFlow<VaultPresentationState>(unlocked())
        val job = forgetStrengthScoresOnLock(state) { forgotten++ }
        yield()
        assertEquals(0, forgotten, "an unlocked vault must keep its cache — that is the whole saving")

        state.value = VaultPresentationState.Locked
        yield()
        assertEquals(1, forgotten, "locking did not wipe the strength cache")

        job.cancel()
    }

    @Test
    fun `unlocking again does not wipe it`() = runBlocking {
        var forgotten = 0
        val state = MutableStateFlow<VaultPresentationState>(VaultPresentationState.Locked)
        val job = forgetStrengthScoresOnLock(state) { forgotten++ }
        yield()
        state.value = unlocked()
        yield()
        assertEquals(1, forgotten, "only the lock should wipe; unlocking wiped again")
        job.cancel()
    }

    /**
     * The wiring, asserted on the source because a Hilt `@Provides` cannot be driven here: the
     * subscription has to be created beside the singleton it guards. Put in the ViewModel it would
     * die with the screen and the cache would survive the lock — which is the defect, not the fix.
     */
    @Test
    fun `the eraser is created with the dashboard, not with the screen`() {
        val module = File("src/main/kotlin/com/localkeep/feature/health/di/HealthModule.kt").readText()
        assertTrue(
            "forgetStrengthScoresOnLock(" in module && "dashboard::forgetStrengthScores" in module,
            "HealthModule must subscribe the dashboard's own eraser to the lock. Found: $module",
        )
        val viewModel = File(
            "src/main/kotlin/com/localkeep/feature/health/HealthDashboardViewModel.kt",
        ).readText()
        assertTrue(
            "forgetStrengthScores" !in viewModel,
            "the cache's lifetime must not depend on this ViewModel — it is destroyed when the " +
                "screen leaves the back stack, and the dashboard singleton is not.",
        )
    }
}
