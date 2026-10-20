package com.localkeep.feature.settings

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coming back from the system's autofill picker has to re-read the autofill row.
 *
 * Device evidence, 2026-10-04 on emulator-5562 (API 36, foss release): Settings › "Autofill
 * service" read "Off — tap to choose LocalKeep"; choosing LocalKeep in the system picker and
 * pressing Back left it reading Off — and "Inline suggestions" still said it was off while
 * LocalKeep isn't the autofill service — while the secure setting already named LocalKeep.
 * Leaving Settings and entering it again showed "On".
 *
 * The re-read existed, but in a `LaunchedEffect(Unit)`, which runs once per composition. The
 * system picker covers this activity without disposing anything in it, so returning from it is a
 * resume, not an entry, and nothing ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutofillRowRefreshesOnReturnTest {

    @Test
    fun `returning from a system settings screen re-reads the row`() {
        var reads = 0
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { RefreshOnReturn { reads++ } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, reads, "the row must be read on entry")

        // The system picker comes up over the app; the user picks LocalKeep and presses Back.
        controller.pause().stop()
        controller.restart().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, reads, "the row was not re-read when the user came back")
    }

    /** And the screen actually uses it for the system-owned rows. */
    @Test
    fun `the settings screen re-reads the system rows through it`() {
        val screen = File("src/main/kotlin/com/localkeep/feature/settings/SettingsScreen.kt")
            .readLines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "RefreshOnReturn { viewModel.refreshSystemCapabilities() }" in screen,
            "the autofill and biometric rows must be re-read on return, not only on entry",
        )
    }
}
