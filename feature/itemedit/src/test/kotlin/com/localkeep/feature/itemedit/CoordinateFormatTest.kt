package com.localkeep.feature.itemedit

import com.localkeep.core.location.CaptureConvergence
import com.localkeep.core.location.LocationFailure
import com.localkeep.core.model.Coordinate
import com.localkeep.core.model.ALWAYS_REAUTH_FIELDS
import com.localkeep.core.model.COORDINATE_FIELD
import java.time.Instant
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a position is written down, and what the editor is allowed to do with it.
 *
 * Plain JVM tests: this is the part of the coordinate feature that is a pure function of its input,
 * and `:feature:itemedit`'s Compose test harness does not reach idle (an open issue), so
 * everything that can be pulled out of a composable and checked here is worth pulling out.
 */
class CoordinateFormatTest {

    private val default = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(default)

    private val fix = Coordinate(
        latitude = 42.135_098,
        longitude = 24.745_129,
        capturedAt = Instant.parse("2026-08-19T09:14:58Z"),
        accuracyMeters = 8.49f,
    )

    @Test
    fun `the decimals shown are the ones the accuracy has earned`() {
        // Each place is worth about a tenth of the one before it: 3 dp is ~111 m, 4 dp ~11 m,
        // 5 dp ~1.1 m, 6 dp ~0.11 m. A screen printing six decimals beside a caption admitting to
        // metres contradicts itself, and the digits are the half people believe.
        assertEquals("42.13510, 24.74513", fix.displayValue())
        assertEquals("42.1351, 24.7451", fix.copy(accuracyMeters = 10f).displayValue())
        assertEquals("42.135, 24.745", fix.copy(accuracyMeters = 100f).displayValue())
        // Keyed off this record's own accuracy rather than a constant, so a rough old reading and a
        // good new one render differently — which is the whole point of deriving it.
        assertEquals("42.13510, 24.74513", fix.copy(accuracyMeters = 2f).displayValue())
        // An unknown error is not a small one.
        assertEquals("42.1351, 24.7451", fix.copy(accuracyMeters = null).displayValue())
        assertEquals("42.1351, 24.7451", fix.copy(accuracyMeters = Float.NaN).displayValue())
        // Trailing zeros are kept rather than trimmed: a fixed width is what makes two saved
        // locations comparable at a glance, and "42.1, 24.7" reads as a coarser measurement than
        // the one that was actually taken.
        assertEquals("0.00000, 0.00000", fix.copy(latitude = 0.0, longitude = 0.0).displayValue())
        assertEquals("-90.00000, 180.00000", fix.copy(latitude = -90.0, longitude = 180.0).displayValue())
    }

    @Test
    fun `no accuracy this app can produce earns a sixth decimal`() {
        // Six decimals is 11 cm, which would be honest only for a sub-metre fix. No consumer
        // receiver this app runs on delivers one, and the capture refuses anything past 100 m, so
        // the whole reachable range renders at five places or fewer.
        listOf(0f, 0.5f, 2f, 9.99f, 10f, 50f, 99.9f, 100f).forEach { accuracy ->
            val places = fix.copy(accuracyMeters = accuracy).displayValue()
                .substringBefore(',').substringAfter('.').length
            assertTrue(places <= 5, "\u0024accuracy m rendered at \u0024places decimals")
        }
    }

    @Test
    fun `the decimal separator is a point in every locale`() {
        // The one place in this app a number is deliberately not localized. A decimal comma is
        // correct French and is also the separator between the two halves of a coordinate pair
        // everywhere: "42,135098, 24,745129" is four numbers to a reader and to a paste target
        // alike. The value is copied to the clipboard exactly as rendered, so what is shown has to
        // be what another tool will accept.
        listOf(Locale.FRANCE, Locale.GERMANY, Locale("pt", "BR"), Locale.US).forEach { locale ->
            Locale.setDefault(locale)
            assertEquals(
                "42.13510, 24.74513",
                fix.displayValue(),
                "decimal separator drifted under $locale",
            )
        }
    }

