package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.VaultItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Bitwarden identity loses nothing it carried, even where [Identity] has no field for it.
 *
 * Device report C3 finding 3 (2026-10-04): `plain-export.json`'s "My Identity" arrived without its
 * title ("Mrs"), middle name ("A") and username ("myusername"). [Identity] has none of the three,
 * the importer simply did not read them, and neither the review nor the result said anything —
 * the item counted as fully imported. Every other importer here carries a value it has no model
 * slot for as a custom field named by the source's own key (`DashlaneRow.remaining`, Enpass,
 * 1Password), and that is what this pins for Bitwarden.
 *
 * Against the real export, not a hand-written one, so the keys are the ones Bitwarden writes.
 */
class BitwardenIdentityKeepsEveryFieldTest {

    private val path = "bitwarden/plain-export.json"

    private fun identityItem(): VaultItem =
        BitwardenJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            .items.first { it.content is Identity }

    @Test
    fun `title, middle name and username survive as custom fields`() {
        val fields = identityItem().customFields.associateBy { it.name }

        assertEquals("Mrs", fields["title"]?.value, "title")
        assertEquals("A", fields["middleName"]?.value, "middle name")
        assertEquals("myusername", fields["username"]?.value, "username")
        // Plain text, like the source: none of the three is a secret Bitwarden masked.
        listOf("title", "middleName", "username").forEach {
            assertEquals(CustomFieldType.TEXT, fields[it]?.type, it)
        }
    }

    @Test
    fun `every value in the export's identity is somewhere on the imported item`() {
        // The general form of the finding: read the file's own identity object and require each
        // non-blank value to land on a model field or a custom field. A future Bitwarden key the
        // mapper has never heard of fails here instead of vanishing.
        val source = Json.parseToJsonElement(RealWorldCorpus.text(path)).jsonObject
            .getValue("items").jsonArray
            .map { it.jsonObject }
            .first { (it["type"] as? JsonPrimitive)?.contentOrNull == "4" }
            .getValue("identity").jsonObject
        val item = identityItem()
        val identity = item.content as Identity
        val carried = listOf(
            identity.firstName, identity.lastName, identity.company, identity.ssn,
            identity.passport, identity.license, identity.email, identity.phone,
            identity.address.line1, identity.address.line2, identity.address.city,
            identity.address.state, identity.address.zip, identity.address.country,
        ).map(String::trim) + item.customFields.map { it.value.trim() }

        source.forEach { (key, value) ->
            val text = value.jsonPrimitive.contentOrNull?.trim().orEmpty()
            if (text.isNotEmpty()) {
                assertTrue(text in carried, "identity.$key = \"$text\" was dropped on import")
            }
        }
    }
}
