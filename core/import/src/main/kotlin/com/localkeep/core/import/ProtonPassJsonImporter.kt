package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Address
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Folder
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import com.localkeep.core.model.Totp
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * Proton Pass's JSON export — `{version, userId, encrypted, vaults}`, where `vaults` is an **object
 * keyed by vault id**, not an array.
 *
 * Proton Pass's *CSV* has been readable since the generic mapper learned to keep `email` and
 * `username` apart ([ProtonPassCsvUsernameTest]); this is the shape the desktop and web apps
 * actually produce, and nothing here claimed it. The near-miss the corpus pins is worth repeating:
 * every one of these files carries a top-level `"encrypted": false`, which is a key
 * `BitwardenExport` also declares — so recognition here rests on `vaults` being an *object*, which
 * no other format in this module writes.
 */
@Serializable
internal data class ProtonPassExport(
    val version: String = "",
    val encrypted: Boolean = false,
    val vaults: Map<String, ProtonPassVault> = emptyMap(),
)

@Serializable
internal data class ProtonPassVault(
    val name: String = "",
    val items: List<ProtonPassItem> = emptyList(),
)

@Serializable
internal data class ProtonPassItem(
    val data: ProtonPassItemData = ProtonPassItemData(),
    /**
     * Proton's own lifecycle flag: 1 is an active item, 2 is one in the trash. A trashed item is
     * reported as [SkipReason.SourceRecycleBin] rather than imported, for the reason that reason
     * exists — importing what the user threw away makes it searchable, autofill-eligible and
     * counted by the health dashboard again.
     */
    val state: Int = 1,
    /** The generated address an `alias` item *is*; null on every other type. */
    val aliasEmail: String? = null,
    /** Epoch **seconds**, both of them — Proton writes real per-item times, so `timestampsKnown`. */
    val createTime: Long = 0L,
    val modifyTime: Long = 0L,
    val pinned: Boolean = false,
)

@Serializable
internal data class ProtonPassItemData(
    val metadata: ProtonPassMetadata = ProtonPassMetadata(),
    val extraFields: List<ProtonPassField> = emptyList(),
    val type: String = "",
    /**
     * Left as a raw element rather than a sealed hierarchy: the shape depends on [type], and
     * `custom`/`sshKey`/an unrecognised type all carry arbitrary keys. Read through
     * [ProtonPassJsonImporter.contentString] so an unexpected shape is a missing value, never a
     * failed file.
     */
    val content: JsonElement = JsonObject(emptyMap()),
)

@Serializable
internal data class ProtonPassMetadata(
    val name: String = "",
    val note: String = "",
)

@Serializable
internal data class ProtonPassField(
    val fieldName: String = "",
    val type: String = "",
    val data: ProtonPassFieldData = ProtonPassFieldData(),
)

@Serializable
internal data class ProtonPassFieldData(
    val content: String = "",
    val totpUri: String = "",
    val timestamp: String = "",
) {
    /** The one of the three this field actually filled, in the order Proton populates them. */
    val value: String get() = content.ifBlank { totpUri.ifBlank { timestamp } }
}

object ProtonPassJsonImporter : Importer {
    override val sourceFormat: SourceFormat = SourceFormat.PROTONPASS

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * `vaults` as a JSON **object** is the whole signal.
     *
     * Not `encrypted`, which Bitwarden writes too and which the corpus test exists to pin; not
     * decode success, which `ignoreUnknownKeys` hands out to any JSON at all. `version` is required
     * beside it so that some future format with an unrelated `vaults` object does not pass.
     */
    override fun detect(bytes: ByteArray): Boolean = runCatching {
        val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
            ?: return@runCatching false
        root["vaults"] is JsonObject && root["version"] is JsonPrimitive
    }.getOrDefault(false)

