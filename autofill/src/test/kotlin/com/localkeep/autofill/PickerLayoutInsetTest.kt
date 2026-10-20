package com.localkeep.autofill

import android.graphics.Insets
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The picker's heading sat flush against the left edge of the window while the list rows were
 * inset. The root view is `fitsSystemWindows`, and the framework replaces such a view's padding
 * with the system-bar insets when they are dispatched, so padding set on the root never survived
 * the first layout pass.
 *
 * Driven by dispatching real insets and laying the tree out, then reading where the heading ended
 * up - a check on the tree's shape alone would pass for a layout that still lost its padding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PickerLayoutInsetTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun leftInRoot(root: View, view: View): Int {
        val rect = Rect(0, 0, view.width, view.height)
        (root as ViewGroup).offsetDescendantRectToMyCoords(view, rect)
        return rect.left
    }

    private fun laidOut(insets: Insets): Triple<View, TextView, ListView> {
        val heading = TextView(context).apply { text = "Fill in Chrome" }
        val list = ListView(context)
        val root = pickerLayout(context, pad = 48, heading, EditText(context), TextView(context), list)
        root.dispatchApplyWindowInsets(
            WindowInsets.Builder().setSystemWindowInsets(insets).build(),
        )
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2000)
        return Triple(root, heading, list)
    }

    @Test
    fun `the heading keeps its margin after system insets are applied`() {
        val (root, heading, _) = laidOut(Insets.of(0, 0, 0, 0))
        assertEquals(48, leftInRoot(root, heading), "the heading lost the picker's padding")
    }

    /** Control: the insets still apply - a layout that dropped fitsSystemWindows would pass the test above. */
    @Test
    fun `system bar insets still push the content in`() {
        val (root, heading, list) = laidOut(Insets.of(0, 120, 0, 90))
        val headingTop = Rect(0, 0, heading.width, heading.height)
            .also { (root as ViewGroup).offsetDescendantRectToMyCoords(heading, it) }.top
        val listBottom = Rect(0, 0, list.width, list.height)
            .also { (root as ViewGroup).offsetDescendantRectToMyCoords(list, it) }.bottom
        assertTrue(headingTop >= 120, "the heading sits under the status bar: top=$headingTop")
        assertTrue(listBottom <= 2000 - 90, "the list runs under the navigation bar: bottom=$listBottom")
        assertTrue(list.height > 0, "the list collapsed")
    }
}
