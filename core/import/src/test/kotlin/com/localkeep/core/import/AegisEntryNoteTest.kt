package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.CustomFieldType
import com.localkeep.core.model.Totp
import com.localkeep.core.model.TotpAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An Aegis entry's own note is user-authored text, and it was being discarded.
 *
 * `AegisEntry` declared type/name/issuer/favorite/info and nothing else, so `ignoreUnknownKeys`
 * dropped a `note` key before `parse` ever saw it — and the resulting item carried no trace of it:
 * no custom field, no skip entry, on an import that reported full success. Different in kind from
 * the base64 `icon` this importer is right to ignore: "backup codes taped inside the router" is
 * something a person wrote, and the wizard's last step then offers to delete the export.
 *
 * [Totp] has no notes field of its own, which is the same shape of gap `BitwardenJsonImporter` and
 * `OnePuxImporter` already rescue for `Identity` — so it uses the same rescue and the same default
 * field name (4-2, 2026-09-13).
 */
class AegisEntryNoteTest {

    private fun parse(json: String): ParsedImport =
        when (val outcome = AegisJsonImporter.parse(json.toByteArray())) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure -> error("expected a successful parse, got: ${outcome.error.message}")
        }

    @Test
    fun `an entry's own note survives as a custom field`() {
        val parsed = parse(
            """
            { "version": 1, "db": { "version": 2, "entries": [
                { "type": "totp", "name": "alice", "issuer": "GitHub",
                  "note": "backup codes taped inside the router",
                  "info": { "secret": "JBSWY3DPEHPK3PXP" } }
            ] } }
            """.trimIndent(),
        )

        val item = parsed.items.single()
        val field = item.customFields.single()
        assertEquals("Notes", field.name)
        assertEquals("backup codes taped inside the router", field.value)
        assertEquals(CustomFieldType.TEXT, field.type)
    }

    /** A Steam token can carry a note too, and goes down the other of the two item branches. */
    @Test
    fun `a Steam entry's note survives the same way`() {
        val parsed = parse(
            """
            { "version": 1, "db": { "version": 2, "entries": [
                { "type": "steam", "name": "gamer", "issuer": "Steam", "note": "the recovery code is offline",
                  "info": { "secret": "JBSWY3DPEHPK3PXP" } }
            ] } }
            """.trimIndent(),
        )

        assertEquals("the recovery code is offline", parsed.items.single().customFields.single().value)
    }

    /**
     * The control: an entry with no note must not grow an empty field, and nothing about the code
     * itself may move — the secret and its parameters are what the import exists to carry.
     */
    @Test
    fun `an entry with no note imports unchanged`() {
        val parsed = parse(
            """
            { "version": 1, "db": { "version": 2, "entries": [
                { "type": "totp", "name": "alice", "issuer": "GitHub",
                  "info": { "secret": "JBSWY3DPEHPK3PXP", "digits": 8, "period": 60, "algo": "SHA256" } }
            ] } }
            """.trimIndent(),
        )

        val item = parsed.items.single()
        assertTrue(item.customFields.isEmpty(), "no note means no custom field")
        val totp = item.content as Totp
        assertEquals("JBSWY3DPEHPK3PXP", totp.secretBase32)
        assertEquals(TotpAlgorithm.SHA256, totp.algorithm)
        assertEquals(8, totp.digits)
        assertEquals(60, totp.periodSec)
        assertEquals("GitHub", totp.issuer)
        assertEquals("alice", totp.account)
    }

    /** A `note` key that is present but empty is not content, and must not become a blank field. */
    @Test
    fun `a blank note grows no custom field`() {
        val parsed = parse(
            """
            { "version": 1, "db": { "version": 2, "entries": [
                { "type": "totp", "name": "alice", "note": "   ",
                  "info": { "secret": "JBSWY3DPEHPK3PXP" } }
            ] } }
            """.trimIndent(),
        )

        assertTrue(parsed.items.single().customFields.isEmpty())
    }
}