    // Manual try/catch rather than runCatchingOutcome, for the reason BitwardenJsonImporter states:
    // the typed reason has to survive to the caller.
    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> = try {
        val export = try {
            json.decodeFromString(ProtonPassExport.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw ImportRejection(ImportFailureReason.MalformedExport(sourceFormat), t)
        }

        val now = Instant.now()
        val folders = mutableListOf<Folder>()
        val items = mutableListOf<VaultItem>()
        val skipped = mutableListOf<SkippedEntry>()

        export.vaults.values.forEach { vault ->
            // Minted lazily, so a vault whose every item is trashed never becomes an empty folder —
            // the rule BitwardenJsonImporter, CsvColumnMapper and AegisJsonImporter all follow.
            // Stamped `now` for the same reason they do: a Proton vault record carries no
            // timestamp, and a null `Folder.updatedAt` loses every rename tie-break.
            var folderId: UUID? = null
            fun folderIdOf(): UUID? {
                val name = vault.name.trim().takeIf { it.isNotEmpty() } ?: return null
                return folderId ?: UUID.randomUUID().also {
                    folderId = it
                    folders += Folder(id = it, name = name, updatedAt = now)
                }
            }
            vault.items.forEach { item ->
                val title = item.data.metadata.name.ifBlank { titles.untitledItem }
                if (item.state == STATE_TRASHED) {
                    skipped += SkippedEntry(title, SkipReason.SourceRecycleBin)
                    return@forEach
                }
                if (item.data.type == TYPE_SSH_KEY) {
                    skipped += SkippedEntry(title, SkipReason.SshKey)
                    return@forEach
                }
                items += mapItem(item, title, folderIdOf(), titles)
            }
        }

        Outcome.Success(
            ParsedImport(items = items, skipped = skipped, folders = folders, timestampsKnown = true),
        )
    } catch (t: ImportRejection) {
        Outcome.Failure(AppError.Validation("Proton Pass import rejected (${t.reason})", t))
    } catch (t: Throwable) {
        // The whole cause chain, because the parser wraps what it throws — same shape as
        // AegisJsonImporter's. An OutOfMemoryError is not a malformed file and must not be
        // described to the user as one.
        val reason = if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
            ImportFailureReason.ExportNeedsMoreMemory(sourceFormat)
        } else {
            ImportFailureReason.MalformedExport(sourceFormat)
        }
        Outcome.Failure(
            AppError.Validation("failed to parse Proton Pass export", ImportRejection(reason, t)),
        )
    }

    private fun mapItem(
        item: ProtonPassItem,
        title: String,
        folderId: UUID?,
        titles: ImportDefaultTitles,
    ): VaultItem {
        val note = item.data.metadata.note
        val extras = mutableListOf<CustomField>()
        var totpFromExtras: Totp? = null
        item.data.extraFields.forEach { field ->
            val value = field.data.value
            if (value.isBlank()) return@forEach
            if (field.type == FIELD_TOTP) {
                val parsed = TotpFieldParser.parse(value)
                if (parsed != null && totpFromExtras == null) {
                    totpFromExtras = parsed
                } else {
                    // Unparseable seeds are rescued into a HIDDEN field rather than dropped — the
                    // same treatment the CSV path gives a TOTP column it cannot read.
                    extras += CustomField(
                        name = field.fieldName.ifBlank { titles.rescuedTotpFieldName },
                        value = value,
                        type = CustomFieldType.HIDDEN,
                    )
                }
                return@forEach
            }
            extras += CustomField(
                name = field.fieldName,
                value = value,
                type = if (field.type == FIELD_HIDDEN) CustomFieldType.HIDDEN else CustomFieldType.TEXT,
            )
        }

        val content = item.data.content as? JsonObject ?: JsonObject(emptyMap())
        val customFields = extras.toMutableList()
        val mapped = when (item.data.type) {
            TYPE_LOGIN -> loginOf(content, note, totpFromExtras, customFields)
            TYPE_ALIAS -> Login(
                // An alias item's content is empty; the generated address lives beside it, and it
                // is the only thing the item is for — so it is the username, not a custom field.
                username = item.aliasEmail.orEmpty(),
                notes = note,
            )
            TYPE_CREDIT_CARD -> cardOf(content, note)
            TYPE_NOTE -> Note(body = note)
            TYPE_IDENTITY -> identityOf(content, note, customFields, titles)
            // `custom`, and any type a later Proton build adds. Everything scalar in `content` is
            // carried as a custom field rather than skipped: the alternative needs a new
            // SkipReason this package cannot add (see the report), and a Note that keeps the
            // user's values is strictly better than a counted loss.
            else -> {
                customFields += scalarFields(content)
                Note(body = note)
            }
        }

        return VaultItem(
            id = UUID.randomUUID(),
            title = title,
            folderId = folderId,
            favorite = item.pinned,
            createdAt = Instant.ofEpochSecond(item.createTime),
            updatedAt = Instant.ofEpochSecond(item.modifyTime),
            customFields = customFields,
            content = mapped,
        )
    }

    private fun loginOf(
        content: JsonObject,
        note: String,
        totpFromExtras: Totp?,
        customFields: MutableList<CustomField>,
    ): Login {
        val email = contentString(content, "itemEmail")
        val username = contentString(content, "itemUsername")
        // `username` wins and `email` is carried beside it, which is the rule
        // ProtonPassCsvUsernameTest already pins for this same source's CSV: one Login.username,
        // and the loser named by the source's own key rather than dropped.
        val chosen = username.ifBlank { email }
        if (username.isNotBlank() && email.isNotBlank()) {
            customFields += CustomField(name = "itemEmail", value = email, type = CustomFieldType.TEXT)
        }
        val totpUri = contentString(content, "totpUri")
        val totp = TotpFieldParser.parse(totpUri) ?: totpFromExtras
        if (totpUri.isNotBlank() && totp == null) {
            customFields += CustomField(
                name = "totpUri",
                value = totpUri,
                type = CustomFieldType.HIDDEN,
            )
        }
        return Login(
            username = chosen,
            password = contentString(content, "password"),
            uris = stringList(content, "urls").map { UriMatch(uri = it) },
            totp = totp,
            notes = note,
        )
    }

