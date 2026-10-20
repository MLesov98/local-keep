package com.localkeep.feature.backup

import java.time.Instant
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test: BackupSettingsScreen's export/snapshot timestamps used a hardcoded
 * `"d MMM yyyy, HH:mm"` pattern, so every locale rendered the same day-then-month, 24-hour string
 * regardless of device settings. [localTimestampFormatter] must build its pattern from the given
 * locale via `getBestDateTimePattern`, matching `HiddenItemsScreen.instantText`.
 *
 * US and UK both spell the month "Aug", so this pair isolates the thing that actually broke —
 * date order and hour-cycle — rather than merely the translated month name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalTimestampFormatterTest {

    @Test
    fun `same instant renders differently for US and UK locales`() {
        val instant = Instant.parse("2026-08-31T18:41:36Z")

        val us = localTimestampFormatter(Locale.US).format(instant)
        val uk = localTimestampFormatter(Locale.UK).format(instant)

        assertNotEquals(us, uk)
    }

    /**
     * Regression for 1C-F3 (release sweep 2026-09-20): the rolling save fires on a change, so
     * several snapshots routinely land inside one minute. Five edits in a minute wrote
     * `vault-20260920-092355 / 092406 / 092416 / 092427 / 092437.enc` and the list showed four
     * consecutive rows all reading `Sep 20, 2026, 12:24 PM` — indistinguishable, in front of an
     * irreversible restore. The two instants below are inside one minute, which is the case the
     * minute-precision label could not tell apart.
     */
    @Test
    fun `two snapshots seconds apart do not share a label`() {
        val first = Instant.parse("2026-09-20T09:23:55Z")
        val second = Instant.parse("2026-09-20T09:23:11Z")
        val formatter = snapshotTimestampFormatter(Locale.US)

        assertNotEquals(formatter.format(first), formatter.format(second))
    }

    /**
     * The control that must stay green, and the reason the seconds live on their own formatter:
     * [localTimestampFormatter] also labels the last export, where a seconds field is noise. It is
     * still minute-precision, which is what makes the test above a statement about the change.
     */
    @Test
    fun `the export timestamp is still minute-precision`() {
        val first = Instant.parse("2026-09-20T09:23:55Z")
        val second = Instant.parse("2026-09-20T09:23:11Z")
        val formatter = localTimestampFormatter(Locale.US)

        assertEquals(formatter.format(first), formatter.format(second))
    }
}
