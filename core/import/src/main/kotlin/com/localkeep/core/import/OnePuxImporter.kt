package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Address
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.File
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.PasswordHistory
import com.localkeep.core.model.PasswordHistoryEntry
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

@Serializable
internal data class OnePuxExport(
    val accounts: List<OnePuxAccount> = emptyList(),
)

@Serializable
internal data class OnePuxAccount(
    val vaults: List<OnePuxVault> = emptyList(),
)

@Serializable
internal data class OnePuxVault(
    val items: List<OnePuxItem> = emptyList(),
)

@Serializable
internal data class OnePuxItem(
    val favIndex: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val state: String = "active",
    val categoryUuid: String = "",
    val overview: OnePuxOverview = OnePuxOverview(),
    val details: OnePuxDetails = OnePuxDetails(),
)

@Serializable
internal data class OnePuxOverview(
    val title: String = "",
    val url: String = "",
    val urls: List<OnePuxUrl> = emptyList(),
    /**
     * 1Password's own tag list for the item. Undeclared until 2026-09-13, so `ignoreUnknownKeys`
     * dropped it before `mapItem` could see it — and `VaultItem.tags` is a real, wired field this
     * importer simply never set, so the user's whole filing scheme arrived empty with nothing said
     * about it (4-4). Every other importer here still drops tags; only this one carries them.
     */
    val tags: List<String> = emptyList(),
)

@Serializable
internal data class OnePuxUrl(
    val url: String = "",
)

@Serializable
internal data class OnePuxDetails(
    val loginFields: List<OnePuxLoginField> = emptyList(),
    val notesPlain: String = "",
    val password: String = "",
    val sections: List<OnePuxSection> = emptyList(),
    /**
     * Previous passwords the user changed away from. Undeclared until 2026-09-13 and therefore
     * silently discarded, while [BitwardenJsonImporter] had already carried the equivalent for its
     * own format since item 3-5 — so 1Password was the one importer left behind on an
     * otherwise-fixed defect class (4-4).
     */
    val passwordHistory: List<OnePuxPasswordHistory> = emptyList(),
    /**
     * The file a Document item (category `006`) *is*. Present on that category and no other, and
     * undeclared until 2026-09-17, which is why the archive's `files/` directory was never read:
     * the only reference into it lives here.
     */
    val documentAttributes: OnePuxDocumentAttributes? = null,
)

/**
 * The `files/` entry a Document item points at.
 *
 * [documentId] is the key, not [fileName]: the archive names the entry `files/<documentId>` or
 * `files/<documentId>__<fileName>` depending on the 1Password version that wrote it, and only the
 * id is stable across both. Two documents may share a filename; they never share an id.
 */
@Serializable
internal data class OnePuxDocumentAttributes(
    val fileName: String = "",
    val documentId: String = "",
    /** The file's size as the export states it. Not trusted for allocation — the cap is. */
    val decryptedSize: Long = 0L,
)

/**
 * One entry of [OnePuxDetails.passwordHistory]: the old secret and when it stopped being used.
 *
 * Both key names are checked against 1Password's published .1pux description rather than inferred
 * from an export (2026-09-13): it defines a history entry as `value` — "the non-current password" —
 * and `time` — "a Unix timestamp of when this password became the non-current password". The sweep
 * flagged the `time` key as an assumption that, if wrong, would silently date every history entry
 * to the item's creation instead; it is not an assumption.
 */
@Serializable
internal data class OnePuxPasswordHistory(
    val value: String = "",
    /** Epoch **seconds**, like [OnePuxItem.createdAt]; 0 when the export did not record one. */
    val time: Long = 0L,
)

@Serializable
internal data class OnePuxLoginField(
    val value: String = "",
    val id: String = "",
    val designation: String = "",
)

@Serializable
internal data class OnePuxSection(
    val fields: List<OnePuxSectionField> = emptyList(),
)

@Serializable
internal data class OnePuxSectionField(
    val id: String = "",
    /** 1Password's display label for the field; `id` is the stable key the mappings below use. */
    val title: String = "",
    val value: JsonObject = JsonObject(emptyMap()),
)

