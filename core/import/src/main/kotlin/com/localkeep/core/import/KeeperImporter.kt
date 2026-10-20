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
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Totp
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * Keeper, in both of the shapes it exports.
 *
 *  - **Native JSON** — `{shared_folders, records}`, where a record carries `$type`, a flat
 *    `custom_fields` map whose keys encode the field's type and label, and a `folders` array naming
 *    one or more paths (personal `folder`, or `shared_folder`).
 *  - **Header-less positional CSV** — seven fixed columns
 *    (`folder,title,login,password,url,notes,shared_folder`) followed by repeating
 *    `name,value` custom-field pairs.
 *
 * The CSV half is the interesting one, and the corpus recorded it as a gap with teeth: with no
 * header row there is nothing for [CsvColumnMapper.autoDetect] to bind, so the generic mapper was
 * right to refuse the file rather than import rows whose fields are in unknown places. That refusal
 * is preserved — [CsvImporter] still says no — and recognition here rests on the positional shape
 * instead (see [detect]).
 */
@Serializable
internal data class KeeperExport(
    @SerialName("shared_folders") val sharedFolders: List<KeeperSharedFolder> = emptyList(),
    val records: List<KeeperRecord> = emptyList(),
)

@Serializable
internal data class KeeperSharedFolder(
    val path: String = "",
    val uid: String = "",
)

@Serializable
internal data class KeeperRecord(
    val title: String = "",
    val uid: String = "",
    val login: String = "",
    val password: String = "",
    @SerialName("login_url") val loginUrl: String = "",
    val notes: String = "",
    /** Keeper's own record type — `login`, `bankCard`, `passport`, `sshKeys`, `pamUser`, … */
    @SerialName("\$type") val type: String = "",
    /** Epoch **seconds**. Keeper writes a real modification time, so `timestampsKnown` is true. */
    @SerialName("last_modified") val lastModified: Long = 0L,
    /**
     * Keyed `$<fieldType>[:<label>[:<index>]]`, valued with anything at all — a string, a number, a
     * bool, a nested object (`$name`, `$address`, `$paymentCard`) or an array of them. Left raw and
     * flattened by [KeeperCustomFields] rather than modelled, because the key space is open: Keeper
     * adds record types (the enterprise export's `$pamSettings`, `$rbiUrl`) faster than any closed
     * model here would track, and an unmodelled field must still reach the user.
     */
    @SerialName("custom_fields") val customFields: Map<String, JsonElement> = emptyMap(),
    val folders: List<KeeperRecordFolder> = emptyList(),
)

@Serializable
internal data class KeeperRecordFolder(
    val folder: String = "",
    @SerialName("shared_folder") val sharedFolder: String = "",
)

object KeeperImporter : Importer {
    override val sourceFormat: SourceFormat = SourceFormat.KEEPER

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    override fun detect(bytes: ByteArray): Boolean =
        detectJson(bytes) || KeeperCsv.detect(bytes)

    /**
     * `records` **and** `shared_folders` as top-level arrays.
     *
     * Both, never either: `records` alone is a key an unrelated document could carry, and decode
     * success is no signal at all under `ignoreUnknownKeys`. Every Keeper JSON export writes both,
     * including the enterprise one whose `shared_folders` holds two entries and whose records are
     * all PAM types.
     */
    private fun detectJson(bytes: ByteArray): Boolean = runCatching {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: return@runCatching false
        root["records"] is JsonArray && root["shared_folders"] is JsonArray
    }.getOrDefault(false)

