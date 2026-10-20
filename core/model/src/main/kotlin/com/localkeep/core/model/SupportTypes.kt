package com.localkeep.core.model

import com.localkeep.core.model.serializers.InstantSerializer
import com.localkeep.core.model.serializers.UuidSerializer
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class Address(
    @ProtoNumber(1) val line1: String = "",
    @ProtoNumber(2) val line2: String = "",
    @ProtoNumber(3) val city: String = "",
    @ProtoNumber(4) val state: String = "",
    @ProtoNumber(5) val zip: String = "",
    @ProtoNumber(6) val country: String = "",
)

@Serializable
data class UriMatch(
    @ProtoNumber(1) val uri: String = "",
    @ProtoNumber(2) val strategy: MatchStrategy = MatchStrategy.DOMAIN,
)

@Serializable
data class PasswordHistoryEntry(
    @ProtoNumber(1) val password: String = "",
    @ProtoNumber(2)
    @Serializable(with = InstantSerializer::class)
    val changedAt: Instant,
)

@Serializable
data class CustomField(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val value: String = "",
    @ProtoNumber(3) val type: CustomFieldType = CustomFieldType.TEXT,
    // LINKED fields reference another VaultItem.id.
    @ProtoNumber(4)
    @Serializable(with = UuidSerializer::class)
    val linkedItemId: UUID? = null,
) {
    override fun toString(): String =
        "CustomField(name=$name, value=${if (type == CustomFieldType.HIDDEN) "redacted" else value}, " +
            "type=$type, linkedItemId=$linkedItemId)"
}

/** Item attachment metadata. [blobRef] uses the same blob store as standalone [File]. */
@Serializable
data class Attachment(
    @ProtoNumber(1)
    @Serializable(with = UuidSerializer::class)
    val id: UUID,
    @ProtoNumber(2) val filename: String = "",
    @ProtoNumber(3) val mime: String = "",
    @ProtoNumber(4) val sizeBytes: Long = 0L,
    @ProtoNumber(5) val blobRef: String = "",
    @ProtoNumber(6) val thumbnailRef: String? = null,
)

@Serializable
data class Folder(
    @ProtoNumber(1)
    @Serializable(with = UuidSerializer::class)
    val id: UUID,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3)
    @Serializable(with = UuidSerializer::class)
    val parentId: UUID? = null,
    /**
     * When this folder was last created or renamed, used to order a rename against another copy of
     * the same folder during a merge (see `VaultImporter.mergeInto`). Without it, merging is
     * union-of-id and the existing name always wins, so importing a backup taken *after* a rename
     * silently discards the rename.
     *
     * Nullable, and null is treated as "older than any known timestamp": folders written by a
     * schema-1 vault carry no field 4 at all, and a null must lose to a real timestamp rather than
     * be mistaken for the present moment.
     */
    @ProtoNumber(4)
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant? = null,
    /**
     * When this folder was last moved to a different parent, ordering a re-parent against another
     * copy of the same folder during a merge — the same job [updatedAt] does for a rename, and a
     * separate field because they are separate edits.
     *
     * One stamp for the whole folder would make a rename in one copy and a move in the other
     * compete, and whichever lost would be discarded even though both were things the user did.
     * With two, `FolderTree.mergeFolder` takes the name from one side and the parent from the
     * other. Null follows [updatedAt]'s rule exactly: older than any known timestamp.
     */
    @ProtoNumber(5)
    @Serializable(with = InstantSerializer::class)
    val parentUpdatedAt: Instant? = null,
    /**
     * When this folder was moved to the trash, or null while it is live — the folder-side twin of
     * [VaultItem.deletedAt], and what lets deleting a folder be recoverable rather than the
     * irreversible operation it has been until now.
     *
     * Deliberately *not* the ordering key for a merge; see [trashUpdatedAt] for why.
     */
    @ProtoNumber(6)
    @Serializable(with = InstantSerializer::class)
    val deletedAt: Instant? = null,
    /**
     * When [deletedAt] and [trashRootId] were last written, ordering a trash against a restore
     * during a merge — the third of the three independent stamps, after a rename ([updatedAt]) and
     * a move ([parentUpdatedAt]).
     *
     * A separate stamp rather than ordering on [deletedAt] itself, because restore *clears*
     * [deletedAt]. If the value were its own ordering key, a restored folder would carry null,
     * `FolderTree.mergeFolder` would read null as "older than anything", and importing a backup
     * taken while the folder was still trashed would silently re-trash a folder the user had
     * deliberately restored. With the companion stamp, restore writes `deletedAt = null,
     * trashUpdatedAt = now` and wins on recency the way every other edit does.
     */
    @ProtoNumber(7)
    @Serializable(with = InstantSerializer::class)
    val trashUpdatedAt: Instant? = null,
    /**
     * The folder whose deletion put this one in the trash — itself for the folder the user actually
     * deleted, an ancestor for everything below it — or null while it is live.
     *
     * Subtree membership has to be recorded rather than inferred. Matching "every folder trashed at
     * the same instant" looks equivalent and is not: `mergeFolder` decides each record separately,
     * so after a merge one subtree can hold several different [trashUpdatedAt] values, and a
     * restore keyed on the instant would bring back part of a tree and leave the rest invisible.
     */
    @ProtoNumber(8)
    @Serializable(with = UuidSerializer::class)
    val trashRootId: UUID? = null,
    /**
     * When presentation concealment of this folder ends, or null if it is not hidden — the folder-side
     * twin of [VaultItem.hiddenUntil]. A folder's concealment hides the folder and everything under
     * it; the projection expands that ancestry (see `:core:vault` `VaultVisibility`). [HiddenLease.
     * INDEFINITE] means "no end date". Field 9, added in schema 5.
     */
    @ProtoNumber(9)
    @Serializable(with = InstantSerializer::class)
    val hiddenUntil: Instant? = null,
    /**
     * When [hiddenUntil] was last written, the fourth independent stamp after a rename ([updatedAt]),
     * a move ([parentUpdatedAt]) and a trash ([trashUpdatedAt]). Ordered on its own so hiding a folder
     * in one copy and renaming it in another both survive a merge, and null on the same terms as the
     * other three: older than any known timestamp. Field 10, added in schema 5.
     */
    @ProtoNumber(10)
    @Serializable(with = InstantSerializer::class)
    val hiddenUpdatedAt: Instant? = null,
)

/**
 * The folders a user-facing list may show: trashed ones belong to the Trash screen and nowhere else.
 *
 * One spelling of the rule, because the wrong side of it is silently destructive rather than merely
 * wrong. `VaultRepository` deliberately does **not** use this — every read in there works on the raw
 * list, and three of them (`withLiveFolder`, `checkFolderTreeHealth`, `repairFolderTree`) would
 * unfile the entire contents of every trashed folder if they were filtered. The filter belongs at
 * the ViewModel boundary, where the question is "what does the user see", not "what does the vault
 * contain".
 */
val List<Folder>.live: List<Folder> get() = filter { it.deletedAt == null }
