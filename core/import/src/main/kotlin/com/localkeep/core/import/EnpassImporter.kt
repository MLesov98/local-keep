package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Folder
import com.localkeep.core.model.FolderTree
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Totp
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/**
 * Enpass, in both of the shapes it exports.
 *
 *  - **Native JSON** — `{folders, items}`, where a folder carries a real `parent_uuid` (the only
 *    source in this module that ships its hierarchy as parent links rather than as a path string)
 *    and an item carries a `category` and a list of typed `fields`.
 *  - **Header-less label/value CSV** — `Amazon,Username,Food,E-mail,Cheese,*Password,…`: a title,
 *    then alternating label and value, then optionally one trailing note.
 *
 * The CSV shape is why [CsvImporter] must go on refusing these files, and the corpus test that
 * pins the refusal is not stale. `login-quoted.csv`'s first row carries the *label* `Username` at
 * index 1 and `Website` at index 7, both real header synonyms, so a column-position reading of it
 * imports four logins whose username is the literal string "Username". Recognition here rests on
 * Enpass's `*` marker for a sensitive label instead, which no header row carries — see [detect].
 */
@Serializable
internal data class EnpassExport(
    val folders: List<EnpassFolder> = emptyList(),
    val items: List<EnpassItem> = emptyList(),
)

@Serializable
internal data class EnpassFolder(
    val uuid: String = "",
    val title: String = "",
    @SerialName("parent_uuid") val parentUuid: String = "",
    /** Epoch seconds. Enpass stamps its folders, so the imported [Folder] carries a real time. */
    @SerialName("updated_at") val updatedAt: Long = 0L,
)

@Serializable
internal data class EnpassItem(
    val uuid: String = "",
    val title: String = "",
    val subtitle: String = "",
    /** `login`, `creditcard`, `note`, … — Enpass's own template family. */
    val category: String = "",
    val note: String = "",
    /** Enpass writes its flags as 0/1 integers, not booleans. */
    val favorite: Int = 0,
    val trashed: Int = 0,
    val archived: Int = 0,
    /** Epoch seconds, both of them. */
    val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    /** The folder uuids this item is filed in; Enpass allows several, LocalKeep records the first. */
    val folders: List<String> = emptyList(),
    val fields: List<EnpassField> = emptyList(),
)

@Serializable
internal data class EnpassField(
    val label: String = "",
    /** `username`, `password`, `url`, `totp`, `ccNumber`, `section`, `.Android#`, … */
    val type: String = "",
    val value: String = "",
    val sensitive: Int = 0,
    val deleted: Int = 0,
    val order: Int = 0,
)

object EnpassImporter : Importer {
    override val sourceFormat: SourceFormat = SourceFormat.ENPASS

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    override fun detect(bytes: ByteArray): Boolean = detectJson(bytes) || EnpassCsv.detect(bytes)

    /**
     * `items` and `folders` as arrays, plus one Enpass-only key on an item.
     *
     * `items` + `folders` alone is the shape [BitwardenJsonImporter] recognises, so the item-level
     * key is what separates them: Bitwarden writes neither `template_type` nor `category`, and
     * every Enpass item carries both.
     *
     * Deliberately **no** allowance for an empty `items` array, which is where Bitwarden's own
     * detect is more generous. Without a single item there is nothing in the file that is Enpass
     * rather than Bitwarden — `{"encrypted":false,"folders":[],"items":[]}` is a real Bitwarden
     * fixture in this corpus — and claiming it would mean answering an empty Bitwarden vault with
     * the wrong brand. An empty Enpass export is therefore not recognised; it is also the one
     * export with nothing in it to lose.
     */
    private fun detectJson(bytes: ByteArray): Boolean = runCatching {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: return@runCatching false
        val items = root["items"] as? JsonArray ?: return@runCatching false
        if (root["folders"] !is JsonArray) return@runCatching false
        items.any { element ->
            element is JsonObject && ("template_type" in element || "category" in element)
        }
    }.getOrDefault(false)

