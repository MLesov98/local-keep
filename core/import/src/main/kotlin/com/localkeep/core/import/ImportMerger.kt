package com.localkeep.core.import

import com.localkeep.core.common.UrlHosts
import com.localkeep.core.model.Card
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemMerge
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.SeedPhrase
import com.localkeep.core.model.Totp
import com.localkeep.core.model.VaultItem
import java.security.MessageDigest

/**
 * Merges freshly-imported items into the vault's existing (non-trashed) items.
 * Identity for dedup purposes is best-effort url+username for a `Login` (spec §8.4). Every other
 * type used to have no identity and was always appended, which meant importing the same export
 * twice doubled every card, identity, note, 2FA code and seed phrase in it (found on a device
 * 2026-09-14, on a Bitwarden export whose logins merged cleanly while its five other items came in
 * again). They now carry the natural identity their content has — see [identityKeysOf] — and only
 * coordinates and passkeys remain always-additive.
 *
 * Files joined them on 2026-09-17 (V3-03): a `.1pux` is the only format here carrying file bytes, so
 * re-importing one duplicated the largest items in the vault while everything around them merged.
 * Coordinates and passkeys stay additive deliberately — two readings of one place are two facts, and
 * a passkey's credential id is minted per registration, so neither has an identity two imports of
 * the same export would agree on.
 */
object ImportMerger {

    /** How the user wants the selected items applied (UI/UX spec §6.3 "merge-vs-add"). */
    enum class Mode {
        /** Collapse url+username collisions, newest wins. The safe default. */
        MERGE,

        /** Append everything as-is, duplicates included — for deliberately keeping both copies. */
        ADD_ALL,
    }

    /** What would happen to one imported item, for the preview. */
    enum class Disposition {
        /** Nothing in the vault matches; it will be added. */
        NEW,

        /** Matches an existing item and is newer — merging would update the vault copy. */
        UPDATES_EXISTING,

        /**
         * Matches an existing item the merge would keep — merging fills in what the vault copy
         * lacks and discards the rest of this one.
         *
         * Two ways to get here, and the preview has to tell them apart: the vault copy is genuinely
         * newer, or the import carries no dates at all ([ParsedImport.timestampsKnown] false) and
         * therefore cannot claim to be. See `rowReason` in the wizard.
         */
        ALREADY_CURRENT,
    }

    data class PlannedItem(
        val item: VaultItem,
        val disposition: Disposition,
        /** Title of the item it collides with, so the preview can name it. */
        val collidesWith: String? = null,
        /**
         * True when the collision is with something already in the vault, false when it is with an
         * earlier row of the same export.
         *
         * Both were counted as one number and described with one sentence — "N already looks like an
         * entry you have" — so an export containing the same login twice told the user their vault
         * held something it did not. Measured 2026-09-11 with a Chrome CSV carrying two identical
         * rows against a vault with no such entry. Meaningless for [Disposition.NEW].
         */
        val collidesWithVault: Boolean = false,
    )

