package com.localkeep.core.model

import com.localkeep.core.model.serializers.InstantSerializer
import com.localkeep.core.model.serializers.UuidSerializer
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class VaultItem(
    @ProtoNumber(1)
    @Serializable(with = UuidSerializer::class)
    val id: UUID,
    @ProtoNumber(2) val title: String = "",
    @ProtoNumber(3)
    @Serializable(with = UuidSerializer::class)
    val folderId: UUID? = null,
    @ProtoNumber(4) val tags: List<String> = emptyList(),
    @ProtoNumber(5) val favorite: Boolean = false,
    @ProtoNumber(6)
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @ProtoNumber(7)
    @Serializable(with = InstantSerializer::class)
    val updatedAt: Instant,
    @ProtoNumber(8) val reprompt: Boolean = false,
    @ProtoNumber(9) val customFields: List<CustomField> = emptyList(),
    @ProtoNumber(10) val attachments: List<Attachment> = emptyList(),
    @ProtoNumber(11)
    @Serializable(with = InstantSerializer::class)
    val deletedAt: Instant? = null,
    @ProtoNumber(12) val content: ItemContent,
    /**
     * The folder whose deletion trashed this item, or null if it was trashed on its own (or is
     * live). What tells a folder restore which items to bring back with it.
     *
     * The item is tagged with the *root* of the trash operation rather than its own folder, so that
     * restoring that root restores everything it took down in one pass. The marker is a claim of
     * membership, not a restore instruction: a restore must additionally require the item's own
     * folder to be live once the folders are back, or a merge that tears a subtree apart can
     * resurrect an item into a folder that is still in the trash.
     */
    @ProtoNumber(13)
    @Serializable(with = UuidSerializer::class)
    val trashedWithFolderId: UUID? = null,
    /**
     * When presentation concealment of this item ends, or null if it is not hidden. A plain
     * wall-clock lease (see [HiddenLease]); [HiddenLease.INDEFINITE] means "no end date". Field 14,
     * added in schema 5.
     *
     * Concealment is a projection over a decrypted vault, never encryption: a hidden item is still in
     * `vault.items`, still exported, still in every backup. It is absent from the presentation the UI
     * builds, and from every Android credential surface, only while the projection reports it hidden.
     */
    @ProtoNumber(14)
    @Serializable(with = InstantSerializer::class)
    val hiddenUntil: Instant? = null,
    /**
     * When [hiddenUntil] was last written, ordering a hide against an unhide across two copies of the
     * same item during a merge — the item-side twin of the three independent folder stamps, and null
     * on the same terms: older than any known timestamp, so a null never overwrites a real one.
     * Field 15, added in schema 5.
     *
     * Lazy expiry normalization advances this to the lease's expiry instant — not save time — when it
     * clears an expired [hiddenUntil], so a later extension still beats the earlier logical expiry
     * after a merge (design Decision 3).
     */
    @ProtoNumber(15)
    @Serializable(with = InstantSerializer::class)
    val hiddenUpdatedAt: Instant? = null,
) {
    val isDeleted: Boolean
        get() = deletedAt != null
}
