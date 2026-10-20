package com.localkeep.core.health

import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReuseOldDetectorTest {
    private val now: Instant = Instant.parse("2026-07-24T00:00:00Z")

    private fun loginItem(pw: String, updatedAt: Instant, passwordChangedAt: Instant?): VaultItem =
        VaultItem(
            id = UUID.randomUUID(),
            createdAt = updatedAt.minus(400, ChronoUnit.DAYS),
            updatedAt = updatedAt,
            content = Login(username = "u", password = pw, passwordChangedAt = passwordChangedAt),
        )

    @Test
    fun `stale password is flagged even when metadata was edited recently`() {
        // updatedAt is now (e.g. a rename), but the password itself is 200 days old.
        val item = loginItem("p", updatedAt = now, passwordChangedAt = now.minus(200, ChronoUnit.DAYS))

        assertTrue(ReuseOldDetector.findOld(listOf(item), thresholdDays = 90, now = now).contains(item))
    }

    @Test
    fun `recently changed password is not flagged even when updatedAt is old`() {
        val item = loginItem(
            "p",
            updatedAt = now.minus(200, ChronoUnit.DAYS),
            passwordChangedAt = now.minus(5, ChronoUnit.DAYS),
        )

        assertFalse(ReuseOldDetector.findOld(listOf(item), thresholdDays = 90, now = now).contains(item))
    }

    @Test
    fun `falls back to updatedAt when passwordChangedAt is null`() {
        val old = loginItem("p", updatedAt = now.minus(200, ChronoUnit.DAYS), passwordChangedAt = null)
        val fresh = loginItem("q", updatedAt = now.minus(5, ChronoUnit.DAYS), passwordChangedAt = null)

        val result = ReuseOldDetector.findOld(listOf(old, fresh), thresholdDays = 90, now = now)

        assertTrue(result.contains(old))
        assertFalse(result.contains(fresh))
    }
}
