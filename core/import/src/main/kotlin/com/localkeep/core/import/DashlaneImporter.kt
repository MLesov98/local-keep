package com.localkeep.core.import

import com.localkeep.core.common.AppError
import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Address
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Note
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID

/**
 * Dashlane's four non-credential CSV exports.
 *
 * Dashlane does not export one file — it exports one per record kind, and only
 * `credentials.csv` / `credentials-otpurl.csv` are logins. Those two have a password column and are
 * already read by [CsvImporter]; the other four do not, so nothing recognised them at all and a
 * user migrating from Dashlane got their logins and nothing else — with the wizard's last step then
 * offering to securely delete the export.
 *
 * Recognised by **header set**, exactly, rather than by synonym matching. These headers are fixed
 * by Dashlane's exporter and each one names its record kind in its own `type` column, so an exact
 * match is both available and the only honest signal: `payments.csv`'s `cc_number` means a card
 * number, and being *recognised* by the generic mapper would not have meant being *understood*.
 */
object DashlaneImporter : Importer {
    /**
     * The four non-credential files only. A Dashlane `credentials.csv` goes through [CsvImporter]
     * and still reports [SourceFormat.CSV], which is what it is.
     */
    override val sourceFormat: SourceFormat = SourceFormat.DASHLANE

    override fun detect(bytes: ByteArray): Boolean =
        runCatching { schemaOf(CsvColumnMapper.parseRows(bytes)) != null }.getOrDefault(false)

    // Manual try/catch rather than runCatchingOutcome, for the reason BitwardenJsonImporter states.
    override fun parse(bytes: ByteArray, titles: ImportDefaultTitles): Outcome<ParsedImport> = try {
        val rows = CsvColumnMapper.parseRows(bytes)
        val schema = schemaOf(rows)
            ?: throw ImportRejection(ImportFailureReason.MalformedExport(sourceFormat))
        val header = rows.first().map { it.trim().lowercase() }
        val now = Instant.now()
        val items = mutableListOf<VaultItem>()
        val skipped = mutableListOf<SkippedEntry>()

        rows.drop(1).filter { row -> row.any(String::isNotBlank) }.forEach { row ->
            val record = DashlaneRow(header, row)
            val mapped = when (schema) {
                DashlaneSchema.ID -> identityDocument(record, titles)
                DashlaneSchema.PAYMENTS -> payment(record, titles)
                DashlaneSchema.PERSONAL_INFO -> personalInfo(record, titles)
                DashlaneSchema.SECURE_NOTES -> secureNote(record, titles)
            }
            if (mapped == null) {
                skipped += SkippedEntry(record.title(titles), SkipReason.IncompleteEntry)
            } else {
                items += mapped.copy(id = UUID.randomUUID(), createdAt = now, updatedAt = now)
            }
        }

        // `timestampsKnown = false`: not one of these four files carries a per-row time, so both
        // stamps are the import instant and mean nothing in a merge (see [ParsedImport]).
        Outcome.Success(ParsedImport(items = items, skipped = skipped, timestampsKnown = false))
    } catch (t: ImportRejection) {
        Outcome.Failure(AppError.Validation("Dashlane import rejected (${t.reason})", t))
    } catch (t: Throwable) {
        val reason = if (generateSequence(t) { it.cause }.any { it is OutOfMemoryError }) {
            ImportFailureReason.ExportNeedsMoreMemory(sourceFormat)
        } else {
            ImportFailureReason.MalformedExport(sourceFormat)
        }
        Outcome.Failure(AppError.Validation("failed to parse Dashlane export", ImportRejection(reason, t)))
    }

    private fun schemaOf(rows: List<List<String>>): DashlaneSchema? {
        val header = rows.firstOrNull()?.map { it.trim().lowercase() }?.toSet() ?: return null
        return DashlaneSchema.entries.firstOrNull { it.header == header }
    }

