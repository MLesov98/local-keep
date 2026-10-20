package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Address
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.MatchStrategy
import com.localkeep.core.model.Note
import com.localkeep.core.model.PasswordHistory
import com.localkeep.core.model.PasswordHistoryEntry
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

@Serializable
internal data class BitwardenExport(
    val encrypted: Boolean = false,
    val folders: List<BitwardenFolder> = emptyList(),
    /**
     * Organisation collections. Absent from a personal-vault export, which carries `folders` only
     * — verified against Bitwarden's own import templates, 2026-09-13.
     */
    val collections: List<BitwardenCollection> = emptyList(),
    val items: List<BitwardenItem> = emptyList(),
)

@Serializable
internal data class BitwardenFolder(
    val id: String? = null,
    val name: String = "",
)

/**
 * One shared grouping in an organisation vault.
 *
 * Undeclared until 2026-09-13, so `ignoreUnknownKeys` dropped the whole array and every item in an
 * organisation export arrived with no record of which collections it had been in — the only filing
 * an org vault has, since org items are usually in no personal folder at all.
 *
 * Mapped to [VaultItem.tags], not to folders, and the reason is the shape of the data rather than a
 * preference. `BitwardenCipherExport.collectionIds` is an *array*: one item belongs to as many
 * collections as it was shared into, while `VaultItem.folderId` is a single nullable id. Folders
 * could therefore only ever record one membership and would have to silently pick a winner, which
 * is precisely the class of loss this importer keeps being fixed for. Tags are a `List<String>` and
 * hold all of them. The semantics agree too: a Bitwarden *folder* is the user's own private filing
 * and already maps to [Folder], so routing collections there as well would merge two different
 * things into one namespace and collide whenever a collection and a folder share a name.
 */
@Serializable
internal data class BitwardenCollection(
    val id: String? = null,
    val name: String = "",
)

@Serializable
internal data class BitwardenItem(
    val id: String? = null,
    /** The export's own opaque folder id, resolved through the map `parse` builds. */
    val folderId: String? = null,
    /**
     * The collections this item is shared into — see [BitwardenCollection]. Many, not one.
     *
     * The element type is nullable because the export writes a null *inside* the array for an item
     * that is in no collection: `"collectionIds": [null]` is what a personal-vault item looks like
     * in Bitwarden's own JSON export. A non-nullable `String` element made that array undecodable,
     * and since the failure is at the container level it took the whole file with it — see the
     * `coerceInputValues` note on [BitwardenJsonImporter]'s `json` for the rest of that defect.
     * Nulls are dropped where the ids are resolved, which `mapNotNull` already did.
     */
    val collectionIds: List<String?> = emptyList(),
    val type: Int = 1,
    val name: String = "",
    val notes: String? = null,
    val favorite: Boolean = false,
    /** ISO-8601 instants, e.g. `2023-04-11T09:15:32.170Z`. Absent from older exports. */
    val creationDate: String? = null,
    val revisionDate: String? = null,
    /** Bitwarden's own re-prompt flag: 0 = none, 1 = master password. Maps to `VaultItem.reprompt`. */
    val reprompt: Int = 0,
    val fields: List<BitwardenField> = emptyList(),
    val passwordHistory: List<BitwardenPasswordHistory> = emptyList(),
    val login: BitwardenLogin? = null,
    val card: BitwardenCard? = null,
    val identity: BitwardenIdentity? = null,
)

@Serializable
internal data class BitwardenField(
    val name: String? = null,
    val value: String? = null,
    /** 0 = text, 1 = hidden, 2 = boolean, 3 = linked. */
    val type: Int = 0,
)

@Serializable
internal data class BitwardenPasswordHistory(
    val lastUsedDate: String? = null,
    val password: String? = null,
)

@Serializable
internal data class BitwardenLoginUri(
    val match: Int? = null,
    val uri: String = "",
)

