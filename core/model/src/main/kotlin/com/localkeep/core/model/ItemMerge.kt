package com.localkeep.core.model

import java.time.Instant

/**
 * Merge helpers shared by every import/restore path (`:core:import` `ImportMerger`,
 * `:feature:backup` `VaultImporter`) so the "who wins" rules live in one place.
 */
object ItemMerge {

    /**
     * The hidden (lease, stamp) pair to keep when the same item id appears on both sides of a merge,
     * ordered on [VaultItem.hiddenUpdatedAt] exactly as [FolderTree.mergeFolder] orders its three
     * folder pairs and [isNewerFolder] orders a rename:
     *
     *  - incoming stamp null → keep [existing]. An undated hide/unhide could be arbitrarily old, and
     *    treating it as current would let an ancient export reveal an item the user just hid (or
     *    re-hide one they just revealed).
     *  - existing stamp null, incoming dated → take incoming. A known time beats an unknown one.
     *  - equal stamps → keep [existing]; a merge should not churn on a tie.
     *
     * The *foreign-format* importers (CSV, KDBX, Bitwarden and the rest) never set the hidden fields,
     * so their incoming stamp is null and this preserves the destination's concealment — which is why
     * [preserveStructure] routes through it rather than letting a blank-sided import overwrite a hide.
     *
     * A LocalKeep-to-LocalKeep backup merge is the exception, and the KDoc used to say "every import
     * path", which is what let a real defect hide behind it: a schema-5 export carries both fields, so
     * a vault-to-vault merge has a genuinely dated incoming lease and must reconcile it. See
     * `VaultImporter.mergeInto`, where that reconciliation sat inside the overall-recency branch and
     * therefore never ran on a tie.
     */
    fun mergeHiddenPair(existing: VaultItem, incoming: VaultItem): Pair<Instant?, Instant?> {
        val incomingStamp = incoming.hiddenUpdatedAt ?: return existing.hiddenUntil to existing.hiddenUpdatedAt
        val existingStamp = existing.hiddenUpdatedAt ?: return incoming.hiddenUntil to incomingStamp
        return if (incomingStamp.isAfter(existingStamp)) {
            incoming.hiddenUntil to incomingStamp
        } else {
            existing.hiddenUntil to existingStamp
        }
    }

    /**
     * [winner] is the item chosen to win a dedup collision (newer `updatedAt`). If BOTH sides are
     * [Login]s and the [loser]'s password is actually *fresher* (by [Login.passwordChangedAt],
     * falling back to the item's `updatedAt` when the field is absent), keep the loser's password —
     * and its change-time + history — on the returned item, taking everything else from [winner].
     *
     * Rationale: `updatedAt` is bumped by any edit (title, tag, folder move), so a stale export
     * whose metadata was touched more recently than the vault item would otherwise overwrite a
     * password the user changed in-vault. This preserves the genuinely newer secret while still
     * accepting the winner's newer non-secret fields.
     *
     * One asymmetry is deliberate, from the 2026-08-06 sweep's 4-1: the winner's fallback to
     * `updatedAt` is trusted only when the winner has no `passwordChangedAt` AND the loser has none
     * either. Undated import formats (CSV, Aegis — and KDBX before it gained real times) stamp
     * `updatedAt` with the *import instant*, so falling back to it would let a years-old export
     * beat a password the user changed in-vault yesterday: the fabricated "now" is always newer
     * than any real change date, and the guard never fires. When the loser proves its password is
     * fresher by its own `passwordChangedAt`, the vault's secret wins even if the winner carries a
     * newer-looking `updatedAt` — no importer carries a password-specific change time, so an import
     * without `passwordChangedAt` can never prove its password is newer. When both sides are
     * undated, the `updatedAt` fallback compares real recency and "newest wins" applies as before.
     */
    fun preserveNewerPassword(winner: VaultItem, loser: VaultItem): VaultItem {
        val w = winner.content
        val l = loser.content
        if (w !is Login || l !is Login) return winner
        val winnerPwAt = w.passwordChangedAt
        val loserPwAtReal = l.passwordChangedAt
        // The vault item's real change time is the only password evidence either side can carry:
        // importers never set `passwordChangedAt`, so a winner without it cannot claim a fresher
        // password, however new its `updatedAt` looks.
        if (winnerPwAt == null && loserPwAtReal != null) {
            return winner.copy(
                content = w.copy(
                    password = l.password,
                    passwordChangedAt = loserPwAtReal,
                    passwordHistory = l.passwordHistory,
                ),
            )
        }
        val winnerPwAtEffective = winnerPwAt ?: winner.updatedAt
        val loserPwAt = loserPwAtReal ?: loser.updatedAt
        if (!loserPwAt.isAfter(winnerPwAtEffective)) return winner
        return winner.copy(
            content = w.copy(
                password = l.password,
                passwordChangedAt = loserPwAt,
                passwordHistory = l.passwordHistory,
            ),
        )
    }

