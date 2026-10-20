package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import com.localkeep.core.model.Login
import com.localkeep.core.model.PasswordHistory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.linguafranca.pwdb.kdbx.Helpers
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxHeader
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.kdbx.dom.DomSerializableDatabase
import org.w3c.dom.Element

/**
 * KeePass keeps a prior copy of an entry per edit, in `<History>`, and this importer read none of
 * it — so a KeePassXC user's password history arrived empty while the Bitwarden and 1Password
 * importers both filled `Login.passwordHistory`, with nothing in the preview to say so
 * (S2, 2026-09-15).
 *
 * **Why the fixtures are built the long way.** `DomDatabaseWrapper` exposes no history API and keeps
 * its element package-private, so the `<History>` children are written into the DOM directly — the
 * same two-pass shape `KdbxRecycleBinTest` uses and for the same reason.
 */
class KdbxPasswordHistoryTest {

    private val password = "correct horse battery staple"
    private fun creds() = KdbxCreds(password.toByteArray(Charsets.UTF_8))

    private fun child(parent: Element, name: String): Element? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == name) return node
        }
        return null
    }

    /** One historic revision: the password the entry held, and when that revision was saved. */
    private data class Revision(val password: String, val savedAt: String)

    /**
     * Writes a one-entry database whose entry carries [revisions] in `<History>`, oldest first as
     * KeePass itself writes them.
     */
    private fun databaseWithHistory(current: String, revisions: List<Revision>): ByteArray {
        val db = DomDatabaseWrapper()
        db.rootGroup.addEntry(
            db.newEntry().apply {
                title = "Example"
                username = "ada@example.com"
                setPassword(current)
            },
        )
        val first = ByteArrayOutputStream().use { out -> db.save(creds(), out); out.toByteArray() }

        val reopened = DomSerializableDatabase.createEmptyDatabase()
        KdbxStreamFormat(KdbxHeader()).load(reopened, creds(), ByteArrayInputStream(first))
        val doc = reopened.doc
        val root = checkNotNull(child(doc.documentElement, "Root")) { "fixture has no Root" }
        val group = checkNotNull(child(root, "Group")) { "fixture has no Group" }
        val entry = checkNotNull(child(group, "Entry")) { "fixture has no Entry" }

        val history = doc.createElement("History")
        revisions.forEach { revision ->
            val historic = doc.createElement("Entry")
            historic.appendChild(doc.createElement("UUID").apply { textContent = Helpers.base64FromUuid(java.util.UUID.randomUUID()) })
            historic.appendChild(
                doc.createElement("String").apply {
                    appendChild(doc.createElement("Key").apply { textContent = "Password" })
                    appendChild(doc.createElement("Value").apply { textContent = revision.password })
                },
            )
            historic.appendChild(
                doc.createElement("Times").apply {
                    appendChild(
                        doc.createElement("LastModificationTime").apply {
                            textContent = Helpers.fromDateV3(Date.from(java.time.Instant.parse(revision.savedAt)))
                        },
                    )
                },
            )
            history.appendChild(historic)
        }
        entry.appendChild(history)

        return ByteArrayOutputStream().use { out ->
            KdbxStreamFormat(KdbxHeader(4)).save(reopened, creds(), out)
            out.toByteArray()
        }
    }

    private fun importSingle(bytes: ByteArray): Login {
        val result = KdbxImporter.parse(bytes, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed: $result")
        assertEquals(1, result.value.items.size, "history entries must not become items of their own")
        return result.value.items.single().content as Login
    }

    @Test
    fun `previous passwords come across newest first`() {
        val login = importSingle(
            databaseWithHistory(
                current = "current-one",
                revisions = listOf(
                    Revision("oldest-one", "2026-01-01T00:00:00Z"),
                    Revision("middle-one", "2026-03-01T00:00:00Z"),
                    Revision("newest-one", "2026-06-01T00:00:00Z"),
                ),
            ),
        )

        assertEquals(
            listOf("newest-one", "middle-one", "oldest-one"),
            login.passwordHistory.map { it.password },
        )
        assertEquals("current-one", login.password, "and the live password is still the live one")
        assertEquals(
            java.time.Instant.parse("2026-06-01T00:00:00Z"),
            login.passwordHistory.first().changedAt,
            "the entry's own LastModificationTime, not the import instant",
        )
    }

    /**
     * KeePass snapshots the whole entry on any edit, a rename included, so the newest snapshot very
     * often repeats the password that is still live. Listing it as a *previous* password is a claim
     * the file never made.
     */
    @Test
    fun `a historic revision that repeats the live password is not listed as a previous one`() {
        val login = importSingle(
            databaseWithHistory(
                current = "current-one",
                revisions = listOf(
                    Revision("was-changed-here", "2026-01-01T00:00:00Z"),
                    Revision("current-one", "2026-06-01T00:00:00Z"),
                ),
            ),
        )

        assertEquals(listOf("was-changed-here"), login.passwordHistory.map { it.password })
    }

    /** The bound every other door into this field goes through applies to this one too. */
    @Test
    fun `history is capped at the shared maximum`() {
        val login = importSingle(
            databaseWithHistory(
                current = "current-one",
                revisions = (1..9).map { Revision("pw-$it", "2026-0${(it % 9) + 1}-01T00:00:00Z") },
            ),
        )

        assertEquals(PasswordHistory.MAX_ENTRIES, login.passwordHistory.size)
    }

    /** Control: an entry with no `<History>` at all must still import, with an empty history. */
    @Test
    fun `an entry with no history imports with none`() {
        val login = importSingle(databaseWithHistory(current = "current-one", revisions = emptyList()))

        assertTrue(login.passwordHistory.isEmpty())
        assertEquals("current-one", login.password)
    }
}
