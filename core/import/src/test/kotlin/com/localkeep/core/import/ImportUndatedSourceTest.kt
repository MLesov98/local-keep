package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Login
import com.localkeep.core.model.UriMatch
import com.localkeep.core.model.VaultItem
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An import that carries no dates must not be able to win a collision on the dates it invented.
 *
 * CSV and Aegis have nowhere to put a per-entry timestamp, so every item they produce is stamped
 * `Instant.now()` — which is newer than anything in the vault by construction. `ImportMerger` then
 * handed the collision to the import and `ItemMerge.preserveStructure` handed it `title`, so
 * re-importing an unchanged export silently reverted every rename and edit the user had made since
 * the last import. The preview said the same thing out loud: "newer than your …", about a file with
 * no notion of newer (S1, 2026-09-15).
 *
 * Bitwarden, 1Password and KDBX write real timestamps and are deliberately unaffected — the control
 * cases below are as much the point of this file as the CSV ones.
 */
class ImportUndatedSourceTest {

    private val longAgo: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val vaultId: UUID = UUID.randomUUID()

    /** What the user has: the same login, renamed by hand, and not touched since. */
    private fun vaultCopy(
        title: String = "GitHub — work account",
        password: String = "hunter2",
        notes: String = "",
        totp: com.localkeep.core.model.Totp? = null,
    ) = VaultItem(
        id = vaultId,
        title = title,
        createdAt = longAgo,
        updatedAt = longAgo,
        content = Login(
            username = "ada",
            password = password,
            uris = listOf(UriMatch(uri = "https://github.com")),
            totp = totp,
            notes = notes,
        ),
    )

    private val csv = """
        name,url,username,password,totp,notes
        GitHub,https://github.com,ada,hunter2,JBSWY3DPEHPK3PXP,from the export
    """.trimIndent().toByteArray(Charsets.UTF_8)

    /** The same account, renamed in Bitwarden and exported afterwards — a real, dated edit. */
    private val bitwarden = """
        {"encrypted":false,"folders":[],"items":[
          {"id":"i1","type":1,"name":"GitHub — renamed in Bitwarden",
           "creationDate":"2026-01-01T00:00:00Z","revisionDate":"2026-06-01T00:00:00Z",
           "login":{"username":"ada","password":"hunter2","uris":[{"uri":"https://github.com"}]}}
        ]}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private fun parse(importer: Importer, bytes: ByteArray): ParsedImport {
        val result = importer.parse(bytes, ImportDefaultTitles())
        assertTrue(result is Outcome.Success, "fixture must parse: $result")
        return result.value
    }

    @Test
    fun `a csv re-import keeps a rename the user made in the vault`() {
        val parsed = parse(CsvImporter, csv)
        assertFalse(parsed.timestampsKnown, "a CSV row has no date to read")

        val merged = ImportMerger.merge(listOf(vaultCopy()), parsed.items, parsed.timestampsKnown)

        assertEquals(1, merged.size, "the re-import must collide, not append")
        assertEquals("GitHub — work account", merged.single().title, "the vault's own title must survive")
        assertEquals(vaultId, merged.single().id, "and it must still be the same item")
    }

    /**
     * The other half of "vault wins": winning must not mean the import was ignored. Everything the
     * vault copy has no value for is still taken from it — `ItemMerge.preserveStructure`'s job, and
     * the reason this is a merge rather than a skip.
     */
    @Test
    fun `a csv re-import still fills in what the vault copy lacks`() {
        val parsed = parse(CsvImporter, csv)
        val merged = ImportMerger.merge(listOf(vaultCopy()), parsed.items, parsed.timestampsKnown)
        val login = merged.single().content as Login

        assertEquals("JBSWY3DPEHPK3PXP", assertNotNull(login.totp).secretBase32, "a seed the vault lacked must arrive")
        assertEquals("from the export", login.notes, "so must a note the vault had none of")
    }

    /**
     * `ImportMerger` skips `ItemMerge.preserveNewerPassword` for an undated source, and this is why:
     * that helper falls back to `loser.updatedAt`, which for a CSV is the import instant and beats
     * every real date in the vault. Run with the vault as the winner it would have handed the
     * import's password straight back.
     */
    @Test
    fun `a csv re-import does not overwrite the vault's password`() {
        val parsed = parse(
            CsvImporter,
            "name,url,username,password\nGitHub,https://github.com,ada,the-old-one\n".toByteArray(Charsets.UTF_8),
        )
        val merged = ImportMerger.merge(listOf(vaultCopy(password = "changed-yesterday")), parsed.items, parsed.timestampsKnown)

        assertEquals("changed-yesterday", (merged.single().content as Login).password)
    }

    /** The preview has to say the same thing the merge will do, or it is a lie about the outcome. */
    @Test
    fun `a csv re-import previews as already current, not as an update`() {
        val parsed = parse(CsvImporter, csv)
        val plan = ImportMerger.plan(listOf(vaultCopy()), parsed.items, parsed.timestampsKnown)

        assertEquals(listOf(ImportMerger.Disposition.ALREADY_CURRENT), plan.map { it.disposition })
        assertTrue(plan.single().collidesWithVault, "and it is the vault it collided with")
    }

    /** Aegis is the other undated format; it must answer the same way. */
    @Test
    fun `an aegis export carries no timestamps`() {
        val aegis = """
            {"version":1,"header":{"slots":null,"params":null},
             "db":{"version":2,"entries":[
               {"type":"totp","uuid":"u0","name":"ada","issuer":"GitHub",
                "info":{"secret":"JBSWY3DPEHPK3PXP","algo":"SHA1","digits":6,"period":30}}]}}
        """.trimIndent().toByteArray(Charsets.UTF_8)

        assertFalse(parse(AegisJsonImporter, aegis).timestampsKnown)
    }

    // ---- Controls: a format with real dates must behave exactly as it did before ----

    @Test
    fun `a bitwarden re-import with a newer revision date still updates the vault copy`() {
        val parsed = parse(BitwardenJsonImporter, bitwarden)
        assertTrue(parsed.timestampsKnown, "Bitwarden writes revisionDate and must keep winning on it")

        val merged = ImportMerger.merge(listOf(vaultCopy()), parsed.items, parsed.timestampsKnown)

        assertEquals(1, merged.size)
        assertEquals("GitHub — renamed in Bitwarden", merged.single().title)
        assertEquals(vaultId, merged.single().id, "an update is still a merge into the vault's own item")
    }

    @Test
    fun `a bitwarden re-import previews as an update`() {
        val parsed = parse(BitwardenJsonImporter, bitwarden)
        val plan = ImportMerger.plan(listOf(vaultCopy()), parsed.items, parsed.timestampsKnown)

        assertEquals(listOf(ImportMerger.Disposition.UPDATES_EXISTING), plan.map { it.disposition })
    }

    /** A row the vault has never seen is added whatever the source's dates are worth. */
    @Test
    fun `an undated row that collides with nothing is still imported`() {
        val parsed = parse(CsvImporter, csv)
        val merged = ImportMerger.merge(emptyList(), parsed.items, parsed.timestampsKnown)

        assertEquals(listOf("GitHub"), merged.map { it.title })
    }
}