/**
 * A WebAuthn passkey attached to a Bitwarden login, declared with **no fields on purpose**.
 *
 * `ignoreUnknownKeys` therefore decodes one to an empty object: its `keyValue` — the credential's
 * private key — is never bound to anything this app holds, which is the right handling for a secret
 * that cannot be imported and must not be retained. What is read is only that the array is not
 * empty, which is the fact the user needs told.
 */
@Serializable
internal class BitwardenFido2Credential

@Serializable
internal data class BitwardenLogin(
    val username: String? = null,
    val password: String? = null,
    val totp: String? = null, // bare Base32 secret OR a full otpauth:// URI
    val uris: List<BitwardenLoginUri> = emptyList(),
    /**
     * Passkeys on this login. Undeclared until 2026-09-21, so `ignoreUnknownKeys` dropped them and
     * a login whose *only* credential was a passkey imported as an ordinary login with no password
     * and no sign that anything was missing — on the step before the wizard offers to delete the
     * export (1A-F8, 2026-09-20). Nothing about a passkey can be carried across; see
     * [BitwardenFido2Credential].
     */
    val fido2Credentials: List<BitwardenFido2Credential> = emptyList(),
) {
    /**
     * True when this login's whole credential is a passkey: no password, no two-factor seed.
     *
     * A login that has a password *and* a passkey still imports usably — the password is what this
     * app can hold — so only the case where nothing usable crosses is reported. `null` and blank
     * are the same thing here: Bitwarden writes `"password": null` for a login that has none.
     */
    val isPasskeyOnly: Boolean
        get() = fido2Credentials.isNotEmpty() && password.isNullOrBlank() && totp.isNullOrBlank()
}

@Serializable
internal data class BitwardenCard(
    val cardholderName: String? = null,
    val brand: String? = null,
    val number: String? = null,
    val expMonth: String? = null,
    val expYear: String? = null,
    val code: String? = null,
)

@Serializable
internal data class BitwardenIdentity(
    val title: String? = null,
    val firstName: String? = null,
    val middleName: String? = null,
    val lastName: String? = null,
    val address1: String? = null,
    val address2: String? = null,
    val address3: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val company: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val ssn: String? = null,
    val username: String? = null,
    val passportNumber: String? = null,
    val licenseNumber: String? = null,
)

object BitwardenJsonImporter : PasswordProtectedImporter {
    override val sourceFormat: SourceFormat = SourceFormat.BITWARDEN

    /**
     * `coerceInputValues` is load-bearing, not tidiness: Bitwarden writes JSON `null` — not `[]` —
     * for every empty list on an item, and without it a null lands on a non-nullable `List` and
     * fails the *whole file*. `"passwordHistory": null` and `"collectionIds": null` appear on
     * ordinary items in Bitwarden's own export, so an export of a personal vault with no password
     * history was answered with "this doesn't look like a valid Bitwarden export" — a claim about a
     * file that is exactly what Bitwarden had just written — and `detect` said no as well, so the
     * wizard could not even reach the message. Measured against the real exports in
     * `src/test/resources/realworld/bitwarden/`, four of which parsed only after this (WP11,
     * 2026-09-16). It coerces a null to the declared default and nothing else; a null on a field
     * with no default still fails, so it cannot quietly invent data.
     */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    override fun detect(bytes: ByteArray): Boolean = runCatching {
        val text = bytes.toString(Charsets.UTF_8)
        val export = json.decodeFromString(BitwardenExport.serializer(), text)
        // `encrypted` counts as recognition, exactly as Aegis's `header.slots != null` does below.
        // A password-protected Bitwarden export carries the flag and an empty `items`/`folders`, so
        // recognising it only by its contents rejected it here — and `parse`, which is the only
        // place that can raise ContainerEncrypted, was then never reached. The typed reason existed,
        // had a test, and could not occur: the wizard answered a password-protected export with the
        // generic "unrecognised format" instead of saying what was actually wrong with it.
        if (export.encrypted || export.items.isNotEmpty() || export.folders.isNotEmpty() ||
            export.collections.isNotEmpty()
        ) {
            return@runCatching true
        }
        // An export of an *empty* vault — `{"encrypted":false,"folders":[],"items":[]}` — makes all
        // three of those false, so the wizard discarded the user's explicit Bitwarden pick and
        // answered "check you exported the unencrypted format, and that the file didn't get
        // truncated": a claim about a file that is perfectly valid and simply has nothing in it,
        // when `parse` would have reported the accurate "contained no items" outcome (1-3,
        // 2026-09-13). Recognition therefore falls back to the keys being *present*, not populated.
        //
        // Decode success alone would be a rubber stamp: `ignoreUnknownKeys` decodes an Aegis export
        // into an all-defaults BitwardenExport just as happily, and this importer is tried before
        // Aegis in `ALL_IMPORTERS`. `items` and `folders` together are what no other format here
        // carries, and every Bitwarden JSON export has written both since the format existed.
        // `collections` stands in for `folders` because an organisation export writes that key and
        // not the other one; requiring `folders` alone would have left an empty org vault
        // unrecognised in exactly the way an empty personal vault was (1-3).
        val root = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching false
        root["items"] is JsonArray && (root["folders"] is JsonArray || root["collections"] is JsonArray)
    }.getOrDefault(false)