    /**
     * `id.csv` — passport, driver's licence, national ID card, SSN and tax number, all in one file
     * and told apart by the row's own `type` cell.
     *
     * Each becomes an [Identity], which is the only content type here with a place for a passport
     * or licence number at all. The three that have no model field (`card`, `tax_number`, and any
     * type a later Dashlane adds) keep their number as a custom field named by the source's own
     * `number` column rather than being dropped.
     */
    private fun identityDocument(row: DashlaneRow, titles: ImportDefaultTitles): VaultItem? {
        val number = row["number"]
        val name = row["name"]
        if (number.isBlank() && name.isBlank()) return null
        val (first, last) = splitName(name)
        val identity = when (row["type"]) {
            "passport" -> Identity(firstName = first, lastName = last, passport = number)
            "license" -> Identity(firstName = first, lastName = last, license = number)
            "social_security" -> Identity(firstName = first, lastName = last, ssn = number)
            else -> Identity(firstName = first, lastName = last)
        }
        val consumed = when (row["type"]) {
            "passport", "license", "social_security" -> setOf("name", "number")
            else -> setOf("name")
        }
        return item(
            // Not `row["type"]`: that is the source's internal key ("tax_number"), and it would read as a
            // title the user typed. It is still carried below as a custom field.
            title = name.ifBlank { titles.untitledRow },
            content = identity.copy(
                address = Address(state = row["state"]),
            ),
            customFields = row.remaining(consumed + "state"),
        )
    }

    /**
     * `payments.csv` — bank accounts and credit cards in one file, again split by `type`.
     *
     * A credit card becomes a [Card]; a bank account becomes a [Note], because [Card] has no place
     * for a routing or account number and putting one in `Card.number` would show the user a
     * payment card that is not one. Its fields survive as custom fields, the two secret ones
     * HIDDEN.
     */
    private fun payment(row: DashlaneRow, titles: ImportDefaultTitles): VaultItem? {
        val title = row["account_name"].ifBlank { row["account_holder"] }
        return when (row["type"]) {
            "credit_card" -> {
                val number = row["cc_number"]
                if (number.isBlank()) return null
                item(
                    title = title.ifBlank { titles.untitledRow },
                    content = Card(
                        holder = row["account_holder"].ifBlank { row["account_name"] },
                        number = number,
                        brand = ImportedCardBrand.of(number, null),
                        expMonth = row["expiration_month"].toIntOrNull(),
                        expYear = row["expiration_year"].toIntOrNull(),
                        cvv = row["code"],
                    ),
                    customFields = row.remaining(CARD_CONSUMED),
                )
            }
            else -> item(
                title = title.ifBlank { titles.untitledRow },
                content = Note(),
                customFields = row.remaining(setOf("type", "account_name")),
            )
        }
    }

    /**
     * `personal-info.csv` — one row per fact about a person (a name, an email, a phone number, an
     * address, a website), not one row per person.
     *
     * Each row becomes its own [Identity] rather than being merged into one: `multiple-personal-info.csv`
     * carries two `name` rows, so there is more than one person in the file and nothing in it says
     * which of the other rows belongs to which. Merging would invent that link; one item per row
     * carries exactly what the export states.
     */
    private fun personalInfo(row: DashlaneRow, titles: ImportDefaultTitles): VaultItem? {
        if (row.isEmptyApartFromType()) return null
        val identity = Identity(
            firstName = row["first_name"],
            lastName = row["last_name"],
            email = row["email"],
            phone = row["phone_number"],
            address = Address(
                line1 = row["address"],
                city = row["city"],
                state = row["state"],
                zip = row["zip"],
                country = row["country"],
            ),
        )
        val fullName = listOf(row["first_name"], row["last_name"]).filter { it.isNotBlank() }
            .joinToString(" ")
        return item(
            title = row["item_name"].ifBlank { fullName }.ifBlank { titles.untitledRow },
            content = identity,
            customFields = row.remaining(PERSONAL_INFO_CONSUMED),
        )
    }

    /** `securenotes.csv` — `title,note`, and nothing else to decide. */
    private fun secureNote(row: DashlaneRow, titles: ImportDefaultTitles): VaultItem? {
        val note = row["note"]
        val title = row["title"]
        if (note.isBlank() && title.isBlank()) return null
        return item(
            title = title.ifBlank { titles.untitledRow },
            content = Note(body = note),
            customFields = emptyList(),
        )
    }

