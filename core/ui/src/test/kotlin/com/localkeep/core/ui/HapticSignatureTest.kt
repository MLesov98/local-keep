package com.localkeep.core.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the property C3 is actually about: that the app's haptic events *feel different from each
 * other*, on every Android version it supports.
 *
 * Before this, six named events shared two waveforms. Nothing in the build objected, because nothing
 * could: the events all resolved, all played, and only a person holding the phone could tell that
 * "copied your password" and "your 2FA code rolled over" were the same sensation. A test that only
 * checked each event maps to *something* would have passed then too — so the assertion here is
 * distinctness, not existence.
 */
class HapticSignatureTest {

    private val all = Haptic.entries

    @Test
    fun `every signature is distinct from API 30 up, bar the one documented pair`() {
        // 30 is where CONFIRM/REJECT arrive; 34 where the toggle and segment ticks do. Both need to
        // hold, and so does everything above.
        listOf(30, 33, 34, 35, 36).forEach { sdk ->
            val shared = all.groupBy { it.feedbackTypeOn(sdk) }
                .filterValues { it.size > 1 }
                .values
                .map { group -> group.toSet() }
                .toSet()
            assertEquals(
                setOf(UNLOCK_AND_SUCCESS),
                shared,
                "on API $sdk the wrong signatures share a waveform",
            )
        }
    }

    @Test
    fun `on 28 and 29 exactly the two documented pairs share`() {
        // Compose exposes five constants below API 30, so two pairs must collide. Which two is a
        // deliberate choice, and this is what stops it drifting into a worse pair later.
        listOf(28, 29).forEach { sdk ->
            val shared = all.groupBy { it.feedbackTypeOn(sdk) }
                .filterValues { it.size > 1 }
                .values
                .map { group -> group.toSet() }
                .toSet()
            assertEquals(
                setOf(
                    UNLOCK_AND_SUCCESS,
                    setOf(Haptic.REVEAL, Haptic.CONCEAL),
                    setOf(Haptic.SELECT, Haptic.TOTP_ROLLOVER),
                ),
                shared,
                "on API $sdk the wrong signatures are sharing a waveform",
            )
        }
    }

    @Test
    fun `no signature asks for a constant the device does not have`() {
        // The failure this guards against is silence, not a crash: Compose passes its constant
        // straight to View.performHapticFeedback with no version check, and an unknown constant is a
        // no-op. So every rung of the ladder has to be reachable on the API level that selects it.
        val minimumApiFor = mapOf(
            HapticFeedbackType.LongPress to 3,
            HapticFeedbackType.VirtualKey to 5,
            HapticFeedbackType.KeyboardTap to 8,
            HapticFeedbackType.ContextClick to 23,
            HapticFeedbackType.TextHandleMove to 27,
            HapticFeedbackType.Confirm to 30,
            HapticFeedbackType.Reject to 30,
            HapticFeedbackType.GestureEnd to 30,
            HapticFeedbackType.ToggleOn to 34,
            HapticFeedbackType.ToggleOff to 34,
            HapticFeedbackType.SegmentTick to 34,
            HapticFeedbackType.SegmentFrequentTick to 34,
            HapticFeedbackType.GestureThresholdActivate to 34,
        )
        val violations = buildList {
            (MIN_SDK..36).forEach { sdk ->
                all.forEach { signature ->
                    val type = signature.feedbackTypeOn(sdk)
                    val needs = minimumApiFor[type]
                    when {
                        needs == null -> add("API $sdk: $signature maps to an unrecognised $type")
                        needs > sdk -> add("API $sdk: $signature asks for $type, which needs API $needs")
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `unlock and error never feel the same, at any version`() {
        // The one pair where a collision would be actively misleading: these are the two outcomes of
        // the same gesture, and they used to share LongPress.
        (MIN_SDK..36).forEach { sdk ->
            assertNotEquals(
                Haptic.UNLOCK.feedbackTypeOn(sdk),
                Haptic.ERROR.feedbackTypeOn(sdk),
                "on API $sdk a failed unlock feels like a successful one",
            )
        }
    }

    @Test
    fun `a commit never feels like a failure, at any version`() {
        // SUCCESS is what every LkNotices confirmation plays, so it fires on far more paths than
        // UNLOCK ever did; sharing a waveform with ERROR would mean "saved" and "could not save"
        // being the same sensation on the screen where the difference matters most.
        (MIN_SDK..36).forEach { sdk ->
            assertNotEquals(
                Haptic.SUCCESS.feedbackTypeOn(sdk),
                Haptic.ERROR.feedbackTypeOn(sdk),
                "on API $sdk a failed save feels like a successful one",
            )
        }
    }

    private companion object {
        /** Matches `minSdk` in the version catalog. */
        const val MIN_SDK = 28

        /**
         * The one collision that is a decision rather than a defect.
         *
         * The platform has exactly one "what you asked for worked" waveform, and both of these
         * events are that. They stay separate enum entries because call sites name the event, not
         * the effect — a Save button playing something called `UNLOCK` would be a lie that outlives
         * whoever wrote it. Pinned here so the *shape* of the collision cannot drift: if a third
         * signature ever joins this group, or either of these pairs with something else, the two
         * distinctness tests fail rather than quietly widening.
         */
        val UNLOCK_AND_SUCCESS = setOf(Haptic.UNLOCK, Haptic.SUCCESS)
    }
}
