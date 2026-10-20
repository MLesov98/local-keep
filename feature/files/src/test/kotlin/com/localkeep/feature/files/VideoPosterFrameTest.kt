package com.localkeep.feature.files

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for 3C.0e-2 (release sweep 2026-09-20): a video File item opened on an entirely black
 * page — no first frame, nothing identifying the file as a video — with only the transport bar
 * floating in it. `MediaPlayer` puts nothing on the surface before the first frame is decoded, and
 * every video import already writes a downsampled JPEG beside the original.
 */
class VideoPosterFrameTest {

    @Test
    fun `a clip that has not been started shows its poster instead of black`() {
        assertTrue(shouldShowVideoPoster(playing = false, positionMs = 0, hasError = false, hasStarted = false))
    }

    @Test
    fun `the poster gets out of the way the moment the decoder has something to show`() {
        assertFalse(shouldShowVideoPoster(playing = true, positionMs = 0, hasError = false, hasStarted = false))
        // Paused mid-clip, or resumed after a rotation: the surface holds a real frame from where
        // the viewer actually is, and the file's first frame would be a lie about the position.
        assertFalse(shouldShowVideoPoster(playing = false, positionMs = 4_200, hasError = false, hasStarted = false))
    }

    @Test
    fun `a failed clip keeps its error, uncovered`() {
        assertFalse(shouldShowVideoPoster(playing = false, positionMs = 0, hasError = true, hasStarted = false))
    }
}