    // Manual try/catch rather than runCatchingOutcome, for the reason BitwardenJsonImporter states.
    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> = try {
        if (detectJson(bytes)) parseJson(bytes, titles) else KeeperCsv.parse(bytes, titles)
    } catch (t: ImportRejection) {
        Outcome.Failure(AppError.Validation("Keeper import rejected (${t.reason})", t))
    } catch (t: Throwable) {
        val reason = if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
            ImportFailureReason.ExportNeedsMoreMemory(sourceFormat)
        } else {
            ImportFailureReason.MalformedExport(sourceFormat)
        }
        Outcome.Failure(AppError.Validation("failed to parse Keeper export", ImportRejection(reason, t)))
    }

    private fun parseJson(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> {
        val export = try {
            json.decodeFromString(KeeperExport.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw ImportRejection(ImportFailureReason.MalformedExport(sourceFormat), t)
        }

        val now = Instant.now()
        val tree = KeeperFolderTree(now)
        val items = mutableListOf<VaultItem>()
        val skipped = mutableListOf<SkippedEntry>()

        export.records.forEach { record ->
            val title = record.title.ifBlank { titles.untitledItem }
            if (record.type in SSH_TYPES) {
                skipped += SkippedEntry(title, SkipReason.SshKey)
                return@forEach
            }
            // The first folder a record names. `VaultItem.folderId` is a single id and a Keeper
            // record can sit in several folders at once; filing it in the first is the same
            // compromise BitwardenJsonImporter makes, and losing the rest silently would not be —
            // so the others are carried as custom fields by `mapRecord`.
            val paths = record.folders
                .map { entry -> entry.folder.ifBlank { entry.sharedFolder } }
                .filter { it.isNotBlank() }
            val folderId = paths.firstNotNullOfOrNull { tree.idOf(KeeperFolderPath.split(it)) }
            items += mapRecord(record, title, folderId, paths.drop(1), titles)
        }

        // `timestampsKnown = true`: `last_modified` is the record's own time. `createdAt` has no
        // counterpart in the export and is stamped with it too — the same instant, which is the
        // honest reading of "this is the only time the file carries".
        return Outcome.Success(
            ParsedImport(
                items = items,
                skipped = skipped,
                folders = tree.folders(),
                timestampsKnown = true,
            ),
        )
    }

    private fun mapRecord(
        record: KeeperRecord,
        title: String,
        folderId: UUID?,
        otherFolders: List<String>,
        titles: ImportDefaultTitles,
    ): VaultItem {
        val fields = KeeperCustomFields.of(record.customFields)
        val customFields = mutableListOf<CustomField>()
        val type = record.type

        val totpRaw = fields.firstValue(FIELD_ONE_TIME_CODE)
        val totp = totpRaw?.let(TotpFieldParser::parse)
        if (totpRaw != null && totp == null) {
            customFields += CustomField(
                name = titles.rescuedTotpFieldName,
                value = totpRaw,
                type = CustomFieldType.HIDDEN,
            )
        }

        val content = when {
            type in CARD_TYPES -> cardOf(record, fields)
            type in IDENTITY_TYPES -> identityOf(record, fields, customFields, titles)
            type in NOTE_TYPES -> Note(body = record.notes)
            record.login.isNotBlank() || record.password.isNotBlank() || record.loginUrl.isNotBlank() ->
                Login(
                    username = record.login,
                    password = record.password,
                    uris = urisOf(record, fields),
                    totp = totp,
                    notes = record.notes,
                )
            else -> Note(body = record.notes)
        }

        customFields += fields.remaining(consumedFor(type)).map { it.toCustomField() }
        // Every folder past the first, named rather than dropped: a record filed in three folders
        // lands in one of them, and the other two are where the user will look for it.
        otherFolders.forEach { path ->
            customFields += CustomField(name = FOLDER_FIELD_NAME, value = path)
        }

        val stamp = Instant.ofEpochSecond(record.lastModified)
        return VaultItem(
            id = UUID.randomUUID(),
            title = title,
            folderId = folderId,
            createdAt = stamp,
            updatedAt = stamp,
            customFields = customFields,
            content = content,
        )
    }

    /**
     * The record's `login_url` plus every `$url` custom field.
     *
     * Split on newlines because a `$url` value may be an *array* — the corpus's Amazon record
     * carries `["https://login.amazon.com", "https://logout.amazon.com"]` — and [KeeperValueText]
     * renders an array one element per line. Joined, that arrived as a single unusable URI string,
     * which autofill would never match and the user would never notice.
     */
    private fun urisOf(record: KeeperRecord, fields: KeeperCustomFields): List<UriMatch> =
        (listOf(record.loginUrl) + fields.values(FIELD_URL).flatMap { it.split('\n') })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { UriMatch(uri = it) }

    private fun cardOf(record: KeeperRecord, fields: KeeperCustomFields): Card {
        val card = fields.firstObject(FIELD_PAYMENT_CARD)
        val number = card.string("cardNumber")
        val expiry = ProtonPassExpiry.of(card.string("cardExpirationDate"))
        return Card(
            holder = fields.firstValue(FIELD_TEXT, LABEL_CARDHOLDER_NAME).orEmpty(),
            number = number,
            brand = ImportedCardBrand.of(number, null),
            expMonth = expiry?.first,
            expYear = expiry?.second,
            cvv = card.string("cardSecurityCode"),
            pin = fields.firstValue(FIELD_PIN_CODE).orEmpty(),
            notes = record.notes,
        )
    }

    private fun identityOf(
        record: KeeperRecord,
        fields: KeeperCustomFields,
        customFields: MutableList<CustomField>,
        titles: ImportDefaultTitles,
    ): Identity {
        // Identity has no notes field; the record's notes are rescued under the same name every
        // other importer here uses for exactly this.
        if (record.notes.isNotBlank()) {
            customFields += CustomField(
                name = titles.identityNotesFieldName,
                value = record.notes,
                type = CustomFieldType.TEXT,
            )
        }
        val name = fields.firstObject(FIELD_NAME)
        val address = fields.firstObject(FIELD_ADDRESS)
        val phone = fields.firstObject(FIELD_PHONE)
        return Identity(
            firstName = name.string("first"),
            lastName = name.string("last"),
            company = fields.firstValue(FIELD_TEXT, LABEL_COMPANY).orEmpty(),
            ssn = fields.firstValue(FIELD_ACCOUNT_NUMBER, LABEL_IDENTITY_NUMBER).orEmpty(),
            passport = fields.firstValue(FIELD_ACCOUNT_NUMBER, LABEL_PASSPORT_NUMBER).orEmpty(),
            license = fields.firstValue(FIELD_ACCOUNT_NUMBER, LABEL_DL_NUMBER).orEmpty(),
            email = fields.firstValue(FIELD_EMAIL).orEmpty(),
            phone = phone.string("number"),
            address = Address(
                line1 = address.string("street1"),
                line2 = address.string("street2"),
                city = address.string("city"),
                state = address.string("state"),
                zip = address.string("zip"),
                country = address.string("country"),
            ),
        )
    }

    /** The `$`-keys a given record type read onto model fields, so they are not carried twice. */
    private fun consumedFor(type: String): Set<KeeperFieldKey> = when {
        type in CARD_TYPES -> setOf(
            KeeperFieldKey(FIELD_PAYMENT_CARD),
            KeeperFieldKey(FIELD_PIN_CODE),
            KeeperFieldKey(FIELD_TEXT, LABEL_CARDHOLDER_NAME),
            KeeperFieldKey(FIELD_ONE_TIME_CODE),
        )
        type in IDENTITY_TYPES -> setOf(
            KeeperFieldKey(FIELD_NAME),
            KeeperFieldKey(FIELD_ADDRESS),
            KeeperFieldKey(FIELD_PHONE),
            KeeperFieldKey(FIELD_EMAIL),
            KeeperFieldKey(FIELD_TEXT, LABEL_COMPANY),
            KeeperFieldKey(FIELD_ACCOUNT_NUMBER, LABEL_IDENTITY_NUMBER),
            KeeperFieldKey(FIELD_ACCOUNT_NUMBER, LABEL_PASSPORT_NUMBER),
            KeeperFieldKey(FIELD_ACCOUNT_NUMBER, LABEL_DL_NUMBER),
            KeeperFieldKey(FIELD_ONE_TIME_CODE),
        )
        else -> setOf(KeeperFieldKey(FIELD_ONE_TIME_CODE), KeeperFieldKey(FIELD_URL))
    }

    internal const val FIELD_ONE_TIME_CODE = "oneTimeCode"
    internal const val FIELD_PAYMENT_CARD = "paymentCard"
    internal const val FIELD_PIN_CODE = "pinCode"
    internal const val FIELD_TEXT = "text"
    internal const val FIELD_NAME = "name"
    internal const val FIELD_ADDRESS = "address"
    internal const val FIELD_PHONE = "phone"
    internal const val FIELD_EMAIL = "email"
    internal const val FIELD_ACCOUNT_NUMBER = "accountNumber"
    internal const val FIELD_URL = "url"

    // Keeper's own label tokens — the second segment of a `$<type>:<label>` custom-field key. Named
    // constants rather than inline literals for the reason the field types above are: they are
    // format keys read out of an export, never text shown to anyone. `checkNoHardcodedUiStrings`
    // reads a named argument called `label` with a string literal as user-facing copy whatever it
    // actually holds, which is why the parameter it is passed to is named `fieldLabel` as well.
    internal const val LABEL_CARDHOLDER_NAME = "cardholderName"
    internal const val LABEL_COMPANY = "company"
    internal const val LABEL_IDENTITY_NUMBER = "identityNumber"
    internal const val LABEL_PASSPORT_NUMBER = "passportNumber"
    internal const val LABEL_DL_NUMBER = "dlNumber"

    /**
     * The custom-field name a record's second and later folders are carried under. Not authored
     * copy: it is Keeper's own key for the array these came out of, shown the way every other
     * source-named custom field in this module is.
     */
    internal const val FOLDER_FIELD_NAME = "folders"

    private val CARD_TYPES = setOf("bankCard")
    private val SSH_TYPES = setOf("sshKeys")
    private val IDENTITY_TYPES = setOf(
        "passport", "driverLicense", "ssnCard", "birthCertificate", "address", "contact",
        "healthInsurance", "membership",
    )

    /**
     * Types that are a note however many other fields they carry — a bank account, a stored file,
     * a photo, an encrypted note. `bankAccount` is here deliberately: its `$bankAccount` object
     * holds a routing and an account number, which [Card] has nowhere to put and [Identity] would
     * misfile, so it becomes a [Note] whose fields survive as custom fields (brief item 1).
     */
    private val NOTE_TYPES = setOf("bankAccount", "encryptedNotes", "file", "photo")
}

/**
 * One flattened Keeper custom field: its `$`-type, its optional label, and a rendered value.
 *
 * Keeper keys a custom field `$<fieldType>`, `$<fieldType>:<label>` or
 * `$<fieldType>:<label>:<index>` — the third form is what a record with two fields of the same type
 * writes, and the index is positional noise rather than something to show. [label] is what the user
 * typed and is therefore what the field is named after; the type is the fallback when there is no
 * label, which is the only name the export offers.
 */
internal data class KeeperFieldKey(val type: String, val label: String = "")

internal data class KeeperField(
    val key: KeeperFieldKey,
    val value: String,
    val sensitive: Boolean,
    val raw: JsonElement,
) {
    fun toCustomField(): CustomField = CustomField(
        name = key.label.ifBlank { key.type },
        value = value,
        type = if (sensitive) CustomFieldType.HIDDEN else CustomFieldType.TEXT,
    )
}

/**
 * A record's `custom_fields` map, flattened once and then queried by type and label.
 *
 * Values are rendered rather than modelled because the map is open-ended (see
 * [KeeperRecord.customFields]): a nested object becomes `sub: value` lines, an array becomes its
 * elements one per line. That is lossy in layout and lossless in content, which is the right trade
 * for a field this app has no editor for — the alternative was dropping it, on an import whose last
 * step offers to securely delete the export.
 */
internal class KeeperCustomFields private constructor(private val fields: List<KeeperField>) {

    fun values(type: String): List<String> = fields.filter { it.key.type == type }.map { it.value }

    fun firstValue(type: String, fieldLabel: String? = null): String? = fields
        .firstOrNull { it.key.type == type && (fieldLabel == null || it.key.label == fieldLabel) }
        ?.value
        ?.takeIf { it.isNotBlank() }

    fun firstObject(type: String): KeeperObject =
        KeeperObject(fields.firstOrNull { it.key.type == type }?.raw as? JsonObject)

    /**
     * Everything not already read onto a model field, in the order the export wrote it.
     *
     * A [consumed] entry with a blank label claims every field of that type; one with a label
     * claims only that exact field, so `$text:cardholderName` can be read onto a card's holder
     * while the record's other `$text:` fields still reach the user.
     */
    fun remaining(consumed: Set<KeeperFieldKey>): List<KeeperField> {
        val wholeTypes = consumed.filter { it.label.isEmpty() }.map { it.type }.toSet()
        return fields.filter { field ->
            field.value.isNotBlank() && field.key.type !in wholeTypes && field.key !in consumed
        }
    }

    companion object {
        fun of(raw: Map<String, JsonElement>): KeeperCustomFields = KeeperCustomFields(
            raw.map { (key, value) ->
                val parts = key.removePrefix("$").split(':')
                val type = parts.getOrElse(0) { "" }
                // parts[2], when present, is Keeper's positional index for a repeated field — not
                // part of the label and never shown.
                val label = parts.getOrElse(1) { "" }
                KeeperField(
                    key = KeeperFieldKey(type, label),
                    value = KeeperValueText.of(value),
                    sensitive = type in SENSITIVE_TYPES,
                    raw = value,
                )
            },
        )

        /** Field types whose value is a secret, so the custom field is HIDDEN rather than TEXT. */
        private val SENSITIVE_TYPES = setOf(
            "pinCode", "secret", "password", "keyPair", "passkey", "oneTimeCode",
            "trafficEncryptionSeed", "privateKey",
        )
    }
}

/** A nested Keeper value object (`$name`, `$address`, `$paymentCard`), read without ever throwing. */
internal class KeeperObject(private val obj: JsonObject?) {
    fun string(key: String): String =
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}

/** Renders any Keeper custom-field value as the text a custom field can hold. */
internal object KeeperValueText {
    fun of(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.content
        is JsonObject -> element.entries
            .mapNotNull { (key, value) -> of(value).takeIf { it.isNotBlank() }?.let { "$key: $it" } }
            .joinToString("\n")
        is JsonArray -> element.mapNotNull { of(it).takeIf(String::isNotBlank) }.joinToString("\n")
    }
}

/**
 * Keeper's folder paths, which are nested with a backslash — and escape a literal one by doubling
 * it, but only in the JSON export.
 *
 * The two shapes genuinely differ, and both are in the corpus:
 *
 *  - **JSON.** `"Development\\Name/with\\\\both/slashes"` decodes to
 *    `Development\Name/with\\both/slashes`: a single backslash separates, a doubled one is a
 *    literal backslash inside a folder name, and `/` is an ordinary character.
 *  - **CSV.** There is no JSON layer to unescape, and the cell reads `Foo\\Baz` for the two-level
 *    path `Foo` › `Baz` — so a run of backslashes is the separator.
 *
 * Reading either with the other's rule produces one folder named `Foo\Baz`, which looks plausible
 * on screen and is not what the user had.
 */
internal object KeeperFolderPath {

    /** The JSON rule: single `\` separates, `\\` is an escaped literal backslash. */
    fun split(path: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < path.length) {
            val character = path[index]
            when {
                character == '\\' && index + 1 < path.length && path[index + 1] == '\\' -> {
                    current.append('\\')
                    index += 2
                }
                character == '\\' -> {
                    segments += current.toString()
                    current.clear()
                    index++
                }
                else -> {
                    current.append(character)
                    index++
                }
            }
        }
        segments += current.toString()
        return clamp(segments)
    }

    /** The CSV rule: a run of one or more backslashes separates. */
    fun splitCsv(path: String): List<String> = clamp(path.split(CSV_SEPARATOR))

    private fun clamp(segments: List<String>): List<String> = segments
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(FolderTree.MAX_DEPTH)

    private val CSV_SEPARATOR = Regex("""\\+""")
}

