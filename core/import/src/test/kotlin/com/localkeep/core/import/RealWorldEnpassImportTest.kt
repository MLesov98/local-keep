package com.localkeep.core.import

import com.localkeep.core.import.RealWorldCorpus.orFail
import com.localkeep.core.model.Login
import com.localkeep.core.model.Note
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Enpass, from `src/test/resources/realworld/enpass/` (see `PROVENANCE.md` there).
 *
 * Both of Enpass's export shapes were gaps. Its CSV is header-less and **label/value interleaved** —
 * `Amazon,Username,Food,E-mail,Cheese,*Password,…` — so a column index means nothing; its JSON is a
 * `{folders,items}` document with typed `fields` and a `parent_uuid` hierarchy.
 *
 * The first assertion below is the one that matters and it has not changed: because the CSV's first
 * row happens to contain the word `Username` in an odd position, `autoDetect` *does* bind columns
 * from it, and forcing the file through the generic mapper produces items whose username is the
 * literal string "Username". [CsvImporter.detect]'s refusal is what stops that reaching a user, and
 * [EnpassCsv.detect] is built on top of it rather than beside it.
 */
class RealWorldEnpassImportTest {

    private val csvFiles = listOf(
        "enpass/login.csv",
        "enpass/login-quoted.csv",
        "enpass/login-no-username.csv",
        "enpass/login-one-time-code.csv",
        "enpass/login-sensitive-custom-field.csv",
        "enpass/login-starred-only.csv",
        "enpass/login-trailing-note.csv",
        "enpass/card.csv",
        "enpass/secure-note.csv",
    )

    private val jsonFiles = listOf(
        "enpass/json-login.json",
        "enpass/json-login-android-url.json",
        "enpass/json-credit-card.json",
        "enpass/json-note.json",
        "enpass/json-folders.json",
    )

    /**
     * The generic mapper still refuses, and the gap has teeth, so the refusal matters.
     * `login-quoted.csv`'s first row carries the *label* `Username` at index 1 and `Website` at
     * index 7, both of which are real header synonyms; if `detect` ever started saying yes, the four
     * rows behind it would import as logins with the username "Username" and the URL "Website".
     */
    @Test
    fun `a label-value Enpass CSV is still refused by the generic CSV mapper`() {
        csvFiles.forEach { path ->
            assertFalse(CsvImporter.detect(RealWorldCorpus.bytes(path)), "$path is recognised — expectation is stale")
        }
    }

    /**
     * Eight of the nine, and the ninth is a deliberate, documented miss.
     *
     * `secure-note.csv` is `MyNote,Some note text`: two cells with nothing in them that is Enpass
     * rather than any other two-column file. Recognising it means recognising every such file, so
     * it is refused — and this test says so out loud rather than leaving the gap to be rediscovered.
     */
    @Test
    fun `every Enpass CSV carrying a sensitive-label marker is recognised`() {
        csvFiles.filterNot { it.endsWith("secure-note.csv") }.forEach { path ->
            assertTrue(EnpassImporter.detect(RealWorldCorpus.bytes(path)), "$path was not recognised")
        }
        assertFalse(
            EnpassImporter.detect(RealWorldCorpus.bytes("enpass/secure-note.csv")),
            "secure-note.csv carries no Enpass-specific marker and must not be claimed",
        )
    }

    /** Enpass's native JSON is claimed by [EnpassImporter] and by nothing that it is not. */
    @Test
    fun `Enpass JSON is claimed by the Enpass importer and by no other`() {
        jsonFiles.forEach { path ->
            val bytes = RealWorldCorpus.bytes(path)
            assertTrue(EnpassImporter.detect(bytes), "$path was not recognised as an Enpass export")
            listOf(BitwardenJsonImporter, AegisJsonImporter, OnePuxImporter, CsvImporter).forEach { importer ->
                assertFalse(
                    importer.detect(bytes),
                    "$path was claimed by ${importer::class.simpleName} — it is not that format",
                )
            }
        }
    }

    @Test
    fun `an Enpass export imports its items`() {
        val path = "enpass/json-login.json"
        assertTrue(EnpassImporter.detect(RealWorldCorpus.bytes(path)))
        assertEquals(1, EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path).items.size)
    }

    /**
     * The folder fixture's own facts: two folder records, `Twitter` carrying
     * `parent_uuid` = Social's uuid, and one note item filed in Twitter.
     */
    @Test
    fun `the Enpass JSON folder hierarchy is read from parent_uuid`() {
        val path = "enpass/json-folders.json"
        val parsed = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        val social = parsed.folders.first { it.name == "Social" }
        val twitter = parsed.folders.first { it.name == "Twitter" }
        assertEquals(null, social.parentId, "Social is top-level")
        assertEquals(social.id, twitter.parentId, "Twitter nests under Social")

        val item = parsed.items.single()
        assertEquals(twitter.id, item.folderId, "the note is filed in Twitter")
        assertEquals("some secure note content", (item.content as Note).body)
        assertTrue(parsed.timestampsKnown, "Enpass JSON writes createdAt and updated_at")
    }

    /**
     * The label/value CSV, read as labels and values rather than as columns.
     *
     * `login-quoted.csv`'s first row is `"Amazon","Username","Food","E-mail","Cheese","*Password",
     * "Adasceafazxc","Website","https://www.amazon.com",…` — so the username is Food, not Username.
     */
    @Test
    fun `the label-value CSV imports labels as labels`() {
        val path = "enpass/login-quoted.csv"
        val parsed = EnpassImporter.parse(RealWorldCorpus.bytes(path)).orFail(path)

        assertEquals(4, parsed.items.size, "one item per row")
        val amazon = parsed.items.first { it.title == "Amazon" }
        val login = amazon.content as Login
        assertEquals("Food", login.username, "the value beside the Username label, not the label")
        assertEquals("Adasceafazxc", login.password)
        assertEquals("https://www.amazon.com", login.uris.single().uri)
        // Both identities are present, so the loser is carried rather than dropped.
        assertEquals("Cheese", amazon.customFields.single { it.name == "email" }.value)
        assertFalse(parsed.timestampsKnown, "an Enpass CSV row carries no time")
    }
}
