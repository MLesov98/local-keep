package com.localkeep.feature.vaultlist

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A row revealed in a reveal session is named "Invisible" to a screen reader, as it is on screen.
 *
 * The drawn marker is decorative to the accessibility tree *because* the row's description is meant
 * to say it; the description did not, so TalkBack read a concealed row exactly like any other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp-xhdpi")
class HiddenRowSpokenNameTest {

    @get:Rule
    val compose = createComposeRule()

    private val item = VaultItem(
        id = ITEM_ID,
        title = "Bank",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        content = Login(username = "someone@example.com", password = "hunter2"),
    )

    private fun spoken(hidden: Boolean, selecting: Boolean = false): String {
        compose.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item,
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                    hidden = hidden,
                    onStopHiding = if (hidden) ({}) else null,
                    selecting = selecting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("vaultlist-row-$ITEM_ID", useUnmergedTree = true)
            .fetchSemanticsNode()
        // The row's own gesture node carries the description; walk to the first node that has one.
        fun find(n: androidx.compose.ui.semantics.SemanticsNode): String? =
            n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: n.children.firstNotNullOfOrNull(::find)
        return find(node) ?: ""
    }

    private val marker get() = RuntimeEnvironment.getApplication().getString(R.string.vaultlist_marker_hidden)

    @Test
    fun aRevealedRowSaysInvisible() {
        val name = spoken(hidden = true)
        assertTrue(marker in name, "the row's spoken name \"$name\" omits \"$marker\"")
    }

    @Test
    fun anOrdinaryRowDoesNot() {
        val name = spoken(hidden = false)
        assertTrue(name.startsWith("Bank"), "control: the row still names itself, got \"$name\"")
        assertFalse(marker in name, "an ordinary row must not say \"$marker\": \"$name\"")
    }

    @Test
    fun theSelectionPhrasingKeepsItsStateWord() {
        val name = spoken(hidden = true, selecting = true)
        assertTrue(marker in name, "selecting mode dropped the marker: \"$name\"")
        assertTrue(
            RuntimeEnvironment.getApplication().getString(R.string.vaultlist_row_state_not_selected) in name,
            "selecting mode lost its own state word: \"$name\"",
        )
    }

    private companion object {
        val ITEM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000cc")
    }
}
