package com.localkeep.core.model

import com.localkeep.core.model.serializers.InstantSerializer
import java.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/** Entire decrypted vault payload. Timestamps stay inside encrypted bytes. */
@Serializable
data class Vault(
    @ProtoNumber(1)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @ProtoNumber(2) val items: List<VaultItem> = emptyList(),
    @ProtoNumber(3) val folders: List<Folder> = emptyList(),
    @ProtoNumber(4)
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant,
    @ProtoNumber(5)
    @Serializable(with = InstantSerializer::class)
    val modifiedAt: Instant,
    /**
     * The stored proof for the vault-wide reveal phrase, or null if the user has never hidden
     * anything (the phrase is created by the first hide, in the same commit — schema Decision 4).
     * Field 6, added in schema 5. Holds no phrase and no key; see [RevealPhraseVerifier].
     */
    @ProtoNumber(6) val revealPhrase: RevealPhraseVerifier? = null,
) {
    companion object {
        /**
         * Schema history:
         *  - 1: initial.
         *  - 2: [Folder.updatedAt] added (field 4), so a folder rename can be ordered during a
         *       merge. Purely additive — a schema-1 vault decodes as a schema-2 one with every
         *       folder's `updatedAt` null, which is exactly what "renamed at an unknown time,
         *       treat as oldest" should mean. No data is rewritten or moved.
         *  - 3: [Folder.parentUpdatedAt] added (field 5), so a folder *move* can be ordered
         *       separately from a rename now that [Folder.parentId] is a real hierarchy rather
         *       than a field nothing read. Additive on the same terms as 2: absent → null →
         *       "older than any known timestamp", and no migration runs.
         *  - 4: folders can be trashed. [Folder.deletedAt], [Folder.trashUpdatedAt] and
         *       [Folder.trashRootId] added (fields 6-8), plus [VaultItem.trashedWithFolderId]
         *       (field 13) so a folder restore knows which items came down with it. Additive on
         *       the same terms as 2 and 3 — every new field is nullable and absent means "live".
         *
         *       Unlike 2 and 3, this is the first bump where the version stamp has to be *written*
         *       rather than merely declared. Until now nothing raised it: [schemaVersion] was a
         *       constructor default that every mutation copied through, so a schema-3 vault stayed
         *       stamped 3 forever. That was harmless while every field an old build did not know
         *       about was also one it did not need. It stops being harmless here: a payload
         *       carrying a trashed folder but stamped 3 passes an old build's version gate, loses
         *       fields 6-8 and 13 to protobuf, and decodes the folder as live — and the next save
         *       writes that truncation back. [VaultSerializer.encode] therefore stamps every save
         *       with this constant, which turns that silent revert into the refusal the gate was
         *       built to give.
         *  - 5: items and folders can be hidden for a lease. [VaultItem.hiddenUntil] /
         *       [VaultItem.hiddenUpdatedAt] (fields 14/15), [Folder.hiddenUntil] /
         *       [Folder.hiddenUpdatedAt] (fields 9/10) and [Vault.revealPhrase] (field 6) added.
         *       Additive on the same terms as 2-4: every new field is nullable and absent means
         *       "not hidden / no phrase", so a schema-4 vault decodes as a schema-5 one with all of
         *       them null. Written for the same reason 4 is — a payload carrying a hidden lease but
         *       stamped 4 would pass an old build's gate, lose fields 14/15 (etc.) to protobuf, and
         *       be saved back as visible. [VaultSerializer.encode] stamps this version so that
         *       silent reveal becomes the downgrade refusal the gate was built to give.
         *  - 6: [Coordinate] added as an [ItemContent] subtype. Additive for existing items —
         *       no schema-5 vault contains one, and every field an old build knows about is
         *       untouched — but *not* additive on the terms 2-5 were, and the difference is the
         *       reason this bump exists.
         *
         *       Those bumps added optional fields to types both builds already knew. This adds a
         *       polymorphic *case*. An older build's decoder has no `coordinate` entry in its
         *       sealed-subtype table, so it does not silently drop the item the way protobuf drops
         *       an unknown field — it fails mid-decode, on the whole payload, with a serialization
         *       error naming a serial name that means nothing to the user. [VaultSerializer.decode]
         *       therefore reads the version stamp *before* the polymorphic decode runs, so the
         *       refusal the user meets is "update the app" rather than a decoder exception.
         *  - 7: [SeedPhrase] added as an [ItemContent] subtype (serial name `seedPhrase`). A new
         *       polymorphic case, exactly like 6 and bumped for exactly the same reason: an older
         *       build has no `seedPhrase` entry in its sealed-subtype table and fails the whole
         *       payload mid-decode rather than dropping the item, so the version stamp must move to
         *       turn that decoder exception into the "update the app" refusal the gate gives. No
         *       migration runs — no schema-6 vault contains one, and every field an old build knows
         *       about is untouched.
         *  - 8: [File.notes] added (field 8), so a stored file can carry the user's own prose the
         *       way a [Login] and a [Card] already do.
         *
         *       Additive in shape — absent decodes as `""`, which is exactly "no note was ever
         *       written" — so a schema-7 vault opens on this build unchanged and no migration
         *       runs. The bump exists for the reason 4 and 5 exist rather than for the reason 6
         *       and 7 do: nothing here is a new polymorphic case, so an older build would not fail
         *       the decode. It would do the quieter thing — drop field 8 the way protobuf drops
         *       any unknown field, show the file with no note, and write that truncation back on
         *       the next save, losing prose a person typed with no error anywhere. Stamping 8 is
         *       what turns that silent loss into the downgrade refusal the version gate gives.
         */
        const val CURRENT_SCHEMA_VERSION: Int = 8

        /**
         * The oldest schema this build can still read. Every version from here to
         * [CURRENT_SCHEMA_VERSION] must decode without loss.
         */
        const val MIN_SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}