object OnePuxImporter : Importer {
    override val sourceFormat: SourceFormat = SourceFormat.ONEPUX

    // Hard cap on the decompressed export.data entry — a decompression-bomb guard for untrusted
    // .1pux files. 256 MB is far beyond any real password export yet bounds worst-case memory.
    private const val MAX_EXPORT_DATA_BYTES = 256L * 1024 * 1024

    /** The archive directory 1Password writes item files into. */
    private const val FILES_PREFIX = "files/"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Recognises the archive without reading it.
     *
     * Deliberately only asks "is this a zip that names itself a 1Password export" — the entry names
     * come from the central directory and cost nothing to walk. Doing the full capped read here
     * instead made both of this importer's own failure reasons unreachable: an archive with no
     * `export.data` returned null, and one whose entry decompressed past the cap threw, and either
     * way `runCatching` turned it into `false`, so `importerFor` never selected this importer and
     * `parse` — the only place `OnePuxMissingExportData` and `ExportTooLarge` are raised — never
     * ran. The wizard answered both with "unrecognised format".
     *
     * Accepting a `.1pux` whose entry later proves too large is the right trade: that file *is* a
     * 1Password export, and the honest answer is that it is too big, not that it is unknown.
     *
     * 2026-10-02: and now it really does read the central directory. It walked `ZipInputStream`,
     * whose `nextEntry` inflates the whole current entry to reach the next one, so "cost nothing"
     * was false: a small archive whose first entry was a highly compressible decoy inflated tens of
     * gigabytes, on the main thread, before a single name was compared.
     */
    override fun detect(bytes: ByteArray): Boolean = runCatching {
        centralDirectoryNames(bytes)?.any { it == "export.data" } == true
    }.getOrDefault(false)

    /**
     * The entry names in [bytes]' zip central directory, read without inflating anything, or null
     * when no end-of-central-directory record is found (not a zip, or a zip64 this does not walk).
     */
    private fun centralDirectoryNames(bytes: ByteArray): List<String>? {
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        // The end record is 22 bytes plus a comment of at most 64 KiB, so it is searched for backwards.
        val eocd = (bytes.size - EOCD_SIZE downTo maxOf(0, bytes.size - EOCD_SIZE - 0xFFFF))
            .firstOrNull { u32(it) == EOCD_SIGNATURE } ?: return null
        val count = u16(eocd + 10)
        var at = u32(eocd + 16)
        val names = ArrayList<String>(count)
        repeat(count) {
            if (at < 0 || at + CDH_SIZE > bytes.size || u32(at.toInt()) != CDH_SIGNATURE) return null
            val header = at.toInt()
            val nameLength = u16(header + 28)
            val next = header.toLong() + CDH_SIZE + nameLength + u16(header + 30) + u16(header + 32)
            if (header + CDH_SIZE + nameLength > bytes.size) return null
            names += String(bytes, header + CDH_SIZE, nameLength, Charsets.UTF_8)
            at = next
        }
        return names
    }

    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val EOCD_SIZE = 22
    private const val CDH_SIGNATURE = 0x02014b50L
    private const val CDH_SIZE = 46