/** Mints folders on first use, so no folder is created that nothing was filed into. */
internal class KeeperFolderTree(private val importedAt: Instant) {
    private val folders = mutableListOf<Folder>()

    /**
     * Keyed by the segment *list*, not by a joined string: any separator character could itself
     * appear in a Keeper folder name (`Shared/With/Slashes` is a real one in the corpus), and a
     * join would then make two different paths collide into one folder.
     */
    private val idsByPath = mutableMapOf<List<String>, UUID>()

    fun idOf(segments: List<String>): UUID? {
        if (segments.isEmpty()) return null
        var parentId: UUID? = null
        val path = mutableListOf<String>()
        for (segment in segments) {
            path += segment
            val here = parentId
            parentId = idsByPath.getOrPut(path.toList()) {
                UUID.randomUUID().also {
                    // Stamped now, not null: a Keeper folder record carries no timestamp, and a
                    // null `Folder.updatedAt` loses every rename tie-break in a merge.
                    folders += Folder(id = it, name = segment, parentId = here, updatedAt = importedAt)
                }
            }
        }
        return parentId
    }

    fun folders(): List<Folder> = folders.toList()
}

/**
 * Keeper's header-less positional CSV.
 *
 * Seven fixed columns — `folder,title,login,password,url,notes,shared_folder` — then repeating
 * `name,value` custom-field pairs, so a valid row always has `7 + 2n` cells.
 */
