package com.localkeep.feature.vaultlist

import androidx.annotation.StringRes
import com.localkeep.core.ui.LkNotices
import com.localkeep.core.vault.FolderNameTakenException
import com.localkeep.core.vault.FolderTooDeepException

/**
 * What a refused folder write says. The repository names its refusals; anything else is
 * [otherwise], the calling screen's ordinary action error.
 *
 * Every folder screen pre-checks the visible siblings and the depth before writing, so the refusals
 * that reach here are the ones it could not see — chiefly a name held by a folder in Trash — and
 * those were answered "That didn't work. Nothing was changed." with nothing on screen to explain it
 * (QA 2026-10-03).
 */
@StringRes
internal fun folderWriteError(failure: Throwable, @StringRes otherwise: Int): Int = when (failure) {
    is FolderNameTakenException ->
        if (failure.inTrash) R.string.vaultlist_folders_error_name_in_trash else R.string.vaultlist_folders_error_name_exists
    is FolderTooDeepException -> R.string.vaultlist_folders_error_too_deep
    else -> otherwise
}

/**
 * Announces a refused folder write as a refusal. These went through `LkNotices.post`, which draws a
 * success check and plays a success haptic, so "Folders can nest eight levels deep" arrived looking
 * like it had worked — over the Cancel button of the screen that had just refused.
 */
internal fun postFolderWriteRefusal(failure: Throwable, @StringRes otherwise: Int) =
    LkNotices.postRefusal(folderWriteError(failure, otherwise))
