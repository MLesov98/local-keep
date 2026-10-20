package com.localkeep.feature.files

import android.content.Context
import android.view.SurfaceView
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The video surface's secure flag follows `lk_obscure_screen`, both ways.
 *
 * A `SurfaceView` is composited as its own layer, so the activity's `FLAG_SECURE` does not reach it:
 * the video is the one thing in this app that a screenshot could otherwise capture. It is therefore
 * gated on the same resource the window flag is — `true` everywhere except `app/src/debug`, which no
 * release variant compiles, so it fails closed on a missing or mis-merged override.
 *
 * The gate is worth a test precisely because both of its failure modes are invisible. Wire it to a
 * constant `true` and QA's screenshots go black again with nothing to explain why; wire it to a
 * constant `false` and every release build leaks video frames to the recents thumbnail while looking
 * completely normal. `setSecure` has no getter, so the flag is read back off the view.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoSurfaceScreenSecurityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** `SurfaceControl.SECURE`, the bit `SurfaceView.setSecure` sets in its surface flags. */
    private val secureFlag = 0x00000080

    private fun SurfaceView.isSecure(): Boolean {
        val field = SurfaceView::class.java.getDeclaredFields()
            .firstOrNull { it.name == "mSurfaceFlags" }
            ?: fail(
                "SurfaceView no longer has mSurfaceFlags — find where the platform keeps the secure " +
                    "bit now and read that instead. Do not delete this test: it is the only check " +
                    "that a release build's video layer is not capturable.",
            )
        field.isAccessible = true
        return (field.getInt(this) and secureFlag) != 0
    }

    @Test
    fun secureWhenTheScreenIsObscured() {
        val view = SurfaceView(context)
        view.applyScreenSecurity(obscure = true)
        assertTrue(view.isSecure(), "the video layer must be secure wherever the window is")
    }

    @Test
    fun capturableWhenTheScreenIsNot() {
        val view = SurfaceView(context)
        view.applyScreenSecurity(obscure = false)
        assertTrue(
            !view.isSecure(),
            "a debug build must be able to capture video frames, or QA cannot tell a playing clip " +
                "from a broken player",
        )
    }

    /**
     * And the default — what the one production call site actually uses — is the resource itself.
     *
     * This module's own resources have no `app/src/debug` override in them, so the value here is the
     * shipped `true`: the assertion is both "it reads the resource" and "the resource fails closed".
     */
    @Test
    fun theDefaultIsTheSameResourceTheWindowFlagUses() {
        val declared = context.resources.getBoolean(com.localkeep.core.ui.R.bool.lk_obscure_screen)
        assertTrue(declared, "lk_obscure_screen must default to true — this build would ship capturable")

        val view = SurfaceView(context)
        view.applyScreenSecurity()
        assertEquals(
            declared,
            view.isSecure(),
            "the surface's secure flag must follow lk_obscure_screen, not a constant",
        )
    }
}