internal object KeeperCsv {

    private const val COL_FOLDER = 0
    private const val COL_TITLE = 1
    private const val COL_LOGIN = 2
    private const val COL_PASSWORD = 3
    private const val COL_URL = 4
    private const val COL_NOTES = 5
    private const val COL_SHARED_FOLDER = 6
    private const val FIXED_COLUMNS = 7

    /** Keeper's own name for the otpauth URI it writes into a custom-field pair. */
    private const val TOTP_FIELD_NAME = "TFC:Keeper"

    /**
     * Recognition of a file with no header to read, which is a strictly weaker position than every
     * other importer here — so the rules are deliberately narrow, and the cost of each is stated.
     *
     *  1. [CsvImporter] does not already recognise the file. A file whose first row *is* a header
     *     the generic mapper can bind is by definition not a header-less one.
     *  2. Every row has `7 + 2n` cells. The shape is the format.
     *  3. **The first row does not look like a header** — see [looksLikeHeader]. This is the rule
     *     that does the real work, because rule 1 only covers headers the generic mapper can bind:
     *     Dashlane's `payments.csv` has eleven columns, so `11 - 7` is even, no password column for
     *     rule 1 to catch, and it was claimed here and mapped by position until this existed.
     *  4. No cell begins with `*`. That is Enpass's marker for a sensitive label, and four of its
     *     label/value fixtures happen to satisfy rule 2; Keeper writes no such label.
     *  5. At least one row has a non-blank title.
     *  6. **The file does not open as JSON.** A JSON document split on commas is a list of rows,
     *     and a fragment of one satisfies every rule above often enough to matter: a truncated
     *     Bitwarden export and a legacy 1Password `.1pif` both previewed as `1 item found · Keeper`
     *     with a raw JSON fragment offered as a vault item (1A-F3, 2026-09-20). Keeper's own CSV
     *     never begins with a brace or a bracket, so this costs nothing and is checked first
     *     because it is the cheapest of the six.
     *
     * A file this refuses falls through to [CsvImporter], which refuses it too, and the wizard says
     * it does not recognise the format — the outcome the corpus test already pins as the safe one.
     */
    fun detect(bytes: ByteArray): Boolean = runCatching {
        if (looksLikeJson(bytes) || CsvImporter.detect(bytes)) return@runCatching false
        val rows = CsvColumnMapper.parseRows(bytes).filter { it.any(String::isNotBlank) }
        rows.isNotEmpty() &&
            rows.all { it.size >= FIXED_COLUMNS && (it.size - FIXED_COLUMNS) % 2 == 0 } &&
            !looksLikeHeader(rows.first()) &&
            rows.none { row -> row.any { it.startsWith("*") } } &&
            rows.any { it[COL_TITLE].isNotBlank() }
    }.getOrDefault(false)

