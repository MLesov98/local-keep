package com.localkeep.feature.files

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for 20-2: rotating the phone mid-video restarted the clip at 0:00.
 *
 * No manifest in this app declares `android:configChanges`, so a rotation destroys the activity and
 * rebuilds the whole composition — every `remember(blobRef)` in the player resets. Most of what it
 * holds is re-derived on the way back up from the decoder itself: the duration, the aspect ratio,
 * the surface. Where the user had got to is not, and turning the phone for a bigger picture is the
 * most ordinary reason there is to be watching a video sideways.
 *
 * Source-scanning rather than a Compose test, for the reason [PlaybackScreenAwakeTest] gives: the
 * player needs a real surface, a real `MediaPlayer` and an open blob, and this module's unit-test
 * classpath has no Compose UI test artifact. What is being asserted is which `remember` a call site
 * uses and where the seek happens, both of which the source states exactly.
 *
 * **Change `rememberSaveable(blobRef)` back to `remember(blobRef)` on `positionMs`, or delete the
 * `seekTo(positionMs)` from `setOnPreparedListener`, and the first test goes red.** The second is
 * the control: saving the position without saving `ended` would leave a finished clip showing Play
 * rather than Replay, which starts the decoder at the very end and completes immediately.
 */
class VideoPositionSurvivesRotationTest {

    private val source: String =
        File("src/main/kotlin/com/localkeep/feature/files/EncryptedVideoPlayer.kt").readText()

    @Test
    fun `playback position is held across a recreation and seeked back on the rebuilt player`() {
        assertTrue(
            "var positionMs by rememberSaveable(blobRef) { mutableIntStateOf(0) }" in source,
            "positionMs must survive the activity recreation a rotation performs",
        )

        // The seek has to be inside setOnPreparedListener and nowhere earlier: a seek before
        // prepare throws, and a player rebuilt after a rotation sits at 0 until it is told
        // otherwise. Scoped to the listener body so a `seekTo` somewhere else in the file — the
        // loop restart, the scrub handler — cannot satisfy this.
        val prepared = Regex("""setOnPreparedListener \{[\s\S]*?\n {20}\}""").find(source)?.value
        assertTrue(prepared != null, "the prepared listener is no longer where the player is armed")
        assertTrue(
            "seekTo(positionMs)" in prepared!!,
            "the restored position has to be handed to the decoder, or it is only remembered: $prepared",
        )
    }

    @Test
    fun `the finished flag travels with the position it belongs to`() {
        assertTrue(
            "var ended by rememberSaveable(blobRef) { mutableStateOf(false) }" in source,
            "a restored end-of-clip position without `ended` shows Play on a finished video, which " +
                "starts at the end and completes on the spot",
        )
        // The control for the pair above: the rest of the player's state is deliberately NOT
        // saved — it is re-derived from the decoder, and persisting a stale duration or aspect
        // ratio across a rebuild would be worse than recomputing them.
        for (transient in listOf("durationMs", "aspect", "scrubbing", "playing", "holder", "player")) {
            assertEquals(
                1,
                Regex("""var $transient by remember\(blobRef\)""").findAll(source).count(),
                "$transient is re-derived from the decoder and must stay a plain remember",
            )
        }
    }
}
