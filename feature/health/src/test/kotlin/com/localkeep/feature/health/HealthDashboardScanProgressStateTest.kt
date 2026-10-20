package com.localkeep.feature.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-40: a QA recording showed the scan's progress ring reporting 0% for ~2s, then jumping straight
 * to 50% then 100% - three discrete steps with a long dwell at zero that read as stuck rather than
 * real. The 0% was fabricated: [HealthDashboardViewModel.refresh] used to seed `scanProgress` at
 * `0f` before [SecurityDashboard.analyze] had measured anything at all - including the breach-corpus
 * load that runs ahead of the first score, which is real, unreported time. [startingScan] and
 * [withScanProgress] are the two pure steps of that state machine, pinned here without needing a
 * ViewModel or a vault.
 */
class HealthDashboardScanProgressStateTest {

    @Test
    fun `a scan starts indeterminate, never a fabricated zero`() {
        val started = HealthDashboardUiState().startingScan(firstScan = true)

        assertEquals(true, started.loading)
        assertNull(started.scanProgress, "must be indeterminate, not a claimed 0f, until real progress lands")
    }

    @Test
    fun `a re-scan does not blank the screen and stays indeterminate too`() {
        val restarted = HealthDashboardUiState(loading = false).startingScan(firstScan = false)

        assertEquals(false, restarted.loading)
        assertNull(restarted.scanProgress)
    }

    @Test
    fun `the reported progress sequence is monotonic and reaches exactly 1_0`() {
        var state = HealthDashboardUiState().startingScan(firstScan = true)
        val total = 4

        val fractions = (1..total).map { scanned ->
            state = state.withScanProgress(scanned, total)
            state.scanProgress!!
        }

        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1.0f), fractions)
        assertTrue(fractions.zipWithNext().all { (earlier, later) -> later > earlier }, "not monotonic: $fractions")
        assertEquals(1.0f, fractions.last())
    }

    @Test
    fun `a progress callback is ignored once the scan has already finished`() {
        val finished = HealthDashboardUiState(loading = false, scanProgress = null)

        val afterStaleCallback = finished.withScanProgress(scanned = 1, total = 2)

        assertNull(afterStaleCallback.scanProgress, "a superseded scan's callback must not resurrect the scanning card")
    }
}