    /**
     * Keeps everything on [loser] that [winner] structurally *cannot* carry.
     *
     * [preserveNewerPassword] protects the secret; this protects everything around it. An item
     * produced by an importer is built from a CSV row or a KDBX entry, so it always arrives with a
     * fresh random id, no attachments, no custom fields, no tags, no folder, and `favorite` and
     * `reprompt` at their defaults. Letting such an item win a collision outright therefore did not
     * merge anything — it replaced a vault item the user still had with a blank-sided copy of
     * itself, and because the id changed too, `BlobGarbageCollector` then deleted the orphaned
     * attachment blobs on the next unlock. Permanently, for files that only ever existed there.
     *
     * The rule is deliberately one-directional: a field is taken from [loser] only where [winner]
     * has nothing. A winner that carries its own tags or attachments keeps them.
     *
     * **Folder and notes are the exception, and [existing] is why.** The paragraph above says an
     * imported item "always arrives with ... no folder", and for CSV, KDBX and Aegis that is true —
     * but Bitwarden exports carry `folders[]`, and `BitwardenJsonImporter` maps them. So a colliding
     * Bitwarden item *does* have a `folderId`, the one-directional rule never fires, and until
     * 2026-09-11 an import silently re-filed an item the user had put somewhere on purpose. Notes
     * went the same way: `winner.notes.ifBlank { loser.notes }` replaced prose the user had written
     * whenever the import carried any, which is the only field here that loses something a person
     * typed. FEATURES.md tells the user the opposite — "an existing item keeps its attachments, tags
     * and folder" — so those two now read from the vault's side regardless of which side is newer.
     *
     * [existing] is the vault's own copy. It defaults to [loser] because that is the usual case —
     * the import is normally the newer side — but it must be passed explicitly whenever the caller
     * knows, because when the *vault* item is newer it is the winner and [loser] is the import.
     *
     * The tradeoff, stated: this cannot distinguish "the import has no tags because CSV has no
     * column for them" from "the user deliberately removed the tags before exporting". It resolves
     * that ambiguity toward keeping data, which is the right way round for a password manager whose
     * attachments have no other copy.
     */
    fun preserveStructure(
        winner: VaultItem,
        loser: VaultItem,
        existing: VaultItem = loser,
    ): VaultItem = winner.copy(
        // The id first: attachment blobs are reachable only through it, and a changed id orphans
        // them into the next garbage-collection sweep. It is the *vault's* id, named through
        // [existing] — this read `loser.id` until 2026-09-14, which was the vault's id only while
        // the import won; when the vault copy was the newer side the merged item took the
        // import's freshly minted id, so a re-import of an older export silently re-identified
        // every item it collided with.
        id = existing.id,
        // The item existed from when the user first made it, not from when they re-imported it.
        createdAt = minOf(winner.createdAt, loser.createdAt),
        // The vault's filing, not the export's — see the note on [existing] above.
        folderId = existing.folderId ?: winner.folderId ?: loser.folderId,
        tags = winner.tags.ifEmpty { loser.tags },
        favorite = winner.favorite || loser.favorite,
        // Never downgrade a security flag the user set by hand: no importer sets `reprompt`, so a
        // false here always means "could not carry it", never "the user turned it off".
        reprompt = winner.reprompt || loser.reprompt,
        customFields = winner.customFields.ifEmpty { loser.customFields },
        attachments = winner.attachments.ifEmpty { loser.attachments },
        content = preserveContent(winner.content, loser.content, existing.content),
    ).let { merged ->
        // Concealment is not a field an importer can carry, so the vault side's hide is preserved
        // unless the incoming item genuinely proves a newer hide/unhide by its own stamp. Without
        // this a re-import would silently reveal every hidden item it collided with.
        // `existing`, not `loser`. These were the same thing while every caller passed the import
        // as the winner; they are not when the vault item is the newer side, and naming the vault
        // copy explicitly is what makes that case right rather than accidentally right.
        val incoming = if (existing === winner) loser else winner
        val (until, stamp) = mergeHiddenPair(existing = existing, incoming = incoming)
        merged.copy(hiddenUntil = until, hiddenUpdatedAt = stamp)
    }

