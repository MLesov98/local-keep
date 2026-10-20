package com.localkeep.core.vault

import com.localkeep.core.model.FolderTree

/**
 * A folder write refused because a sibling already holds the name.
 *
 * [inTrash] when that sibling is in Trash. A trashed folder keeps its name so restoring it cannot
 * produce two, but no list shows it, so a screen has to say where the name is or the refusal reads
 * as "That didn't work" over nothing visible (QA 2026-10-03). Carries no name: vault content does
 * not ride in exceptions.
 *
 * An [IllegalStateException], as the bare `check` it replaces was.
 */
class FolderNameTakenException(val inTrash: Boolean) : IllegalStateException("sibling folder exists")

/** A folder write refused because something would sit deeper than [FolderTree.MAX_DEPTH]. */
class FolderTooDeepException : IllegalStateException("folder would be deeper than ${FolderTree.MAX_DEPTH}")
