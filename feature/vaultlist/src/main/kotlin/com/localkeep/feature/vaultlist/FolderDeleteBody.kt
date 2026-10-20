package com.localkeep.feature.vaultlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * What the delete-folder dialog says happens to the contents.
 *
 * A folder holding nothing gets its own sentence: the counted body read "0 items in all", which is
 * a total of nothing and a CLDR plural nobody speaks. [itemCount] is the count that goes to the
 * trash with the folder, nested folders included, so zero here means the whole subtree is empty.
 *
 * [hasSubfolders] is what separates the two empty cases. "Subfolders included" is true of a folder
 * that has some and a claim about nothing for one that has none, so a folder holding neither items
 * nor folders says exactly that. The counted body keeps the phrase for a folder with items: this
 * stays scoped to the empty case, where the sentence has nothing else to say.
 */
@Composable
internal fun folderDeleteBody(itemCount: Int, hasSubfolders: Boolean): String =
    if (itemCount == 0) {
        stringResource(
            if (hasSubfolders) {
                R.string.vaultlist_folders_delete_body_empty
            } else {
                R.string.vaultlist_folders_delete_body_childless
            },
        )
    } else {
        pluralStringResource(R.plurals.vaultlist_folders_delete_body, itemCount, itemCount)
    }