    /**
     * The first non-whitespace character is `{` or `[` — a document that means to be JSON, whole or
     * truncated.
     *
     * Deliberately not "parses as JSON": the files this has to refuse are the ones that do **not**
     * parse, because a valid Keeper JSON export is claimed by [KeeperImporter.detectJson] long
     * before this is reached. A BOM is skipped for the reason [CsvColumnMapper.parseRows] skips it.
     */
    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val first = text.firstOrNull { !it.isWhitespace() } ?: return false
        return first == '{' || first == '['
    }

    /**
     * Whether a row reads as a **header** rather than as a record — the one question that decides
     * whether a positional reading of this file is legitimate at all.
     *
     * A header's cells are column *names*: all present, all distinct, and drawn from the small
     * alphabet a name uses. A Keeper record's first row is data, and data in these columns carries
     * exactly what a name does not — an address (`john.doe@example.com`), a URL, a backslashed
     * folder path (`Foo\\Baz\\Bar`), a password of punctuation, or an empty cell where the record
     * had nothing. Every Keeper fixture in the corpus fails at least two of these; every Dashlane
     * header passes all of them.
     */
    private fun looksLikeHeader(row: List<String>): Boolean =
        row.all { cell ->
            val value = cell.trim()
            value.isNotEmpty() && HEADER_NAME.matches(value)
        } && row.map { it.trim().lowercase() }.distinct().size == row.size

    private val HEADER_NAME = Regex("""[A-Za-z][A-Za-z0-9 _.-]*""")

    fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> {
        val rows = CsvColumnMapper.parseRows(bytes).filter { it.any(String::isNotBlank) }
        val now = Instant.now()
        val tree = KeeperFolderTree(now)
        val items = mutableListOf<VaultItem>()
        val skipped = mutableListOf<SkippedEntry>()

        rows.forEach { row ->
            if (row.size < FIXED_COLUMNS) {
                skipped += SkippedEntry(row.getOrElse(0) { titles.untitledRow }, SkipReason.IncompleteEntry)
                return@forEach
            }
            fun cell(index: Int) = row.getOrElse(index) { "" }.trim()
            val title = cell(COL_TITLE).ifBlank { titles.untitledRow }
            val folderCell = cell(COL_FOLDER).ifBlank { cell(COL_SHARED_FOLDER) }
            val folderId = tree.idOf(KeeperFolderPath.splitCsv(folderCell))

            val customFields = mutableListOf<CustomField>()
            var totp: Totp? = null
            var index = FIXED_COLUMNS
            while (index + 1 < row.size) {
                val name = row[index].trim()
                val value = row[index + 1].trim()
                index += 2
                if (name.isBlank() && value.isBlank()) continue
                if (name == TOTP_FIELD_NAME) {
                    val parsed = TotpFieldParser.parse(value)
                    if (parsed != null && totp == null) {
                        totp = parsed
                    } else if (value.isNotBlank()) {
                        customFields += CustomField(
                            name = titles.rescuedTotpFieldName,
                            value = value,
                            type = CustomFieldType.HIDDEN,
                        )
                    }
                    continue
                }
                if (value.isNotBlank()) {
                    customFields += CustomField(name = name.ifBlank { titles.untitledRow }, value = value)
                }
            }

            val hasLogin = cell(COL_LOGIN).isNotBlank() ||
                cell(COL_PASSWORD).isNotBlank() ||
                cell(COL_URL).isNotBlank()
            val content = if (hasLogin) {
                Login(
                    username = cell(COL_LOGIN),
                    password = cell(COL_PASSWORD),
                    uris = listOf(cell(COL_URL)).filter { it.isNotBlank() }.map { UriMatch(uri = it) },
                    totp = totp,
                    notes = cell(COL_NOTES),
                )
            } else {
                // No address, username or password anywhere in the row — the same reading
                // `SkipReason.NoLoginFields` describes, except the row still carries a title and
                // custom fields the user wrote, so it becomes a note rather than a loss.
                Note(body = cell(COL_NOTES))
            }

            items += VaultItem(
                id = UUID.randomUUID(),
                title = title,
                folderId = folderId,
                createdAt = now,
                updatedAt = now,
                customFields = customFields,
                content = content,
            )
        }

        // `timestampsKnown = false`: a Keeper CSV row has nowhere to put a time, so both stamps are
        // the import instant and mean nothing in a merge. The JSON half does carry one.
        return Outcome.Success(
            ParsedImport(
                items = items,
                skipped = skipped,
                folders = tree.folders(),
                timestampsKnown = false,
            ),
        )
    }
}
