package com.localkeep.autofill

import android.app.ActivityOptions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The save confirmation's PendingIntent carries the creator's background-launch opt-in on API 34+.
 *
 * Without it the platform's own launch of that PendingIntent is refused whenever the vault locked
 * between its "Save password to LocalKeep?" sheet and the launch, because this app then has no
 * visible window (`BAL_BLOCK`, result code 102, measured on API 35 on 2026-09-17). The service is
 * never told: `SaveCallback.onSuccess(intentSender)` is the last it hears.
 *
 * The mode is asserted through the bundle because [ActivityOptions] exposes no getter for it — the
 * bundle *is* how the value reaches the platform. Matched by key suffix and exact value rather than
 * by the framework's `@hide` key constant verbatim: a renamed key should fail here loudly, and a
 * bundle that merely exists must not pass for one that carries the opt-in.
 */
@RunWith(RobolectricTestRunner::class)
class CreatorBackgroundActivityStartTest {

    private val modeKeySuffix = "BackgroundActivityStartMode"

    @Test
    @Config(sdk = [34])
    fun `on API 34 the options allow the creator's background launch`() {
        val bundle = assertNotNull(
            CreatorBackgroundActivityStart.optionsBundle(),
            "no options at all, so the platform launch is refused exactly as before",
        )
        val key = bundle.keySet().singleOrNull { it.endsWith(modeKeySuffix) }
        assertNotNull(key, "the options carry no background-activity-start mode: ${bundle.keySet()}")
        assertEquals(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            bundle.getInt(key),
            "the mode is present but not ALLOWED",
        )
    }

    /**
     * The control, and the reason the helper guards internally rather than at its call sites:
     * the opt-in does not exist below 34, and [PendingSaveQueue] is what recovers a refusal there.
     * Null is a complete answer — `PendingIntent.getActivity`'s five-argument overload has taken a
     * null options bundle since API 16.
     */
    @Test
    @Config(sdk = [33])
    fun `below API 34 there are no options to pass`() {
        assertNull(CreatorBackgroundActivityStart.optionsBundle())
    }

    /**
     * Both PendingIntents the platform is asked to launch — the login save and the card save — take
     * the options. Read from source because a `SaveRequest` is constructed by the platform and
     * cannot be built in a JVM test, so there is no way to call `SaveHandler.handle` here at all;
     * the alternative to this check is no check.
     */
    @Test
    fun `both save PendingIntents pass the options`() {
        val text = File("src/main/kotlin/com/localkeep/autofill/SaveHandler.kt").readText()
        val launches = Regex("PendingIntent\\.getActivity\\(").findAll(text).count()
        assertEquals(2, launches, "SaveHandler no longer builds exactly two PendingIntents — update this test")
        assertEquals(
            2,
            Regex("CreatorBackgroundActivityStart\\.optionsBundle\\(\\)").findAll(text).count(),
            "a save PendingIntent is built without the creator's background-launch opt-in, so the " +
                "platform will refuse to show its confirmation once the vault has locked",
        )
    }
}
