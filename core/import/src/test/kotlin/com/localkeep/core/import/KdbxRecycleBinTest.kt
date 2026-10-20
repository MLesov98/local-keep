package com.localkeep.core.import

import com.localkeep.core.common.Outcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.linguafranca.pwdb.kdbx.Helpers
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.KdbxHeader
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import org.linguafranca.pwdb.kdbx.dom.DomSerializableDatabase
import org.w3c.dom.Element

/**
 * Entries the user deleted in KeePass must not come back to life.
 *
 * The Recycle Bin was walked like any other group, so everything the user had thrown away arrived
 * as a live, searchable, autofill-eligible item inside a folder named after the bin — and the
 * health dashboard then counted those passwords as things to fix. Dropping them without a word
 * would have been the same defect facing the other way, so they are reported as skipped with
 * [SkipReason.SourceRecycleBin] (2026-09-13).
 *
 * **Why the fixtures are built the long way.** KeePassJava2 2.2.4's own recycle-bin support is
 * broken in a way that matters here: `DomGroupWrapper.isRecycleBin()` and
 * `DomDatabaseWrapper.getRecycleBin()` both look Meta up as `RecycleBinUuid`, while KDBX spells the
 * element `RecycleBinUUID` — so on a real database both answer "no bin", and `getRecycleBin()`
 * falls through to *creating* a fresh group instead of finding the one that is there (measured
 * 2026-09-13; the sweep's own note that `isRecycleBin()` "is reliable across a save/reload" is
 * wrong). The importer therefore reads `Meta/RecycleBinUUID` itself, and these fixtures have to
 * write that element themselves too, because the library never will.
 */
class KdbxRecycleBinTest {

    private val password = "correct horse battery staple"
    private fun creds() = KdbxCreds(password.toByteArray(Charsets.UTF_8))

    private fun DomDatabaseWrapper.entry(title: String) = newEntry().apply {
        this.title = title
        username = "u"
        setPassword("p")
    }

    private fun child(parent: Element, name: String): Element? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == name) return node
        }
        return null
    }

    /**
     * Saves [db], then rewrites `Meta/RecycleBinUUID` to [binId] and saves again.
     *
     * A two-pass fixture rather than a DOM edit in place, because `DomDatabaseWrapper` keeps its
     * `Meta` element package-private. `DomSerializableDatabase` is the same object the wrapper
     * loads into, so nothing about the container changes but that one element.
     */
    private fun writeWithRecycleBin(db: DomDatabaseWrapper, binId: java.util.UUID?): ByteArray {
        val first = ByteArrayOutputStream().use { out -> db.save(creds(), out); out.toByteArray() }
        val reopened = DomSerializableDatabase.createEmptyDatabase()
        KdbxStreamFormat(KdbxHeader()).load(reopened, creds(), ByteArrayInputStream(first))
        val meta = checkNotNull(child(reopened.doc.documentElement, "Meta")) { "fixture has no Meta" }
        val element = checkNotNull(child(meta, "RecycleBinUUID")) { "fixture has no RecycleBinUUID" }
        element.textContent = binId?.let(Helpers::base64FromUuid) ?: element.textContent
        checkNotNull(child(meta, "RecycleBinEnabled")).textContent = "True"
        return ByteArrayOutputStream().use { out ->
            KdbxStreamFormat(KdbxHeader(4)).save(reopened, creds(), out)
            out.toByteArray()
        }
    }

    private fun parse(bytes: ByteArray): ParsedImport {
        val result = KdbxImporter.parse(bytes, password.toCharArray())
        assertTrue(result is Outcome.Success, "import must succeed: $result")
        return result.value
    }

    @Test
    fun `a deleted entry is reported as skipped, not imported as a live item`() {
        val db = DomDatabaseWrapper()
        val work = db.newGroup("Work").also { db.rootGroup.addGroup(it) }
        work.addEntry(db.entry("live entry"))
        val bin = db.newGroup("Recycle Bin").also { db.rootGroup.addGroup(it) }
        bin.addEntry(db.entry("deleted entry"))

        val parsed = parse(writeWithRecycleBin(db, bin.uuid))
        val titles = parsed.items.map { it.title }

        // Control — an ordinary group is still a folder and its entry is still live.
        assertTrue("live entry" in titles, "an ordinary group's entry must still import")
        assertTrue(parsed.folders.any { it.name == "Work" }, "an ordinary group must still become a folder")

        assertFalse("deleted entry" in titles, "a deleted entry must not arrive as a live item")
        assertFalse(parsed.folders.any { it.name == "Recycle Bin" }, "the bin must not arrive as a folder")
        assertEquals(
            listOf(SkippedEntry("deleted entry", SkipReason.SourceRecycleBin)),
            parsed.skipped,
            "and the user must be told, by name, what was left behind",
        )
    }

    @Test
    fun `entries nested inside the bin are reported too`() {
        val db = DomDatabaseWrapper()
        val bin = db.newGroup("Recycle Bin").also { db.rootGroup.addGroup(it) }
        val subfolder = db.newGroup("Old project").also { bin.addGroup(it) }
        subfolder.addEntry(db.entry("nested deleted entry"))

        val parsed = parse(writeWithRecycleBin(db, bin.uuid))
        assertTrue(parsed.items.isEmpty())
        assertTrue(parsed.folders.isEmpty(), "a group inside the bin is not a folder either")
        assertEquals(
            listOf(SkippedEntry("nested deleted entry", SkipReason.SourceRecycleBin)),
            parsed.skipped,
        )
    }

    @Test
    fun `a database that names no bin keeps every group`() {
        // The all-zero UUID is KeePass's own "there is no recycle bin", and it is what the empty
        // template ships with. Treating it as a real id would match nothing, but asserting it keeps
        // that guard honest: without it, a group whose own id happened to be zero would vanish.
        val db = DomDatabaseWrapper()
        val work = db.newGroup("Work").also { db.rootGroup.addGroup(it) }
        work.addEntry(db.entry("kept"))

        val parsed = parse(writeWithRecycleBin(db, binId = null))
        assertEquals(listOf("kept"), parsed.items.map { it.title })
        assertTrue(parsed.skipped.isEmpty())
        assertTrue(parsed.folders.any { it.name == "Work" })
    }

    @Test
    fun `a group that merely looks like a bin is still imported`() {
        // Identification is by id, never by name: a user may call any folder "Recycle Bin", and
        // KeePassXC in German calls its own bin "Papierkorb". Name matching would be wrong in both
        // directions — and is what the library's `getRecycleBin()` falls back to.
        val db = DomDatabaseWrapper()
        val lookalike = db.newGroup("Recycle Bin").also { db.rootGroup.addGroup(it) }
        lookalike.addEntry(db.entry("not actually deleted"))

        val parsed = parse(writeWithRecycleBin(db, binId = null))
        assertEquals(listOf("not actually deleted"), parsed.items.map { it.title })
        assertTrue(parsed.skipped.isEmpty())
    }
}
