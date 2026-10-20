package com.localkeep.core.import

import com.localkeep.core.model.Address
import com.localkeep.core.model.Card
import com.localkeep.core.model.CustomField
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.ItemContent
import com.localkeep.core.model.Note

/**
 * LastPass's secure notes, which its CSV export writes as login rows.
 *
 * Every secure note leaves LastPass with `http://sn` in the `url` column — a sentinel, not an
 * address — and its whole content as `Key:Value` lines in `extra`. Read as a login, the row became
 * a [com.localkeep.core.model.Login] pointing at a host called `sn`: autofill-eligible for it, and
 * counted by the health dashboard as a login with a blank password (found against a real
 * LastPass export).
 *
 * The blob's first line names a LastPass note template, and four of them have a closer home here
 * than a note: a Credit Card is a [Card], a Passport and a Driver's License are each an [Identity].
 * Everything else — Bank Account, Server, Software Licence, a plain note — stays a [Note] carrying
 * the blob verbatim.
 *
 * **Nothing is dropped.** A field this app has no home for is carried as a [CustomField] under the
 * label LastPass itself wrote, which is data read out of the export rather than copy this module
 * would have to author (it cannot — see [ImportFailureReason]'s kdoc).
 */
internal object LastPassSecureNote {

    /** The `url` cell LastPass writes on every secure note. */
    private const val SENTINEL = "http://sn"

    /** What one blob became: the item's content, plus the labelled fields that had no home in it. */
    data class Mapped(val content: ItemContent, val customFields: List<CustomField>)

    fun isSentinel(url: String): Boolean = url.trim().equals(SENTINEL, ignoreCase = true)

    fun map(extra: String): Mapped {
        val fields = parse(extra)
        return when (fields["NoteType"]?.trim()?.lowercase()) {
            "credit card" -> card(fields)
            "passport" -> identity(fields, passport = fields["Number"].orEmpty())
            "driver's license", "drivers license" -> identity(fields, license = fields["Number"].orEmpty())
            else -> Mapped(Note(body = extra), emptyList())
        }
    }

    private fun card(fields: Map<String, String>): Mapped {
        val number = fields["Number"].orEmpty()
        val expiry = monthAndYear(fields["Expiration Date"])
        val consumed = setOf("NoteType", "Name on Card", "Type", "Number", "Security Code", "Notes") +
            if (expiry != null) setOf("Expiration Date") else emptySet()
        return Mapped(
            Card(
                holder = fields["Name on Card"].orEmpty(),
                number = number,
                // The digits decide the brand, and LastPass's `Type` is the fallback — see
                // ImportedCardBrand for why that order and not the other one.
                brand = ImportedCardBrand.of(number, fields["Type"]),
                expMonth = expiry?.first,
                expYear = expiry?.second,
                cvv = fields["Security Code"].orEmpty(),
                notes = fields["Notes"].orEmpty(),
            ),
            rest(fields, consumed),
        )
    }

    private fun identity(fields: Map<String, String>, passport: String = "", license: String = ""): Mapped {
        val name = fields["Name"].orEmpty().trim()
        val consumed = setOf(
            "NoteType", "Name", "Number",
            "Address", "City / Town", "State", "ZIP / Postal Code", "Country",
        )
        return Mapped(
            Identity(
                // First token is the given name, the rest the family name. Crude and stated so: the
                // export gives one `Name` field and this app has two, and putting the whole string
                // in one of them reads wrong on every screen that shows a name.
                firstName = name.substringBefore(' '),
                lastName = name.substringAfter(' ', missingDelimiterValue = ""),
                passport = passport,
                license = license,
                address = Address(
                    line1 = fields["Address"].orEmpty(),
                    city = fields["City / Town"].orEmpty(),
                    state = fields["State"].orEmpty(),
                    zip = fields["ZIP / Postal Code"].orEmpty(),
                    country = fields["Country"].orEmpty(),
                ),
            ),
            rest(fields, consumed),
        )
    }

    /** Every field the content above had no home for, under the label the export wrote. */
    private fun rest(fields: Map<String, String>, consumed: Set<String>): List<CustomField> =
        fields.filterKeys { it !in consumed }
            .filterValues { it.isNotBlank() }
            .map { (label, value) -> CustomField(name = label, value = value, type = CustomFieldType.TEXT) }

    /**
     * `June,2020` → 6 and 2020. Null whenever either half is unreadable, and the whole line is then
     * carried as a custom field instead — a wrong expiry on a card is worse than an absent one, and
     * LastPass writes the month as a word, in whatever language the account was using.
     */
    private fun monthAndYear(cell: String?): Pair<Int, Int>? {
        val parts = cell?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        val year = parts.firstNotNullOfOrNull { it.toIntOrNull()?.takeIf { n -> n in 1000..9999 } } ?: return null
        val month = parts.firstNotNullOfOrNull { part ->
            part.toIntOrNull()?.takeIf { it in 1..12 }
                ?: MONTHS.indexOfFirst { it.equals(part, ignoreCase = true) }.takeIf { it >= 0 }?.plus(1)
        } ?: return null
        return month to year
    }

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /**
     * The blob's `Key:Value` lines, in the order LastPass wrote them.
     *
     * A line with no colon continues the value above it: `Notes:` is the last key in every template
     * and the user's own text follows it, newlines and all.
     */
    private fun parse(extra: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var key = ""
        for (line in extra.lines()) {
            val colon = line.indexOf(':')
            // A key never starts with whitespace, so an indented line inside a user's own note text
            // stays part of the value above it rather than inventing a field.
            if (colon > 0 && line.first().isWhitespace().not()) {
                key = line.substring(0, colon)
                out[key] = line.substring(colon + 1)
            } else if (key.isNotEmpty()) {
                out[key] = out.getValue(key) + "\n" + line
            }
        }
        return out.mapValues { it.value.trim() }
    }
}