    /**
     * Classifies each imported item against the vault *without* changing anything, so the preview
     * can tell the user what an import would actually do before they commit to it.
     *
     * Uses exactly the same identity rule as [merge] — and the same [timestampsKnown] — because the
     * preview would be a lie otherwise.
     */
    fun plan(
        existing: List<VaultItem>,
        imported: List<VaultItem>,
        timestampsKnown: Boolean = true,
    ): List<PlannedItem> {
        // Mirrors [merge]'s own bookkeeping exactly: a mutable list of slots plus key->index, so a
        // slot that an earlier imported row already won is the thing the next row is compared
        // against. This used to be a plain key->item map written only when an item was classified
        // NEW, which never advanced past the *first* row of a collision chain — so with three rows
        // sharing one identity arriving Jan1, Jan5, Jan3, the third was compared against Jan1 and
        // previewed as "will update", while merge() compared it against the Jan5 slot and
        // discarded it. The vault content was right either way (merge is a running max), but the
        // preview named an outcome that never happened.
        val slots = existing.toMutableList()
        val indexByIdentity = mutableMapOf<String, Int>()
        slots.forEachIndexed { idx, item ->
            identityKeysOf(item).forEach { indexByIdentity.putIfAbsent(it, idx) }
        }

        return imported.map { item ->
            val keys = identityKeysOf(item)
            if (keys.isEmpty()) return@map PlannedItem(item, Disposition.NEW)
            val collisionIdx = keys.firstNotNullOfOrNull { indexByIdentity[it] }
            if (collisionIdx == null) {
                val idx = slots.size
                keys.forEach { indexByIdentity.putIfAbsent(it, idx) }
                slots.add(item)
                PlannedItem(item, Disposition.NEW)
            } else {
                val collision = slots[collisionIdx]
                // An import whose timestamps were fabricated at the import instant cannot win on
                // them: see [ParsedImport.timestampsKnown]. Every collision from such a source is
                // ALREADY_CURRENT, which is also what the row was already previewed as when it
                // repeated an *earlier row of the same export* — those share one `now` and so were
                // never "after" each other.
                val wins = timestampsKnown && item.updatedAt.isAfter(collision.updatedAt)
                // merge() replaces the slot with the newer of the two (ItemMerge keeps the
                // winner's updatedAt), so advance the running winner here the same way.
                if (wins) slots[collisionIdx] = item
                keys.forEach { indexByIdentity.putIfAbsent(it, collisionIdx) }
                val disposition = if (wins) Disposition.UPDATES_EXISTING else Disposition.ALREADY_CURRENT
                // `slots` opens as the vault's own items and grows as imported rows claim new
                // identities, so the index alone says which side this row collided with.
                PlannedItem(
                    item,
                    disposition,
                    collidesWith = collision.title,
                    collidesWithVault = collisionIdx < existing.size,
                )
            }
        }
    }

    /**
     * Applies only the items the user kept selected, in the chosen [mode].
     *
     * [Mode.ADD_ALL] deliberately bypasses dedup: the user has been shown which items collide and
     * has asked for both copies anyway, and silently dropping one would contradict the preview
     * they just approved.
     */
    fun apply(
        existing: List<VaultItem>,
        selected: List<VaultItem>,
        mode: Mode,
        timestampsKnown: Boolean = true,
    ): List<VaultItem> = when (mode) {
        Mode.MERGE -> merge(existing, selected, timestampsKnown)
        Mode.ADD_ALL -> existing + selected
    }