    private fun cardOf(content: JsonObject, note: String): Card {
        val number = contentString(content, "number")
        val expiry = ProtonPassExpiry.of(contentString(content, "expirationDate"))
        return Card(
            holder = contentString(content, "cardholderName"),
            number = number,
            brand = ImportedCardBrand.of(number, null),
            expMonth = expiry?.first,
            expYear = expiry?.second,
            cvv = contentString(content, "verificationNumber"),
            pin = contentString(content, "pin"),
            notes = note,
        )
    }

    private fun identityOf(
        content: JsonObject,
        note: String,
        customFields: MutableList<CustomField>,
        titles: ImportDefaultTitles,
    ): Identity {
        // Identity has no notes field, so the item's own note is rescued under the same name
        // BitwardenJsonImporter, OnePuxImporter and AegisJsonImporter all use for this.
        if (note.isNotBlank()) {
            customFields += CustomField(
                name = titles.identityNotesFieldName,
                value = note,
                type = CustomFieldType.TEXT,
            )
        }
        customFields += scalarFields(content, IDENTITY_MAPPED_KEYS)
        return Identity(
            firstName = contentString(content, "firstName"),
            lastName = contentString(content, "lastName"),
            company = contentString(content, "organization"),
            ssn = contentString(content, "socialSecurityNumber"),
            passport = contentString(content, "passportNumber"),
            license = contentString(content, "licenseNumber"),
            email = contentString(content, "email"),
            phone = contentString(content, "phoneNumber"),
            address = Address(
                line1 = contentString(content, "streetAddress"),
                city = contentString(content, "city"),
                state = contentString(content, "stateOrProvince"),
                zip = contentString(content, "zipOrPostalCode"),
                country = contentString(content, "countryOrRegion"),
            ),
        )
    }

    /** Every scalar key in [content] not already mapped onto a model field, as TEXT custom fields. */
    private fun scalarFields(content: JsonObject, except: Set<String> = emptySet()): List<CustomField> =
        content.entries
            .filter { (key, value) -> key !in except && value is JsonPrimitive && value.content.isNotBlank() }
            .map { (key, value) -> CustomField(name = key, value = (value as JsonPrimitive).content) }

    /** A string-valued key, or "" — never a throw, because [content] is untrusted input. */
    private fun contentString(content: JsonObject, key: String): String =
        (content[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun stringList(content: JsonObject, key: String): List<String> =
        (content[key] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.filter { it.isNotBlank() }
            .orEmpty()

    private const val STATE_TRASHED = 2
    private const val TYPE_LOGIN = "login"
    private const val TYPE_ALIAS = "alias"
    private const val TYPE_NOTE = "note"
    private const val TYPE_CREDIT_CARD = "creditCard"
    private const val TYPE_IDENTITY = "identity"
    private const val TYPE_SSH_KEY = "sshKey"
    private const val FIELD_HIDDEN = "hidden"
    private const val FIELD_TOTP = "totp"

    /** Keys [identityOf] reads onto model fields, so they are not also carried as custom fields. */
    private val IDENTITY_MAPPED_KEYS = setOf(
        "firstName", "lastName", "organization", "email", "phoneNumber",
        "socialSecurityNumber", "passportNumber", "licenseNumber",
        "streetAddress", "city", "stateOrProvince", "zipOrPostalCode", "countryOrRegion",
    )
}

/**
 * Proton Pass's card expiry, `YYYY-MM`.
 *
 * `MM/YYYY` and `MM/YY` are accepted too, not out of generosity but because a card expiry is copied
 * between password managers constantly and a shape this refuses lands the user with a card that has
 * no expiry at all and no indication one was in the file.
 */
internal object ProtonPassExpiry {
    fun of(raw: String): Pair<Int, Int>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('-', '/').map { it.trim() }
        if (parts.size != 2) return null
        val (first, second) = parts
        val a = first.toIntOrNull() ?: return null
        val b = second.toIntOrNull() ?: return null
        // Whichever side is four digits is the year; a two-digit year is 20xx, the only reading a
        // payment card admits.
        return when {
            first.length == 4 -> if (b in 1..12) b to a else null
            second.length == 4 -> if (a in 1..12) a to b else null
            a in 1..12 -> a to 2000 + b
            else -> null
        }
    }
}
