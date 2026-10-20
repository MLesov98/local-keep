package com.localkeep.autofill

import android.graphics.Insets
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The generated-password prompt keeps its margin, for the reason [PickerLayoutInsetTest] gives: the
 * root view is `fitsSystemWindows`, so the framework replaces any padding set on it with the system-
 * bar insets, and the heading, the password and the buttons ran flush against the window's edges.
 *
 * Driven by dispatching real insets and laying the tree out, then reading where the heading and the
 * widest control ended up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeneratorLayoutInsetTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun bounds(root: View, view: View): Rect =
        Rect(0, 0, view.width, view.height).also { (root as ViewGroup).offsetDescendantRectToMyCoords(view, it) }

    private fun laidOut(insets: Insets): Triple<View, TextView, Button> {
        val heading = TextView(context).apply { text = "Suggested password" }
        val cancel = Button(context).apply { text = "Cancel" }
        val root = generatorLayout(
            context,
            pad = 48,
            gap = 24,
            heading = heading,
            value = TextView(context),
            explanation = TextView(context),
            use = Button(context),
            regenerate = Button(context),
            cancel = cancel,
        )
        root.dispatchApplyWindowInsets(WindowInsets.Builder().setSystemWindowInsets(insets).build())
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2000)
        return Triple(root, heading, cancel)
    }

    @Test
    fun `the heading and the buttons keep the margin after system insets are applied`() {
        val (root, heading, cancel) = laidOut(Insets.of(0, 0, 0, 0))
        assertEquals(48, bounds(root, heading).left, "the heading lost the prompt's padding")
        assertEquals(1080 - 48, bounds(root, cancel).right, "the buttons run past the right margin")
    }

    /** Control: the insets still apply - a layout that dropped fitsSystemWindows would pass the test above. */
    @Test
    fun `system bar insets still push the content in`() {
        val (root, heading, cancel) = laidOut(Insets.of(0, 120, 0, 90))
        assertTrue(bounds(root, heading).top >= 120, "the heading sits under the status bar")
        assertTrue(bounds(root, cancel).bottom <= 2000 - 90, "the buttons run under the navigation bar")
    }
}