    /** The per-type half of [preserveStructure]: fields inside [ItemContent] that imports can drop. */
    private fun preserveContent(
        winner: ItemContent,
        loser: ItemContent,
        existing: ItemContent,
    ): ItemContent =
        if (winner is Login && loser is Login) {
            winner.copy(
                // The one direction in which an undated import may supply a password: the vault's
                // copy has none at all. A blank password is not a password the user chose over the
                // import's — it is an item they saved without one (a note-like login, an entry
                // created from autofill before the credential was typed), and the import is the
                // only side that has the secret. Filling it in is therefore never a loss.
                //
                // Guarded on `existing` — the vault's own copy — rather than on `loser`, for the
                // same reason the folder and notes rules above are: `loser` is the vault's side
                // only while the import is the newer one. When the vault item wins, `winner` IS
                // `existing`, its blank password is what this reads, and the import's password
                // arrives from `loser`. A non-blank vault password falls straight through to
                // `winner.password` and is never replaced, which is the whole of the asymmetry:
                // see [preserveNewerPassword] for who owns a genuine password-vs-password contest.
                password = winner.password.ifBlank {
                    if ((existing as? Login)?.password.isNullOrBlank()) loser.password else winner.password
                },
                // A TOTP seed is the second factor. A CSV import carries none, and losing it locks
                // the user out of the very account the import was meant to bring across.
                totp = winner.totp ?: loser.totp,
                // Unioned rather than replaced: most import formats carry at most one URI (a CSV row
                // has one URL column, a KDBX entry one URL string — Bitwarden's `uris` array is the
                // exception), so a vault item holding [A, B]
                // colliding with an import carrying [A] would otherwise lose B — a URL the user
                // still had, silently, changing where autofill offers the credential. Extra URIs
                // are additive and never conflicting, so keeping both is always right; the one-
                // directional rule below still applies to fields where a winner value could mean
                // a deliberate removal.
                uris = (winner.uris + loser.uris).distinct(),
                androidPackages = (winner.androidPackages + loser.androidPackages).distinct(),
                // The vault's note wins outright. An import carrying a note is not evidence the
                // user wanted theirs replaced — see the note on [preserveStructure.existing].
                notes = (existing as? Login)?.notes?.ifBlank { winner.notes.ifBlank { loser.notes } }
                    ?: winner.notes.ifBlank { loser.notes },
            )
        } else if (winner is File && loser is File) {
            winner.copy(
                // The vault's note wins outright, exactly as a Login's does above and for the same
                // reason: it is the only field here a person typed by hand, and an import carrying
                // one is not evidence they wanted theirs replaced. Added with the field itself
                // (schema 8) rather than after a re-import had already eaten someone's prose.
                notes = (existing as? File)?.notes?.ifBlank { winner.notes.ifBlank { loser.notes } }
                    ?: winner.notes.ifBlank { loser.notes },
            )
        } else {
            winner
        }

    /**
     * Whether [incoming] should replace [existing] when the same folder id appears on both sides of
     * a merge — i.e. whether it carries a strictly more recent rename.
     *
     * [Folder.updatedAt] is nullable because every folder written before schema 2 has no timestamp,
     * and the null cases are the whole point of this function rather than an afterthought:
     *
     *  - incoming null → never wins. An undated folder could be arbitrarily old, and treating it as
     *    current would let an ancient export silently rename a folder the user just renamed.
     *  - existing null, incoming dated → incoming wins. A known time beats an unknown one.
     *  - both null → keeps [existing], reproducing the old union-of-id behaviour exactly, so
     *    merging two pre-schema-2 vaults behaves as it always did.
     *  - equal timestamps → keeps [existing]; a merge should not churn on a tie.
     */
    fun isNewerFolder(incoming: Folder, existing: Folder): Boolean {
        val incomingAt = incoming.updatedAt ?: return false
        val existingAt = existing.updatedAt ?: return true
        return incomingAt.isAfter(existingAt)
    }
}
