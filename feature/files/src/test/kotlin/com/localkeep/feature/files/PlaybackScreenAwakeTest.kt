package com.localkeep.feature.files

import android.view.View
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A video must survive the screen timeout, and only while it is actually playing.
 *
 * Nothing in this app kept the display awake, so a clip longer than the system timeout put the
 * screen to sleep mid-playback; `LocalKeepApplication`'s `ACTION_SCREEN_OFF` receiver then locked the
 * vault, and the video ended on the unlock screen. The receiver is right and is not what changed —
 * the player is.
 *
 * Two halves, because either one alone can rot silently: the flag must follow the state in both
 * directions, and the composable must actually hand it the playback state rather than something
 * coarser like "a video is open".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackScreenAwakeTest {

    @Test
    fun `the flag follows playback in both directions`() {
        val view = View(ApplicationProvider.getApplicationContext())

        applyPlaybackScreenAwake(view, playing = true)
        assertTrue(view.keepScreenOn, "the display must be held awake while the decoder is running")

        // Pause, completion, the decoder error listener and the ON_STOP observer all land here. A
        // one-way flag would leave the screen lit on a paused frame for as long as the item is open.
        applyPlaybackScreenAwake(view, playing = false)
        assertFalse(view.keepScreenOn, "the display must be released the moment playback stops")
    }

    @Test
    fun `the player wires the flag to the playing state`() {
        // The wiring lives inside a composable that cannot be rendered here: this module's unit-test
        // classpath has no Compose UI test artifact, and the player needs a real surface, a real
        // MediaPlayer and an open blob besides. Reading the source is what is left, and it guards
        // the half that would otherwise regress invisibly — the helper above can keep passing while
        // nothing calls it.
        val source = File("src/main/kotlin/com/localkeep/feature/files/EncryptedVideoPlayer.kt").readText()
        // Lookbehind so the function's own declaration — which of course mentions `playing`, as a
        // parameter name — cannot satisfy the assertion below on its own.
        val call = Regex("""(?<!fun )applyPlaybackScreenAwake\([^)]*\)""")
            .findAll(source)
            .map { it.value }
            .toList()

        assertTrue(call.isNotEmpty(), "EncryptedVideoPlayer no longer keeps the display awake at all")
        assertTrue(
            call.any { "playing" in it },
            "the screen-awake flag must be scoped to the playing state, not to the viewer being open; found $call",
        )
    }
}