    /**
     * True only for the *password-protected* shape — see [BitwardenEncryptedExport] for why the
     * account-encrypted one is not a password question at all. An unencrypted export answers false
     * and never sees the password step.
     */
    override fun needsPassword(bytes: ByteArray): Boolean =
        BitwardenEncryptedExport.envelopeOf(bytes.toString(Charsets.UTF_8))?.passwordProtected == true

    /**
     * Opens a password-protected export with [password], then hands the plaintext to the ordinary
     * [parse] above — the mapping is identical, because the decrypted bytes *are* an unencrypted
     * Bitwarden export.
     *
     * [keyFile] is ignored: a Bitwarden export password is the whole credential, and the parameter
     * exists only because [PasswordProtectedImporter] is shared with KDBX, which has key files.
     *
     * The password is wiped in `finally`, unconditionally and on every path — the same contract
     * `KdbxImporter` holds, and the reason the wizard may hand its only copy over and forget it.
     */
    override fun parse(
        bytes: ByteArray,
        password: CharArray,
        titles: ImportDefaultTitles,
        keyFile: ByteArray?,
    ): Outcome<ParsedImport> {
        var plaintext: ByteArray? = null
        return try {
            val envelope = BitwardenEncryptedExport.envelopeOf(bytes.toString(Charsets.UTF_8))
            when {
                envelope == null -> parse(bytes, titles)
                // Account-encrypted: no password opens it, so one was never asked for and typing
                // one changes nothing. Falls through to the ordinary parse, which names it.
                !envelope.passwordProtected -> parse(bytes, titles)
                else -> {
                    plaintext = BitwardenEncryptedExport.decrypt(envelope, password)
                    parse(plaintext, titles)
                }
            }
        } catch (t: ImportRejection) {
            Outcome.Failure(AppError.Validation("Bitwarden import rejected (${t.reason})", t))
        } catch (t: Throwable) {
            // Never the library's message — see the catch-all in the password-free parse below.
            val reason = if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
                ImportFailureReason.ExportNeedsMoreMemory(SourceFormat.BITWARDEN)
            } else {
                ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN)
            }
            Outcome.Failure(AppError.Validation("failed to open Bitwarden export", ImportRejection(reason, t)))
        } finally {
            // The decrypted export is every password in the user's vault. It is wiped as soon as it
            // has been mapped, rather than left for the collector — the mapped items hold the same
            // secrets, but they are the ones the user asked for, and this copy is not.
            plaintext?.fill(0)
            password.fill('\u0000')
        }
    }

    // NOTE: uses manual try/catch (not runCatchingOutcome) so the specific failure reason (e.g.
    // ContainerEncrypted) reaches the caller — runCatchingOutcome discards the caught exception
    // entirely and always reports a generic ImportFailureReason.Unexpected-shaped failure.
    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> = try {
        val text = bytes.toString(Charsets.UTF_8)
        val export = try {
            json.decodeFromString(BitwardenExport.serializer(), text)
        } catch (t: Throwable) {
            throw ImportRejection(ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN), t)
        }
        if (export.encrypted) {
            // Bitwarden's two encrypted shapes need two different sentences, and until V3-02 they
            // shared one that named the wrong case. A password-protected file is opened by the
            // password step (`needsPassword` sends it there), so the only way it reaches here is a
            // caller that skipped that step — ContainerEncrypted, which at least says a password is
            // involved. An account-restricted file can never be opened at all, and that is its own
            // reason with its own remedy.
            throw ImportRejection(
                if (BitwardenEncryptedExport.envelopeOf(text)?.passwordProtected == true) {
                    ImportFailureReason.ContainerEncrypted(SourceFormat.BITWARDEN)
                } else {
                    ImportFailureReason.AccountEncrypted(SourceFormat.BITWARDEN)
                },
            )
        }
        // Stamped now, not with the export's date: Bitwarden's folder records carry no timestamp,
        // and `Folder.updatedAt` is the merge tie-break for a rename. A null would lose to any
        // existing copy of the same folder, which is the wrong default for something arriving fresh.
        val importedAt = Instant.now()
        val folders = mutableListOf<Folder>()
        val folderIdsByPath = mutableMapOf<String, UUID>()
        // Bitwarden ids are its own opaque strings, so every folder is minted a fresh LocalKeep
        // UUID and items are pointed at that. Named folders only: an export can carry a folder with
        // a blank name, and a folder the user cannot tell apart from "no folder" is not worth
        // creating in their vault.
        val folderIds = export.folders
            .filter { it.id != null && it.name.isNotBlank() }
            .mapNotNull { folder ->
                val leaf = folderTreeFor(folder.name, folders, folderIdsByPath, importedAt)
                leaf?.let { folder.id!! to it }
            }
            .toMap()

        // Collections are carried by name, not by minted id: they become tags, and a tag *is* its
        // name. An unnamed collection is skipped for the same reason an unnamed folder is — a label
        // the user cannot read is not one they can act on.
        val collectionNames = export.collections
            .filter { it.id != null && it.name.isNotBlank() }
            .associate { it.id!! to it.name.trim() }

        val items = mutableListOf<VaultItem>()
        val skipped = mutableListOf<SkippedEntry>()
        var passkeyOnly = 0
        for (item in export.items) {
            val mapped = mapItem(item, folderIds, collectionNames, titles)
            if (mapped != null) items += mapped else skipped += skipReason(item, titles)
            // Counted on the items that *did* cross, which is the point: the entry keeps its name,
            // its username and its URL, so it is not a skip and never appeared in `skipped` — it
            // simply arrived with nothing to sign in with.
            if (mapped != null && item.login?.isPasskeyOnly == true) passkeyOnly++
        }
        // Only folders something actually landed in. An export lists every folder the account had,
        // including empty ones and ones whose only items were types this build skips — carrying
        // those across would file nothing into them and leave the user tidying up after an import
        // they did not ask to reorganise anything.
        //
        // Ancestors are kept even when nothing sits in them directly: since nesting is read out of
        // the name, `Test` may hold no items of its own and still be the only thing that makes
        // `Test/Subfolder` reachable. Dropping it would leave a child pointing at a parent the
        // import never creates.
        val occupied = items.mapNotNull(VaultItem::folderId).toSet()
        val byId = folders.associateBy { it.id }
        val kept = buildSet {
            occupied.forEach { id ->
                var current: UUID? = id
                while (current != null && add(current)) current = byId[current]?.parentId
            }
        }
        Outcome.Success(
            ParsedImport(
                items = items,
                skipped = skipped,
                folders = folders.filter { it.id in kept },
                passkeyOnlyLoginCount = passkeyOnly,
            ),
        )
    } catch (t: ImportRejection) {
        Outcome.Failure(AppError.Validation("Bitwarden import rejected (${t.reason})", t))
    } catch (t: Throwable) {
        // Anything not thrown deliberately above: the message belongs to a library and may quote
        // the file's own bytes or a path, so it never reaches the UI. See ImportRejection.
        // An OutOfMemoryError is not a malformed file. It carries a message about an
        // allocation, matched nothing above, and fell through to "this doesn't look like a
        // valid export" — a claim about the user's file that the app had not earned
        // (V14, 2026-09-13). The whole cause chain is checked because the parser wraps what
        // it throws; same shape as KdbxImporter.kt:128.
        val reason = if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
            ImportFailureReason.ExportNeedsMoreMemory(SourceFormat.BITWARDEN)
        } else {
            ImportFailureReason.MalformedExport(SourceFormat.BITWARDEN)
        }
        Outcome.Failure(AppError.Validation("failed to parse Bitwarden export", ImportRejection(reason, t)))
    }

    /**
     * Bitwarden's URI match type, which was parsed off the wire and then dropped on the floor.
     *
     * Every imported URI took [UriMatch]'s DOMAIN default, so a login the user had deliberately
     * pinned to "Exact" — the setting people reach for when one host holds several accounts — came
     * across matching the whole domain, and autofill started offering it on pages it was told not
     * to. A "Never" URI is the same mistake in the direction that matters more: the one strategy
     * that exists to suppress a suggestion became the one that shows it everywhere.
     *
     * The integers are Bitwarden's `UriMatchType` in declaration order, which happens to line up
     * one-for-one with [MatchStrategy]'s own. Anything else — a null from an older export, a value
     * from a newer Bitwarden than this build knows — keeps the DOMAIN default rather than guessing,
     * because a wrong strategy silently changes where a password is offered.
     */
    private fun matchStrategy(match: Int?): MatchStrategy = when (match) {
        0 -> MatchStrategy.DOMAIN
        1 -> MatchStrategy.HOST
        2 -> MatchStrategy.STARTS_WITH
        3 -> MatchStrategy.EXACT
        4 -> MatchStrategy.REGEX
        5 -> MatchStrategy.NEVER
        else -> MatchStrategy.DOMAIN
    }

    /** The reason the wizard shows for an item this importer could not carry. */
    private fun skipReason(item: BitwardenItem, titles: ImportDefaultTitles): SkippedEntry = SkippedEntry(
        title = item.name.ifBlank { titles.untitledItem },
        reason = when (item.type) {
            1, 3, 4 -> SkipReason.IncompleteEntry
            5 -> SkipReason.SshKey
            else -> SkipReason.UnsupportedBitwardenType(item.type)
        },
    )

    /**
     * Bitwarden's per-item custom fields, which the decoder was throwing away.
     *
     * `ignoreUnknownKeys = true` meant an undeclared `fields` array simply vanished, taking the
     * hidden fields people keep recovery codes and security answers in with it — on an import that
     * reported full success.
     *
     * Bitwarden's type 3 is a *linked* field: it points at another field on the same item by an
     * integer id, which is not what [CustomFieldType.LINKED] means here (that carries a
     * `VaultItem.id`). Mapping it across as LINKED would produce a dangling reference, so it lands
     * as TEXT — the name survives, and nothing claims a link this vault cannot resolve.
     */
    private fun mapFields(fields: List<BitwardenField>): List<CustomField> = fields.mapNotNull { field ->
        val name = field.name.orEmpty()
        val value = field.value.orEmpty()
        if (name.isBlank() && value.isBlank()) return@mapNotNull null
        CustomField(
            name = name,
            value = value,
            type = when (field.type) {
                1 -> CustomFieldType.HIDDEN
                2 -> CustomFieldType.BOOLEAN
                else -> CustomFieldType.TEXT
            },
        )
    }

    /**
     * Bitwarden keeps password history without a bound, so an export can carry hundreds of live
     * secrets per item. [PasswordHistory.trim] cuts that to the newest few on the way in — an
     * import must not be a door around the app's own retention limit.
     *
     * [undatedAt] stands in for an entry whose `lastUsedDate` is absent, blank or unparseable —
     * the item's own creation date, which is the earliest anything on the item can have happened.
     * The date used to be the entry's only ticket in (`?: return@mapNotNull null`), so a history
     * entry carrying a real password but no valid date was dropped whole, and nothing recorded it:
     * `skipped` is appended only when [mapItem] returns null, which it does not here, so the item
     * still read as fully imported. The password is the part worth keeping; a stand-in date sorts
     * it oldest in [PasswordHistory.trim], which is the honest place for a date nobody knows, and
     * only costs it a slot when the item already carries [PasswordHistory.MAX_ENTRIES] dated ones.
     * This also matches [parseDate]'s own stated rule that an absent date never drops data.
     */
    private fun mapPasswordHistory(
        history: List<BitwardenPasswordHistory>,
        undatedAt: Instant,
    ): List<PasswordHistoryEntry> =
        PasswordHistory.trim(
            history.mapNotNull { entry ->
                val password = entry.password.orEmpty()
                if (password.isEmpty()) return@mapNotNull null
                PasswordHistoryEntry(
                    password = password,
                    changedAt = parseDate(entry.lastUsedDate) ?: undatedAt,
                )
            },
        )

    /**
     * The folder a Bitwarden folder record names, creating it and every ancestor it needs.
     *
     * Bitwarden has no parent field: nesting lives in the *name*, as `Test/Subfolder/AnotherSubFolder`.
     * Created verbatim, that arrives as top-level folders with slashes in them — the one importer
     * here that did not build a real tree, while [CsvColumnMapper.folderIdOf] and [KdbxImporter]
     * both do. This is the same splitting [CsvColumnMapper] does for a CSV folder *cell*, on the
     * separator Bitwarden writes.
     *
     * Keyed by the exact path, so `Test/Subfolder` and `Test/SubFolder` stay two folders: Bitwarden
     * treats them as distinct and so does this app's own folder tree, and quietly merging two
     * folders the user can see side by side would move items they did not ask to move. Two export
     * records with the *identical* path do collapse into one folder — a path is what identifies a
     * folder here, and there is nothing to tell those two apart.
     *
     * Depth is clamped to [FolderTree.MAX_DEPTH], matching [CsvColumnMapper]: a deeper export lands
     * near where it was rather than being refused. Returns null when every segment was blank, which
     * is a folder named only of separators and is not a folder.
     */
    /** How Bitwarden writes nesting inside a folder name. LastPass uses a backslash; this format is fixed. */
    private const val FOLDER_SEPARATOR = '/'

    private fun folderTreeFor(
        name: String,
        folders: MutableList<Folder>,
        idsByPath: MutableMap<String, UUID>,
        importedAt: Instant,
    ): UUID? {
        val segments = name.split(FOLDER_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(FolderTree.MAX_DEPTH)
        var parentId: UUID? = null
        val path = StringBuilder()
        for (segment in segments) {
            if (path.isNotEmpty()) path.append(FOLDER_SEPARATOR)
            path.append(segment)
            val here = parentId
            parentId = idsByPath.getOrPut(path.toString()) {
                UUID.randomUUID().also { folders += Folder(id = it, name = segment, parentId = here, updatedAt = importedAt) }
            }
        }
        return parentId
    }

    private fun mapItem(
        item: BitwardenItem,
        folderIds: Map<String, UUID>,
        collectionNames: Map<String, String>,
        titles: ImportDefaultTitles,
    ): VaultItem? {
        val now = Instant.now()
        // The export's own dates, as 1Password's importer already does. Stamping every item with
        // the import moment made a ten-year-old password look brand new, so the security dashboard
        // — whose "old passwords" check is a date comparison — reported nothing to fix on a vault
        // that had just arrived full of things to fix, and the merge tie-break (newest `updatedAt`
        // wins) had nothing to go on either.
        val revised = parseDate(item.revisionDate)
        // An item cannot have been created after it was last revised, so a missing creation date
        // falls back to the revision date before it falls back to now — otherwise an export that
        // carries only one of the two produced `createdAt` in the future of `updatedAt`, and both
        // the dashboard's age check and the merge tie-break read those.
        val created = parseDate(item.creationDate) ?: revised ?: now
        val updated = revised ?: created
        val content: ItemContent = when (item.type) {
            1 -> {
                val login = item.login ?: return null
                Login(
                    username = login.username.orEmpty(),
                    password = login.password.orEmpty(),
                    passwordHistory = mapPasswordHistory(item.passwordHistory, undatedAt = created),
                    // Trimmed, and a blank one dropped. The URI used to be stored exactly as the
                    // export wrote it, and `ItemEditViewModel.toContent()` trims the form's copy
                    // before matching it back against the stored one by exact string equality — so
                    // an imported URI carrying surrounding whitespace found no match on the first
                    // save from the editor and silently fell back to the DOMAIN default, losing the
                    // EXACT or NEVER the user had set in Bitwarden and changing where autofill
                    // offers the credential. Whitespace around a URL is never part of it
                    // (7-4 residual, 2026-09-13).
                    uris = login.uris.mapNotNull {
                        val uri = it.uri.trim()
                        if (uri.isEmpty()) null else UriMatch(uri = uri, strategy = matchStrategy(it.match))
                    },
                    totp = login.totp?.let(TotpFieldParser::parse),
                    notes = item.notes.orEmpty(),
                )
            }
            2 -> Note(body = item.notes.orEmpty())
            3 -> {
                val card = item.card ?: return null
                Card(
                    holder = card.cardholderName.orEmpty(),
                    number = card.number.orEmpty(),
                    brand = ImportedCardBrand.of(card.number, card.brand),
                    expMonth = card.expMonth?.toIntOrNull(),
                    expYear = card.expYear?.toIntOrNull(),
                    cvv = card.code.orEmpty(),
                    notes = item.notes.orEmpty(),
                )
            }
            4 -> {
                val identity = item.identity ?: return null
                Identity(
                    firstName = identity.firstName.orEmpty(),
                    lastName = identity.lastName.orEmpty(),
                    company = identity.company.orEmpty(),
                    ssn = identity.ssn.orEmpty(),
                    passport = identity.passportNumber.orEmpty(),
                    license = identity.licenseNumber.orEmpty(),
                    email = identity.email.orEmpty(),
                    phone = identity.phone.orEmpty(),
                    address = Address(
                        line1 = identity.address1.orEmpty(),
                        line2 = identity.address2.orEmpty(),
                        city = identity.city.orEmpty(),
                        state = identity.state.orEmpty(),
                        zip = identity.postalCode.orEmpty(),
                        country = identity.country.orEmpty(),
                    ),
                )
            }
            else -> return null // unknown/future Bitwarden item type — skip, don't crash
        }
        return VaultItem(
            id = UUID.randomUUID(),
            title = item.name.ifBlank { titles.importedItem },
            // Dropped until 2026-07-31, when `ParsedImport` grew the folder list that made
            // resolving it possible — before that, keeping Bitwarden's id here would have pointed
            // each item at a folder this vault does not have. `folderIds` maps the export's own
            // opaque ids onto the UUIDs minted in `parse`; an unnamed or unknown folder is absent
            // from it, so the item arrives filed nowhere rather than pointing at nothing.
            folderId = item.folderId?.let(folderIds::get),
            // Organisation collections, in the order the export listed them. Membership in several
            // is normal, which is why this is `tags` and not `folderId` — see BitwardenCollection.
            tags = item.collectionIds.mapNotNull { it?.let(collectionNames::get) }.distinct(),
            favorite = item.favorite,
            createdAt = created,
            updatedAt = updated,
            // Bitwarden's own re-prompt flag. Dropping it meant an item the user had deliberately
            // gated in Bitwarden arrived ungated: `reprompt` is live here too, read by
            // DatasetBuilder to decide whether an autofill suggestion needs re-authentication and
            // by ItemEditViewModel to decide whether revealing a secret does. Any non-zero value
            // means "ask", so a future Bitwarden prompt type still errs towards the gate.
            reprompt = item.reprompt != 0,
            customFields = mapFields(item.fields) + identityExtraFields(item) +
                identityNotesField(item, titles) + rescuedTotpField(item, titles),
            content = content,
        )
    }

    /**
     * A `login.totp` [TotpFieldParser] refuses is the user's second factor, and the export is
     * often its only remaining copy. `totp = login.totp?.let(TotpFieldParser::parse)` turned such
     * a value into null and the item was still built and still counted as fully imported —
     * `skipped` is only appended when [mapItem] returns null, which it does not here, so nothing
     * anywhere recorded the loss. The parser's null is correct (a `steam://` token, an
     * `otpauth://hotp/...` URI, `digits=7`, a non-decodable Base32 secret all describe codes this
     * app can never render), so carry the raw text across as a HIDDEN custom field instead of
     * dropping it — the same rescue CsvColumnMapper and KdbxImporter already perform.
     *
     * Lives outside the `when` in [mapItem] because `customFields` is assembled after it, where
     * the login is no longer in scope. [mapFields] reads `item.fields` only and never `login.totp`,
     * so there is nothing here to duplicate.
     */
    private fun rescuedTotpField(item: BitwardenItem, titles: ImportDefaultTitles): List<CustomField> {
        if (item.type != 1) return emptyList()
        val raw = item.login?.totp?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (TotpFieldParser.parse(raw) != null) return emptyList()
        return listOf(CustomField(name = titles.rescuedTotpFieldName, value = raw, type = CustomFieldType.HIDDEN))
    }

    /**
     * The identity values [Identity] has no field for — title, middle name, a third address line
     * and username — as plain-text custom fields named by Bitwarden's own keys.
     *
     * They were simply not read, so "Mrs", "A" and "myusername" from `plain-export.json` vanished
     * while the item still counted as fully imported (device report C3 finding 3, 2026-10-04). A
     * custom field named by the source is how every importer here carries a value with no model slot
     * (`DashlaneRow.remaining`, Enpass's `username`): the name is the source's word, not authored
     * copy, and the value stays on the item it belongs to where the user can see, copy or move it.
     * Bitwarden masks none of the four, so neither does this.
     */
    private fun identityExtraFields(item: BitwardenItem): List<CustomField> {
        if (item.type != 4) return emptyList()
        val identity = item.identity ?: return emptyList()
        return listOf(
            "title" to identity.title,
            "middleName" to identity.middleName,
            "address3" to identity.address3,
            "username" to identity.username,
        ).mapNotNull { (name, value) ->
            value?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { CustomField(name = name, value = it, type = CustomFieldType.TEXT) }
        }
    }

    /**
     * [Identity] has no notes field of its own, unlike types 1-3 above, which all carry
     * `item.notes` directly — so an Identity item's free-text notes would otherwise be silently
     * discarded while the wizard still counted the item as fully carried.
     */
    private fun identityNotesField(item: BitwardenItem, titles: ImportDefaultTitles): List<CustomField> {
        if (item.type != 4) return emptyList()
        val notes = item.notes?.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(CustomField(name = titles.identityNotesFieldName, value = notes, type = CustomFieldType.TEXT))
    }

    /** Lenient on purpose: an unparseable or absent date is not a reason to drop the item. */
    private fun parseDate(raw: String?): Instant? =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