    // Manual try/catch rather than runCatchingOutcome, for the reason BitwardenJsonImporter states.
    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> = try {
        if (detectJson(bytes)) parseJson(bytes, titles) else EnpassCsv.parse(bytes, titles)
    } catch (t: ImportRejection) {
        Outcome.Failure(AppError.Validation("Enpass import rejected (${t.reason})", t))
    } catch (t: Throwable) {
        val reason = if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
            ImportFailureReason.ExportNeedsMoreMemory(sourceFormat)
        } else {
            ImportFailureReason.MalformedExport(sourceFormat)
        }
        Outcome.Failure(AppError.Validation("failed to parse Enpass export", ImportRejection(reason, t)))
    }

    private fun parseJson(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> {
        val export = try {
            json.decodeFromString(EnpassExport.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw ImportRejection(ImportFailureReason.MalformedExport(sourceFormat), t)
        }

        val items = mutableListOf<VaultItem>()
        val skipped = mutableListOf<SkippedEntry>()
        // Enpass is the one source here whose folder records carry parent links, so the tree is
        // read rather than reconstructed from a path string. Minted lazily, and only for a folder
        // an item actually lands in — with every ancestor that folder needs, or the child would
        // point at a parent the vault does not have.
        val byUuid = export.folders.filter { it.uuid.isNotBlank() && it.title.isNotBlank() }
            .associateBy { it.uuid }
        val folders = mutableListOf<Folder>()
        val idsByUuid = mutableMapOf<String, UUID>()

        fun folderIdOf(uuid: String, depth: Int = 0): UUID? {
            if (depth >= FolderTree.MAX_DEPTH) return null
            val folder = byUuid[uuid] ?: return null
            idsByUuid[uuid]?.let { return it }
            val parentId = folder.parentUuid.takeIf { it.isNotBlank() }?.let { folderIdOf(it, depth + 1) }
            val id = UUID.randomUUID()
            idsByUuid[uuid] = id
            folders += Folder(
                id = id,
                name = folder.title,
                parentId = parentId,
                // Enpass's own stamp, unlike every other importer here, which has to use `now`.
                updatedAt = folder.updatedAt.takeIf { it > 0L }?.let(Instant::ofEpochSecond),
            )
            return id
        }

        export.items.forEach { item ->
            val title = item.title.ifBlank { titles.untitledItem }
            if (item.trashed != 0) {
                skipped += SkippedEntry(title, SkipReason.SourceRecycleBin)
                return@forEach
            }
            val folderId = item.folders.firstNotNullOfOrNull { folderIdOf(it) }
            items += mapItem(item, title, folderId, titles)
        }

        return Outcome.Success(
            ParsedImport(items = items, skipped = skipped, folders = folders, timestampsKnown = true),
        )
    }

    private fun mapItem(
        item: EnpassItem,
        title: String,
        folderId: UUID?,
        titles: ImportDefaultTitles,
    ): VaultItem {
        val builder = EnpassFieldSink(titles)
        item.fields
            .filter { it.deleted == 0 && it.type != FIELD_SECTION && it.value.isNotBlank() }
            .sortedBy { it.order }
            .forEach(builder::accept)

        val content = when (item.category) {
            CATEGORY_CREDIT_CARD -> builder.toCard(item.note)
            CATEGORY_NOTE -> Note(body = item.note)
            CATEGORY_LOGIN -> builder.toLogin(item.note)
            // Any other template — identity, wifi, passport, … Enpass's templates are open-ended
            // and their fields are already carried as custom fields, so the item becomes a note
            // holding its own text rather than a counted loss. Nothing is dropped either way.
            else -> if (builder.looksLikeLogin()) builder.toLogin(item.note) else Note(body = item.note)
        }

        return VaultItem(
            id = UUID.randomUUID(),
            title = title,
            folderId = folderId,
            favorite = item.favorite != 0,
            createdAt = Instant.ofEpochSecond(item.createdAt),
            updatedAt = Instant.ofEpochSecond(item.updatedAt),
            customFields = builder.customFields(),
            content = content,
        )
    }

    internal const val CATEGORY_LOGIN = "login"
    internal const val CATEGORY_CREDIT_CARD = "creditcard"
    internal const val CATEGORY_NOTE = "note"
    internal const val FIELD_SECTION = "section"
}

/**
 * Collects one Enpass item's fields into the pieces a [VaultItem] is built from.
 *
 * Shared by the JSON and CSV halves, which differ only in how a field's *type* is known: the JSON
 * export states it (`"type": "ccNumber"`), and the CSV has to infer it from the label the user sees
 * (`*Number`). [EnpassCsv] therefore translates a label to the same type token before handing it
 * over, so both halves map identically from that point on and there is one place where "what does
 * `ccExpiry` mean" is answered.
 */
internal class EnpassFieldSink(private val titles: ImportDefaultTitles) {
    private val extras = mutableListOf<CustomField>()
    private val uris = mutableListOf<String>()
    private val androidPackages = mutableListOf<String>()
    private val card = mutableMapOf<String, String>()
    private var username = ""
    private var email = ""
    private var password = ""
    private var totp: Totp? = null

    fun accept(field: EnpassField) {
        val sensitive = field.sensitive != 0
        when (field.type) {
            "username" -> if (username.isBlank()) username = field.value else carry(field, sensitive)
            "email" -> if (email.isBlank()) email = field.value else carry(field, sensitive)
            "password" -> if (password.isBlank()) password = field.value else carry(field, true)
            "url" -> uris += field.value
            "totp" -> acceptTotp(field)
            ANDROID_FIELD_TYPE -> androidPackages += EnpassAndroidPackage.of(field.value)
            in CARD_FIELD_TYPES -> card[field.type] = field.value
            else -> carry(field, sensitive)
        }
    }

    private fun acceptTotp(field: EnpassField) {
        val parsed = TotpFieldParser.parse(field.value)
        if (parsed != null && totp == null) {
            totp = parsed
        } else {
            // Unreadable seeds are rescued into a HIDDEN field rather than dropped, which is what
            // the CSV path already does for a TOTP column it cannot parse.
            extras += CustomField(
                name = titles.rescuedTotpFieldName,
                value = field.value,
                type = CustomFieldType.HIDDEN,
            )
        }
    }

    private fun carry(field: EnpassField, sensitive: Boolean) {
        extras += CustomField(
            name = field.label.ifBlank { field.type },
            value = field.value,
            type = if (sensitive) CustomFieldType.HIDDEN else CustomFieldType.TEXT,
        )
    }

    /** True when the fields seen carry a credential, whatever the item's template says. */
    fun looksLikeLogin(): Boolean =
        username.isNotBlank() || email.isNotBlank() || password.isNotBlank() || uris.isNotEmpty()

    fun toLogin(note: String): Login {
        // `username` wins over `email`, and the loser is carried beside it rather than dropped —
        // the rule ProtonPassCsvUsernameTest pins for every source that writes both.
        val chosen = username.ifBlank { email }
        if (username.isNotBlank() && email.isNotBlank()) {
            extras += CustomField(name = EMAIL_FIELD_NAME, value = email, type = CustomFieldType.TEXT)
        }
        return Login(
            username = chosen,
            password = password,
            uris = uris.distinct().map { UriMatch(uri = it) },
            androidPackages = androidPackages.filter { it.isNotBlank() }.distinct(),
            totp = totp,
            notes = note,
        )
    }

    fun toCard(note: String): Card {
        val number = card["ccNumber"].orEmpty()
        val expiry = ProtonPassExpiry.of(card["ccExpiry"].orEmpty())
        // The card's own login fields (Enpass's "INTERNET BANKING" section) have nowhere to go on
        // `Card`, so they are carried as custom fields rather than lost.
        if (username.isNotBlank()) {
            extras += CustomField(name = USERNAME_FIELD_NAME, value = username)
        }
        if (email.isNotBlank()) {
            extras += CustomField(name = EMAIL_FIELD_NAME, value = email)
        }
        if (password.isNotBlank()) {
            extras += CustomField(
                name = PASSWORD_FIELD_NAME,
                value = password,
                type = CustomFieldType.HIDDEN,
            )
        }
        uris.distinct().forEach { extras += CustomField(name = URL_FIELD_NAME, value = it) }
        return Card(
            holder = card["ccName"].orEmpty(),
            number = number,
            brand = ImportedCardBrand.of(number, card["ccType"]),
            expMonth = expiry?.first,
            expYear = expiry?.second,
            cvv = card["ccCvc"].orEmpty(),
            pin = card["ccPin"].orEmpty(),
            notes = note,
        )
    }

    fun customFields(): List<CustomField> = extras.toList()

    private companion object {
        /**
         * Enpass's autofill field. The literal `#` is part of the type, not a comment: Enpass
         * numbers repeats as `.Android#1`, `.Android#2`, and the corpus fixture carries four.
         */
        const val ANDROID_FIELD_TYPE = ".Android#"

        /** Source-named, not authored copy — these are Enpass's own field types. */
        const val EMAIL_FIELD_NAME = "email"
        const val USERNAME_FIELD_NAME = "username"
        const val PASSWORD_FIELD_NAME = "password"
        const val URL_FIELD_NAME = "url"

        val CARD_FIELD_TYPES = setOf("ccName", "ccType", "ccNumber", "ccCvc", "ccPin", "ccExpiry")
    }
}

/**
 * The Android package inside an Enpass autofill value.
 *
 * Enpass writes four shapes, all four of them in the corpus fixture: a bare package
 * (`com.amazon.0`), a signed `android://<hash>@<package>`, and the two unsigned prefixes
 * `android://` and `androidapp://`. What LocalKeep stores is the package name, so the hash and the
 * scheme are stripped — a stored `android://…@com.amazon` would match no app at autofill time.
 */
internal object EnpassAndroidPackage {
    fun of(raw: String): String = raw.trim()
        .removePrefix("androidapp://")
        .removePrefix("android://")
        .substringAfterLast('@')
        .trim()
}

/**
 * Enpass's header-less, label/value-interleaved CSV.
 *
 * A row is `title` then alternating `label,value`, with one optional trailing note when the cell
 * count after the title is odd. A label prefixed with `*` is Enpass's marker for a sensitive value.
 */
internal object EnpassCsv {

    /**
     * A cell beginning with `*` at an odd index — Enpass's sensitive-label marker, in a label
     * position.
     *
     * Deliberately narrow. Matching on the labels themselves (`Username`, `Website`) would claim
     * any CSV whose *header* uses those words, which is most of them, and this importer runs before
     * [CsvImporter]. No header row has ever started a column name with `*`, and eight of the nine
     * Enpass fixtures carry one (`*Password`, `*Number`, `*CVC`, `*Security answer`).
     *
     * The ninth, `secure-note.csv`, is `MyNote,Some note text` — two cells with nothing in them
     * that is Enpass rather than any other two-column file, so it is **not** recognised. [parse]
     * reads it correctly if it is ever handed one; `detect` will not claim it, because claiming it
     * means claiming every two-column CSV. That is recorded rather than hidden: a user exporting
     * only secure notes from Enpass gets "format not recognised", which is true.
     */
    fun detect(bytes: ByteArray): Boolean = runCatching {
        // A file the generic mapper can bind by header is not a header-less file, whatever else it
        // looks like — the same first rule [KeeperCsv.detect] applies, for the same reason.
        if (CsvImporter.detect(bytes)) return@runCatching false
        val rows = CsvColumnMapper.parseRows(bytes).filter { it.any(String::isNotBlank) }
        rows.isNotEmpty() &&
            rows.all { it.size >= MIN_CELLS } &&
            rows.any { row ->
                row.indices.any { index -> index % 2 == 1 && row[index].startsWith(SENSITIVE_MARKER) }
            }
    }.getOrDefault(false)

    fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> {
        val rows = CsvColumnMapper.parseRows(bytes).filter { it.any(String::isNotBlank) }
        val now = Instant.now()
        val items = mutableListOf<VaultItem>()
        val skipped = mutableListOf<SkippedEntry>()

        rows.forEach { row ->
            val title = row.getOrElse(0) { "" }.trim().ifBlank { titles.untitledRow }
            val rest = row.drop(1)
            if (rest.isEmpty()) {
                skipped += SkippedEntry(title, SkipReason.IncompleteEntry)
                return@forEach
            }
            // An odd cell count after the title leaves one over, which is Enpass's trailing note.
            val pairCount = rest.size / 2
            val trailingNote = if (rest.size % 2 == 1) rest.last().trim() else ""

            val sink = EnpassFieldSink(titles)
            var isCard = false
            var hasPair = false
            for (index in 0 until pairCount) {
                val rawLabel = rest[index * 2].trim()
                val value = rest[index * 2 + 1].trim()
                if (rawLabel.isBlank() && value.isBlank()) continue
                hasPair = true
                val sensitive = rawLabel.startsWith(SENSITIVE_MARKER)
                val label = rawLabel.removePrefix(SENSITIVE_MARKER).trim()
                val type = typeOf(label)
                if (type in CARD_TYPES) isCard = true
                if (value.isBlank()) continue
                sink.accept(
                    EnpassField(
                        label = label,
                        type = type,
                        value = value,
                        sensitive = if (sensitive) 1 else 0,
                        order = index,
                    ),
                )
            }

            val content = when {
                isCard -> sink.toCard(trailingNote)
                !hasPair || !sink.looksLikeLogin() && !hasCredentialPair(rest) -> Note(body = trailingNote)
                else -> sink.toLogin(trailingNote)
            }

            items += VaultItem(
                id = UUID.randomUUID(),
                title = title,
                createdAt = now,
                updatedAt = now,
                customFields = sink.customFields(),
                content = content,
            )
        }

        // `timestampsKnown = false`: an Enpass CSV row has nowhere to put a time, so both stamps
        // are the import instant and mean nothing in a merge. The JSON half does carry them.
        return Outcome.Success(
            ParsedImport(items = items, skipped = skipped, timestampsKnown = false),
        )
    }

    /** Whether the row named a credential label at all, even one whose value was blank. */
    private fun hasCredentialPair(rest: List<String>): Boolean =
        rest.indices.any { index ->
            index % 2 == 0 &&
                typeOf(rest[index].trim().removePrefix(SENSITIVE_MARKER).trim()) in CREDENTIAL_TYPES
        }

    /**
     * The JSON export's field type for a label the CSV shows, so both halves share one mapping.
     *
     * An unrecognised label keeps its own text as the type, which sends it to
     * [EnpassFieldSink.accept]'s `else` branch and out as a custom field named after the label —
     * which is what "ADDITIONAL DETAILS", "Security question" and "Phone number" all are.
     */
    private fun typeOf(label: String): String = when (label.lowercase()) {
        "username" -> "username"
        "e-mail", "email" -> "email"
        "password" -> "password"
        "website", "url" -> "url"
        "one-time code", "totp" -> "totp"
        "cardholder" -> "ccName"
        "number" -> "ccNumber"
        "type" -> "ccType"
        "cvc", "cvv" -> "ccCvc"
        "pin" -> "ccPin"
        "expiry date" -> "ccExpiry"
        else -> label
    }

    private const val SENSITIVE_MARKER = "*"

    /** Title plus at least one label: anything shorter carries nothing this could read. */
    private const val MIN_CELLS = 2

    private val CARD_TYPES = setOf("ccNumber", "ccCvc", "ccExpiry", "ccName")
    private val CREDENTIAL_TYPES = setOf("username", "email", "password", "url", "totp")
}