    @Test
    fun `accuracy is a band of three, and absent stays absent`() {
        // The band is the whole of what the screen says about accuracy — no metre figure appears
        // anywhere in this feature (AccuracyBand carries the 2026-09-03 decision and its cost).
        assertEquals(AccuracyBand.PRECISE, fix.displayAccuracyBand())
        assertEquals(AccuracyBand.PRECISE, fix.copy(accuracyMeters = 10f).displayAccuracyBand())
        assertEquals(AccuracyBand.STREET, fix.copy(accuracyMeters = 10.1f).displayAccuracyBand())
        assertEquals(AccuracyBand.STREET, fix.copy(accuracyMeters = 50f).displayAccuracyBand())
        assertEquals(AccuracyBand.AREA, fix.copy(accuracyMeters = 50.1f).displayAccuracyBand())
        assertEquals(AccuracyBand.AREA, fix.copy(accuracyMeters = 90f).displayAccuracyBand())
        assertNull(fix.copy(accuracyMeters = null).displayAccuracyBand())
        // A value that cannot be read honestly gets no band at all. Calling NaN "precise" would put
        // a reassurance beside a position that has nothing behind it.
        assertNull(fix.copy(accuracyMeters = Float.NaN).displayAccuracyBand())
        assertNull(fix.copy(accuracyMeters = -1f).displayAccuracyBand())
    }

    @Test
    fun `an ordinary urban reading is street level, not the worst band`() {
        // The defect the third band exists to close, in one assertion. With two bands split at the
        // settle radius, 20 m — a normal, good urban fix — shared a bucket with a 90 m one and was
        // described with the same pejorative word. Reported 2026-09-03: "a user that doesn't know
        // these things sees 20 m and it will cause a bad feeling." A word that is forced to cover
        // 10 m to 100 m cannot be true and encouraging at once, which is why there are three.
        val ordinary = fix.copy(accuracyMeters = 20f).displayAccuracyBand()
        val poor = fix.copy(accuracyMeters = 90f).displayAccuracyBand()
        assertEquals(AccuracyBand.STREET, ordinary)
        assertTrue(ordinary != poor, "20 m and 90 m must not be described with the same word")
    }

    @Test
    fun `the band never widens as the reading improves`() {
        // The live line's only claim to being progress: the word must move in one direction as the
        // series converges, or a user watching it learns nothing from the change.
        val order = listOf(AccuracyBand.AREA, AccuracyBand.STREET, AccuracyBand.PRECISE)
        val converging = listOf(95f, 80f, 55f, 50f, 40f, 25f, 12f, 10f, 6f, 3f)
        converging
            .map { m -> order.indexOf(fix.copy(accuracyMeters = m).displayAccuracyBand()) }
            .zipWithNext()
            .forEachIndexed { i, (before, after) ->
                assertTrue(
                    after >= before,
                    "${converging[i]} m -> ${converging[i + 1]} m moved the band backwards",
                )
            }
    }

    @Test
    fun `the precise boundary is the shipped settle radius, not a number chosen for the caption`() {
        // The one band edge that is policy rather than copy. If FixRequest's settle radius ever
        // moves, the word "precise" must move with it — the offer gate and the indoor explanation
        // both key on this band, so a caption boundary drifting away from the policy one would
        // silently change behaviour and not just wording.
        val settle = com.localkeep.core.location.FixRequest().settleAccuracyMeters
        assertEquals(AccuracyBand.PRECISE, fix.copy(accuracyMeters = settle).displayAccuracyBand())
        assertTrue(
            fix.copy(accuracyMeters = settle + 0.1f).displayAccuracyBand() != AccuracyBand.PRECISE,
            "the precise band extends past FixRequest().settleAccuracyMeters",
        )
    }