    /**
     * @param existing the vault's current non-trashed items (e.g. `vaultRepository.getAllItems()`).
     * @param imported items freshly parsed from a source file.
     * @param timestampsKnown [ParsedImport.timestampsKnown] — false when the source format carries
     *        no per-entry times and every imported item is stamped with the import instant. Such an
     *        item can never win a collision, however new it looks.
     * @return the full item list to persist: [existing] items (with url+username collisions
     *         replaced by whichever of the two has the newer `updatedAt`) plus every imported
     *         item that didn't collide with anything.
     */
    fun merge(
        existing: List<VaultItem>,
        imported: List<VaultItem>,
        timestampsKnown: Boolean = true,
    ): List<VaultItem> {
        val result = existing.toMutableList()
        val indexByIdentity = mutableMapOf<String, Int>()
        result.forEachIndexed { idx, item ->
            identityKeysOf(item).forEach { indexByIdentity.putIfAbsent(it, idx) }
        }

        for (item in imported) {
            val keys = identityKeysOf(item)
            if (keys.isEmpty()) {
                result.add(item) // no url+username identity — always additive, never deduped
                continue
            }
            val existingIdx = keys.firstNotNullOfOrNull { indexByIdentity[it] }
            if (existingIdx == null) {
                val idx = result.size
                keys.forEach { indexByIdentity.putIfAbsent(it, idx) }
                result.add(item)
            } else {
                val existingItem = result[existingIdx]
                // See [plan]: a fabricated `updatedAt` is not evidence of anything, so an undated
                // source never takes the slot.
                val importWins = timestampsKnown && item.updatedAt.isAfter(existingItem.updatedAt)
                val winner = if (importWins) item else existingItem
                val loser = if (importWins) existingItem else item
                // newest updatedAt wins, but never clobber a password the vault item changed
                // more recently than the import touched its own (see ItemMerge) — and never let
                // "wins" mean "replaces with blanks". An imported item usually has no attachments,
                // custom fields, tags or TOTP seed to carry, because most import formats have
                // nowhere to put them; winning outright therefore deleted all of those off an item
                // the user still had, and the id change orphaned the attachment blobs into the next
                // GC sweep. `preserveStructure` keeps the loser's copy wherever the winner has none.
                //
                // `existing` is passed explicitly rather than left to its default. Folder is the
                // field that made it necessary: Bitwarden exports *do* carry folders, so the
                // "winner has nothing, take the loser's" rule never fired for them and an import
                // re-filed items the user had deliberately filed. The vault's copy has to be named
                // for `preserveStructure` to prefer it, and `loser` is only the vault's copy while
                // the import happens to be newer.
                //
                // `preserveNewerPassword` is skipped entirely when the source carries no dates, and
                // its own rules are the reason: it falls back to `loser.updatedAt` when neither
                // side has a `passwordChangedAt`, and for an undated import that fallback is the
                // import instant — always newer than any real vault date. Run here it would hand
                // the import's password to a vault item that just won the collision, undoing the
                // whole point of this branch. There is nothing for it to rescue either: the loser
                // is the import, and an import can never prove its password is fresher (no
                // importer sets `passwordChangedAt`).
                result[existingIdx] = ItemMerge.preserveStructure(
                    winner = if (timestampsKnown) {
                        ItemMerge.preserveNewerPassword(winner = winner, loser = loser)
                    } else {
                        winner
                    },
                    loser = loser,
                    existing = existingItem,
                )
                // the winner may carry a host the loser didn't (or vice versa) — register every
                // key of the imported item so a later import matching only the new host still
                // collides with this same slot instead of appending a second copy.
                keys.forEach { indexByIdentity.putIfAbsent(it, existingIdx) }
            }
        }
        return result
    }

    /**
     * Every host+username key a Login answers to, via the same [UrlHosts.normalise] the duplicate
     * detector compares on. Empty (no identity) for every other content type.
     *
     * This used to be a single key off the *first* uri only, and the copy still had the bug the
     * detector's version was written to fix: when `URI.host` came back null — which it does for any
     * host containing an underscore — it fell back to the *entire raw URL*, path and query included.
     * So `https://my_bank.example.com/login` and `https://my_bank.example.com` were two different
     * identities and never merged, and the user finished an import holding two copies of one
     * account, which the security dashboard then correctly reported as a duplicate.
     *
     * [DuplicateDetector] matches on ANY shared host, not just the first, and a login with no URI at
     * all falls back to its title rather than username alone. Returning every key (instead of one)
     * lets the caller treat a match against any of them as a collision, mirroring that relation; a
     * blank username has no identity, matching the detector's "skipped entirely" rule.
     */
    private fun identityKeysOf(item: VaultItem): List<String> = when (val content = item.content) {
        is Login -> loginKeys(item, content)
        is Card -> card(content)
        is Identity -> identity(item, content)
        is Note -> note(item, content)
        is Totp -> totp(content)
        is SeedPhrase -> seedPhrase(content)
        is File -> file(content)
        else -> emptyList()
    }

