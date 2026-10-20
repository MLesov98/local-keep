package com.localkeep.feature.vaultlist

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.ui.theme.LkActionEmphasis
import com.localkeep.core.ui.theme.LkDialog
import com.localkeep.core.ui.theme.LkDialogAction
import com.localkeep.core.ui.theme.LkIcons
import com.localkeep.core.ui.theme.LkTheme
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The folder-delete confirmation has to fit on screen in every language it ships in.
 *
 * This exists because of a specific change and a specific risk. Sweep 3's adversarial vote found
 * that this dialog carried no reversibility language at all — `itemedit_folder_delete_confirm_body`
 * says "deleted permanently — folders have no trash" for the identical action, and
 * `vaultlist_trash_delete_confirm_title` says "forever", but this one said neither, in any of the
 * five languages. The English under-warned and all four translations were faithful to it. Fixing
 * that meant adding roughly forty-five characters to the body in five languages at once, and German
 * and French are the longest of them.
 *
 * A body that grows past the dialog's height does not truncate — `LkDialogSurface` sets `widthIn`
 * but no `heightIn` and no scroll, so the Column simply keeps laying out and the buttons end up
 * below the bottom of the screen. The user is left looking at a warning they cannot dismiss or
 * confirm. Nothing in lint, `check_catalogue.py` or a catalogue review can see that: they all read
 * strings, and this is a layout consequence of string length.
 *
 * **The assertion measures screen position, not `assertIsDisplayed`.** That was the first attempt
 * and it does not work: a dialog is its own window, the window wraps its content, and a node in a
 * window taller than the display still reports as displayed. Padding the German body with 700
 * characters passed that assertion cleanly. `positionOnScreen` is the instrument that actually
 * fails, so it is the one used here.
 *
 * **Measured on `LocalKeep_API_36`, 2026-08-11, so the margin is a fact and not a hope.** Screen is
 * 1857px. The shipping bodies are 113–170 characters and put the confirm button at 1163–1216px, so
 * there are roughly 640px — about 2500 characters — of slack. Padding the German body to 2657
 * characters moved the button to 1805px, which still passes: it takes very nearly a full extra
 * screen of text to break this, and that is the honest size of the guard. It is kept because the
 * failure it catches is unrecoverable for the user, not because it is close to tripping.
 *
 * The count is 1000 on purpose. It is the widest the `%1$d` renders in practice and it selects the
 * `other` branch, which is the longer of the two in every language.
 */
@RunWith(AndroidJUnit4::class)
class FolderDeleteDialogLocaleTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun localized(tag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }

    private fun assertDialogFits(tag: String, count: Int) {
        val localeContext = localized(tag)
        rule.setContent {
            CompositionLocalProvider(LocalContext provides localeContext) {
                LocalKeepTheme {
                    LkDialog(
                        onDismiss = {},
                        title = localeContext.getString(
                            R.string.vaultlist_folders_delete_title,
                            // A long folder name is the other half of the height budget.
                            "Arbeitsunterlagen",
                        ),
                        body = localeContext.resources.getQuantityString(
                            R.plurals.vaultlist_folders_delete_body,
                            count,
                            count,
                        ),
                        icon = LkIcons.Trash,
                        accent = LkTheme.colors.coral,
                        confirm = LkDialogAction(
                            label = localeContext.getString(R.string.vaultlist_folders_delete_confirm),
                            onClick = {},
                            emphasis = LkActionEmphasis.Destructive,
                        ),
                        dismissLabel = localeContext.getString(R.string.vaultlist_folders_delete_dismiss),
                    )
                }
            }
        }

        // Both, not either. A dialog that keeps only its destructive button is worse than one that
        // keeps neither: the safe way out is the one that must never be the casualty of a long
        // sentence.
        val screenHeight = context.resources.displayMetrics.heightPixels.toFloat()
        for (label in listOf(
            localeContext.getString(R.string.vaultlist_folders_delete_confirm),
            localeContext.getString(R.string.vaultlist_folders_delete_dismiss),
        )) {
            val node = rule.onNodeWithText(label).fetchSemanticsNode()
            val bottom = node.positionOnScreen.y + node.size.height
            assertTrue(
                bottom <= screenHeight,
                "[$tag, n=$count] \"$label\" ends at ${bottom}px, past the ${screenHeight}px screen " +
                    "— the delete dialog has outgrown the display and this button is unreachable.",
            )
        }
    }

    @Test fun englishKeepsBothButtons() = assertDialogFits("en", 1000)

    @Test fun frenchKeepsBothButtons() = assertDialogFits("fr", 1000)

    @Test fun germanKeepsBothButtons() = assertDialogFits("de", 1000)

    @Test fun spanishKeepsBothButtons() = assertDialogFits("es", 1000)

    @Test fun portugueseKeepsBothButtons() = assertDialogFits("pt", 1000)

    @Test
    fun theSingularBranchAlsoFits() {
        // French and Portuguese put 0 in the "one" branch, so the singular is a live rendering and
        // not just the n=1 case.
        assertDialogFits("fr", 1)
    }
}