    /**
     * A person's name as Dashlane writes it — one cell, `John Doe`.
     *
     * Split on the last space, so `Mary Jane Watson` keeps `Mary Jane` as the first name rather
     * than losing the middle one: [Identity] has no middle-name field, and a name the user cannot
     * find their own record by is worse than one whose parts sit slightly off.
     */
    private fun splitName(name: String): Pair<String, String> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "" to ""
        val cut = trimmed.lastIndexOf(' ')
        return if (cut < 0) trimmed to "" else trimmed.substring(0, cut) to trimmed.substring(cut + 1)
    }

    /** Ids and timestamps are set once by the caller, so every mapper here returns a shell. */
    private fun item(title: String, content: ItemContent, customFields: List<CustomField>): VaultItem =
        VaultItem(
            id = PLACEHOLDER_ID,
            title = title,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            customFields = customFields,
            content = content,
        )

    private val PLACEHOLDER_ID: UUID = UUID(0L, 0L)

    private val CARD_CONSUMED = setOf(
        "type", "account_name", "account_holder", "cc_number", "code",
        "expiration_month", "expiration_year",
    )

    private val PERSONAL_INFO_CONSUMED = setOf(
        "type", "first_name", "last_name", "email", "phone_number",
        "address", "city", "state", "zip", "country", "item_name",
    )
}

/** Which of Dashlane's four non-credential exports a file is, decided by its exact header set. */
internal enum class DashlaneSchema(val header: Set<String>) {
    ID(setOf("type", "number", "name", "issue_date", "expiration_date", "place_of_issue", "state")),
    PAYMENTS(
        setOf(
            "type", "account_name", "account_holder", "cc_number", "code", "expiration_month",
            "expiration_year", "routing_number", "account_number", "country", "issuing_bank",
        ),
    ),
    PERSONAL_INFO(
        setOf(
            "type", "title", "first_name", "middle_name", "last_name", "login", "date_of_birth",
            "place_of_birth", "email", "email_type", "item_name", "phone_number", "address",
            "country", "state", "city", "zip", "address_recipient", "address_building",
            "address_apartment", "address_floor", "address_door_code", "job_title", "url",
        ),
    ),

    /**
     * `title,note`, which is as generic a header as a CSV has.
     *
     * Claiming it is still right: it has no address, username or password column, so [CsvImporter]
     * refuses it and the alternative to claiming it is the wizard saying it does not recognise the
     * file. A two-column `title,note` CSV from anywhere at all imports as notes, which is what it
     * is.
     */
    SECURE_NOTES(setOf("title", "note")),
}

/** One data row, addressed by its header name rather than by column index. */
internal class DashlaneRow(private val header: List<String>, private val row: List<String>) {

    operator fun get(column: String): String {
        val index = header.indexOf(column)
        return if (index < 0) "" else row.getOrElse(index) { "" }.trim()
    }

    /** True when the row states nothing but its own kind — the blank trailing line of an export. */
    fun isEmptyApartFromType(): Boolean =
        header.indices.none { header[it] != "type" && row.getOrElse(it) { "" }.isNotBlank() }

    /**
     * Every non-blank cell not already read onto a model field, as a custom field named by its own
     * column header.
     *
     * Source-named, not authored copy: `place_of_issue` and `date_of_birth` are Dashlane's words,
     * shown the way every other rescued column in this module is. The `type` column is always
     * consumed by the caller — it is what chose the mapping, not a fact about the record.
     */
    fun remaining(consumed: Set<String>): List<CustomField> = header.indices
        .mapNotNull { index ->
            val name = header[index]
            val value = row.getOrElse(index) { "" }.trim()
            if (name in consumed || name.isBlank() || value.isBlank()) {
                null
            } else {
                CustomField(
                    name = name,
                    value = value,
                    type = if (name in SENSITIVE_COLUMNS) CustomFieldType.HIDDEN else CustomFieldType.TEXT,
                )
            }
        }

    fun title(titles: ImportDefaultTitles): String =
        this["item_name"].ifBlank { this["account_name"] }.ifBlank { this["name"] }
            .ifBlank { this["title"] }
            .ifBlank { titles.untitledRow }

    private companion object {
        /** Columns whose value is a secret — masked the way a HIDDEN custom field is. */
        val SENSITIVE_COLUMNS = setOf("routing_number", "account_number", "code", "cc_number")
    }
}