    // NOTE: uses manual try/catch (not runCatchingOutcome) so the specific failure reason
    // reaches the caller — see the same note in BitwardenJsonImporter.
    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> = try {
        val exportDataJson = readExportDataEntry(bytes)
            ?: throw ImportRejection(ImportFailureReason.OnePuxMissingExportData)
        val export = try {
            json.decodeFromString(OnePuxExport.serializer(), exportDataJson)
        } catch (t: Throwable) {
            throw ImportRejection(ImportFailureReason.MalformedExport(SourceFormat.ONEPUX), t)
        }
        val items = mutableListOf<VaultItem>()
        val skipped = mutableListOf<SkippedEntry>()
        val payloads = mutableMapOf<UUID, List<ImportedFile>>()
        // Read once, up front, and only for the ids the records actually name — an archive may hold
        // entries nothing points at (this corpus's own `1PasswordExport.1pux` carries a vault
        // *avatar* in `files/`), and decompressing those would spend the cap on bytes that can
        // never reach the vault.
        val wanted = export.accounts
            .flatMap { it.vaults }
            .flatMap { it.items }
            .filter { it.state == "active" }
            .mapNotNull { it.details.documentAttributes?.documentId?.takeIf(String::isNotBlank) }
            .toSet()
        val files = if (wanted.isEmpty()) emptyMap() else readFileEntries(bytes, wanted)
        export.accounts
            .flatMap { it.vaults }
            .flatMap { it.items }
            .forEach { item ->
                if (item.state != "active") {
                    // Archived items are the user's own data in the Archive vault, and every other
                    // importer names what it cannot carry. Filtering them out before the skipped
                    // list was built made the wizard's "not imported" tally silent about them —
                    // and the wizard then offered to delete the export, destroying the only local
                    // copy the user was never told held anything.
                    skipped += SkippedEntry(
                        title = item.overview.title.ifBlank { titles.untitledItem },
                        reason = SkipReason.OnePasswordArchived,
                    )
                    return@forEach
                }
                val document = item.details.documentAttributes
                val fileBytes = document?.documentId?.let(files::get)
                val mapped = mapItem(item, titles, fileBytes)
                if (mapped != null) {
                    items += mapped
                    if (document != null && fileBytes != null) {
                        val name = document.fileName.ifBlank { titles.untitledItem }
                        payloads[mapped.id] = listOf(
                            ImportedFile(filename = name, mime = mimeForFilename(name), bytes = fileBytes),
                        )
                    }
                } else {
                    skipped += SkippedEntry(
                        title = item.overview.title.ifBlank { titles.untitledItem },
                        reason = SkipReason.UnsupportedOnePasswordCategory(item.categoryUuid),
                    )
                }
            }
        Outcome.Success(ParsedImport(items = items, skipped = skipped, attachmentPayloads = payloads))
    } catch (t: ImportRejection) {
        Outcome.Failure(AppError.Validation("1Password import rejected (${t.reason})", t))
    } catch (t: Throwable) {
        // See ImportRejection: a library's message about an untrusted file is not shippable copy.
        // An OutOfMemoryError is not a malformed file. It carries a message about an
        // allocation, matched nothing above, and fell through to "this doesn't look like a
        // valid export" — a claim about the user's file that the app had not earned
        // (V14, 2026-09-13). The whole cause chain is checked because the parser wraps what
        // it throws; same shape as KdbxImporter.kt:128.
        val reason = if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
            ImportFailureReason.ExportNeedsMoreMemory(SourceFormat.ONEPUX)
        } else {
            ImportFailureReason.MalformedExport(SourceFormat.ONEPUX)
        }
        Outcome.Failure(AppError.Validation("failed to parse .1pux export", ImportRejection(reason, t)))
    }

    /** Opens the .1pux zip in-memory and returns export.data's UTF-8 text, or null if absent. */
    private fun readExportDataEntry(bytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var skipped = 0L
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "export.data") return readCapped(zip).toString(Charsets.UTF_8)
                skipped = skipCapped(zip, skipped)
                entry = zip.nextEntry
            }
        }
        return null
    }

    /**
     * Drains the current entry without keeping it, and returns [skippedSoFar] plus what it inflated to.
     *
     * 2026-10-02: `nextEntry` drains an unread entry itself, uncapped — only the entries this importer
     * *kept* went through [readCapped], so a decoy entry ahead of the real ones inflated without limit,
     * on a worker that Back cannot interrupt. Every byte inflated and thrown away now counts too.
     */
    private fun skipCapped(zip: ZipInputStream, skippedSoFar: Long, limit: Long = MAX_EXPORT_DATA_BYTES): Long {
        val buf = ByteArray(64 * 1024)
        var total = skippedSoFar
        while (true) {
            val n = zip.read(buf)
            if (n < 0) return total
            total += n
            if (total > limit) {
                throw ImportRejection(
                    ImportFailureReason.ExportTooLarge(MAX_EXPORT_DATA_BYTES / (1024 * 1024)),
                )
            }
        }
    }

    /**
     * Reads a single zip entry into memory with a hard size cap. `.1pux` files are UNTRUSTED, so a
     * naive `readBytes()` on a decompression bomb (a highly compressible multi-GB entry) would OOM
     * the process. Anything larger than [MAX_EXPORT_DATA_BYTES] throws (surfaced as Outcome.Failure
     * by the caller's try/catch) instead of being decompressed.
     */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_EXPORT_DATA_BYTES) {
                throw ImportRejection(
                    ImportFailureReason.ExportTooLarge(MAX_EXPORT_DATA_BYTES / (1024 * 1024)),
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * The `files/` entries [wanted] names, by document id.
     *
     * The entry is `files/<documentId>` or `files/<documentId>__<fileName>` — both shapes are in
     * the wild — so the id is matched against the entry name's leading segment rather than against
     * the whole of it.
     *
     * Every entry read goes through [readCapped], and the *running total* is capped as well: a
     * hundred entries each just under the limit is the same decompression bomb as one entry over
     * it, and capping only per entry would have left the second door open while closing the first.
     */
    private fun readFileEntries(bytes: ByteArray, wanted: Set<String>): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        var total = 0L
        var skipped = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val id = if (!entry.isDirectory && name.startsWith(FILES_PREFIX)) {
                    val leaf = name.substring(FILES_PREFIX.length)
                    wanted.firstOrNull { leaf == it || leaf.startsWith("$it.") || leaf.startsWith("${it}__") }
                        ?.takeIf { it !in out }
                } else {
                    null
                }
                if (id != null) {
                    val read = readCapped(zip)
                    total += read.size
                    if (total > MAX_EXPORT_DATA_BYTES) {
                        throw ImportRejection(
                            ImportFailureReason.ExportTooLarge(MAX_EXPORT_DATA_BYTES / (1024 * 1024)),
                        )
                    }
                    out[id] = read
                } else {
                    // Twice the cap: `export.data` itself is skipped here, and the read before this
                    // one has already held it to MAX_EXPORT_DATA_BYTES.
                    skipped = skipCapped(zip, skipped, limit = 2 * MAX_EXPORT_DATA_BYTES)
                }
                entry = zip.nextEntry
            }
        }
        return out
    }

    /**
     * The media type for an imported file, from its extension and nothing else.
     *
     * A `.1pux` states no type for the files it carries, and sniffing the bytes of an untrusted
     * archive to decide what to call them is the wrong trade for a viewer that only needs to know
     * whether it can show a picture. Anything unrecognised is `application/octet-stream`, which the
     * viewer treats as "offer it, do not render it" — the safe reading for a file this app cannot
     * vouch for.
     */
    private fun mimeForFilename(filename: String): String =
        when (filename.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }

    /**
     * [documentBytes] is the archive entry this item's `documentAttributes` names, when the archive
     * actually held it. Null means there is no file — including for the loose `export.data`
     * fragments in the corpus, which carry the record and not the archive — and a Document item
     * with no file is skipped rather than imported as an empty one pointing at nothing.
     */
    private fun mapItem(
        item: OnePuxItem,
        titles: ImportDefaultTitles,
        documentBytes: ByteArray? = null,
    ): VaultItem? {
        val created = if (item.createdAt > 0) Instant.ofEpochSecond(item.createdAt) else Instant.now()
        val updated = if (item.updatedAt > 0) Instant.ofEpochSecond(item.updatedAt) else created
        val fieldsById = item.details.sections.flatMap { it.fields }.associateBy { it.id }

        val content: ItemContent = when (item.categoryUuid) {
            "001" -> { // Login
                val username = item.details.loginFields
                    .find { it.designation == "username" || it.id == "username" }?.value.orEmpty()
                val passwordField = item.details.loginFields
                    .find { it.designation == "password" || it.id == "password" }?.value
                val password = passwordField?.takeIf { it.isNotBlank() } ?: item.details.password
                val uris = (listOfNotNull(item.overview.url.takeIf { it.isNotBlank() }) + item.overview.urls.map { it.url })
                    .distinct()
                    .map { UriMatch(uri = it) }
                Login(
                    username = username,
                    password = password,
                    passwordHistory = mapPasswordHistory(item.details.passwordHistory, undatedAt = created),
                    uris = uris,
                    totp = findTotp(item.details),
                    notes = item.details.notesPlain,
                )
            }
            "006" -> { // Document — the one category whose content IS a file
                val document = item.details.documentAttributes
                // `blobRef` is deliberately left empty: sealing the bytes needs the vault key and a
                // blob store, neither of which exists in this module. The wizard fills it from
                // `ParsedImport.attachmentPayloads` and drops the item if that write fails, so an
                // item pointing at a blob that was never written cannot reach the vault.
                if (document == null || documentBytes == null) return null
                val name = document.fileName.ifBlank { item.overview.title.ifBlank { titles.importedItem } }
                File(
                    filename = name,
                    mime = mimeForFilename(name),
                    // The bytes actually read, not `decryptedSize` — that number comes out of the
                    // untrusted archive and is shown to the user beside the file.
                    sizeBytes = documentBytes.size.toLong(),
                )
            }
            "003" -> Note(body = item.details.notesPlain) // Secure Note
            "004" -> Identity( // best-effort from labeled sections — see design decision #2
                firstName = fieldsById["firstname"].textValue().orEmpty(),
                lastName = fieldsById["lastname"].textValue().orEmpty(),
                company = fieldsById["company"].textValue().orEmpty(),
                email = fieldsById["email"].textValue().orEmpty(),
                phone = fieldsById["defphone"].textValue().orEmpty(),
                // 1Password writes the address as one `address` object, not five fields; the
                // separate city/state/zip/country ids are kept as a fallback for exports that do.
                address = fieldsById["address"].addressValue() ?: Address(
                    line1 = fieldsById["address"].textValue().orEmpty(),
                    city = fieldsById["city"].textValue().orEmpty(),
                    state = fieldsById["state"].textValue().orEmpty(),
                    zip = fieldsById["zip"].textValue().orEmpty(),
                    country = ImportedCountry.of(fieldsById["country"].textValue().orEmpty()),
                ),
            )
            "002" -> { // Credit Card
                val expiry = fieldsById["expiry"].monthYearValue()
                val number = fieldsById["ccnum"].textValue().orEmpty()
                Card(
                    holder = fieldsById["cardholder"].textValue().orEmpty(),
                    number = number,
                    brand = ImportedCardBrand.of(number, fieldsById["type"].textValue()),
                    expMonth = expiry?.second,
                    expYear = expiry?.first,
                    cvv = fieldsById["cvv"].textValue().orEmpty(),
                    pin = fieldsById["pin"].textValue().orEmpty(),
                    notes = item.details.notesPlain,
                )
            }
            else -> return null // unsupported category (Software License, Bank Account, ...) — skip
        }

        return VaultItem(
            id = UUID.randomUUID(),
            title = item.overview.title.ifBlank { titles.importedItem },
            // Blanks dropped: an empty tag is an empty chip in the vault list, which reads as a bug
            // rather than as a tag the export happened to write empty.
            tags = item.overview.tags.filter { it.isNotBlank() },
            favorite = item.favIndex != 0,
            createdAt = created,
            updatedAt = updated,
            customFields = customFields(item, consumedBy(item.categoryUuid)) + identityNotesField(item, titles) +
                rescuedTotpField(item, titles),
            content = content,
        )
    }

    /**
     * 1Password's per-item password history, bounded on the way in.
     *
     * Deliberately the same shape as [BitwardenJsonImporter]'s own `mapPasswordHistory`, for the
     * same two reasons stated there: an export can carry far more live secrets per item than this
     * app retains, so [PasswordHistory.trim] applies the app's own limit rather than letting an
     * import walk around it; and a missing or zero `time` never drops the entry — the password is
     * the part worth keeping, and [undatedAt] (the item's own creation date, the earliest anything
     * on it can have happened) sorts it oldest instead (4-4, 2026-09-13).
     */
    private fun mapPasswordHistory(
        history: List<OnePuxPasswordHistory>,
        undatedAt: Instant,
    ): List<PasswordHistoryEntry> =
        PasswordHistory.trim(
            history.mapNotNull { entry ->
                if (entry.value.isEmpty()) return@mapNotNull null
                PasswordHistoryEntry(
                    password = entry.value,
                    changedAt = if (entry.time > 0) Instant.ofEpochSecond(entry.time) else undatedAt,
                )
            },
        )

    /**
     * A one-time-password section field [TotpFieldParser] refuses, carried across rather than lost.
     *
     * The last importer in this module still missing the rescue its three siblings have. [findTotp]
     * turned a refused seed into null and [customFields] then dropped every `totp`-union field
     * unconditionally, so the item was built, counted as fully imported, and carried no trace of
     * the second factor — and the wizard's last step then offers to delete the export. The parser's
     * null is correct (`digits=7`, an `otpauth://hotp/…` URI, a seed `Base32.decode` throws on all
     * describe codes this app can never render); what was wrong was treating "cannot render it" as
     * "need not keep it". Found against the real `1PasswordExport.1pux`, whose one-time-password
     * fields hold seeds of exactly that kind (WP11, 2026-09-16).
     *
     * Only the *first* such field is rescued, matching [findTotp]'s own `firstNotNullOfOrNull`, so
     * the two cannot disagree about which field is the item's TOTP.
     */
    private fun rescuedTotpField(
        item: OnePuxItem,
        titles: ImportDefaultTitles,
    ): List<com.localkeep.core.model.CustomField> {
        val raw = item.details.sections.flatMap { it.fields }
            .firstNotNullOfOrNull { field -> field.value.text("totp") }
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        if (TotpFieldParser.parse(raw) != null) return emptyList()
        return listOf(
            com.localkeep.core.model.CustomField(
                name = titles.rescuedTotpFieldName,
                value = raw,
                type = CustomFieldType.HIDDEN,
            ),
        )
    }

    /**
     * [Identity] has no notes field of its own, unlike Login/Note/Card above, which all carry
     * `notesPlain` directly — so an Identity item's free-text notes would otherwise be silently
     * discarded while the wizard still counted the item as fully carried.
     */
    private fun identityNotesField(item: OnePuxItem, titles: ImportDefaultTitles): List<com.localkeep.core.model.CustomField> {
        if (item.categoryUuid != "004") return emptyList()
        val notes = item.details.notesPlain.takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(
            com.localkeep.core.model.CustomField(
                name = titles.identityNotesFieldName,
                value = notes,
                type = CustomFieldType.TEXT,
            ),
        )
    }

    /**
     * The section-field ids each category mapping already reads, so they are not carried a second
     * time as a custom field.
     */
    private fun consumedBy(categoryUuid: String): Set<String> = when (categoryUuid) {
        "002" -> setOf("cardholder", "ccnum", "type", "cvv", "pin", "expiry")
        "004" -> setOf(
            "firstname", "lastname", "company", "email", "defphone",
            "address", "city", "state", "zip", "country",
        )
        else -> emptySet()
    }

    /**
     * Everything else 1Password kept in the item's sections.
     *
     * A .1pux item's sections are where the values that are not username/password live — a router's
     * WPA key, a licence key, the answer to a security question — and none of them crossed over.
     * A `concealed` value lands as [CustomFieldType.HIDDEN] so it stays masked here too; anything
     * whose value union this importer cannot read as text is left out rather than stringified into
     * something the editor would show as JSON.
     */
    private fun customFields(item: OnePuxItem, consumed: Set<String>): List<com.localkeep.core.model.CustomField> =
        item.details.sections
            .flatMap { it.fields }
            .filter { it.id !in consumed && it.value["totp"] == null }
            .mapNotNull { field ->
                val value = field.textValue()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = field.title.ifBlank { field.id }.ifBlank { return@mapNotNull null }
                com.localkeep.core.model.CustomField(
                    name = name,
                    value = value,
                    type = if (field.value["concealed"] != null) {
                        CustomFieldType.HIDDEN
                    } else {
                        CustomFieldType.TEXT
                    },
                )
            }

    // The 1Password-category -> display-label mapping that used to live here
    // (SkipReason.UnsupportedOnePasswordCategory's 16 known uuids, "005".."114", plus a fallback for
    // any other) now lives in feature/import, where it can be a @StringRes — this module cannot
    // author the label text itself. See ImportWizardScreen.kt's onePasswordCategoryRes.

    private fun findTotp(details: OnePuxDetails): com.localkeep.core.model.Totp? =
        details.sections.flatMap { it.fields }
            .firstNotNullOfOrNull { field -> field.value.text("totp") }
            ?.let(TotpFieldParser::parse)

    /**
     * Reads whichever textual value-union key is present.
     *
     * A `.1pux` section field carries its value under a key naming the field's *type*, and the
     * credit-card types were missing from this list — which mattered more than it looks, because
     * 1Password does not store a card number as a `string`. It stores it as a `creditCardNumber`,
     * so the one field on a card item that a password manager exists to carry was the one that
     * came through blank. `menu` and `url` are here for the same reason: they are the plain-text
     * unions this importer's own lookups can land on.
     */
    private fun OnePuxSectionField?.textValue(): String? {
        val v = this?.value ?: return null
        return v.text("string")
            ?: v.text("concealed")
            ?: v.emailText()
            ?: v.text("phone")
            ?: v.text("creditCardNumber")
            ?: v.text("creditCardType")
            ?: v.text("menu")
            ?: v.text("url")
            ?: v.addressObject()?.let(::addressText)
    }

    /**
     * A value-union member as text, or null when it is absent *or not a primitive*. Every read
     * here used `.jsonPrimitive`, which throws on an object — and 1Password writes `email` and
     * `address` as objects, so one identity with an email address threw, the throw was caught as
     * "this doesn't look like a valid 1Password export", and the whole file was refused (device,
     * 2026-09-14). A union member this reader does not understand is now simply not text.
     */
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** `email` is `{ "email_address": ..., "provider": ... }` in real exports; a bare string in older ones. */
    private fun JsonObject.emailText(): String? =
        text("email") ?: (this["email"] as? JsonObject)?.text("email_address")

    private fun JsonObject.addressObject(): JsonObject? = this["address"] as? JsonObject

    private fun OnePuxSectionField?.addressValue(): Address? =
        this?.value?.addressObject()?.let { a ->
            Address(
                line1 = a.text("street").orEmpty(),
                city = a.text("city").orEmpty(),
                state = a.text("state").orEmpty(),
                zip = a.text("zip").orEmpty(),
                // 1Password writes this as a lowercased alpha-2 code, which rendered as `gb`
                // under a London address (1A-F10).
                country = ImportedCountry.of(a.text("country").orEmpty()),
            )
        }

    /**
     * The address as one line for a custom field: the non-blank parts, comma-joined.
     *
     * The country goes through [ImportedCountry] here as well as in the structured address, or the
     * same document would read `gb` in a custom field and `United Kingdom` on an Identity.
     */
    private fun addressText(a: JsonObject): String? =
        listOf("street", "city", "state", "zip", "country")
            .mapNotNull { key ->
                a.text(key)?.takeIf(String::isNotBlank)
                    ?.let { if (key == "country") ImportedCountry.of(it) else it }
            }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

    /**
     * The `monthYear` union — a card expiry as the single number `YYYYMM` — split into year to
     * month. Null unless it is a plausible calendar month, so a `0`, a placeholder, or a value in
     * some other shape leaves both [Card] fields absent rather than stamping the card with an
     * expiry of month 0 that the editor would then show and the user would have to correct.
     */
    private fun OnePuxSectionField?.monthYearValue(): Pair<Int, Int>? {
        val raw = this?.value?.text("monthYear")?.toIntOrNull()
            ?: return null
        val year = raw / 100
        val month = raw % 100
        return if (year in 1900..9999 && month in 1..12) year to month else null
    }
}
