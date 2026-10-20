package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.localkeep.core.ui.theme.LkSpace
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Restore and Delete forever sit side by side with no gap between their touch boxes, on the item row
 * and on the folder row alike.
 *
 * Reported 2026-10-12: "Why is there so much spacing in trash between trashbin icon and green one?
 * It's ridiculous, cut it a little bit." Measured on a device: the touch boxes were 16dp apart on an
 * item row and 8dp apart on a folder row, and `LkIconButton` draws a 34dp plate inside a 48dp box, so
 * the plates the eye compares were ~30dp and ~22dp apart. The two rows disagreed with each other.
 * What is pinned here is the *box* gap — zero — because that is the floor: below it the two 48dp
 * targets would overlap on a destructive control.
 *
 * [GraphicsMode.Mode.NATIVE] for real layout, as in [VaultItemRowFontScaleBoundsTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrashRowActionsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun boundsOf(label: String) = compose.onNodeWithContentDescription(label).getBoundsInRoot()

    @Test
    fun `the two actions abut and each keeps the 48dp floor`() {
        compose.setContent {
            LocalKeepTheme { TrashRowActions("Restore X", {}, "Delete X forever", {}, enabled = true) }
        }
        val restore = boundsOf("Restore X")
        val delete = boundsOf("Delete X forever")

        assertTrue(restore.width.value >= 48f && restore.height.value >= 48f, "restore target is $restore")
        assertTrue(delete.width.value >= 48f && delete.height.value >= 48f, "delete target is $delete")
        assertEquals(0f, delete.left.value - restore.right.value, 0.5f, "gap between the touch boxes")
    }

    /**
     * The row that had the 30dp gap: it arranges with `spacedBy(LkSpace.md)`, which spaces *between
     * children*. The pair has to arrive as one child, or that spacing lands between the buttons.
     */
    @Test
    fun `a spaced parent row does not leak its spacing between the two`() {
        compose.setContent {
            LocalKeepTheme {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LkSpace.md)) {
                    Text("Title", Modifier.weight(1f))
                    TrashRowActions("Restore X", {}, "Delete X forever", {}, enabled = true)
                }
            }
        }
        val restore = boundsOf("Restore X")
        val delete = boundsOf("Delete X forever")

        assertEquals(0f, delete.left.value - restore.right.value, 0.5f, "gap between the touch boxes")
    }

    /** Both rows draw the pair through [TrashRowActions]; neither assembles its own buttons again. */
    @Test
    fun `the item row and the folder row share the one pair`() {
        val source = File("src/main/kotlin/com/localkeep/feature/vaultlist/TrashScreen.kt").readText()
        assertEquals(
            2,
            Regex("""TrashRowActions\(\s*\n\s*restoreLabel =""").findAll(source).count(),
            "TrashItemRow and TrashFolderRow must each call TrashRowActions",
        )
        // Restore is drawn with Undo (a bin with a return arrow), once, inside the pair. It was
        // Export — an arrow leaving a box, the glyph for taking a copy out.
        assertEquals(
            1,
            Regex("""icon = LkIcons\.Undo""").findAll(source).count(),
            "a Restore button is being assembled outside TrashRowActions",
        )
        assertEquals(
            0,
            Regex("""icon = LkIcons\.Export""").findAll(source).count(),
            "Restore must not wear the Export glyph",
        )
    }
}
