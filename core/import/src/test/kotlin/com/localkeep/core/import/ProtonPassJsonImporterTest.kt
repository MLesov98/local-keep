package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.failureReason
import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Identity
import com.localkeep.core.model.Login
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [ProtonPassJsonImporter]'s own behaviour: recognition, the identity mapping, and expiry shapes. */
class ProtonPassJsonImporterTest {

    /**
     * `vaults` as an **object** is the signal, and the two halves of that are both load-bearing: a
     * document with a `vaults` *array* is some other format, and one with no `version` beside it is
     * not a Proton Pass export either.
     */
    @Test
    fun `recognition needs a vaults object and a version`() {
        assertTrue(
            ProtonPassJsonImporter.detect("""{"version":"1.21.2","vaults":{}}""".toByteArray()),
        )
        assertFalse(
            ProtonPassJsonImporter.detect("""{"version":"1.21.2","vaults":[]}""".toByteArray()),
            "an array of vaults is not this format",
        )
        assertFalse(
            ProtonPassJsonImporter.detect("""{"vaults":{}}""".toByteArray()),
            "no version — some other document with a vaults object",
        )
    }

    /**
     * `username` wins over `email` and the loser is carried beside it, which is the rule
     * [ProtonPassCsvUsernameTest] already pins for this same source's CSV. A login with only an
     * `itemEmail` still gets a username, which is the control that rule needs.
     */
    @Test
    fun `itemUsername wins and itemEmail is carried beside it`() {
        val both = protonLogin("""{"itemEmail":"ada@example.com","itemUsername":"realuser","password":"pw"}""")
        assertEquals("realuser", (both.content as Login).username)
        assertEquals("ada@example.com", both.customFields.single { it.name == "itemEmail" }.value)

        val emailOnly = protonLogin("""{"itemEmail":"ada@example.com","password":"pw"}""")
        assertEquals("ada@example.com", (emailOnly.content as Login).username)
        assertTrue(emailOnly.customFields.isEmpty(), "nothing to carry, nothing carried")
    }

    /**
     * An `identity` item's document numbers have [Identity] fields of their own, and leaving them
     * in the generic custom-field sweep would file a passport number as loose text on an item type
     * that has a place for it.
     */
    @Test
    fun `an identity item fills its own document number fields`() {
        val path = "protonpass/export-bitwarden-fixture.json"
        val parsed = ProtonPassJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
        val item = parsed.items.first { it.title == "Identity" }
        val identity = item.content as Identity

        assertEquals("98378264782", identity.ssn)
        assertEquals("7173716378612", identity.passport)
        assertEquals("21234", identity.license)
        assertEquals("Bitwarden", identity.company)
        assertEquals("test@gmail.com", identity.email)
        assertEquals("New York", identity.address.city)
        // Everything the model has no field for is still carried, named by Proton's own key.
        assertEquals("Male", item.customFields.first { it.name == "gender" }.value)
        assertFalse(
            item.customFields.any { it.name == "passportNumber" },
            "a mapped key must not also arrive as a custom field",
        )
    }

    /** `hidden` extra fields are secrets and arrive masked; `text` ones do not. */
    @Test
    fun `extra field types decide whether a custom field is masked`() {
        val path = "protonpass/export.json"
        val login = ProtonPassJsonImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)
            .items.first { it.title == "Test Login" }

        assertEquals(
            CustomFieldType.HIDDEN,
            login.customFields.first { it.name == "hidden field" }.type,
        )
        assertEquals(
            CustomFieldType.TEXT,
            login.customFields.first { it.name == "non-hidden field" }.type,
        )
    }

    /**
     * Proton writes `YYYY-MM`. The other two shapes are accepted because a card expiry is copied
     * between password managers constantly, and a shape this refuses leaves the user with a card
     * that has no expiry and no sign that one was in the file.
     */
    @Test
    fun `card expiry is read in every shape a card carries one`() {
        assertEquals(1 to 2025, ProtonPassExpiry.of("2025-01"))
        assertEquals(6 to 2030, ProtonPassExpiry.of("06/2030"))
        assertEquals(3 to 2023, ProtonPassExpiry.of("03/23"))
        assertEquals(null, ProtonPassExpiry.of(""))
        assertEquals(null, ProtonPassExpiry.of("not a date"))
        assertEquals(null, ProtonPassExpiry.of("2025-13"), "there is no thirteenth month")
    }

    /** A claimed file that will not decode fails with a named reason rather than throwing. */
    @Test
    fun `a malformed vaults object fails with a typed reason`() {
        val broken = """{"version":"1","vaults":{"A":42}}""".toByteArray()
        assertTrue(ProtonPassJsonImporter.detect(broken))
        assertEquals(
            ImportFailureReason.MalformedExport(SourceFormat.PROTONPASS),
            ProtonPassJsonImporter.parse(broken).failureReason("vaults-of-numbers"),
        )
    }

    private fun protonLogin(content: String) = ProtonPassJsonImporter.parse(
        """
        {"version":"1.0.0","encrypted":false,"vaults":{"V":{"name":"Personal","items":[
          {"data":{"metadata":{"name":"Example","note":""},"extraFields":[],"type":"login",
           "content":$content},"state":1,"createTime":1,"modifyTime":1,"pinned":false}
        ]}}}
        """.trimIndent().toByteArray(),
    ).orFail("inline").items.single()
}
