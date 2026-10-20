package com.localkeep.feature.files

import android.media.AudioAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for 4C.2-mp3-2 (release sweep 2026-09-20): the player published no `AudioAttributes` at
 * all, so `dumpsys audio` reported `attr:AudioAttributes: usage=USAGE_UNKNOWN
 * content=CONTENT_TYPE_UNKNOWN` for every clip out of the vault. An unknown usage takes no audio
 * focus — the OS cannot duck or pause another app for it, and an incoming call does not pause it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackAudioAttributesTest {

    @Test
    fun `playback declares itself as media, not as an unknown sound`() {
        val attributes = playbackAudioAttributes()

        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MOVIE, attributes.contentType)
    }
}
