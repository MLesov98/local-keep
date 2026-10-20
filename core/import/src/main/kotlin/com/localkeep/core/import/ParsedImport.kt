package com.localkeep.core.import

import com.localkeep.core.model.Folder
import com.localkeep.core.model.VaultItem
import java.util.UUID

/**
 * What an importer got out of a source file: the entries it could map, and an account of the ones
 * it could not.
 *
 * The second half is the reason this type exists at all. Every importer already dropped entries it
 * had no mapping for — a 1Password Bank Account, a Bitwarden type this build predates, an Aegis
 * HOTP code — and returned a bare list, so the count the wizard showed was the *post-drop* count.
 * An import that lost 30% of the source file was indistinguishable from a complete one, and the
 * wizard's last step then offers to securely delete the export: the only copy of what was dropped
 * went with it.
 */
data class ParsedImport(
    val items: List<VaultItem>,
    /** One entry per source record that did not become a vault item. */
    val skipped: List<SkippedEntry> = emptyList(),
    /**
     * Folders the source organised its entries into, already carrying LocalKeep ids that the
     * returned [items] point at through `VaultItem.folderId`.
     *
     * The contract carried items and nothing else until 2026-07-31, which is why every importer
     * dropped folder membership on the floor: an item pointing at a folder the vault does not have
     * is a dangling reference, so dropping it was the honest option while there was nowhere to put
     * the folder itself. This is the "contract grows a folder list" the Bitwarden importer's own
     * comment said the fix would need.
     *
     * Empty from every importer that has no notion of folders, which is all of them but Bitwarden.
     * The wizard reconciles these against the folders the vault already has — see its `applyImport`
     * — so an importer may mint ids freely here without worrying about collisions or duplicates.
     */
    val folders: List<Folder> = emptyList(),
    /**
     * Whether the `createdAt`/`updatedAt` on [items] were read out of the source file, or fabricated
     * at the import instant because the format carries no per-entry times.
     *
     * True for Bitwarden, 1Password and KDBX, which all write real timestamps. False for CSV and
     * Aegis, whose rows have nowhere to put one — every item they produce is stamped
     * `Instant.now()`, which is newer than anything in the vault by construction.
     *
     * `ImportMerger` reads this rather than guessing, because "newest `updatedAt` wins" is only a
     * merge rule while the dates mean something. With a fabricated one it was a rule that the
     * import always won: re-importing an unchanged CSV export silently reverted every rename and
     * edit the user had made in the vault since the first import, and the preview told them the
     * import was "newer than" the entry it was about to overwrite (S1, 2026-09-15).
     */
    val timestampsKnown: Boolean = true,
    /**
     * File bytes an export carried alongside its records, keyed by the [items] entry they belong to.
     *
     * They are not on the [VaultItem] itself because a `File` or an `Attachment` is identified by a
     * `blobRef` into the encrypted blob store, and `core/import` has no vault key and no store to
     * write one into — it is a pure-Kotlin leaf module. So the importer carries the plaintext bytes
     * and the wizard's apply step does the sealing, through the same `FileBlobStore.encryptToBlob`
     * the item editor uses. An item whose payload is not written therefore has an empty `blobRef`,
     * which is why the wizard drops the item rather than saving a reference to nothing.
     *
     * Empty from every importer but [OnePuxImporter]: a `.1pux` is the only supported format that is
     * an archive rather than one flat file, and so the only one with anywhere to put a file.
     */
    val attachmentPayloads: Map<UUID, List<ImportedFile>> = emptyMap(),
    /**
     * Source column headers this importer had no field for and carried across as custom fields
     * instead, in the order the file wrote them.
     *
     * The counterpart of [skipped], one axis over: that one accounts for whole records that did not
     * cross, this one for *columns* that crossed under a different shape. A CSV column the mapper
     * did not recognise used to be dropped with no value, no field and no count, so an import that
     * lost a `memo` column read exactly like a complete one — and the wizard's last step then
     * offers to delete the export (1B-F1, 2026-09-20).
     *
     * Only columns that actually carried something on at least one row: a header present but empty
     * throughout kept nothing, so there is nothing to report. Empty from every importer but
     * [CsvImporter] — the others read a named schema, where an unknown field is an unknown field
     * rather than a column.
     */
    val carriedColumns: List<String> = emptyList(),
    /**
     * Entries whose two-factor seed could not become a live code, and was kept as a hidden custom
     * field instead.
     *
     * The rescue itself is old; saying so is not. A seed that `TotpFieldParser` refuses — a HOTP
     * URI, a Base32 string with a stray trailing bit — was preserved on the item and reported
     * nowhere, so an import that quietly downgraded a working second factor into a field the user
     * has to act on read as a complete success (1B-F5, 2026-09-20). The Aegis path already has
     * exactly this vocabulary in [skipped]; this is the same fact for entries that did cross over.
     */
    val unusableTotpCount: Int = 0,
    /**
     * Items that still carry a KeePass field reference — `{REF:U@I:…}` — as the literal text of a
     * field, because nothing in the database answers it.
     *
     * KeePass lets one entry borrow a field from another rather than copy it, and [KdbxImporter]
     * substitutes those it can follow. What is left is a token where a username or a password
     * should be: unusable, and indistinguishable from a normal item in the preview until this was
     * counted (1A-F7, 2026-09-20). Zero from every importer but KDBX — the reference syntax is
     * KeePass's.
     */
    val unresolvedFieldReferenceCount: Int = 0,
    /**
     * Bitwarden logins whose only credential was a passkey, which no export format this app reads
     * can carry across.
     *
     * `login.fido2Credentials` is a WebAuthn private key bound to Bitwarden's own store; there is
     * nothing to import and the caveat on the source step says so. But such an entry still *becomes
     * an item* — it keeps its name, its username and its URL — so the preview showed an ordinary
     * login with no password and said nothing, on the step before the one that offers to delete the
     * export (1A-F8, 2026-09-20). Zero from every other importer.
     */
    val passkeyOnlyLoginCount: Int = 0,
)

/**
 * One file read out of an export archive, still in plaintext.
 *
 * [bytes] is the whole file in memory, which is what bounds how large an import can be — it is read
 * under the same decompression cap as the export's own records, never streamed straight to disk,
 * because an untrusted archive that expands past the cap must be refused before anything is written.
 */
data class ImportedFile(
    val filename: String,
    val mime: String,
    val bytes: ByteArray,
) {
    /**
     * Identity, not content. A data class over a `ByteArray` would otherwise compare by reference in
     * `equals` and hash by reference in `hashCode` — the pair of defects the compiler warns about —
     * and nothing here needs value semantics over a file's bytes.
     */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    /** Never the bytes, and never a length that hints at the contents of a user's file. */
    override fun toString(): String = "ImportedFile(filename=$filename, mime=$mime)"
}

/**
 * A source record an importer could not carry across.
 *
 * [title] is whatever the source called it, so the user can find it again in their export; [reason]
 * is what the preview groups by ("Bank Account", "HOTP code") — a typed [SkipReason], not a label:
 * see that type's kdoc for why this module cannot author the label text itself.
 */
data class SkippedEntry(val title: String, val reason: SkipReason)
