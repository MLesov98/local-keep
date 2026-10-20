package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.billing.Entitlement
import com.localkeep.core.crypto.CryptoConstants
import com.localkeep.core.crypto.EnvelopeCrypto
import com.localkeep.core.crypto.KdfParams
import com.localkeep.core.crypto.KeystoreManager
import com.localkeep.core.crypto.SecureRandomProvider
import com.localkeep.core.crypto.Zeroizer
import com.localkeep.core.location.CaptureConvergence
import com.localkeep.core.location.CurrentLocationProvider
import com.localkeep.core.location.FixOutcome
import com.localkeep.core.location.FixRequest
import com.localkeep.core.location.LocationFix
import com.localkeep.core.ui.theme.LocalKeepTheme
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The rough-reading caveat, and the fact that it appears only when it is true.
 *
 * This is the second half of the principal's 5.5 (2026-08-20): the offer must wait for a reading
 * worth offering, *"but there's additional info saying it'd not be accurate"*. The waiting half is
 * [readingIsOfferable] and is unit-tested in `CoordinateOfferTest`; this is the half that only
 * exists on screen, and it is here rather than in a device sweep because **the state cannot be
 * reached on an emulator at all**. `adb emu geo fix` feeds the gps provider a synthetic fix whose
 * accuracy is always well inside `FixRequest().settleAccuracyMeters`, so every emulator capture
 * lands in [AccuracyBand.PRECISE] and the caveat is correctly absent. Verified by hand on
 * 2026-08-21, which is what sent this case here.
 *
 * Since 2026-08-21 the caveat is the offer button's own label — "Keep the rough reading" — rather
 * than a caption under it (the principal's call: the words and the action are one thing). The two
 * tests therefore assert on the button's text, and they are still each other's control: a label
 * wired unconditionally to the rough wording would pass the first test on its own; only the second
 * one goes red for it.
 *
 * Entitled Pro, for [EditorHeaderInstrumentedTest]'s reason: `COORDINATE` is a Pro type and a free
 * session raises the upgrade prompt instead of the form. Fixture duplicated rather than shared,
 * which is the precedent the other test classes in this module set.
 */
@RunWith(AndroidJUnit4::class)
class CoordinateOfferCaptionInstrumentedTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    /**
     * A provider that hands the test its own callbacks and then never answers.
     *
     * Never answering is deliberate: the caption only exists *during* a capture, so the test needs
     * the capture still open while it asserts. The deferred is completed by nobody and the coroutine
     * is cancelled with the ViewModel at teardown.
     */
    private class OpenCaptureProvider : CurrentLocationProvider {
        var publishInterim: ((LocationFix) -> Unit)? = null
        var publishConvergence: ((CaptureConvergence) -> Unit)? = null
        private val never = CompletableDeferred<FixOutcome>()

        override suspend fun currentFix(
            request: FixRequest,
            onInterim: (LocationFix) -> Unit,
            onConvergence: (CaptureConvergence) -> Unit,
        ): FixOutcome {
            publishInterim = onInterim
            publishConvergence = onConvergence
            return never.await()
        }
    }

    @BeforeTest
    fun setUp() = runBlocking {
        cleanupFiles()
        createFreshVault()
        lockManager = VaultLockManager(context, KeystoreManager(context))
        lockManager.unlock(password())
        repository = VaultRepository(lockManager)
    }

    @AfterTest
    fun tearDown() {
        if (::lockManager.isInitialized) lockManager.lock()
        cleanupFiles()
    }

    /**
     * The offer button reads the same whatever the band — it names the act, not the quality.
     *
     * This test used to assert the opposite: 42 m relabelled the button to "Keep the rough reading",
     * and that was the design until 2026-09-03. It stopped being defensible the moment the status
     * line above the button began naming the band in plain words, because the same 42 m reading is
     * called "street level" there — leaving one screen calling one reading two contradictory things,
     * the second of which reads as a failure. The quality moved to the status line; the button kept
     * the verb.
     *
     * Both accuracies still go through the same render, because the point is that they now agree:
     * 4 m is `PRECISE` and 42 m is `STREET`, which is the old ROUGH set, so this is exactly the pair
     * that used to diverge.
     */
    @Test
    fun theOfferButtonReadsTheSameWhateverTheBand() {
        val expected = context.getString(R.string.itemedit_coordinate_cancel_capture)

        renderMidCaptureWith(accuracyMeters = 4f)
        compose.onNodeWithTag(OFFER_BUTTON).assertTextContains(expected)
    }

    @Test
    fun aNonPreciseReadingDoesNotGetItsOwnOfferLabel() {
        // Comfortably outside FixRequest().settleAccuracyMeters, so the band is not PRECISE — 42 m
        // is AccuracyBand.STREET, which is the set the retired two-band ROUGH covered.
        renderMidCaptureWith(accuracyMeters = 42f)

        compose.onNodeWithTag(OFFER_BUTTON)
            .assertTextContains(context.getString(R.string.itemedit_coordinate_cancel_capture))
    }

    /**
     * Opens a capture, pushes one interim fix of the given accuracy, and renders the editor with
     * that capture still running.
     *
     * `SETTLED` rather than waiting out the tracking floor: [readingIsOfferable] takes any of its
     * three routes, and this is the one that does not cost the test `FixRequest().minTrackingMillis`
     * of real time. Which route opened the offer is not what is under test here — whether the
     * caption follows the *band* is.
     */
    private fun renderMidCaptureWith(accuracyMeters: Float) {
        val provider = OpenCaptureProvider()
        val model = ItemEditViewModel(
            repository,
            lockManager,
            ProEntitlement(),
            SavedStateHandle(),
            provider,
        )

        runBlocking {
            model.presetType(ItemEditUiState.ItemType.COORDINATE)
            model.captureCurrentLocation()
            awaitUntil { model.state.value.coordinateCapturing }
            // The callbacks are captured inside the provider call, which is entered after
            // `coordinateCapturing` flips — awaiting the flag alone races, as
            // CoordinateCaptureInstrumentedTest found.
            awaitUntil { provider.publishInterim != null && provider.publishConvergence != null }

            provider.publishInterim!!(
                LocationFix(
                    latitude = 42.0,
                    longitude = 24.0,
                    capturedAt = Instant.parse("2026-08-21T01:47:00Z"),
                    accuracyMeters = accuracyMeters,
                    elapsedRealtimeNanos = 1_000L,
                ),
            )
            provider.publishConvergence!!(CaptureConvergence.SETTLED)

            awaitUntil {
                model.state.value.coordinateFix?.accuracyMeters == accuracyMeters &&
                    model.state.value.coordinateConvergence == CaptureConvergence.SETTLED
            }
        }

        // The ViewModel refreshes TOTP codes on a `while (isActive)` loop, so Compose never reports
        // idle and every assertion would time out. Driving the clock by hand is the supported
        // answer; see EditorHeaderInstrumentedTest, which hit this first.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LocalKeepTheme {
                ItemEditScreen(
                    viewModel = model,
                    itemId = null,
                    onSaved = {},
                    onBack = {},
                    presetType = ItemEditUiState.ItemType.COORDINATE,
                )
            }
        }
        // Enough frames for the colour crossfades and the caption's one-shot `lkRise` to finish.
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun awaitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition never held within ${timeoutMillis}ms")
    }

    private fun password() = "correct horse battery staple".toCharArray()

    private fun createFreshVault() {
        val password = password()
        val dek = EnvelopeCrypto.generateDek()
        val salt = SecureRandomProvider.nextBytes(CryptoConstants.SALT_LEN)
        val kek = EnvelopeCrypto.deriveKek(password, salt, KdfParams.FLOOR)
        try {
            AtomicVaultWriter.write(
                context,
                VaultFileCodec.create(
                    dek = dek,
                    salt = salt,
                    kdfParams = KdfParams.FLOOR,
                    wrappedDek = EnvelopeCrypto.wrapDek(kek, dek),
                    commitment = EnvelopeCrypto.slotCommitment(dek),
                ),
            )
        } finally {
            Zeroizer.wipe(password)
            Zeroizer.wipe(dek)
            Zeroizer.wipe(kek)
        }
    }

    private fun cleanupFiles() {
        context.filesDir.listFiles()?.forEach(File::deleteRecursively)
    }

    private class ProEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(true)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }

    private companion object {
        /** The tag the offer/stop button carries in ItemEditScreen.kt, spelled once. */
        const val OFFER_BUTTON = "coordinate-stop"
    }
}
