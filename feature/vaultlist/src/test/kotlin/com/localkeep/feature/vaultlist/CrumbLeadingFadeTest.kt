package com.localkeep.feature.vaultlist

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A scrolled breadcrumb strip dissolves at its left edge instead of slicing a crumb mid-glyph.
 *
 * Arriving in a folder scrolls the strip to its end, which leaves the first crumb cut by the left
 * edge — a bare "ns" of "All items". The modifier is exercised in the production arrangement (fade
 * outside, `horizontalScroll` inside) over a solid block, so the assertion is about the edge it makes
 * and not about which letters happen to land on it. [GraphicsMode.Mode.NATIVE] is what makes the
 * pixels real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrumbLeadingFadeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var scroll: ScrollState

    private fun show() {
        compose.setContent {
            scroll = rememberScrollState()
            Row(
                Modifier
                    .testTag("strip")
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Color.Black)
                    .fadeLeadingEdgeWhileScrolled(scroll)
                    .horizontalScroll(scroll),
            ) {
                // Far wider than the viewport, so there is somewhere to scroll to.
                Box(Modifier.width(1200.dp).height(40.dp).background(Color.White))
            }
        }
        compose.waitForIdle()
    }

    /**
     * Red channel of the strip at [x], mid-height: black strip background, white content.
     *
     * Drawn by painting the activity's decor view into a bitmap rather than through
     * `captureToImage`, which waits on a hardware redraw that Robolectric never delivers.
     */
    private fun whiteness(x: Int): Float {
        val bounds = compose.onNodeWithTag("strip").fetchSemanticsNode().boundsInWindow
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val px = bitmap.getPixel(bounds.left.toInt() + x, bounds.center.y.toInt())
        return android.graphics.Color.red(px) / 255f
    }

    @Test
    fun anAtRestStripIsDrawnWhole() {
        show()
        assertTrue(whiteness(0) > 0.99f, "nothing is scrolled off to the left, so nothing may fade")
    }

    @Test
    fun aScrolledStripDissolvesAtItsLeftEdgeOnly() {
        show()
        runBlocking { scroll.scrollTo(scroll.maxValue) }
        compose.waitForIdle()
        assertTrue(scroll.value > 0, "the control: the strip really is scrolled")
        assertTrue(whiteness(0) < 0.05f, "the left edge is still opaque at ${whiteness(0)}")
        assertTrue(whiteness(400) > 0.99f, "the fade washed the middle of the strip: ${whiteness(400)}")
    }

    /** The breadcrumb itself wires it outside the scroll, which is what makes the fade the viewport's. */
    @Test
    fun theBreadcrumbAppliesItOutsideTheScroll() {
        val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/FolderBrowsing.kt").readText()
        val fade = source.indexOf(".fadeLeadingEdgeWhileScrolled(scroll)")
        val scrollCall = source.indexOf(".horizontalScroll(scroll)")
        assertTrue(fade in 0 until scrollCall, "the crumb strip must fade outside its horizontalScroll")
    }
}
