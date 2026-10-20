package com.localkeep.feature.backup

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleExportReminderTest {
    private val now = 1_800_000_000_000L

    /**
     * A vault created moments ago is NOT overdue. Driving onboarding on a device caught the banner
     * appearing over step 3, telling someone who had not finished setting up that it had been a
     * while since they last exported — measured from the epoch, because "never exported" had no
     * anchor.
     */
    @Test
    fun `a vault that has just been created is not yet stale`() {
        assertFalse(StaleExportReminder.isStale(lastExportMs = null, nowMs = now, since = now))
    }

    @Test
    fun `a vault that has never been exported goes stale once the threshold passes`() {
        val created = now - TimeUnit.DAYS.toMillis(StaleExportReminder.STALE_THRESHOLD_DAYS) - 1
        assertTrue(StaleExportReminder.isStale(lastExportMs = null, nowMs = now, since = created))
    }

    @Test
    fun `an export inside the threshold is not stale`() {
        val yesterday = now - TimeUnit.DAYS.toMillis(1)
        assertFalse(StaleExportReminder.isStale(lastExportMs = yesterday, nowMs = now))
    }

    /** Exactly at the threshold is still fresh — the reminder fires *past* it, not on it. */
    @Test
    fun `an export exactly at the threshold is not yet stale`() {
        val exactly = now - TimeUnit.DAYS.toMillis(StaleExportReminder.STALE_THRESHOLD_DAYS)
        assertFalse(StaleExportReminder.isStale(lastExportMs = exactly, nowMs = now))
    }

    @Test
    fun `an export past the threshold is stale`() {
        val old = now - TimeUnit.DAYS.toMillis(StaleExportReminder.STALE_THRESHOLD_DAYS) - 1
        assertTrue(StaleExportReminder.isStale(lastExportMs = old, nowMs = now))
    }

    /**
     * A clock that has moved backwards — a manual time change, or a restored backup carrying a
     * future timestamp — must not read as "30 days overdue" and nag on every launch.
     */
    @Test
    fun `an export timestamped in the future is not stale`() {
        val future = now + TimeUnit.DAYS.toMillis(365)
        assertFalse(StaleExportReminder.isStale(lastExportMs = future, nowMs = now))
    }
}
