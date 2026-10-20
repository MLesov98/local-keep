package com.localkeep.feature.backup

import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KdfRecalibration
import com.localkeep.core.vault.RewrapOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the Encryption section is left saying after a check and after an update.
 *
 * Two stale-claim defects, both from `kdfVerdict` being forced to KEEP after an update while
 * `kdfMeasuredMillis` kept the reading taken *before* it:
 *
 *  - After an OFFER_FASTER update the section could say "slow here, already at the lowest setting"
 *    about parameters that had just been replaced, because the old 2830 ms was still beside KEEP.
 *  - A blocked update (a vault whose setup does not allow the change) put "suits this device"
 *    directly under the error saying the change was refused.
 *
 * Null is the true statement in both: not checked. It also brings the check button back, which a
 * verdict of any kind hides.
 */
class KdfCheckResultStateTest {

    private val suggestion = KdfParams.DEFAULT
    private val slow = KdfRecalibration.UNUSABLY_SLOW_MS + 1_000L

    /** An update screen as it stands after an OFFER_FASTER reading: verdict, suggestion, 2.5 s. */
    private fun afterOfferFaster() = BackupSettingsUiState(isWorking = true).withKdfReading(
        verdict = KdfRecalibration.Verdict.OFFER_FASTER,
        suggestion = suggestion,
        measuredMillis = slow,
    )

    @Test
    fun `a successful update leaves the section unchecked rather than carrying the old reading`() {
        val after = afterOfferFaster().afterRecalibration(RewrapOutcome.OK)

        assertNull(after.kdfVerdict, "the parameters changed, so the old reading no longer describes them")
        assertNull(after.kdfSuggestion)
        assertEquals(0L, after.kdfMeasuredMillis, "a slow reading must not outlive the parameters it measured")
        assertEquals(BackupMessage.Res(R.string.backup_status_kdf_recalibrated), after.status)
    }

    @Test
    fun `a refused update does not claim the settings suit this device`() {
        val after = afterOfferFaster().afterRecalibration(RewrapOutcome.DURESS_CONFIGURED)

        assertNull(after.kdfVerdict, "KEEP renders 'suits this device' under the refusal")
        assertEquals(BackupMessage.Res(R.string.backup_error_kdf_recalibrate_blocked), after.error)
    }

    @Test
    fun `a wrong password leaves the offer in place so it can be retried`() {
        // The control: the two changes above must not reach the path where nothing was written.
        val before = afterOfferFaster()
        val after = before.afterRecalibration(RewrapOutcome.WRONG_PASSWORD)

        assertEquals(KdfRecalibration.Verdict.OFFER_FASTER, after.kdfVerdict)
        assertEquals(suggestion, after.kdfSuggestion)
        assertEquals(before.kdfMeasuredMillis, after.kdfMeasuredMillis)
        assertEquals(BackupMessage.Res(R.string.backup_error_kdf_wrong_password), after.error)
    }

    @Test
    fun `a good check answers in the section itself and not with a banner at the top of the screen`() {
        val after = BackupSettingsUiState(isWorking = true).withKdfReading(
            verdict = KdfRecalibration.Verdict.KEEP,
            suggestion = suggestion,
            measuredMillis = 400L,
        )

        assertEquals(KdfRecalibration.Verdict.KEEP, after.kdfVerdict)
        assertNull(
            after.status,
            "a status line scrolls the screen to the top, away from the result it is reporting",
        )
    }

    @Test
    fun `a check that finds the vault stuck at the floor still says so up top`() {
        // The control: only the good result moved. The slow one is not good news and keeps its line.
        val after = BackupSettingsUiState(isWorking = true).withKdfReading(
            verdict = KdfRecalibration.Verdict.KEEP,
            suggestion = suggestion,
            measuredMillis = slow,
        )

        assertEquals(BackupMessage.Res(R.string.backup_status_kdf_check_slow, listOf(slow)), after.status)
        assertNotNull(after.kdfVerdict)
    }
}
