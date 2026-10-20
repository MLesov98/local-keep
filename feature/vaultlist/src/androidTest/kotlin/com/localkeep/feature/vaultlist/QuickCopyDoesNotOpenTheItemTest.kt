package com.localkeep.feature.vaultlist

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.model.Login
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The row's inline copy control copies, and does *only* that.
 *
 * The row's gesture node is `lkPressAndDrag`, which opens with
 * `awaitFirstDown(requireUnconsumed = false)` and then decides "was this a tap?" by asking whether
 * the pointer is still down. That question cannot separate "an ancestor took the gesture for a
 * scroll" (still down) from "a *descendant* consumed it as its own click" (up, exactly like a tap) —
 * so the pill's own `clickable` fired and the row opened the item as well. Measured on
 * emulator-5556 on 2026-08-29: one tap on `Copy password for …` both put the password on the
 * clipboard and left the user on the item detail screen.
 *
 * The same shape was already found and fixed once for the overflow control, which is why that one
 * is a sibling overlay outside the gesture node (see `VaultItemRow`'s `padding(end = 58.dp)`). The
 * pill never got the same treatment, and a fix that only moves this one control would leave the
 * next child control to rediscover it — so the guard lives in the gesture node and is pinned here.
 *
 * Driven with `performClick()`, which injects a real down/up rather than invoking the semantics
 * action, because the semantics action is not the path that broke.
 */
@RunWith(AndroidJUnit4::class)
class QuickCopyDoesNotOpenTheItemTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun item(content: com.localkeep.core.model.ItemContent) = VaultItem(
        id = UUID.randomUUID(),
        title = TITLE,
        content = content,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    /** Resolved, never hardcoded: the instrumentation process follows the *system* locale. */
    private fun pillDescription(actionRes: Int): String = context.getString(
        R.string.vaultlist_quickcopy_description,
        context.getString(actionRes),
        TITLE,
    )

    private fun assertCopiesWithoutOpening(content: com.localkeep.core.model.ItemContent, actionRes: Int) {
        var opened = 0
        var copied = 0
        rule.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item(content),
                    onClick = { opened++ },
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = { copied++ },
                )
            }
        }

        rule.onNodeWithContentDescription(pillDescription(actionRes)).performClick()
        rule.waitForIdle()

        assertEquals(1, copied, "the copy control did not copy")
        assertEquals(0, opened, "tapping copy also opened the item")
    }

    @Test
    fun tappingCopyPasswordCopiesAndDoesNotOpenTheItem() =
        assertCopiesWithoutOpening(
            Login(username = "ada@example.com", password = "correct horse battery staple"),
            R.string.vaultlist_quickcopy_copy_password,
        )

    /** Same pill, same gesture node — a fix that only covers the password variant is half a fix. */
    @Test
    fun tappingCopyCodeCopiesAndDoesNotOpenTheItem() =
        assertCopiesWithoutOpening(
            Totp(secretBase32 = "JBSWY3DPEHPK3PXP"),
            R.string.vaultlist_quickcopy_copy_totp,
        )

    /**
     * The control, and it is not optional: the guard added for the two tests above lives in the row's
     * own tap path, so a version of it that simply stops the row reacting would turn both of them
     * green while breaking the primary action of the app's primary screen. This one must stay green.
     */
    @Test
    fun tappingTheRowItselfStillOpensTheItem() {
        var opened = 0
        var copied = 0
        rule.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item(Login(username = "ada@example.com", password = "correct horse battery staple")),
                    onClick = { opened++ },
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = { copied++ },
                )
            }
        }

        // The title, not the row's centre: the pill sits on the row's trailing edge and the point of
        // this test is to land somewhere that is unambiguously the row and not the pill.
        rule.onNodeWithText(TITLE).performClick()
        rule.waitForIdle()

        assertEquals(1, opened, "tapping the row no longer opens the item")
        assertEquals(0, copied, "tapping the row copied a password it was never asked to copy")
    }

    private companion object {
        const val TITLE = "Acme Mail"
    }
}