    @Test
    fun `the indoor explanation waits for a capture that settled somewhere loose`() {
        val loose = fix.copy(accuracyMeters = 45f)
        val tight = fix.copy(accuracyMeters = 4f)

        // The defect this closes, reported 2026-08-19: the hint was rendered under no condition at
        // all, so someone standing in a field with a clean satellite lock was told that indoors
        // this is tens of metres and that they should step outside.
        assertFalse(indoorExplanationApplies(CaptureConvergence.SEARCHING, null))
        assertFalse(indoorExplanationApplies(CaptureConvergence.SEARCHING, loose))
        // Still converging is not an explanation for anything — nothing has stopped yet.
        assertFalse(indoorExplanationApplies(CaptureConvergence.IMPROVING, loose))
        // Settled and tight: outdoors, or as good as indoors ever gets. Nothing to excuse.
        assertFalse(indoorExplanationApplies(CaptureConvergence.SETTLED, tight))
        // Settled with no accuracy on the record at all — an unknown error is not evidence of a
        // roof, and the app does not invent one.
        assertFalse(indoorExplanationApplies(CaptureConvergence.SETTLED, fix.copy(accuracyMeters = null)))

        // The one case that earns the sentence.
        assertTrue(indoorExplanationApplies(CaptureConvergence.SETTLED, loose))
    }

    @Test
    fun `every failure has its own sentence`() {
        // Distinct resources, not one generic "could not get location". Each of these is a
        // different situation with a different way out, and collapsing them would leave the user
        // guessing which one they are in.
        val resources = LocationFailure.entries
            .filter { it != LocationFailure.CANCELLED }
            .map { it.messageRes() }
        assertEquals(
            resources.size,
            resources.toSet().size,
            "two failures share a message; the user cannot tell which situation they are in",
        )
    }

    @Test
    fun `the two permission answers and a switched-off provider offer a trip to settings`() {
        // PROVIDER_DISABLED joined this list 2026-09-10 (see LocationProviderDisabledSettingsRouteTest):
        // it does have a fix, the system's location toggle, this app's own settings page just
        // cannot reach it — which is why isFixableInSettings alone is not the whole story any more;
        // settingsActionLabelRes and Context.openSettingsFor decide *which* page each one means.
        assertTrue(LocationFailure.PERMISSION_DENIED.isFixableInSettings())
        assertTrue(LocationFailure.APPROXIMATE_ONLY.isFixableInSettings())
        assertTrue(LocationFailure.PROVIDER_DISABLED.isFixableInSettings())
        // The rest send the user to a screen with nothing on it to change, and an action that leads
        // nowhere is worse than none — it spends the attention the message had.
        assertFalse(LocationFailure.UNAVAILABLE.isFixableInSettings())
        assertFalse(LocationFailure.TIMED_OUT.isFixableInSettings())
        assertFalse(LocationFailure.CANCELLED.isFixableInSettings())
    }

    @Test
    fun `a position is gated on every item, whatever its reprompt flag says`() {
        // The equivalence this feature rests on: a saved position is treated exactly as a card's
        // CVV is. Unlike a password it cannot be changed after someone sees it.
        assertTrue(COORDINATE_FIELD in ALWAYS_REAUTH_FIELDS)
        val ordinary = ItemEditUiState(type = ItemEditUiState.ItemType.COORDINATE, reprompt = false)
        assertTrue(ordinary.requiresReauthFor(COORDINATE_FIELD))
    }

    @Test
    fun `the mask reveals nothing about the value behind it`() {
        // Fixed width. A mask sized to its content would leak the number of digits, and for a
        // coordinate that is a statement about magnitude — a single-digit latitude is the tropics.
        assertFalse(COORDINATE_MASK.any(Char::isDigit))
        // Long enough that it cannot be mistaken for a short value, and unrelated to any real one:
        // a formatted pair is 21-23 characters, so the mask is deliberately not that length either.
        assertTrue(COORDINATE_MASK.length >= 12)
        assertEquals(setOf('•'), COORDINATE_MASK.toSet())
    }
}
