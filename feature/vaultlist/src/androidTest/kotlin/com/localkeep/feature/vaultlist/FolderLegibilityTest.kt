package com.localkeep.feature.vaultlist

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Login
import com.localkeep.core.model.VaultItem
import com.localkeep.core.ui.theme.LocalKeepTheme
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * What the list says about folders — the whole of the reported defect.
 *
 * "I added one item into a folder, it never went there." The filter mechanism was working the entire
 * time: tapping the folder chip narrowed the list correctly. What was missing was any statement, on
 * any screen the user was looking at, that the item had been filed at all. The row was identical
 * before and after, and the header counted the narrowed list without naming what had narrowed it.
 *
 * So these assert *legibility*, not filtering. The filtering has its own tests, and it never broke.
 *
 * Every header case below builds its state with `projectionVersion = 1L` and an explicit
 * `scopeItemCount`, because that is what a state the header can describe looks like now. Since
 * 2026-09-13 `countLabel` answers "Opening your vault…" while `projectionVersion` is 0 (no scan
 * has landed), and since 2026-09-16 (F-31) it counts `scopeItemCount` — the whole vault at the
 * root — rather than `items`. These cases were written before both and built neither field, so
 * the two that look for a count went red ("not displayed" is what Compose 1.11 says for a node
 * that does not exist), and the two that assert an *absence* went green for the wrong reason: no
 * folder name in "Opening your vault…" proves nothing. Each absence check is now paired with a
 * presence check on the same line, so it cannot pass against a header that never rendered.
 */
@RunWith(AndroidJUnit4::class)
class FolderLegibilityTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work = Folder(id = UUID.randomUUID(), name = "Work")

    private fun item(folderId: UUID? = null) = VaultItem(
        id = UUID.randomUUID(),
        title = "Acme Mail",
        content = Login(username = "ada@example.com"),
        folderId = folderId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    @Test
    fun aFiledItemNamesItsFolderOnTheRow() {
        rule.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item(folderId = work.id),
                    folderName = work.name,
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                )
            }
        }

        rule.onNodeWithText(work.name).assertIsDisplayed()
    }

    @Test
    fun theFolderIsSpokenAsWellAsShown() {
        // The row replaces its children's semantics with one contentDescription, so a marker added
        // only to the visual tree is invisible to a screen reader. Adding a silent cue would have
        // widened a gap this row's own comments already record.
        rule.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item(folderId = work.id),
                    folderName = work.name,
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                )
            }
        }

        val expected = context.getString(
            R.string.vaultlist_row_description_folder,
            context.getString(R.string.vaultlist_row_description, "Acme Mail", "ada@example.com"),
            work.name,
        )
        rule.onNodeWithContentDescription(expected).assertIsDisplayed()
    }

    @Test
    fun anItemInNoFolderCarriesNoMarker() {
        // Most items in most vaults are in no folder. A "none" marker on every one of them would
        // cost every row to inform a few.
        rule.setContent {
            LocalKeepTheme {
                VaultItemRow(
                    item = item(),
                    onClick = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onQuickCopy = {},
                )
            }
        }

        rule.onNodeWithText(work.name).assertDoesNotExist()
        rule.onNodeWithContentDescription(
            context.getString(R.string.vaultlist_row_description, "Acme Mail", "ada@example.com"),
        ).assertIsDisplayed()
    }

    @Test
    fun theHeaderDoesNotCallTheBrowsedFolderAFilter() {
        // It used to name the folder here, because while a folder was a filter this line was the
        // only thing naming it. Now the breadcrumb sits directly above and names it permanently, so
        // repeating it read as "filter: folder Work" over a screen with a Work crumb on it —
        // navigation described as filtering, with "clear filters" offered as the way out of a place
        // the user walked into. Found on a device, 2026-08-12.
        val state = VaultListUiState(
            items = listOf(item(folderId = work.id)),
            folders = listOf(work),
            folderStack = listOf(work.id),
            scopeItemCount = 1,
            projectionVersion = 1L,
        )
        rule.setContent { LocalKeepTheme { Text(state.countLabel()) } }

        // The header did render, and counts the browsed folder's one item...
        rule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.vaultlist_count_items, 1, 1),
            substring = true,
        ).assertIsDisplayed()
        // ...without naming the folder it is in.
        rule.onNodeWithText(work.name, substring = true).assertDoesNotExist()
    }

    @Test
    fun theRootHeaderCountsFoldersAsWellAsItems() {
        // The root lists only unfiled items, so counting items alone read as an emptied vault the
        // moment everything was filed. Here the one item is filed in Work: no rows of its own at the
        // root, one item in the vault, one folder holding it.
        val state = VaultListUiState(
            items = emptyList(),
            folders = listOf(work),
            scopeItemCount = 1,
            projectionVersion = 1L,
        )
        rule.setContent { LocalKeepTheme { Text(state.countLabel()) } }

        rule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.vaultlist_count_folders, 1, 1),
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun theFavoritesHeaderDropsTheFolderCount() {
        // Favorites hides the folders — they are not favourites — so the header must not still count
        // them, or it claims "5 folders" over a screen showing none. Here the folder plural must be
        // absent; the "0 items · favourites" line is honest, and the empty state explains it.
        val state = VaultListUiState(
            items = emptyList(),
            folders = listOf(work),
            showFavoritesOnly = true,
            projectionVersion = 1L,
        )
        rule.setContent { LocalKeepTheme { Text(state.countLabel()) } }

        // The header did render, as the favourites line...
        rule.onNodeWithText(
            context.getString(R.string.vaultlist_narrowed_favourites),
            substring = true,
        ).assertIsDisplayed()
        // ...and it does not count the folders that view hides.
        rule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.vaultlist_count_folders, 1, 1),
            substring = true,
        ).assertDoesNotExist()
    }

    @Test
    fun theHeaderIsUnchangedWhenNothingIsFilteredAndThereAreNoFolders() {
        // No folders, so the plain suffix — the folder count only appears when there is one to
        // report. A vault that never uses folders sees exactly the line it always did.
        val state = VaultListUiState(
            items = listOf(item()),
            folders = emptyList(),
            scopeItemCount = 1,
            projectionVersion = 1L,
        )
        val expected = context.getString(
            R.string.vaultlist_count_device_suffix,
            context.resources.getQuantityString(R.plurals.vaultlist_count_items, 1, 1),
        )
        rule.setContent { LocalKeepTheme { Text(state.countLabel()) } }

        rule.onNodeWithText(expected).assertIsDisplayed()
    }
}
