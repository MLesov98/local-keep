package com.localkeep.feature.itemedit

import android.app.Activity
import android.content.Context
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
import com.localkeep.core.location.LocationFailure
import com.localkeep.core.location.LocationFix
import com.localkeep.core.model.Coordinate
import com.localkeep.core.vault.AtomicVaultWriter
import com.localkeep.core.vault.VaultFileCodec
import com.localkeep.core.vault.VaultLockManager
import com.localkeep.core.vault.VaultRepository
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * The capture half of the coordinate item: what the editor does with an answer that arrives late,
 * for the wrong item, or after the vault has locked.
 *
 * Instrumented for the same reason [ItemEditViewModelTest] is — `ItemEditViewModel` needs a real
 * `VaultRepository`, which needs a `KeystoreManager`, which opens AndroidKeyStore in its
 * constructor. The location provider itself is a fake here, deliberately: every case below is a
 * *timing* relationship between a tap, an answer and something that happened in between, and none
 * of them is reachable by driving a real GNSS receiver on a desk.
 */
@RunWith(AndroidJUnit4::class)
class CoordinateCaptureInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var lockManager: VaultLockManager
    private lateinit var repository: VaultRepository

    /** A provider whose answer is handed over by the test, whenever the test chooses. */
    private class GatedProvider : CurrentLocationProvider {
        val calls = mutableListOf<FixRequest>()
        var answer = CompletableDeferred<FixOutcome>()

        /** Captured so a test can push a convergence change the way a real receiver would. */
        var publishConvergence: ((CaptureConvergence) -> Unit)? = null

        /** Captured so a test can land an interim reading in the form mid-capture. */
        var publishInterim: ((LocationFix) -> Unit)? = null

        /**
         * How many captures have installed their callbacks. Waiting on `publishInterim != null` is
         * not enough: a cancelled capture's callbacks survive until its `finally` unwinds, so a
         * test starting a replacement can see the dead closure of the one it just abandoned, publish
         * into it, and then wait five seconds for a state change that cannot come. Waiting for this
         * count to advance is the only signal that means "the capture I just started is listening".
         * `abandoningPutsBackWhatTheFormHeldBeforeTheTap` failed on exactly that, and only under
         * full-suite load — in isolation the unwind won the race and the test passed.
         */
        var installs = 0

        override suspend fun currentFix(
            request: FixRequest,
            onInterim: (LocationFix) -> Unit,
            onConvergence: (CaptureConvergence) -> Unit,
        ): FixOutcome {
            calls += request
            publishConvergence = onConvergence
            publishInterim = onInterim
            installs += 1
            // Cleared when the capture ends, and this is load-bearing rather than tidiness. These
            // used to hold the FIRST capture's callbacks for the life of the provider, so a test
            // waiting on `publishInterim != null` before a SECOND capture was satisfied instantly
            // by the dead closure of the first — then published an interim into a capture that had
            // already finished and waited five seconds for a state change that could never come.
            //
            // In a `finally`, because `abandonLocationCapture` cancels this coroutine and a clear
            // placed after the await would never run on the one path the test exercises most.
            // Guarded by identity, because the replacing capture installs its own callbacks before
            // a cancelled one finishes unwinding — an unguarded clear would null out the live
            // capture's callbacks and reintroduce the same hang from the other side.
            try {
                val outcome = answer.await()
                answer = CompletableDeferred()
                return outcome
            } finally {
                if (publishInterim === onInterim) {
                    publishConvergence = null
                    publishInterim = null
                }
            }
        }
    }

    private fun fix(latitude: Double = 42.0, longitude: Double = 24.0) = FixOutcome.Success(
        LocationFix(
            latitude = latitude,
            longitude = longitude,
            capturedAt = Instant.parse("2026-08-19T09:14:58Z"),
            accuracyMeters = 8.5f,
            elapsedRealtimeNanos = 1_000L,
        ),
    )

    @BeforeTest
    fun setUp() = runBlocking {
        // Same fixture as ItemEditViewModelTest, for the same reason: a real vault file is the only
        // thing VaultLockManager will open, and the capture path refuses outright while sealed.
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

    private fun viewModel(provider: CurrentLocationProvider) = ItemEditViewModel(
        repository,
        lockManager,
        FreeEntitlement(),
        SavedStateHandle(),
        provider,
    )

    @Test
    fun aFixLandsInTheFormAndIsMarkedAsTakenHere() = runBlocking {
        val provider = GatedProvider()
        val model = viewModel(provider)
        model.presetType(ItemEditUiState.ItemType.COORDINATE)

        model.captureCurrentLocation()
        awaitUntil { model.state.value.coordinateCapturing }
        provider.answer.complete(fix())
        awaitUntil { !model.state.value.coordinateCapturing }

        val state = model.state.value
        assertEquals(Coordinate(42.0, 24.0, Instant.parse("2026-08-19T09:14:58Z"), 8.5f), state.coordinateFix)
        assertNull(state.coordinateFailure)
        // The flag that decides whether the editor shows the value or masks it. A fix this session
        // took is shown; one loaded from the vault is not.
        assertTrue(state.coordinateJustCaptured)
    }

    @Test
    fun aSupersededCaptureCannotOverwriteTheOneThatReplacedIt() = runBlocking {
        // The sequence that motivates the generation counter, and it is ordinary: capture indoors,
        // wait, walk to the window, capture again — and the first request resolves *after* the
        // second, with a position from the first spot.
        val provider = GatedProvider()
        val model = viewModel(provider)
        model.presetType(ItemEditUiState.ItemType.COORDINATE)

        model.captureCurrentLocation()
        // The provider's *call*, not the `coordinateCapturing` flag: the flag is set before the
        // coroutine that calls the provider is dispatched, so waiting on it let the second capture
        // cancel the first before it had ever asked — and `calls` then stuck at one forever.
        awaitUntil { provider.calls.size == 1 }
        val stale = provider.answer

        model.captureCurrentLocation() // supersedes the first
        awaitUntil { provider.calls.size == 2 }
        provider.answer.complete(fix(latitude = 1.0, longitude = 2.0))
        awaitUntil { model.state.value.coordinateFix != null }

        stale.complete(fix(latitude = 99.0, longitude = 99.0))
        // Give the stale coroutine every chance to write, then prove it did not.
        repeat(20) { Thread.sleep(10) }

        assertEquals(1.0, model.state.value.coordinateFix?.latitude)
    }

    @Test
    fun cancellingStopsTheSpinnerAndKeepsWhateverWasAlreadySaved() = runBlocking {
        val provider = GatedProvider()
        val model = viewModel(provider)
        model.presetType(ItemEditUiState.ItemType.COORDINATE)

        model.captureCurrentLocation()
        awaitUntil { model.state.value.coordinateCapturing }
        provider.answer.complete(fix())
        awaitUntil { model.state.value.coordinateFix != null }

        model.captureCurrentLocation()
        awaitUntil { model.state.value.coordinateCapturing }
        model.cancelLocationCapture()

        assertFalse(model.state.value.coordinateCapturing)
        // Cancelling a *replacement* must not throw away what the item already had. The user asked
        // to stop looking, not to forget where they were.
        assertNotNull(model.state.value.coordinateFix)
        // `assertNotNull` returns its argument, so without this the expression-bodied `runBlocking`
        // makes the method return a Coordinate — and JUnit rejects the whole *class* at load time
        // with "should be void", which surfaces as an initializationError naming no test in
        // particular. Every case in this file is unrunnable until it is Unit.
        Unit
    }

    @Test
    fun abandoningPutsBackWhatTheFormHeldBeforeTheTap() = runBlocking {
        // The "Cancel" label's half of the contract. Interim readings overwrite `coordinateFix` as
        // they arrive, so until abandonLocationCapture() existed a Cancel pressed over a rough
        // first epoch *kept* that epoch — the label said discard and the button did the opposite.
        val provider = GatedProvider()
        val model = viewModel(provider)
        model.presetType(ItemEditUiState.ItemType.COORDINATE)

        // A first capture, completed, so the form holds a saved reading to fall back to.
        model.captureCurrentLocation()
        awaitUntil { model.state.value.coordinateCapturing }
        provider.answer.complete(fix(latitude = 42.0))
        awaitUntil { !model.state.value.coordinateCapturing }
        val before = assertNotNull(model.state.value.coordinateFix)

        // A replacement in flight, with a rough interim already in the form.
        val installed1 = provider.installs
        model.captureCurrentLocation()
        awaitUntil { provider.installs > installed1 && model.state.value.coordinateCapturing }
        provider.publishInterim!!(
            LocationFix(
                latitude = 43.0,
                longitude = 25.0,
                capturedAt = Instant.parse("2026-09-02T09:14:00Z"),
                accuracyMeters = 42f,
                elapsedRealtimeNanos = 2_000L,
            ),
        )
        awaitUntil { model.state.value.coordinateFix?.latitude == 43.0 }

        model.abandonLocationCapture()

        assertFalse(model.state.value.coordinateCapturing)
        assertEquals(before, model.state.value.coordinateFix)
        assertEquals(CaptureConvergence.SEARCHING, model.state.value.coordinateConvergence)
        // The control: the keeping function must still keep, or the two are not different.
        val installed2 = provider.installs
        model.captureCurrentLocation()
        awaitUntil { provider.installs > installed2 && model.state.value.coordinateCapturing }
        provider.publishInterim!!(
            LocationFix(
                latitude = 43.0,
                longitude = 25.0,
                capturedAt = Instant.parse("2026-09-02T09:15:00Z"),
                accuracyMeters = 42f,
                elapsedRealtimeNanos = 3_000L,
            ),
        )
        awaitUntil { model.state.value.coordinateFix?.latitude == 43.0 }
        model.cancelLocationCapture()
        assertEquals(43.0, model.state.value.coordinateFix?.latitude)
    }

    @Test
    fun aSettleReachesTheFormAndIsClearedWhenTheCaptureEnds() = runBlocking {
        val provider = GatedProvider()
        val model = viewModel(provider)
        model.presetType(ItemEditUiState.ItemType.COORDINATE)

        model.captureCurrentLocation()
        awaitUntil { model.state.value.coordinateCapturing }
        assertEquals(CaptureConvergence.SEARCHING, model.state.value.coordinateConvergence)

        // `coordinateCapturing` flips before the provider call is entered, so the callback is not
        // captured yet at that point — awaiting the flag alone raced and threw.
        awaitUntil { provider.publishConvergence != null }
        // What a real receiver does when the series flattens: no better fix arrives, so this is the
        // only signal the screen would ever get. Before this existed the settle was computed to
        // stop the capture and then discarded, and the UI had no way to say "waiting is over".
        provider.publishConvergence!!(CaptureConvergence.SETTLED)
        awaitUntil { model.state.value.coordinateConvergence == CaptureConvergence.SETTLED }

        provider.answer.complete(fix())
        awaitUntil { !model.state.value.coordinateCapturing }
        // Cleared on the way out. Left at SETTLED it would be the next capture's opening answer
        // about a series that has not produced a single reading.
        assertEquals(CaptureConvergence.SEARCHING, model.state.value.coordinateConvergence)
    }

    @Test
    fun aCancellationIsNeverShownToTheUserAsAnError() = runBlocking {
        val provider = GatedProvider()
        val model = viewModel(provider)
        model.presetType(ItemEditUiState.ItemType.COORDINATE)

        model.captureCurrentLocation()
        awaitUntil { model.state.value.coordinateCapturing }
        provider.answer.complete(FixOutcome.Failure(LocationFailure.CANCELLED))
        awaitUntil { !model.state.value.coordinateCapturing }

        assertNull(
            model.state.value.coordinateFailure,
            "the user pressing Back is not a failure to report back to them",
        )
    }

    @Test
    fun everyOtherFailureIsShown() = runBlocking {
        listOf(
            LocationFailure.PERMISSION_DENIED,
            LocationFailure.APPROXIMATE_ONLY,
            LocationFailure.PROVIDER_DISABLED,
            LocationFailure.UNAVAILABLE,
            LocationFailure.TIMED_OUT,
        ).forEach { reason ->
            val provider = GatedProvider()
            val model = viewModel(provider)
            model.presetType(ItemEditUiState.ItemType.COORDINATE)

            model.captureCurrentLocation()
            awaitUntil { model.state.value.coordinateCapturing }
            provider.answer.complete(FixOutcome.Failure(reason))
            awaitUntil { !model.state.value.coordinateCapturing }

            assertEquals(reason, model.state.value.coordinateFailure)
            assertNull(model.state.value.coordinateFix, "a failed capture must create no value")
        }
    }

    @Test
    fun aLockedVaultRefusesToStartACaptureAtAll() = runBlocking {
        val provider = GatedProvider()
        val model = viewModel(provider)
        model.presetType(ItemEditUiState.ItemType.COORDINATE)

        lockManager.lock()
        model.captureCurrentLocation()
        repeat(20) { Thread.sleep(10) }

        assertTrue(provider.calls.isEmpty(), "a sealed vault must not power the receiver")
        assertFalse(model.state.value.coordinateCapturing)
    }

    @Test
    fun savingRefusesAnItemWithANameAndNoPosition() = runBlocking {
        val model = viewModel(GatedProvider())
        model.presetType(ItemEditUiState.ItemType.COORDINATE)
        model.onTitleChanged("Where I parked")

        model.save()
        repeat(20) { Thread.sleep(10) }

        assertFalse(model.state.value.saved)
        assertEquals(R.string.itemedit_error_coordinate_required, model.state.value.error)
        // The guard has to be the reason, not a coincidence: nothing reached the vault.
        assertTrue(repository.visibilitySnapshot().liveItems.none { it.title == "Where I parked" })
    }

    @Test
    fun aCapturedFixSurvivesTheSaveAndComesBackOffDiskMasked() = runBlocking {
        val provider = GatedProvider()
        val model = viewModel(provider)
        model.presetType(ItemEditUiState.ItemType.COORDINATE)
        model.onTitleChanged("Trailhead")
        model.captureCurrentLocation()
        awaitUntil { model.state.value.coordinateCapturing }
        provider.answer.complete(fix(latitude = 42.135_098, longitude = 24.745_129))
        awaitUntil { model.state.value.coordinateFix != null }

        model.save()
        awaitUntil { model.state.value.saved }

        val saved = repository.visibilitySnapshot().liveItems.single { it.title == "Trailhead" }
        val stored = saved.content as Coordinate
        assertEquals(42.135_098, stored.latitude)
        assertEquals(24.745_129, stored.longitude)

        val reopened = viewModel(GatedProvider())
        reopened.loadItem(saved.id.toString())
        awaitUntil { reopened.state.value.coordinateFix != null }

        assertEquals(stored, reopened.state.value.coordinateFix)
        // The value came off disk, so the editor masks it. Showing a stored position without the
        // prompt would make the detail screen's gate decorative — one tap on Edit and around it.
        assertFalse(reopened.state.value.coordinateJustCaptured)
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

    /** The `foss` posture: never Pro, nothing to buy. */
    private class FreeEntitlement : Entitlement {
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false)
        override val canPurchase: Boolean = false
        override val displayPrice: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun purchase(activity: Activity): com.localkeep.core.billing.PurchaseOutcome =
            com.localkeep.core.billing.PurchaseOutcome.Failed
        override suspend fun restore(): Boolean = false
    }

    /** Polls rather than sleeping a fixed amount: a fixed sleep is a flake with a timer on it. */
    private fun awaitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition never held within ${timeoutMillis}ms")
    }
}
