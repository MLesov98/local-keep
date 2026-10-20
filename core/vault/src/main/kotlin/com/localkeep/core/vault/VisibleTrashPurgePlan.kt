package com.localkeep.core.vault

import java.security.MessageDigest
import java.util.UUID

/**
 * Which trash the user is about to destroy, decided at the moment they were shown the confirmation.
 *
 * Emptying the trash used to mean "remove every record with a `deletedAt`", which is correct right
 * up until part of the trash is concealed. Then the sentence the user answered — the count on the
 * dialog, the rows behind it — describes less than the mutation would take, and the difference is
 * exactly the entries they asked the app to protect. A concealed trashed item destroyed by an Empty
 * Trash the user could not see the contents of is unrecoverable and silent: nothing on screen ever
 * named it, so nothing on screen can report that it is gone.
 *
 * So the destructive path stopped being a predicate and became a list. [itemIds] and [folderIds] are
 * the exact records the confirmation stood for, and the mutation removes those and nothing else.
 *
 * [fingerprint] closes the gap between the two. A dialog can sit open across a lease expiry, a
 * reveal, a Stop hiding, or a restore from another surface, and any of those changes what "the
 * visible trash" means. Re-deriving the plan at mutation time and comparing fingerprints turns that
 * race into a refusal ([PurgeOutcome.Stale]) rather than into a purge of a set the user never saw.
 */
data class VisibleTrashPurgePlan(
    val scope: PurgeScope,
    val itemIds: Set<UUID>,
    val folderIds: Set<UUID>,
    val fingerprint: String,
)

/** What a purge was aimed at: the whole trash, or one trash root's subtree. */
sealed interface PurgeScope {
    data object AllTrash : PurgeScope

    data class Folder(val id: UUID) : PurgeScope
}

sealed interface PurgeOutcome {
    /** Destroyed exactly the planned ids. Counts are for the confirmation, not for a re-derivation. */
    data class Purged(val itemCount: Int, val folderCount: Int) : PurgeOutcome

    /**
     * The visible trash changed between the confirmation and the tap. **Nothing was deleted.** The
     * caller re-reads and asks again; it must never retry silently, because the whole point is that
     * the user has not yet agreed to the new set.
     */
    data object Stale : PurgeOutcome
}

/**
 * A stable digest of what a plan covers.
 *
 * Sorted ids, so two plans over the same records agree regardless of vault order; scope-prefixed, so
 * an Empty Trash plan can never be mistaken for a single-folder plan that happens to cover the same
 * records. SHA-256 rather than [Set.hashCode] because a 32-bit accidental collision here authorises
 * an irreversible deletion of a set the user did not see, and the cost of the real digest is a few
 * microseconds on a list that fits on a screen.
 */
internal fun purgeFingerprint(scope: PurgeScope, itemIds: Set<UUID>, folderIds: Set<UUID>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val scopeTag = when (scope) {
        PurgeScope.AllTrash -> "all"
        is PurgeScope.Folder -> "folder:${scope.id}"
    }
    digest.update(scopeTag.toByteArray())
    digest.update(0)
    itemIds.map(UUID::toString).sorted().forEach {
        digest.update(it.toByteArray())
        digest.update(0)
    }
    digest.update(1)
    folderIds.map(UUID::toString).sorted().forEach {
        digest.update(it.toByteArray())
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