    private fun loginKeys(item: VaultItem, login: Login): List<String> {
        val username = login.username.trim().lowercase()
        if (username.isEmpty()) return emptyList()
        val hosts = login.uris.map { UrlHosts.normalise(it.uri) }.filter { it.isNotEmpty() }.distinct()
        if (hosts.isNotEmpty()) return hosts.map { "$it\u0000$username" }
        val title = item.title.trim().lowercase()
        if (title.isEmpty()) return emptyList()
        return listOf("title:$title\u0000$username")
    }

    /** The PAN, digits only. Fewer than eight is not a card number, just a placeholder. */
    private fun card(card: Card): List<String> {
        val digits = card.number.filter { it.isDigit() }
        return if (digits.length < 8) emptyList() else listOf("card:$digits")
    }

    /**
     * Title plus the strongest document number present. Two documents that share a title and a
     * passport number are the same passport; without any number, title plus name is the best the
     * content offers, and a blank identity carries nothing.
     */
    private fun identity(item: VaultItem, identity: Identity): List<String> {
        val title = item.title.trim().lowercase()
        if (title.isEmpty()) return emptyList()
        val number = listOf(identity.passport, identity.license, identity.ssn, identity.email)
            .map { it.trim().lowercase() }
            .firstOrNull { it.isNotEmpty() }
        val name = (identity.firstName.trim() + " " + identity.lastName.trim()).trim().lowercase()
        val discriminator = number ?: name.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf("identity:$title\u0000$discriminator")
    }

    /** Exact contents: a note that differs by one character is a different note. */
    private fun note(item: VaultItem, note: Note): List<String> {
        if (note.body.isBlank()) return emptyList()
        return listOf("note:${item.title.trim().lowercase()}\u0000${sha256(note.body)}")
    }

    /**
     * The seed plus the account it is for. The seed alone would be the stronger key, but an
     * authenticator export can legitimately hold one seed under several accounts (test fixtures
     * do, and so does anyone who enrolled one device twice), and collapsing distinct accounts is
     * worse than keeping a duplicate. The issuer is left out: it is a label the user retypes.
     */
    private fun totp(totp: Totp): List<String> {
        val secret = totp.secretBase32.filter { !it.isWhitespace() && it != '=' }.uppercase()
        if (secret.isEmpty()) return emptyList()
        return listOf("totp:$secret\u0000${totp.account.trim().lowercase()}")
    }

    /**
     * Filename, exact byte count and MIME type — the identity a stored file still has once it is in
     * the vault.
     *
     * Attachments are the largest items a vault holds, so they were the duplicate that cost most,
     * and a `.1pux` re-import added every one of them again (V3-03, 2026-09-17): the only source
     * with files in it was also the one type left without a key here.
     *
     * Deliberately **not** the source's own document id, even though a `.1pux` carries one. A key is
     * useful only when both sides can compute it, and [com.localkeep.core.model.File] has nowhere to
     * keep a foreign id — so the vault's copy, written by the first import, would answer to no key
     * at all and the second import would still append. Giving it one is a vault-format change with a
     * version bump and a migration, and it would buy nothing the content already provides.
     *
     * `blobRef` is left out for the mirror-image reason: it is minted per import (the wizard seals
     * the bytes and fills it), so it differs between two imports of the same file and is empty
     * during the preview. Size must be real — a zero-length file is no evidence of anything — and a
     * blank filename carries nothing to match on.
     */
    private fun file(file: File): List<String> {
        val filename = file.filename.trim().lowercase()
        if (filename.isEmpty() || file.sizeBytes <= 0L) return emptyList()
        return listOf("file:$filename\u0000${file.sizeBytes}\u0000${file.mime.trim().lowercase()}")
    }

    private fun seedPhrase(phrase: SeedPhrase): List<String> {
        if (phrase.words.isEmpty()) return emptyList()
        val words = phrase.words.joinToString(" ") { it.trim().lowercase() }
        return listOf("seed:${sha256(words + "\u0000" + phrase.passphrase)}")
    }

    /** Keys live only in memory for the length of one merge; hashing keeps a note body out of them. */
    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
