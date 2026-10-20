package com.localkeep.feature.itemedit

import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reloading a login whose second factor was removed must remove it from the screen too.
 *
 * Device pass 2026-10-04 (A3, LOG-07), release on API 35: Edit › Two-factor › Remove two-factor ›
 * Save. The removal persisted — the list row lost its 2FA pill and a reopened editor showed the
 * chooser — but the detail screen it returned to kept the live code card and the "2FA secret" row,
 * ticking, until the user left it.
 *
 * The detail screen follows a save made in the editor by calling `loadItem`, and `loadItem` builds
 * the reloaded state on top of the *current* one. A login with no factor reached
 * [withLoginTotp] with `null`, which returned the state unchanged — the old secret included — so
 * [carriesTotpSecret] stayed true and the ticker kept computing codes for a factor that no longer
 * existed. A fresh screen never showed it, because a fresh state has no secret to keep.
 *
 * The ViewModel-level reproduction is `DetailDropsARemovedTwoFactorInstrumentedTest`, which needs
 * AndroidKeyStore; this pins the state transition the bug lived in, on the JVM.
 */
class LoginReloadDropsARemovedTwoFactorTest {

    /** What the detail screen held before the save: a login showing its factor and a live code. */
    private val showingAFactor = ItemEditUiState(
        type = ItemEditUiState.ItemType.LOGIN,
        totpIssuer = "Acme",
        totpAccount = "alice@example.com",
        totpSecret = "JBSWY3DPEHPK3PXP",
        totpAlgorithm = TotpAlgorithm.SHA256,
        totpDigits = "8",
        totpPeriodSec = "60",
        totpSteam = false,
        totpCurrentCode = "51750007",
        totpRemainingSeconds = 17,
    )

    @Test
    fun `a login reloaded without a factor no longer carries the old one`() {
        val reloaded = showingAFactor.withLoginTotp(null)

        // FAILS WITHOUT THE FIX: the null branch returned the state as it was.
        assertFalse(reloaded.carriesTotpSecret(), "the ticker still has a secret to compute codes from")
        assertEquals("", reloaded.totpSecret)
        assertEquals("", reloaded.totpIssuer)
        assertEquals("", reloaded.totpAccount)
    }

    /**
     * The settings go back to what a login loaded fresh would hold, not merely the secret: a factor
     * added later on the same screen must not inherit the removed one's SHA256/8/60.
     */
    @Test
    fun `a login reloaded without a factor reads exactly like one opened fresh`() {
        val fresh = ItemEditUiState(type = ItemEditUiState.ItemType.LOGIN)
        val reloaded = showingAFactor.withLoginTotp(null)

        assertEquals(fresh.totpAlgorithm, reloaded.totpAlgorithm)
        assertEquals(fresh.totpDigits, reloaded.totpDigits)
        assertEquals(fresh.totpPeriodSec, reloaded.totpPeriodSec)
        assertEquals(fresh.totpSteam, reloaded.totpSteam)
        assertEquals("", reloaded.totpCurrentCode, "the stale code would show until the next tick")
        assertEquals(0, reloaded.totpRemainingSeconds)
    }

    /** The control: a login that still has a factor still shows it, with its own settings. */
    @Test
    fun `a login reloaded with a factor shows that factor`() {
        val replacement = Totp(
            issuer = "Other",
            account = "bob",
            secretBase32 = "GEZDGNBVGY3TQOJQ",
            algorithm = TotpAlgorithm.SHA1,
            digits = 6,
            periodSec = 30,
        )

        val reloaded = showingAFactor.withLoginTotp(replacement)

        assertTrue(reloaded.carriesTotpSecret())
        assertEquals("GEZDGNBVGY3TQOJQ", reloaded.totpSecret)
        assertEquals("Other", reloaded.totpIssuer)
        assertEquals("bob", reloaded.totpAccount)
        assertEquals(TotpAlgorithm.SHA1, reloaded.totpAlgorithm)
        assertEquals("6", reloaded.totpDigits)
        assertEquals("30", reloaded.totpPeriodSec)
    }
}
